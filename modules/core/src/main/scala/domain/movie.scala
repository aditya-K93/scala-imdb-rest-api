package domain

object movie:

//  describe title basics schema using \d+ title_basics to generate domain model
  case class Movie(
      movieId: String,
      titleType: Option[String],
      primaryTitle: Option[String],
      originalTitle: Option[String],
      ratedAdult: Option[Boolean],
      yearReleased: Option[Int],
      yearEnded: Option[Int],
      runtimeInMinutes: Option[Int],
      genres: Option[String]
  )
