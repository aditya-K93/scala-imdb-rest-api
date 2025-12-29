package http

import domain.movieDetail.MovieDetail
import io.circe.generic.auto.*
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import services.{ CastAndCrew, Movies }

import cats.Monad
import cats.syntax.all.*

final case class MovieDetailRoutes[F[_]: Monad](movie: Movies[F], crew: CastAndCrew[F]) extends Http4sDsl[F]:

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] { case GET -> Root / titleName =>
    for
      movie        <- movie.findByTitle(titleName, titleName)
      castCrew     <- crew.findByTitleId(movie.fold("")(_.movieId))
      titleDetails <- Ok(MovieDetail(movie, castCrew))
    yield titleDetails

  }

  val prefixPath = "/movies"

  val routes: HttpRoutes[F] = Router(prefixPath -> httpRoutes)
