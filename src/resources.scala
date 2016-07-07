package android

import java.io.File

import android.Dependencies.{AarLibrary, ApkLibrary, LibraryDependency}
import com.android.builder.core.{AaptPackageProcessBuilder, AndroidBuilder, VariantType}
import com.android.builder.model.AaptOptions
import com.android.builder.dependency.{LibraryDependency => AndroidLibrary}
import com.android.builder.png.VectorDrawableRenderer
import com.android.ide.common.res2._
import com.android.resources.Density
import com.android.utils.ILogger
import sbt.Keys.TaskStreams
import sbt._

import collection.JavaConverters._
import language.postfixOps
import Dependencies.LibrarySeqOps
import sbt.classpath.ClasspathUtilities

import scala.util.Try
import scala.xml.XML

object Resources {
  val ANDROID_NS = "http://schemas.android.com/apk/res/android"
  def resourceUrl =
    Resources.getClass.getClassLoader.getResource _

  val reservedWords = Set(
    "def",
    "forSome",
    "implicit",
    "lazy",
    "match",
    "object",
    "override",
    "sealed",
    "trait",
    "type",
    "val",
    "var",
    "with",
    "yield"
  )

  def doCollectResources( bldr: AndroidBuilder
                        , minSdk: Int
                        , noTestApk: Boolean
                        , isLib: Boolean
                        , libs: Seq[LibraryDependency]
                        , layout: ProjectLayout
                        , extraAssets: Seq[File]
                        , extraRes: Seq[File]
                        , renderVectors: Boolean
                        , pngcrunch: Boolean
                        , logger: ILogger
                        , cache: File
                        , s: TaskStreams
                        )(implicit m: BuildOutput.Converter): (File,File) = {
    val assetBin = layout.mergedAssets
    val assets = layout.assets
    val resTarget = layout.mergedRes
    val rsResources = layout.rsRes

    resTarget.mkdirs()
    assetBin.mkdirs

    val depassets = collectdeps(libs) collect {
      case m: ApkLibrary => m
      case n: AarLibrary => n
    } collect { case n if n.getAssetsFolder.isDirectory => n.getAssetsFolder }
    // copy assets to single location
    depassets ++ (libs collect {
      case r if r.layout.assets.isDirectory => r.layout.assets
    }) foreach { a => IO.copyDirectory(a, assetBin, false, true) }
    extraAssets foreach { a =>
      if (a.isDirectory) IO.copyDirectory(a, assetBin, false, true)
    }

    if (assets.exists) IO.copyDirectory(assets, assetBin, false, true)
    if (noTestApk && layout.testAssets.exists)
      IO.copyDirectory(layout.testAssets, assetBin, false, true)
    // prepare resource sets for merge
    val res = extraRes ++ Seq(layout.res, rsResources) ++
      (libs map { _.layout.res } filter { _.isDirectory })

    s.log.debug("Local/library-project resources: " + res)
    // this needs to wait for other projects to at least finish their
    // apklibs tasks--handled if androidBuild() is called properly
    val depres = collectdeps(libs) collect {
      case m: ApkLibrary => m
      case n: AarLibrary => n
    } collect { case n if n.getResFolder.isDirectory => n.getResFolder }
    val nonGeneratingRes = depres.toSet
    s.log.debug("apklib/aar resources: " + depres)

    val respaths = depres ++ res.reverse ++
      (if (layout.res.isDirectory) Seq(layout.res) else Seq.empty) ++
      (if (noTestApk && layout.testRes.isDirectory)
        Seq(layout.res) else Seq.empty)
    val vectorprocessor = new VectorDrawableRenderer(
      if (renderVectors) minSdk else math.max(minSdk,21),
      layout.generatedVectors, Set(Density.MEDIUM,
        Density.HIGH,
        Density.XHIGH,
        Density.XXHIGH).asJava,
      logger)
    val sets = respaths.distinct flatMap { r =>
      s.log.debug("Adding resource path: " + r)
      val set = new ResourceSet(r.getAbsolutePath)
      set.addSource(r)

      // see https://code.google.com/p/android/issues/detail?id=214182#c5
      if (nonGeneratingRes(r)) {
        List(set)
      } else {
        set.setPreprocessor(vectorprocessor)
        val generated = new GeneratedResourceSet(set)
        set.setGeneratedSet(generated)
        List(generated, set)
      }
    }

    val inputs = (respaths flatMap { r => (r ***) get }) filter (n =>
      !n.getName.startsWith(".") && !n.getName.startsWith("_"))
    var needsFullResourceMerge = false

    FileFunction.cached(cache / "nuke-res-if-changed", FilesInfo.lastModified) { in =>
      needsFullResourceMerge = true
      IO.delete(resTarget)
      in
    }(depres.toSet)
    FileFunction.cached(cache / "collect-resources")(
      FilesInfo.lastModified, FilesInfo.exists) { (inChanges,outChanges) =>
      s.log.info("Collecting resources")

      incrResourceMerge(layout, minSdk, resTarget, isLib, libs, cache / "collect-resources",
                        logger, bldr, sets, pngcrunch, vectorprocessor, inChanges, needsFullResourceMerge, s.log)
      ((resTarget ** FileOnlyFilter).get ++ (layout.generatedVectors ** FileOnlyFilter).get).toSet
    }(inputs.toSet)

    (assetBin, resTarget)
  }

