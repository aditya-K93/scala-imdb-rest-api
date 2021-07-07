package resources

import config.types._

import cats.effect.std.Console
import cats.effect.{ Concurrent, Resource }
import cats.syntax.all._

import eu.timepit.refined.auto._
import fs2.io.net.Network
import natchez.Trace.Implicits.noop
import org.typelevel.log4cats.Logger
import skunk._
import skunk.codec.text._
import skunk.implicits._

sealed abstract class AppResources[F[_]](
    val postgres: Resource[F, Session[F]]
)

object AppResources {

  def make[F[_]: Concurrent: Console: Logger: Network](
      cfg: AppConfig
  ): Resource[F, AppResources[F]] = {

    def checkPostgresConnection(
        postgres: Resource[F, Session[F]]
    ): F[Unit] =
      postgres.use { session =>
        session.unique(sql"select version();".query(text)).flatMap { v =>
          Logger[F].info(s"Connected to Postgres $v")
        }
      }

    def mkPostgreSqlResource(c: PostgreSQLConfig): SessionPool[F] =
      Session
        .pooled[F](
          host = c.host.value,
          port = c.port.value,
          user = c.user.value,
          password = Some(c.password.value),
          database = c.database.value,
          max = c.max.value
        )
        .evalTap(checkPostgresConnection)

    (
      mkPostgreSqlResource(cfg.postgreSQL)
    ).map(new AppResources[F](_) {})

  }

}
