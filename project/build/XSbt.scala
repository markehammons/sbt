import sbt._

class XSbt(info: ProjectInfo) extends ParentProject(info)
{
		/* Subproject declarations*/

	val launchInterfaceSub = project(launchPath / "interface", "Launcher Interface", new LaunchInterfaceProject(_))
	val launchSub = project(launchPath, "Launcher", new LaunchProject(_), launchInterfaceSub)

	val interfaceSub = project("interface", "Interface", new InterfaceProject(_))

	val controlSub = project(utilPath / "control", "Control", new Base(_))
	val collectionSub = project(utilPath / "collection", "Collections", new Base(_))
	val ioSub = project(utilPath / "io", "IO", new IOProject(_), controlSub)
	val classpathSub = project(utilPath / "classpath", "Classpath", new Base(_))

	val ivySub = project("ivy", "Ivy", new IvyProject(_), interfaceSub)
	val logSub = project(utilPath / "log", "Logging", new Base(_), interfaceSub)

	val compileInterfaceSub = project(compilePath / "interface", "Compiler Interface", new CompilerInterfaceProject(_), interfaceSub)

	val taskSub = project(tasksPath, "Tasks", new TaskProject(_), controlSub, collectionSub)
	val cacheSub = project(cachePath, "Cache", new CacheProject(_), taskSub, ioSub)
	val trackingSub = project(cachePath / "tracking", "Tracking", new Base(_), cacheSub)
	val compilerSub = project(compilePath, "Compile", new CompileProject(_),
		launchInterfaceSub, interfaceSub, ivySub, ioSub, classpathSub, compileInterfaceSub)
	val stdTaskSub = project(tasksPath / "standard", "Standard Tasks", new StandardTaskProject(_), trackingSub, compilerSub)

	val mainSub = project("main", "Main", new Base(_), stdTaskSub)
	val distSub = project("dist", "Distribution", new DistProject(_))

		/* Multi-subproject paths */
	def cachePath = path("cache")
	def tasksPath = path("tasks")
	def launchPath = path("launch")
	def utilPath = path("util")
	def compilePath = path("compile")

	class DistProject(info: ProjectInfo) extends Base(info) with ManagedBase
	{
		lazy val interDependencies = (XSbt.this.dependencies.toList -- List(distSub, launchSub, launchInterfaceSub, interfaceSub, compileInterfaceSub)) flatMap {
			 case b: Base =>  b :: Nil; case _ => Nil
		}
		override def dependencies = interfaceSub :: compileInterfaceSub :: interDependencies
		lazy val dist = increment dependsOn(publishLocal)
		override def artifactID = "xsbt"
	}

	def increment = task {
		val Array(keep, inc) = projectVersion.value.toString.split("_")
		projectVersion() = Version.fromString(keep + "_" + (inc.toInt + 1)).right.get
		log.info("Version is now " + projectVersion.value)
		None
	}

	def compilerInterfaceClasspath = compileInterfaceSub.projectClasspath(Configurations.Test)

	//run in parallel
	override def parallelExecution = true

		/* Subproject configurations*/
	class LaunchProject(info: ProjectInfo) extends Base(info) with TestWithIO with TestDependencies with ProguardLaunch
	{
		val jline = "jline" % "jline" % "0.9.94"
		val ivy = "org.apache.ivy" % "ivy" % "2.0.0"
		def rawJarPath = jarPath
		lazy val rawPackage = packageTask(packagePaths +++ launchInterfaceSub.packagePaths, rawJarPath, packageOptions).dependsOn(compile)
		// to test the retrieving and loading of the main sbt, we package and publish the test classes to the local repository
		override def defaultMainArtifact = Artifact(idWithVersion)
		override def projectID = ModuleID(organization, idWithVersion, "test-" + version)
		override def packageAction = packageTask(packageTestPaths, outputPath / (idWithVersion + "-" + projectID.revision +".jar"), packageOptions).dependsOn(rawTestCompile)
		override def deliverProjectDependencies = Nil
		def idWithVersion = "xsbt_" + ScalaVersion.currentString
		lazy val rawTestCompile = super.testCompileAction
		override def testCompileAction = publishLocal dependsOn(rawTestCompile)
	}
	trait TestDependencies extends Project
	{
		val sc = "org.scala-tools.testing" % "scalacheck" % "1.5" % "test->default"
		val sp = "org.scala-tools.testing" % "specs" % "1.5.0" % "test->default"
		val ju = "junit" % "junit" % "4.5" % "test->default" // required by specs to compile properly
	}
	class StandardTaskProject(info: ProjectInfo) extends Base(info)
	{
		override def testClasspath = super.testClasspath +++ compilerSub.testClasspath --- compilerInterfaceClasspath
	}

