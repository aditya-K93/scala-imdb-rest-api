package domain

import domain.castCrew.*
import domain.movie.*

object movieDetail:

  //  describe movie ++ castCrew details
  case class MovieDetail(movie: Option[Movie], castAndCrew: List[Crew])
