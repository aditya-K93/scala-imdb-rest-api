import Dependencies.{ Libraries, _ }

ThisBuild / scalaVersion                        := "3.7.3"
ThisBuild / version                             := "0.0.1"
ThisBuild / evictionErrorLevel                  := Level.Warn
ThisBuild / scalafixDependencies += Libraries.typelevelScalafix
ThisBuild / githubWorkflowJavaVersions          := Seq(JavaSpec.temurin("21"))
ThisBuild / githubWorkflowPublishTargetBranches := Seq()
Global / onChangedBuildSource                   := ReloadOnSourceChanges
ThisBuild / semanticdbEnabled                   := true
ThisBuild / semanticdbVersion                   := scalafixSemanticdb.revision
ThisBuild / scalacOptions ~= (_.filterNot(Set("-Werror", "-Xfatal-warnings")))
ThisBuild / scalacOptions ++= Seq("-Wunused:imports")

resolvers += Resolver.sonatypeCentralSnapshots

val scalafixCommonSettings = inConfig(Compile)(scalafixConfigSettings(Compile))
lazy val root              = (project in file(".")).settings(name := "imdb-assignment").aggregate(core, tests)

lazy val tests = (project in file("modules/tests")).settings(
  name := "imdb-assignment-test-suite",
  scalacOptions ++= Seq("-deprecation", "-feature"),
  scalafixCommonSettings,
  libraryDependencies ++= Seq(
    Libraries.munit,
    Libraries.munitScalacheck,
    Libraries.scalacheck,
    Libraries.scalacheckEffect,
    Libraries.munitCatsEffect
  )
).dependsOn(core)

lazy val core = (project in file("modules/core")).enablePlugins(AshScriptPlugin).settings(
  name                 := "imdb-assignment-core",
  Docker / packageName := "imdb-assignment",
  scalacOptions ++= Seq("-deprecation", "-feature"),
  Compile / run / fork := true,
  resolvers += Resolver.sonatypeCentralSnapshots,
  scalafixCommonSettings,
  libraryDependencies ++= Seq(
    Libraries.cats,
    Libraries.catsEffect,
    Libraries.circeCore,
    Libraries.circeGeneric,
    Libraries.circeParser,
    Libraries.cirisCore,
    Libraries.cirisEnum,
    Libraries.cirisRefined,
    Libraries.fs2,
    Libraries.http4sDsl,
    Libraries.http4sServer,
    Libraries.http4sClient,
    Libraries.http4sCirce,
    Libraries.log4cats,
    Libraries.logback % Runtime,
    Libraries.refinedCore,
    Libraries.refinedCats,
    Libraries.skunkCore,
    Libraries.skunkCirce
  )
)

addCommandAlias("runLinter", "; scalafixAll --check")
addCommandAlias("fixLinter", "; scalafixAll;scalafmtAll")
addCommandAlias("run", "; core/run")
