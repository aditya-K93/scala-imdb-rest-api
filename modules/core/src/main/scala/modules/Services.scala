package modules

import services.*
import skunk.Session

import cats.effect.*

object Services:

  def make[F[_]: Concurrent](postgres: Resource[F, Session[F]]): Services[F] = new Services[F](
    movies = Movies.make[F](postgres),
    crew = CastAndCrew.make[F](postgres),
    ratings = Ratings.make[F](postgres),
    kevinBacon = KevinBaconDetails.make[F](postgres)
  ) {}

sealed abstract class Services[F[_]] private (
    val movies: Movies[F],
    val crew: CastAndCrew[F],
    val ratings: Ratings[F],
    val kevinBacon: KevinBaconDetails[F]
)
