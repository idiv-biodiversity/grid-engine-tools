// ----------------------------------------------------------------------------
// sbt plugins
// ----------------------------------------------------------------------------

enablePlugins(BuildInfoPlugin)
enablePlugins(GitVersioning)

// ----------------------------------------------------------------------------
// basic project settings
// ----------------------------------------------------------------------------

name := "grid-engine-tools"

git.baseVersion in ThisBuild := "0.7.2"

scalaVersion in ThisBuild := "2.12.8"

libraryDependencies += "org.typelevel" %% "cats-core" % "1.5.0"
libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.1.1"
libraryDependencies += "com.beachape" %% "enumeratum" % "1.5.13"
libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.14.0" % "test"

doctestTestFramework := DoctestTestFramework.ScalaCheck

fork in run := true

// ----------------------------------------------------------------------------
// scala compiler options
// ----------------------------------------------------------------------------

scalacOptions in ThisBuild ++= Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-feature",
  "-unchecked"
)

// ----------------------------------------------------------------------------
// build info
// ----------------------------------------------------------------------------

buildInfoKeys := Seq[BuildInfoKey](name, version)

buildInfoPackage := "grid.engine"

// ----------------------------------------------------------------------------
// linting
// ----------------------------------------------------------------------------

scalastyleConfig := file(".scalastyle-config.xml")

wartremoverErrors in (Compile, compile) ++= Seq(
  Wart.ArrayEquals,
  Wart.Equals,
  Wart.FinalCaseClass,
  Wart.OptionPartial,
  Wart.TryPartial
)

// ----------------------------------------------------------------------------
// assembly
// ----------------------------------------------------------------------------

assemblyJarName in assembly := "grid-engine-tools.jar"

target in assembly := file("assembly")

assemblyExcludedJars in assembly := {
  val cp = (fullClasspath in assembly).value
  cp filter { _.data.getName == "jgdi.jar" }
}

// ----------------------------------------------------------------------------
// install
// ----------------------------------------------------------------------------

val prefix = settingKey[String]("Installation prefix.")

val scripts = taskKey[Unit]("Creates the scripts.")

prefix := sys.env.getOrElse("PREFIX", "/usr/local")

scripts := {
  val scriptDir = target.value / "scripts"
  if (!scriptDir.exists) scriptDir.mkdir()

  val nameV = name.value
  val prefixV = prefix.value

  val classpath =
    s"${prefixV}/share/${nameV}/${nameV}.jar" ::
    "$SGE_ROOT/lib/jgdi.jar" ::
    Nil

  def script(clazz: String) =
    s"""|#!/bin/bash
        |java \\
        |  $${JAVA_OPTS:--Xmx1G} \\
        |  -cp "${classpath.mkString(":")}" \\
        |  '$clazz' \\
        |  "$$@"
        |""".stripMargin

  (discoveredMainClasses in Compile).value foreach { clazz =>
    val app = clazz.drop(clazz.lastIndexOf(".") + 1).replaceAll("\\$minus", "-")
    val s = scriptDir / app
    IO.write(s, script(clazz))
  }
}

scripts := (scripts dependsOn assembly).value
