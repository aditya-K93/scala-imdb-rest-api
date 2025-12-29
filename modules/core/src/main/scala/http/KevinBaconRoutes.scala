package http

import cats.Monad
import io.circe.generic.auto.*
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import services.KevinBaconDetails

final case class KevinBaconRoutes[F[_]: Monad](actorDetails: KevinBaconDetails[F]) extends Http4sDsl[F]:

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] { case GET -> Root / actorName =>
    Ok(actorDetails.degreesOfSeparationKevinBacon(actorName))

  }

  val prefixPath = "/kevinBaconNumber"

  val routes: HttpRoutes[F] = Router(prefixPath -> httpRoutes)
