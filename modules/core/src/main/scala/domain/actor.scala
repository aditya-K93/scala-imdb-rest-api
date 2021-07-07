package domain

object actor {

//  describe actor
  case class Actor(ActorName: String, actorId: String, movieIdList: List[String])
//  val kevinBaconActorID = "nm0000102"
//  val KevinBacon: Actor = Actor("Kevin Bacon",kevinBaconActorID, List("t1","t2" )
}
