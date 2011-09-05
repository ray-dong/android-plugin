package android

import sbt._
import Process._
import Path._
import scala.xml._

abstract class AndroidProject(info: ProjectInfo) extends DefaultProject(info) with AndroidProjectPaths {

  import AndroidProject._

  lazy val manifest = Manifest(this)
  lazy val sdk = SDK(manifest.sdkVersion)

  lazy val libraryJarPath: sbt.Path = {
    log.info("Using version " + manifest.sdkVersion)
    getLibPath(this).getOrElse(sdk.androidJar)
    Path.fromFile("/opt/android-sdk-linux_86/platforms/android-8/android.jar")
  }

  def getLibPath(p: sbt.Project): Option[sbt.Path] = {
    p match {
      case x: AndroidLibraryProject ⇒ getLibPath(p.info.parent.get)
      case x: AndroidProject ⇒ Some(sdk.androidJar)
      case _ ⇒ None
    }
  }

  // def androidPlatformName = sdk.apiLevelToName(manifest.sdkVersion)  

  override def unmanagedClasspath = super.unmanagedClasspath +++ libraryJarPath

  /**
   * Gets assets folder for each of the plugin on top of the current project
   */
  lazy val assetDirectories: PathFinder = {
    (assetsDirectoryPath.asInstanceOf[sbt.PathFinder] /: this.dependencies) { (pf, project) ⇒
      if (project.isInstanceOf[AndroidLibraryProject]) pf +++ project.asInstanceOf[AndroidProject].assetDirectories else pf
    }
  } filter (_.exists)

  /**
   * Gets resource folder for each of the plugin on top of the current project
   */
  lazy val resDirectories: PathFinder = {
    (resDirectoryPath.asInstanceOf[sbt.PathFinder] /: this.dependencies) { (pf, project) ⇒
      if (project.isInstanceOf[AndroidLibraryProject]) pf +++ project.asInstanceOf[AndroidProject].resDirectories else pf
    }
  } filter (_.exists)

  /*
   * Android Asset Packaging Tool, generating the R file
   */
  lazy val aapt = aaptGenerateAction
  def aaptGenerateAction = aaptGenerateTask describedAs ("Generating R file")
  def aaptGenerateTask = execTask {
    log.info("generating the R file")
    swi
    <x>{ sdk.aapt.absolutePath } package --auto-add-overlay -m -M { manifestPath.absolutePath } -S { resDirectories.getPaths.mkString(" -S ") } -I { libraryJarPath.absolutePath } -J { genDirectoryPath.absolutePath }</x>

    /*
     * for each library do the above with --custom-package <original package<
     */
  } dependsOn directory("gen")

  lazy val aidl = aaptGenerateAction

  def directory(dir: Path) = fileTask(dir :: Nil) {
    FileUtilities.createDirectory(dir, log)
  }

  class DoubleEntrySource extends MainCompileConfig {
    override def sources = descendents(mainJavaSourcePath, "*.java") +++ descendents("gen", "R.java")
  }

  override def mainCompileConfiguration = new DoubleEntrySource

  /**
   * TASKS
   */
  override def compileAction = super.compileAction dependsOn (aapt, aidl)

  lazy val dex = dexAction
  def dexAction = dexTask describedAs ("Converting .class to dex files")
  def dexTask = execTask {
    <x>{ sdk.dex.absolutePath } { "-JXmx512m" } --dex --output={ classesDexPath.absolutePath } { mainCompilePath.absolutePath } { " " }</x>
  } dependsOn (compile)

  /**
   * Package into APK
   */
  override def packageAction = super.packageAction dependsOn (packageIntoApkAction)

