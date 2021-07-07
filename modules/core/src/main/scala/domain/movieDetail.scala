package domain

import domain.movie._
import domain.castCrew._

object movieDetail {

  //  describe movie ++ castCrew details
  case class MovieDetail(movie: Option[Movie], castAndCrew: List[Crew])

}
