name := "grid-engine-tools"

version := "0.1.0"

scalaVersion := "2.11.7"

libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.0.5"

assemblyJarName in assembly := "grid-engine-tools.jar"

target in assembly := file("assembly")

assemblyExcludedJars in assembly := {
  val cp = (fullClasspath in assembly).value
  cp filter { _.data.getName == "jgdi.jar" }
}