  def incrResourceMerge(
    layout: ProjectLayout,
    minSdk: Int,
    resTarget: File,
    isLib: Boolean,
    libs: Seq[LibraryDependency],
    blobDir: File,
    logger: ILogger,
    bldr: AndroidBuilder,
    resources: Seq[ResourceSet],
    pngcrunch: Boolean,
    preprocessor: ResourcePreprocessor,
    changes: ChangeReport[File],
    needsFullResourceMerge: Boolean,
    slog: Logger
  )(implicit m: BuildOutput.Converter) {

    def merge() = fullResourceMerge(layout, minSdk, resTarget, isLib, libs, blobDir,
      logger, bldr, resources, pngcrunch, preprocessor, slog)

    val merger = new ResourceMerger(minSdk)
    if (!merger.loadFromBlob(blobDir, true)) {
      slog.debug("Could not load merge blob (no full merge yet?)")
      merge()
    } else if (!merger.checkValidUpdate(resources.asJava)) {
      slog.debug("requesting full merge: !checkValidUpdate")
      merge()
    } else if (needsFullResourceMerge) {
      slog.debug("requesting full merge: dependency resources have changed!")
      merge()
    } else {
      merger.getDataSets.asScala.foreach(_.setPreprocessor(preprocessor))

      val fileValidity = new FileValidity[ResourceSet]
      val exists = changes.added ++ changes.removed ++ changes.modified exists {
        file =>
          val status = if (changes.added contains file)
            FileStatus.NEW
          else if (changes.removed contains file)
            FileStatus.REMOVED
          else if (changes.modified contains file)
            FileStatus.CHANGED
          else
            sys.error("Unknown file status: " + file)

          merger.findDataSetContaining(file, fileValidity)
          val vstatus = fileValidity.getStatus

          if (vstatus == FileValidity.FileStatus.UNKNOWN_FILE) {
            merge()
            slog.debug("Incremental merge aborted, unknown file: " + file)
            true
          } else if (vstatus == FileValidity.FileStatus.VALID_FILE) {
            // begin workaround
            // resource merger doesn't seem to actually copy changed files over...
            // values.xml gets merged, but if files are changed...
            val targetFile = resTarget / (
              file relativeTo fileValidity.getSourceFile).get.getPath
            val copy = Seq((file, targetFile))
            status match {
              case FileStatus.NEW =>
              case FileStatus.CHANGED =>
                if (targetFile.exists) IO.copy(copy, false, true)
              case FileStatus.REMOVED => targetFile.delete()
            }
            // end workaround
            try {
              if (!fileValidity.getDataSet.updateWith(
                fileValidity.getSourceFile, file, status, logger)) {
                slog.debug("Unable to handle changed file: " + file)
                merge()
                true
              } else
                false
            } catch {
              case e: RuntimeException =>
                slog.warn("Unable to handle changed file: " + file + ": " + e)
                merge()
                true
            }
          } else
            false
      }
      if (!exists) {
        slog.info("Performing incremental resource merge")
        val writer = new MergedResourceWriter(resTarget,
          bldr.getAaptCruncher(SbtProcessOutputHandler(slog)),
          pngcrunch, true, layout.publicTxt, layout.mergeBlame,
          preprocessor)
        merger.mergeData(writer, true)
        merger.writeBlobTo(blobDir, writer)
      }
    }
  }
  def fullResourceMerge(layout: ProjectLayout,
                        minSdk: Int,
                        resTarget: File,
                        isLib: Boolean,
                        libs: Seq[LibraryDependency],
                        blobDir: File,
                        logger: ILogger,
                        bldr: AndroidBuilder,
                        resources: Seq[ResourceSet],
                        pngcrunch: Boolean,
                        preprocessor: ResourcePreprocessor,
                        slog: Logger)(implicit m: BuildOutput.Converter) {

    slog.info("Performing full resource merge")
    val merger = new ResourceMerger(minSdk)

    resTarget.mkdirs()

    resources foreach { r =>
      r.loadFromFiles(logger)
      merger.addDataSet(r)
    }
    val writer = new MergedResourceWriter(resTarget,
      bldr.getAaptCruncher(SbtProcessOutputHandler(slog)),
      pngcrunch, true, layout.publicTxt, layout.mergeBlame, preprocessor)
    merger.mergeData(writer, false)
    merger.writeBlobTo(blobDir, writer)
  }

