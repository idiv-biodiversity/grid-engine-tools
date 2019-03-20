// ----------------------------------------------------------------------------
// sbt plugins
// ----------------------------------------------------------------------------

enablePlugins(BuildInfoPlugin)
enablePlugins(GitVersioning)

// ----------------------------------------------------------------------------
// basic project settings
// ----------------------------------------------------------------------------

name := "grid-engine-tools"

git.baseVersion in ThisBuild := "0.8.0"

scalaVersion in ThisBuild := "2.12.8"

libraryDependencies += "org.typelevel" %% "cats-core" % "1.6.0"
libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.1.1"
libraryDependencies += "com.beachape" %% "enumeratum" % "1.5.13"
libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.14.0" % "test"
libraryDependencies += "com.github.scopt" %% "scopt" % "3.7.1"
libraryDependencies +=
  "com.github.wookietreiber" %% "scala-cli-tools" % "0.3.1"

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
  "-unchecked",
  "-target:jvm-1.8",
  "-Xfuture",
  "-Xlint"
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

assemblyJarName in assembly := s"""${name.value}.jar"""

// ----------------------------------------------------------------------------
// scripts / install
// ----------------------------------------------------------------------------

val prefix = settingKey[String]("Installation prefix.")

val scriptsDir = settingKey[File]("Target path to scripts.")

val scripts = taskKey[Unit]("Creates the scripts.")

val install = taskKey[Unit]("Install to prefix.")

prefix := sys.env.getOrElse("PREFIX", "/usr/local")

scriptsDir := target.value / "scripts"

scripts := {
  val dir = scriptsDir.value
  if (!dir.exists) dir.mkdir()

  val p = prefix.value
  val n = name.value

  def script(clazz: String) =
    s"""|#!/bin/bash
        |java \\
        |  $${JAVA_OPTS:--Xmx1G} \\
        |  -cp "${p}/share/${n}/${n}.jar" \\
        |  '$clazz' \\
        |  "$$@"
        |""".stripMargin

  (discoveredMainClasses in Compile).value foreach { clazz =>
    val app = clazz
      .drop(clazz.lastIndexOf(".") + 1)
      .replaceAll("\\$minus", "-")

    val s = dir / app
    IO.write(s, script(clazz))
    s.setExecutable(true)
  }
}

scripts := (scripts dependsOn assembly).value

install := {
  import java.nio.file.{Files, StandardCopyOption}

  val s = (scriptsDir.value * "*").get
  val j = assembly.value
  val p = file(prefix.value)

  val bindir = p / "bin"
  if (!bindir.exists) bindir.mkdirs()

  for (script <- s) {
    val source = script.toPath
    val target = (bindir / s"""${script.name}""").toPath

    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES,
      StandardCopyOption.REPLACE_EXISTING)
  }

  IO.copyFile(
    sourceFile = j,
    targetFile = p / "share" / name.value / (name.value + ".jar")
  )

  val bashScripts = ((sourceDirectory.value / "main" / "bash") * "*.sh").get

  for (script ← bashScripts) {
    val source = script.toPath
    val target = (bindir / script.name.replaceAll(".sh", "")).toPath

    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES,
      StandardCopyOption.REPLACE_EXISTING)

    target.toFile.setExecutable(true)
  }

  val other = List("LICENSE", "NOTICE.md")

  for (f ← other) {
    IO.copyFile(
      sourceFile = file(f),
      targetFile = p / "share" / name.value / f
    )
  }
}

install := (install dependsOn scripts).value
