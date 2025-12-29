package domain

object baconNumber:

//  describe baconNumber
  case class BaconNumber(baconNumber: Int)

  /** Represents a single step in the path from one actor to another through a movie. */
  case class PathStep(actorName: String, movieTitle: String)

  /** Represents a complete path from Kevin Bacon to the target actor. */
  case class BaconPath(baconNumber: Int, path: List[PathStep])

  /** Response containing the Bacon number and optionally multiple shortest paths. */
  case class BaconNumberWithPaths(baconNumber: Int, paths: Option[List[BaconPath]])