  def aapt(bldr: AndroidBuilder, manifest: File, pkg: String,
           extraParams: Seq[String], resConfigs: Seq[String],
           libs: Seq[LibraryDependency], lib: Boolean, debug: Boolean,
           res: File, assets: File, resApk: String, gen: File, proguardTxt: String,
           logger: Logger) = synchronized {

    gen.mkdirs()
    val options = new AaptOptions {
      override def getIgnoreAssets = null
      override def getNoCompress = null
      override def getFailOnMissingConfigEntry = false
      override def getAdditionalParameters = extraParams.asJava
    }
    val genPath = gen.getAbsolutePath
    val all = collectdeps(libs)
    logger.debug("All libs: " + all)
    logger.debug("packageForR: " + pkg)
    logger.debug("proguard.txt: " + proguardTxt)
    val aaptCommand = new AaptPackageProcessBuilder(manifest, options)
    if (res.isDirectory)
      aaptCommand.setResFolder(res)
    if (assets.isDirectory)
      aaptCommand.setAssetsFolder(assets)
    aaptCommand.setLibraries(all.asJava)
    aaptCommand.setPackageForR(pkg)
    aaptCommand.setResPackageOutput(resApk)
    aaptCommand.setResourceConfigs(resConfigs.asJava)
    aaptCommand.setSourceOutputDir(if (resApk == null) genPath else null)
    aaptCommand.setSymbolOutputDir(if (resApk == null) genPath else null)
    aaptCommand.setProguardOutput(proguardTxt)
    aaptCommand.setType(if (lib) VariantType.LIBRARY else VariantType.DEFAULT)
    aaptCommand.setDebuggable(debug)
    try {
      bldr.processResources(aaptCommand, true, SbtProcessOutputHandler(logger))
    } catch {
      case e: com.android.ide.common.process.ProcessException =>
        PluginFail(e.getMessage)
    }
  }

  def collectdeps(libs: Seq[AndroidLibrary]): Seq[AndroidLibrary] = {
    libs
      .map(_.getDependencies.asScala)
      .flatMap(collectdeps)
      .++(libs)
      .distinctLibs
  }

