import ReleaseTransformations._
import com.jsuereth.sbtpgp.PgpKeys
import org.wartremover.TravisYaml.travisScalaVersions
import xsbti.api.{ClassLike, DefinitionType}
import scala.reflect.NameTransformer
import java.lang.reflect.Modifier

Global / onChangedBuildSource := ReloadOnSourceChanges

val latestScala211 = settingKey[String]("")
val latestScala212 = settingKey[String]("")
val latestScala213 = settingKey[String]("")

def latest(n: Int, versions: Seq[String]) = {
  val prefix = "2." + n + "."
  prefix + versions.filter(_ startsWith prefix).map(_.drop(prefix.length).toLong).reduceLeftOption(_ max _).getOrElse(s"not found Scala ${prefix}x version ${versions}")
}

lazy val baseSettings = Def.settings(
  latestScala211 := latest(11, travisScalaVersions.value),
  latestScala212 := latest(12, travisScalaVersions.value),
  latestScala213 := latest(13, travisScalaVersions.value),
  scalacOptions ++= Seq(
    "-deprecation"
  ),
  scalaVersion := latestScala212.value,
)

lazy val commonSettings = Def.settings(
  baseSettings,
  Seq(packageBin, packageDoc, packageSrc).flatMap {
    // include LICENSE file in all packaged artifacts
    inTask(_)(Seq(
      mappings in Compile += ((baseDirectory in ThisBuild).value / "LICENSE") -> "LICENSE"
    ))
  },
  organization := "org.wartremover",
  licenses := Seq(
    "The Apache Software License, Version 2.0" ->
      url("http://www.apache.org/licenses/LICENSE-2.0.txt")
  ),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  scalacOptions in (Compile, doc) ++= {
    val base = (baseDirectory in LocalRootProject).value.getAbsolutePath
    val t = sys.process.Process("git rev-parse HEAD").lineStream_!.head
    Seq(
      "-sourcepath",
      base,
      "-doc-source-url",
      "https://github.com/wartremover/wartremover/tree/" + t + "€{FILE_PATH}.scala"
    )
  },
  publishTo := sonatypePublishToBundle.value,
  homepage := Some(url("http://wartremover.org")),
  pomExtra :=
    <scm>
      <url>git@github.com:wartremover/wartremover.git</url>
      <connection>scm:git:git@github.com:wartremover/wartremover.git</connection>
    </scm>
    <developers>
      <developer>
        <id>puffnfresh</id>
        <name>Brian McKenna</name>
        <url>http://brianmckenna.org/</url>
      </developer>
      <developer>
        <name>Chris Neveu</name>
        <url>http://chrisneveu.com</url>
      </developer>
    </developers>
)

commonSettings
publishArtifact := false
releaseCrossBuild := true
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommandAndRemaining("+publishSigned"),
  releaseStepCommand("sonatypeBundleRelease"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)

val coreId = "core"

def crossSrcSetting(c: Configuration) = {
  unmanagedSourceDirectories in c += {
    val dir = (baseDirectory in LocalProject(coreId)).value / "src" / Defaults.nameForSrc(c.name)
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, v)) if v >= 13 =>
        dir / s"scala-2.13+"
      case _ =>
        dir / s"scala-2.13-"
    }
  }
}

val coreSettings = Def.settings(
  commonSettings,
  name := "wartremover",
  fork in Test := true,
  crossScalaVersions := travisScalaVersions.value,
  libraryDependencies := {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, v)) if v >= 13 =>
        libraryDependencies.value :+ ("org.scala-lang.modules" %% "scala-xml" % "1.2.0" % "test")
      case _ =>
        libraryDependencies.value
    }
  },
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value
  ),
  libraryDependencies ++= {
    Seq("org.scalatest" %% "scalatest" % "3.1.1" % "test")
  },
  pomPostProcess := { node =>
    import scala.xml._
    import scala.xml.transform._
    val strip = new RewriteRule {
      override def transform(n: Node) =
        if ((n \ "groupId").text == "test-macros" && (n \ "artifactId").text.startsWith("test-macros_"))
          NodeSeq.Empty
        else
          n
    }
    new RuleTransformer(strip).transform(node)(0)
  },
  assemblyOutputPath in assembly := file("./wartremover-assembly.jar")
)

