package modules

import http.{ KevinBaconRoutes, MovieDetailRoutes, MovieRatingRoutes, version }

import scala.concurrent.duration._
import cats.effect.Async
import cats.syntax.all._
import org.http4s._
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.middleware._

object HttpApi {
  def make[F[_]: Async](
      services: Services[F]
  ): HttpApi[F] =
    new HttpApi[F](services) {}
}

sealed abstract class HttpApi[F[_]: Async] private (
    services: Services[F]
) {

  private val movieDetailRoutes = MovieDetailRoutes[F](services.movies, services.crew).routes
  private val movieRatingRoutes = MovieRatingRoutes[F](services.ratings).routes
  private val kevinBaconRoutes  = KevinBaconRoutes[F](services.kevinBacon).routes

  // Combining all the http routes
  private val openRoutes: HttpRoutes[F] =
    movieDetailRoutes <+> movieRatingRoutes <+> kevinBaconRoutes

  private val routes: HttpRoutes[F] = Router(
    version.v1 -> openRoutes
  )

  private val middleware: HttpRoutes[F] => HttpRoutes[F] = {
    { http: HttpRoutes[F] =>
      AutoSlash(http)
    } andThen { http: HttpRoutes[F] =>
      CORS(http)
    } andThen { http: HttpRoutes[F] =>
      Timeout(60.seconds)(http)
    }
  }

  private val loggers: HttpApp[F] => HttpApp[F] = {
    { http: HttpApp[F] =>
      RequestLogger.httpApp(true, true)(http)
    } andThen { http: HttpApp[F] =>
      ResponseLogger.httpApp(true, true)(http)
    }
  }

  val httpApp: HttpApp[F] = loggers(middleware(routes).orNotFound)

}
