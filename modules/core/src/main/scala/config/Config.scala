package config

import cats.effect.Async
import ciris.*
import ciris.refined.*
import com.comcast.ip4s.*
import config.AppEnvironment.*
import config.types.*
import eu.timepit.refined.api.Refined
import eu.timepit.refined.cats.*
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString

object Config:

  def load[F[_]: Async]: F[AppConfig] = env("SC_APP_ENV").as[AppEnvironment].flatMap {
    case Test => default[F](
        PostGresHostPort(refineV[eu.timepit.refined.collection.NonEmpty]("localhost").toOption.get, port"5432")
      )
    case Prod => default[F](
        PostGresHostPort(refineV[eu.timepit.refined.collection.NonEmpty]("localhost").toOption.get, port"5432")
      )
  }.load[F]

  private def default[F[_]](postgresHostPortUri: PostGresHostPort): ConfigValue[F, AppConfig] = env(
    "SC_POSTGRES_PASSWORD"
  ).as[NonEmptyString].secret.map(postgresPassword =>
    AppConfig(
      PostgreSQLConfig(
        host = postgresHostPortUri.host,
        port = postgresHostPortUri.port,
        user = refineV[eu.timepit.refined.collection.NonEmpty]("postgres").toOption.get,
        password = postgresPassword,
        database = refineV[eu.timepit.refined.collection.NonEmpty]("imdb").toOption.get,
        max = refineV[eu.timepit.refined.numeric.Positive](10).toOption.get
      ),
      HttpServerConfig(host = host"0.0.0.0", port = port"8080")
    )
  )
