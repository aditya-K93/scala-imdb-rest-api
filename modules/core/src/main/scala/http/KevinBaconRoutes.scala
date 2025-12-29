package http

import io.circe.generic.auto.*
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import services.KevinBaconDetails

import cats.Monad

final case class KevinBaconRoutes[F[_]: Monad](actorDetails: KevinBaconDetails[F]) extends Http4sDsl[F]:

  // Query parameter matcher for max paths limit
  object MaxPathsQueryParamMatcher extends OptionalQueryParamDecoderMatcher[Int]("maxPaths")

  private val httpRoutes: HttpRoutes[F] = HttpRoutes
    .of[F] { case GET -> Root / actorName :? MaxPathsQueryParamMatcher(maxPathsOpt) =>
      maxPathsOpt match
        case Some(maxPaths) =>
          // Return paths when maxPaths is specified
          Ok(actorDetails.degreesOfSeparationKevinBaconWithPaths(actorName, Some(maxPaths)))
        case None =>
          // Default behavior: return just the number
          Ok(actorDetails.degreesOfSeparationKevinBacon(actorName))
    }

  val prefixPath = "/kevinBaconNumber"

  val routes: HttpRoutes[F] = Router(prefixPath -> httpRoutes)
