package http

import cats.Monad
import org.http4s._
import org.http4s.circe.CirceEntityEncoder._
import io.circe.generic.auto._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import services.KevinBaconDetails

final case class KevinBaconRoutes[F[_]: Monad](
    actorDetails: KevinBaconDetails[F]
) extends Http4sDsl[F] {

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / titleName =>
      Ok(actorDetails.degreesOfSeparationKevinBacon(titleName))

  }
  val prefixPath = "/kevinBaconNumber"

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
