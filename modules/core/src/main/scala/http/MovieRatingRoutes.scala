package http

import cats.Monad
import org.http4s._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import services.Ratings
import io.circe.generic.auto._

final case class MovieRatingRoutes[F[_]: Monad](
    rating: Ratings[F]
) extends Http4sDsl[F] {

  object LimitQueryParam extends OptionalQueryParamDecoderMatcher[Int]("limit")

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / genreName :? LimitQueryParam(limit) =>
      Ok(limit.fold(rating.findByGenre(genreName)())(l => rating.findByGenre(genreName)(l)))
  }
  val prefixPath = "/ratings"

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
