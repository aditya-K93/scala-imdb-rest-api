import sbt._

object Dependencies {

  object V {
    val cats             = "2.12.0"
    val catsEffect       = "3.6.3"
    val circe            = "0.14.12"
    val ciris            = "3.6.0"
    val fs2              = "3.12.2"
    val http4s           = "1.0.0-M23"
    val log4cats         = "2.7.0"
    val newtype          = "0.4.4"
    val refined          = "0.11.2"
    val skunk            = "0.6.4"
    val logback          = "1.5.19"
    val organizeImports  = "0.6.0"
    val munit            = "1.0.3"
    val munitScalacheck  = "1.2.0"
    val scalacheck       = "1.19.0"
    val scalacheckEffect = "1.0.4"
    val munitCatsEffect  = "2.0.0"

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

    // Testing
    val munit            = "org.scalameta"  %% "munit"             % V.munit            % Test
    val munitScalacheck  = "org.scalameta"  %% "munit-scalacheck"  % V.munitScalacheck  % Test
    val scalacheck       = "org.scalacheck" %% "scalacheck"        % V.scalacheck       % Test
    val scalacheckEffect = "org.typelevel"  %% "scalacheck-effect" % V.scalacheckEffect % Test
    val munitCatsEffect  = "org.typelevel"  %% "munit-cats-effect" % V.munitCatsEffect  % Test

    // Scalafix rules
    val organizeImports = "com.github.liancheng" %% "organize-imports" % V.organizeImports
  }

}