  def aaptPackageAction = aaptPackageTask dependsOn (dex) describedAs ("Package resources and assets.")
  def aaptPackageTask = execTask {
    log.info("packaging resources into temporary APK file")
    log.debug("the following asset folders will be included: %s".format(assetDirectories.getPaths.mkString(",")))
    <x>{ sdk.aapt.absolutePath } package --auto-add-overlay -f -M { manifestPath.absolutePath } -S { resDirectories.getPaths.mkString(" -S ") } -A { assetDirectories.getPaths.mkString(" -A ") } -I { libraryJarPath.absolutePath } -F { tmpResApkPath.absolutePath }</x>
  } dependsOn directory(assetsDirectoryPath)

  lazy val cleanApk = cleanTask(apkPath) describedAs ("Remove apk package")

  def packageIntoApkAction = packageApkTask(false) dependsOn (aaptPackageAction)

  def packageApkTask(signPackage: Boolean) = task {
    log.info("Creating final APK in debug mode")
    ApkBuilder(this).create
  } dependsOn (cleanApk)
}

abstract class AndroidLibraryProject(info: ProjectInfo) extends AndroidProject(info) {

  /**
   * Use the android.jar from Parent.
   */
  def doNothing = task { None }
  override def packageAction = doNothing
  override def publishLocalAction = doNothing
  override def deliverLocalAction = doNothing
  override def publishAction = doNothing
  override def deliverAction = doNothing
}

/**
 * Task description
 */
object AndroidProject {
  val AaptDescription = "Generate resource file for the given project."
  val DexDescription = "Convert .class file to classes.dex."
  val AaptPackageDescription = "Package the project into deployable APK."
  val SignForReleaseDescription = "Signs the APK in order to release to market."
  val SignForDebugDescription = "Signs the APK with debug key located in ~/.android/debug.keystore."

  def apply(info: ProjectInfo) = {
  }
}

/**
 * Some Android specific Layout and folder structure
 */
trait AndroidProjectPaths extends DefaultProject {
  import AndroidProjectPaths._

  def defaultApkName = name + "_" + version + ".apk"

  // Override defaults to fit Android structure
  override def testSourcePath = path(DefaultTestDirectoryName)
  override def outputDirectoryName = DefaultMainCompileDirectoryName
  override def mainSourcePath = path(DefaultSourceDirectoryName)
  override def mainJavaSourcePath = path(DefaultSourceDirectoryName)

  def resDirectoryName = DefaultResourcesDirectoryName
  def resDirectoryPath = path(resDirectoryName)
  def genDirectoryName = DefaultGeneratedDirectoryName
  def genDirectoryPath = path(genDirectoryName)
  def assetsDirectoryName = DefaultAssetsDirectoryName
  def assetsDirectoryPath = path(assetsDirectoryName)
  def classesDexName = DefaultClassesDexName
  def classesDexPath = outputPath / classesDexName
  def apkPath = outputPath / defaultApkName

  def manifestName = DefaultManifestName
  def manifestPath = info.projectPath / manifestName

  def tmpResApkName = DefaultTmpResourceApk
  def tmpResApkPath = outputPath / tmpResApkName

  // Tests
  def testLocalSourcePath = testSourcePath / DefaultLocalTestDirectoryName
  def testInstrumenationSourcePath = testSourcePath / DefaultInstrumentationTestDirectoryName
  def testMonkeySourcePath = testSourcePath / DefaultMonkeyTestDirectoryName
}

/**
 * Default Paths for Android project with Monkey, Instrumentation and Local Unit tests
 */
object AndroidProjectPaths {
  val DefaultSourceDirectoryName = "src"
  val DefaultMainCompileDirectoryName = "bin"
  val DefaultResourcesDirectoryName = "res"
  val DefaultGeneratedDirectoryName = "gen"
  val DefaultAssetsDirectoryName = "assets"

  def DefaultClassesDexName = "classes.dex"
  def DefaultTmpResourceApk = "tmp.apk"
  def DefaultManifestName = "AndroidManifest.xml"

  val DefaultTestDirectoryName = "tests"
  val DefaultLocalTestDirectoryName = "local"
  val DefaultInstrumentationTestDirectoryName = "instrumentation"
  val DefaultMonkeyTestDirectoryName = "monkey"
}