  lazy val androidJarMemo = scalaz.Memo.immutableHashMapMemo[File, ClassLoader](ClasspathUtilities.toLoader(_: File))
  def classForLabel(j: String, l: String) = {
    if (l contains ".") Some(l)
    else {
      Seq("android.widget."
        , "android.view."
        , "android.webkit.").flatMap {
        pkg => Try(androidJarMemo(file(j)).loadClass(pkg + l).getName).toOption
      }.headOption
    }
  }
  def generateTR(t: Boolean, a: Seq[File], p: String, layout: ProjectLayout,
                 platformApi: Int, platform: (String,Seq[String]), sv: String,
                 l: Seq[LibraryDependency], f: Boolean, includeAar: Boolean,
                 withViewHolders: Boolean, i: Seq[String], s: TaskStreams): Seq[File] = {

    val j = platform._1
    val r = layout.res
    val g = layout.gen
    val ignores = i.toSet

    val tr = p.split("\\.").foldLeft (g) { _ / _ } / "TR.scala"

    if (!t)
      Seq.empty[File]
    else
      FileFunction.cached(s.cacheDirectory / "typed-resources-generator", FilesInfo.hash) { in =>
        if (in.nonEmpty) {
          s.log.info("Regenerating TR.scala because R.java has changed")
          val layouts = (r ** "layout*" ** "*.xml" get) ++
            (for {
              lib <- l filterNot {
                case p: Dependencies.Pkg => ignores(p.pkg)
                case a: AarLibrary       => !includeAar
                case _                   => false
              }
              xml <- lib.getResFolder ** "layout*" ** "*.xml" get
            } yield xml)

          s.log.debug("Layouts: " + layouts)
          // XXX handle package references? @id/android:ID or @id:android/ID
          val re = "@\\+id/(.*)".r

          def warn(res: Seq[(String,String)]) = {
            // nice to have:
            //   merge to a common ancestor, this is possible for androidJar
            //   but to do so is perilous/impossible for project code...
            // instead:
            //   reduce to ViewGroup for *Layout, and View for everything else
            val overrides = res.groupBy(r => r._1) filter (
              _._2.toSet.size > 1) collect {
              case (k,v) =>
                s.log.warn("%s was reassigned: %s" format (k,
                  v map (_._2) mkString " => "))
                k -> (if (v endsWith "Layout")
                  "android.view.ViewGroup" else "android.view.View")
            }

            (res ++ overrides).toMap
          }
          val layoutTypes = warn(for {
            file   <- layouts
            layout  = XML loadFile file
            l      <- classForLabel(j, layout.label).orElse(Some("android.view.View"))
          } yield file.getName.stripSuffix(".xml") -> l)

          val resources = warn(for {
            b      <- layouts
            layout  = XML loadFile b
            n      <- layout.descendant_or_self
            re(id) <- n.attribute(ANDROID_NS, "id") map { _.head.text }
            l      <- classForLabel(j, n.label)
          } yield id -> l)

          val trTemplate = IO.readLinesURL(
            resourceUrl("tr.scala.template")) mkString "\n"

          tr.delete()

          val resdirs = if (f) {
            r +: (for {
              lib <- l filterNot {
                case p: Dependencies.Pkg => ignores(p.pkg)
                case a: AarLibrary       => !includeAar
                case _                   => false
              }
            } yield lib.getResFolder)
          } else Nil
          val rms1 = processValuesXml(resdirs, s)
          val rms2 = processResourceTypeDirs(resdirs, s)
          val combined = reduceResourceMap(Seq(rms1, rms2)).filter(_._2.nonEmpty)
          val combined1 = combined.map { case (k, xs) =>
            val k2 = if (k endsWith "-array") "array" else k
            val trt = trTypes(k)
            val ys = xs.toSet[String].map { x =>
              val y = x.replace('.', '_')
              s"    final val ${wrap(y)} = TypedRes[TypedResource.$trt](R.$k2.${wrap(y)})"
            }
            k -> ys
          }
          val combined2 = combined1.foldLeft(emptyResourceMap) { case (acc, (k, xs)) =>
            val k2 = if (k endsWith "-array") "array" else k
            acc + ((k2, acc(k2) ++ xs))
          }
          val trs = combined2.foldLeft(List.empty[String]) { case (acc, (k, xs)) =>
            val k2 = if (k endsWith "-array") "array" else k
            s"""
               |  object $k2 {
               |${xs.mkString("\n")}
               |  }""".stripMargin :: acc
          }

          val deprForward = {
            if (platformApi < 21) ""
            else {
              val color =
                """
                  |    @TargetApi(23)
                  |    @inline def getColor(c: Context, resid: Int): Int = {
                  |      if (Build.VERSION.SDK_INT >= 23)
                  |        c.getColor(resid)
                  |      else
                  |        c.getResources.getColor(resid)
                  |    }""".stripMargin
              val drawable =
               """
                  |    @TargetApi(21)
                  |    @inline def getDrawable(c: Context, resid: Int): Drawable = {
                  |      if (Build.VERSION.SDK_INT >= 21)
                  |        c.getDrawable(resid)
                  |      else
                  |        c.getResources.getDrawable(resid)
                  |    }""".stripMargin

              val methods = if (platformApi >= 23) color + "\n\n" + drawable else drawable

              s"""
                |  // Helper object to suppress deprecation warnings as discussed in
                |  // https://issues.scala-lang.org/browse/SI-7934
                |  @deprecated("", "")
                |  private trait compat {
                |$methods
                |  }
                |  private object compat extends compat""".stripMargin
            }
          }

          val getColor = "      " + (if (platformApi >= 23) {
            "compat.getColor(c,resid)"
          } else {
            "c.getResources.getColor(resid)"
          })
          val getDrawable = "      " + (if (platformApi >= 21) {
            "compat.getDrawable(c,resid)"
          } else {
            "c.getResources.getDrawable(resid)"
          })

          IO.write(tr, trTemplate format (p,
            if (withViewHolders) "" else  " extends AnyVal",
            resources map { case (k,v) =>
              "  final val %s = TypedResource[%s](R.id.%s)" format (wrap(k),v,wrap(k))
            } mkString "\n",
            layoutTypes map { case (k,v) =>
              "    final val %s = TypedLayout[%s](R.layout.%s)" format (wrap(k),v,wrap(k))
            } mkString "\n", trs.mkString, getColor, getDrawable, getDrawable, deprForward))
          Set(tr)
        } else Set.empty
      }(a.toSet).toSeq
  }
  def wrap(s: String) = if (reservedWords(s)) s"`$s`" else s

