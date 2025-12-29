package config

import ciris.*
import com.comcast.ip4s.{ Host, Port }
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString

object types:

  case class AppConfig(postgreSQL: PostgreSQLConfig, httpServerConfig: HttpServerConfig)

  case class PostgreSQLConfig(
    host: NonEmptyString,
    port: Port,
    user: NonEmptyString,
    password: Secret[NonEmptyString],
    database: NonEmptyString,
    max: PosInt
  )

  case class HttpServerConfig(host: Host, port: Port)

  case class PostGresHostPort(host: NonEmptyString, port: Port)
