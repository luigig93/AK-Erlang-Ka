name := "ak-erl-ka"

version := "0.1"

scalaVersion := "2.13.3"

val AkkaVersion = "2.6.9"
libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "ch.qos.logback" % "logback-classic" % "1.2.3"
)