  val trTypes = Map(
    "anim"          -> "ResAnim",
    "animator"      -> "ResAnimator",
    "array"         -> "ResArray",
    "string-array"  -> "ResStringArray",
    "integer-array" -> "ResIntegerArray",
    "attr"          -> "ResAttr",
    "bool"          -> "ResBool",
    "color"         -> "ResColor",
    "dimen"         -> "ResDimen",
    "drawable"      -> "ResDrawable",
    "fraction"      -> "ResFraction",
    "integer"       -> "ResInteger",
    "interpolator"  -> "ResInterpolator",
    "menu"          -> "ResMenu",
    "mipmap"        -> "ResMipMap",
    "plurals"       -> "ResPlurals",
    "raw"           -> "ResRaw",
    "string"        -> "ResString",
    "style"         -> "ResStyle",
    "transition"    -> "ResTransition",
    "xml"           -> "ResXml"
  )

  val itemTypes = Set(
    "anim",
    "animator",
    "array",
    "bool",
    "color",
    "dimen",
    "drawable",
    "fraction",
    "integer",
    "interpolator",
    "menu",
    "mipmap",
    "plurals",
    "raw",
    "string",
    "style",
    "transition",
    "xml"
  )

  val formatTypes = List(
    "boolean"   -> "bool",
    "color"     -> "color",
    "dimension" -> "dimen",
    "fraction"  -> "fraction",
    "integer"   -> "integer",
    "string"    -> "string"
  ).toMap

