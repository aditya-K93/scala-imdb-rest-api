package config


import config.AppEnvironment._
import config.types._
import cats.effect.Async
import ciris._
import ciris.refined._
import com.comcast.ip4s._
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.string.NonEmptyString

object Config {

  def load[F[_]: Async]: F[AppConfig] =
    env("SC_APP_ENV")
      .as[AppEnvironment]
      .flatMap {
        case Test =>
          default[F](
            PostGresHostPort("localhost", port"5432")
          )
        case Prod =>
          default[F](
            PostGresHostPort("localhost", port"5432")
          )
      }
      .load[F]

  private def default[F[_]](
      postgresHostPortUri: PostGresHostPort
  ): ConfigValue[F, AppConfig] =
    (
      env("SC_POSTGRES_PASSWORD").as[NonEmptyString].secret
    ).map( { postgresPassword =>
      AppConfig(
        PostgreSQLConfig(
          host = postgresHostPortUri.host,
          port = postgresHostPortUri.port,
          user = "postgres",
          password = postgresPassword,
          database = "imdb",
          max = 10
        ),
        HttpServerConfig(
          host = host"0.0.0.0",
          port = port"8080"
        )
      )
    }
    )

}
