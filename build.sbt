enablePlugins(GitVersioning)

import sbt._
import Keys._
import Process._

name := "grid-engine-tools"

scalaVersion := "2.11.8"

libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.0.5"

assemblyJarName in assembly := "grid-engine-tools.jar"

target in assembly := file("assembly")

assemblyExcludedJars in assembly := {
  val cp = (fullClasspath in assembly).value
  cp filter { _.data.getName == "jgdi.jar" }
}

val scripts = taskKey[Unit]("Creates the scripts.")

scripts := {
  val scriptDir = target.value / "scripts"
  if (!scriptDir.exists) scriptDir.mkdir()

  val prefix = sys.env.getOrElse("PREFIX", "/usr/local")

  def script(clazz: String) =
    s"""|#!/bin/sh
        |java -cp "${prefix}/share/grid-engine-tools/grid-engine-tools.jar:$$SGE_ROOT/lib/jgdi.jar" '$clazz'
        |""".stripMargin

  (discoveredMainClasses in Compile).value foreach { clazz =>
    val app = clazz.drop(clazz.lastIndexOf(".") + 1).replaceAll("\\$minus", "-")
    val s = scriptDir / app
    IO.write(s, script(clazz))
  }
}

scripts <<= scripts dependsOn assembly