  type ResourceMap = Map[String,List[String]]
  val emptyResourceMap = Map.empty[String,List[String]].withDefaultValue(Nil)
  def reduceResourceMap(rms: Seq[ResourceMap]): ResourceMap =
    rms.foldLeft(emptyResourceMap) { (m, n) =>
      n.keys.foldLeft(m)((m2, k) => m2 + (k -> (m2(k) ++ n(k))))
    }
  def attributeText(n: xml.Node, attr: String): Option[String] =
    n.attribute(attr).flatMap(_.headOption).map(_.text)
  def processValuesXml(resdirs: Seq[File], s: TaskStreams): ResourceMap = {
    val valuesxmls = resdirs flatMap { d => d * "values*" * "*.xml" get }
    val rms = valuesxmls.map { xml =>
      val values = XML.loadFile(xml)

      val items = values \ "item"
      val itemEntries = items.flatMap { node =>
        (for {
          name <- attributeText(node, "name")
          typ <- attributeText(node, "type").filter(itemTypes).orElse(
            attributeText(node, "format").flatMap(formatTypes.get))
        } yield (typ, name)).toSeq
      }
      val itemMap = itemEntries.foldLeft(emptyResourceMap) { case (m, (t,n)) =>
        m + ((t,n :: m(t)))
      }

      def foldKey(key: String): (ResourceMap,scala.xml.Node) => ResourceMap = (m,node) => {
        node.attribute("name").flatMap(_.headOption).fold(m)(n => m + ((key,n.text :: m(key))))
      }
      def foldNodes(in: ResourceMap, key: String): ResourceMap = {
        (values \ key).foldLeft(in)(foldKey(key))
      }

      List("string", "string-array", "array", "plurals", "integer",
        "integer-array", "bool", "attr", "color", "dimen", "style"
      ).foldLeft(itemMap)(foldNodes)
    }
    reduceResourceMap(rms)
  }
  val resdirTypes = List(
    "anim",
    "animator",
    "color",
    "drawable",
    "interpolator",
    "menu",
    "mipmap",
    "raw",
    "transition",
    "xml"
  )

  def processResourceTypeDirs(resdirs: Seq[File], s: TaskStreams): ResourceMap = {
    val rms2 = for {
      res <- resdirs
      restype <- resdirTypes
    } yield restype ->
      (res * s"$restype*" * "*").get.map(_.getName.takeWhile(_ != '.')).toList.filter(_.nonEmpty)
    rms2.foldLeft(emptyResourceMap) { case (m, (t, xs)) => m + (t -> (m(t) ++ xs)) }
  }