lazy val coreCrossBinary = Project(
  id = "core-cross-binary",
  base = file("core-cross-binary")
).settings(
  coreSettings,
  crossSrcSetting(Compile),
  Compile / scalaSource := (core / Compile / scalaSource).value,
  Compile / resourceDirectory := (core / Compile / resourceDirectory).value,
  crossScalaVersions := Seq(latestScala211.value, latestScala212.value, latestScala213.value),
  crossVersion := CrossVersion.binary
)
  .dependsOn(testMacros % "test->compile")
  .enablePlugins(TravisYaml)


lazy val core = Project(
  id = coreId,
  base = file("core")
).settings(
  coreSettings,
  crossSrcSetting(Compile),
  crossSrcSetting(Test),
  crossScalaVersions := travisScalaVersions.value,
  crossVersion := CrossVersion.full,
  crossTarget := {
    // workaround for https://github.com/sbt/sbt/issues/5097
    target.value / s"scala-${scalaVersion.value}"
  },
  assemblyOutputPath in assembly := file("./wartremover-assembly.jar")
)
  .dependsOn(testMacros % "test->compile")
  .enablePlugins(TravisYaml)

val wartClasses = Def.task {
  val loader = (testLoader in (core, Test)).value
  val wartTraverserClass = Class.forName("org.wartremover.WartTraverser", false, loader)
  Tests.allDefs((compile in (core, Compile)).value).collect{
    case c: ClassLike =>
      val decoded = c.name.split('.').map(NameTransformer.decode).mkString(".")
      c.definitionType match {
        case DefinitionType.Module =>
          decoded + "$"
        case _ =>
          decoded
      }
  }
  .flatMap(c =>
    try {
      List[Class[_]](Class.forName(c, false, loader))
    } catch {
      case _: ClassNotFoundException =>
        Nil
    }
  )
  .filter(c => !Modifier.isAbstract(c.getModifiers) && wartTraverserClass.isAssignableFrom(c))
  .map(_.getSimpleName.replace("$", ""))
  .filterNot(Set("Unsafe", "ForbidInference")).sorted
}

lazy val sbtPlug: Project = Project(
  id = "sbt-plugin",
  base = file("sbt-plugin")
).settings(
  commonSettings,
  name := "sbt-wartremover",
  sbtPlugin := true,
  scriptedBufferLog := false,
  scriptedLaunchOpts ++= {
    val javaVmArgs = {
      import scala.collection.JavaConverters._
      java.lang.management.ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toList
    }
    javaVmArgs.filter(
      a => Seq("-Xmx", "-Xms", "-XX", "-Dsbt.log.noformat").exists(a.startsWith)
    )
  },
  scriptedLaunchOpts += ("-Dplugin.version=" + version.value),
  crossScalaVersions := Seq(latestScala212.value),
  sourceGenerators in Compile += Def.task {
    val base = (sourceManaged in Compile).value
    val file = base / "wartremover" / "Wart.scala"
    val warts = wartClasses.value
    val expectCount = 36
    assert(
      warts.size == expectCount,
      s"${warts.size} != ${expectCount}. please update build.sbt when add or remove wart"
    )
    val wartsDir = core.base / "src" / "main" / "scala" / "wartremover" / "warts"
    val unsafe = warts.filter(IO.read(wartsDir / "Unsafe.scala") contains _)
    val content =
      s"""package wartremover
         |// Autogenerated code, see build.sbt.
         |final class Wart private[wartremover](val clazz: String) {
         |  override def toString: String = clazz
         |}
         |object Wart {
         |  val PluginVersion: String = "${version.value}"
         |  private[wartremover] lazy val AllWarts = List(${warts mkString ", "})
         |  private[wartremover] lazy val UnsafeWarts = List(${unsafe mkString ", "})
         |  /** A fully-qualified class name of a custom Wart implementing `org.wartremover.WartTraverser`. */
         |  def custom(clazz: String): Wart = new Wart(clazz)
         |  private[this] def w(nm: String): Wart = new Wart(s"org.wartremover.warts.$$nm")
         |""".stripMargin +
        warts.map(w => s"""  val $w = w("${w}")""").mkString("\n") + "\n}\n"
    IO.write(file, content)
    Seq(file)
  }
)
  .enablePlugins(ScriptedPlugin)
  .enablePlugins(TravisYaml)

lazy val testMacros: Project = Project(
  id = "test-macros",
  base = file("test-macros")
).settings(
  baseSettings,
  crossScalaVersions := travisScalaVersions.value,
  skip in publish := true,
  publishArtifact := false,
  publish := {},
  publishLocal := {},
  PgpKeys.publishSigned := {},
  PgpKeys.publishLocalSigned := {},
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided"
  )
).enablePlugins(TravisYaml)
