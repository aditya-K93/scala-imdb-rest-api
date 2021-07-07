package domain

import domain.movie.Movie

object rating {

  //  describe movie with rating schema
  case class Rating(movie: Movie, averageRating: Double, numOfVotes: Int)
}