  def generateViewHolders(generate: Boolean,
                          pkg: String,
                          platform: (String,Seq[String]),
                          layout: ProjectLayout,
                          libs: Seq[LibraryDependency], includeAar: Boolean,
                          ignores: Seq[String], s: TaskStreams): Seq[File] = {
    val re = """@\+id/(\w+)""".r
    val re2 = """@(\w+):id/(\w+)""".r
    val includedre = """@layout/(\w+)""".r

    val j = platform._1
    if (!generate) Nil
    else {
      object LayoutFile {
        implicit val ord: Ordering[LayoutFile] = new Ordering[LayoutFile] {
          override def compare(x: LayoutFile, y: LayoutFile) = {
            val n = x.name.compareTo(y.name)
            val s = x.configs.size - y.configs.size
            if (n == 0) s else n
          }
        }
      }
      case class LayoutFile(name: String, configs: List[String], path: File)
      sealed trait LayoutEntry
      case class LayoutInclude(id: Option[String], layout: String) extends LayoutEntry
      case class LayoutView(name: String, id: String, viewType: String) extends LayoutEntry
//      case object LayoutMerge extends LayoutEntry
      case class LayoutStructure(name: String,
                                 rootView: String, rootId: Option[String],
                                 views: List[LayoutEntry],
                                 configs: List[LayoutStructure])

      def parseLayout(n: String, f: File, includeRoot: Boolean): LayoutStructure = {
        val xml = XML.loadFile(f)
        val (r,c) = xml.descendant_or_self.foldLeft((Option.empty[(Option[String],String)],List.empty[LayoutEntry])) { case ((root, children), n) =>
          val viewId = n.attribute(ANDROID_NS, "id") map { _.head.text } match {
            case Some(re(id))       => Some((wrap(id), s"R.id.${wrap(id)}"))
            case Some(re2(p, id))   => Some((wrap(id), s"$p.R.id.${wrap(id)}"))
            case _                  => None
          }
          // TODO handle 'include' and 'merge'
          if (n.label == "layout" || n.label == "#PCDATA") // noop, skip
            (root,children)
          else if (n.label == "merge") { // TODO HANDLE merge
            (root,children)
          }
          else if (n.label == "include") {
            val includeId = n.attribute(ANDROID_NS, "id") map (_.head.text) match {
              case Some(re(id))       => Some(s"R.id.${wrap(id)}")
              case Some(re2(p, id))   => Some(s"$p.R.id.${wrap(id)}")
              case _                  => None
            }
            val included = n.attribute("layout").fold("")(_.head.text).collect {
              case includedre(l) => l
            }.head

            (root, LayoutInclude(includeId, included) :: children)
          } else if (root.isEmpty && !includeRoot)
            (Some((viewId.map(_._2), n.label)),children)
          else if (viewId.isEmpty) // no ID, don't record a viewholder entry
            (root,children)
          else {
//            val viewType = if (n.label == "view") {
//              n.attribute("class").map (_.head.text).get
//            } else
//              n.label
            (root, LayoutView(viewId.get._1, viewId.get._2, n.label) :: children)
          }
        }
        LayoutStructure(n, r.fold("")(_._2), r.flatMap(_._1), c, Nil)
      }
      val ig = ignores.toSet
      val libsToProcess = libs filterNot {
        case p: Dependencies.Pkg => ig(p.pkg)
        case a: AarLibrary => !includeAar
        case _ => false
      }
      val files = (layout.res ** "layout*" ** "*.xml" get) ++
        (for {
          lib <- libsToProcess
          xml <- lib.getResFolder ** "layout*" ** "*.xml" get
        } yield xml)


//      FileFunction.cached(
//        s.cacheDirectory / "viewHoldersGenerator", FilesInfo.lastModified) { in =>
        val vhs = pkg.split("\\.").foldLeft(layout.gen) { _ / _ } / "viewHolders.scala"
        val vhTemplate = IO.readLinesURL(
          resourceUrl("viewHolders.scala.template")) mkString "\n"
        vhs.delete()

      val layouts = files.map { f =>
        val parent = f.getParentFile.getName
        LayoutFile(f.getName.stripSuffix(".xml"), parent.split("-").toList.drop(1).sorted, f)
      }
      val grouped = layouts.groupBy(_.name).mapValues(_.sorted)
      val viewholders = grouped.map { case (n, data) =>
        val main = data.head
        val rest = data.drop(1)
        val struct = parseLayout(main.name, main.path, false)

        struct.copy(configs =
          rest.map(l => parseLayout(l.configs.map(_.capitalize).mkString, l.path, false)).toList)
      }.map(s => s.name -> s).toMap

      val (vhlist, facts) = viewholders.values.map { s =>
        val wname = wrap(s.name)
        val rootClass = classForLabel(j, s.rootView).getOrElse("android.view.View")
        val views = s.views.map {
          case LayoutView(name, id, viewType) =>
            val castType = classForLabel(j, viewType).getOrElse("android.view.View")
            val cast = if (castType == "android.view.View") "" else s".asInstanceOf[$castType]"
            s"    lazy val ${wrap(name)} = rootView.findViewById($id)$cast"
          case LayoutInclude(id, included) => "TODO INCLUDE"
        }
        val vh = s"""  case class $wname(val rootView: $rootClass) extends TypedViewHolder[$rootClass] {
                     |    val rootViewId = ${s.rootId.getOrElse("-1")}
                     |${views.mkString("\n")}
                     |  }""".stripMargin

        val vhname = s"TypedViewHolder.${wrap(s.name)}"
        val f = s"""  implicit val ${s.name}_ViewHolderFactory: TypedViewHolderFactory[TR.layout.${wrap(s.name)}.type] { type VH = $vhname }  = new TypedViewHolderFactory[TR.layout.$wname.type] {
                    |    type V = $rootClass
                    |    type VH = $vhname
                    |    def create(v: V): $vhname = new $vhname(v)
                    |  }""".stripMargin

        println(vh)
        println(f)
        (vh,f)
      }.unzip

        IO.write(vhs, vhTemplate format (pkg, facts.mkString("\n"), vhlist.mkString("\n")))
//        Set(vhs)
//      }(files.toSet).toSeq
      Seq(vhs)
    }
  }
}
