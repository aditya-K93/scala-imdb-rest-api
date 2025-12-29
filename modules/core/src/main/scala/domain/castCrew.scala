package domain

object castCrew:

  // roles in a movie(actor,director,producer,writer) and corresponding people
  // describe name_basics and principals_basics table schema using \d+ <table> to generate domain model for crew
  // e.g. {"role": "actor":  "people":["Tom Cruise", "Dustin Hoffman"]

  case class Crew(role: Option[String], people: List[String])
