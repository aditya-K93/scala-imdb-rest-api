package http

import cats.Monad
import org.http4s._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import services.{ CastAndCrew, Movies }
import io.circe.generic.auto._
import cats.syntax.all._
import domain.movieDetail.MovieDetail

final case class MovieDetailRoutes[F[_]: Monad](
    movie: Movies[F],
    crew: CastAndCrew[F]
) extends Http4sDsl[F] {

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / titleName =>
      for {
        movie        <- movie.findByTitle(titleName, titleName)
        castCrew     <- crew.findByTitleId(movie.fold("")(_.movieId))
        titleDetails <- Ok(MovieDetail(movie, castCrew))

      } yield titleDetails

  }
  val prefixPath = "/movies"

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
