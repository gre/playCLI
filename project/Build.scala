import sbt._
import Keys._

object BuildSettings {
  val buildVersion = "0.1"

  val buildSettings = Defaults.defaultSettings ++ Seq(
    organization := "fr.greweb",
    version := buildVersion,
    scalaVersion := "2.10.0",
    crossScalaVersions := Seq("2.10.0"),
    crossVersion := CrossVersion.binary
  ) ++ Publish.settings
}

object Publish {
  object TargetRepository {
    def sonatype: Project.Initialize[Option[sbt.Resolver]] = version { (version: String) =>
      val nexus = "https://oss.sonatype.org/"
      if (version.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    }
  }
  lazy val settings = Seq(
    publishTo <<= TargetRepository.sonatype,
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    licenses := Seq("Apache 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    homepage := Some(url("http://greweb.fr/playcli")),
    pomExtra := (
      <scm>
        <url>git://github.com/gre/playCLI.git</url>
        <connection>scm:git://github.com/gre/playCLI.git</connection>
      </scm>
      <developers>
        <developer>
          <id>greweb</id>
          <name>Gaëtan Renaudeau</name>
          <url>http://greweb.fr/</url>
        </developer>
      </developers>)
    )
}

object CLIBuild extends Build {
  import BuildSettings._

  val logbackVer = "1.0.9"

  lazy val cli = Project(
    "playCLI",
    file("."),
    settings = buildSettings ++ Seq(
      resolvers := Seq(
        "Typesafe repository snapshots" at "http://repo.typesafe.com/typesafe/snapshots/",
        "Typesafe repository releases" at "http://repo.typesafe.com/typesafe/releases/"
      ),
      libraryDependencies ++= Seq(
        "play" %% "play-iteratees" % "2.1-RC2",
        "com.typesafe" % "config" % "1.0.0",
        "ch.qos.logback" % "logback-core" % logbackVer,
        "ch.qos.logback" % "logback-classic" % logbackVer,
        "org.specs2" %% "specs2" % "1.12.3" % "test"
      )
    )
  )
}
