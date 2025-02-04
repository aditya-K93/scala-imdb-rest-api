import sbt._

object Dependencies {

  object V {
    val cats             = "2.6.1"
    val catsEffect       = "3.1.1"
    val circe            = "0.14.1"
    val ciris            = "2.0.1"
    val fs2              = "3.0.6"
    val http4s           = "1.0.0-M23"
    val log4cats         = "2.1.1"
    val newtype          = "0.4.4"
    val refined          = "0.9.26"
    val skunk            = "0.2.0"
    val betterMonadicFor = "0.3.1"
    val kindProjector    = "0.13.3"
    val logback          = "1.2.3"
    val organizeImports  = "0.5.0"

  }

  object Libraries {
    def circe(artifact: String): ModuleID  = "io.circe"   %% s"circe-$artifact"  % V.circe
    def ciris(artifact: String): ModuleID  = "is.cir"     %% artifact            % V.ciris
    def http4s(artifact: String): ModuleID = "org.http4s" %% s"http4s-$artifact" % V.http4s

    val cats       = "org.typelevel" %% "cats-core"   % V.cats
    val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
    val fs2        = "co.fs2"        %% "fs2-core"    % V.fs2

    val circeCore    = circe("core")
    val circeGeneric = circe("generic")
    val circeParser  = circe("parser")
    val circeRefined = circe("refined")

    val cirisCore    = ciris("ciris")
    val cirisEnum    = ciris("ciris-enumeratum")
    val cirisRefined = ciris("ciris-refined")

    val http4sDsl    = http4s("dsl")
    val http4sServer = http4s("ember-server")
    val http4sClient = http4s("ember-client")
    val http4sCirce  = http4s("circe")

    val refinedCore = "eu.timepit" %% "refined"      % V.refined
    val refinedCats = "eu.timepit" %% "refined-cats" % V.refined

    val log4cats = "org.typelevel" %% "log4cats-slf4j" % V.log4cats
    val newtype  = "io.estatico"   %% "newtype"        % V.newtype

    val skunkCore  = "org.tpolecat" %% "skunk-core"  % V.skunk
    val skunkCirce = "org.tpolecat" %% "skunk-circe" % V.skunk

    // Runtime
    val logback = "ch.qos.logback" % "logback-classic" % V.logback

    // Scalafix rules
    val organizeImports = "com.github.liancheng" %% "organize-imports" % V.organizeImports
  }

  object CompilerPlugin {
    val betterMonadicFor = compilerPlugin(
      "com.olegpy" %% "better-monadic-for" % V.betterMonadicFor
    )
    val kindProjector = compilerPlugin(
      "org.typelevel" % "kind-projector" % V.kindProjector cross CrossVersion.full
    )

  }

}