	class IOProject(info: ProjectInfo) extends Base(info) with TestDependencies
	class TaskProject(info: ProjectInfo) extends Base(info) with TestDependencies
	class CacheProject(info: ProjectInfo) extends Base(info)
	{
		// these compilation options are useful for debugging caches and task composition
		//override def compileOptions = super.compileOptions ++ List(Unchecked,ExplainTypes, CompileOption("-Xlog-implicits"))
	}
	class Base(info: ProjectInfo) extends DefaultProject(info) with ManagedBase with Component
	{
		override def scratch = true
		override def consoleClasspath = testClasspath
	}
	class CompileProject(info: ProjectInfo) extends Base(info) with TestWithLog
	{
		override def testCompileAction = super.testCompileAction dependsOn(launchSub.testCompile, compileInterfaceSub.`package`, interfaceSub.`package`)
		 // don't include launch interface in published dependencies because it will be provided by launcher
		override def deliverProjectDependencies = Set(super.deliverProjectDependencies.toSeq : _*) - launchInterfaceSub.projectID
		override def testClasspath = super.testClasspath +++ launchSub.testClasspath +++ compileInterfaceSub.jarPath +++ interfaceSub.jarPath --- compilerInterfaceClasspath
		override def compileOptions = super.compileOptions ++ Seq(CompileOption("-Xno-varargs-conversion")) //needed for invoking nsc.scala.tools.Main.process(Array[String])
	}
	class IvyProject(info: ProjectInfo) extends Base(info) with TestWithIO with TestWithLog
	{
		val ivy = "org.apache.ivy" % "ivy" % "2.0.0"
	}
	class InterfaceProject(info: ProjectInfo) extends DefaultProject(info) with ManagedBase with TestWithLog with Component
	{
		// ensure that interfaces are only Java sources and that they cannot reference Scala classes
		override def mainSources = descendents(mainSourceRoots, "*.java")
		override def compileOrder = CompileOrder.JavaThenScala
		override def componentID: Option[String] = Some("xsbti")
	}
	class LaunchInterfaceProject(info: ProjectInfo) extends  InterfaceProject(info)
	{
		override def componentID = None
	}
	class CompilerInterfaceProject(info: ProjectInfo) extends Base(info) with SourceProject with TestWithIO with TestWithLog
	{
		def xTestClasspath =  projectClasspath(Configurations.Test)
		override def componentID = Some("compiler-interface-src")
	}
	trait TestWithIO extends BasicScalaProject
	{
		// use IO from tests
		override def testCompileAction = super.testCompileAction dependsOn(ioSub.testCompile)
		override def testClasspath = super.testClasspath +++ ioSub.testClasspath
	}
	trait TestWithLog extends BasicScalaProject
	{
		override def testCompileAction = super.testCompileAction dependsOn(logSub.compile)
		override def testClasspath = super.testClasspath +++ logSub.compileClasspath
	}
}

trait SourceProject extends BasicScalaProject
{
	override final def crossScalaVersions = Set.empty // don't need to cross-build a source package
	override def packagePaths = mainResources +++ mainSources // the default artifact is a jar of the main sources and resources
}
trait ManagedBase extends BasicScalaProject
{
	override def deliverScalaDependencies = Nil //
	override def crossScalaVersions = Set("2.7.5")
	override def managedStyle = ManagedStyle.Ivy
	override def useDefaultConfigurations = false
	val defaultConf = Configurations.Default
	val testConf = Configurations.Test
}
trait Component extends DefaultProject
{
	override def projectID = componentID match { case Some(id) => super.projectID extra("e:component" -> id); case None => super.projectID }
	def componentID: Option[String] = None
}