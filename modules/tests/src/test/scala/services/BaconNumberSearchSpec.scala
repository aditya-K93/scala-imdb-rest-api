package services

import cats.effect.IO
import munit.CatsEffectSuite

class BaconNumberSearchSpec extends CatsEffectSuite:

  test("bidirectionalBfs returns 0 when start == target") {
    KevinBaconDetails
      .bidirectionalBfs[IO](startActor = "A", targetActor = "A", maxDepth = 6, neighbors = _ => IO.pure(Set.empty))
      .map(result => assertEquals(result, Some(0)))
  }

  test("bidirectionalBfs finds the shortest path length") {
    // A -- B -- C -- D
    val adj: Map[String, Set[String]] =
      Map("A" -> Set("B"), "B" -> Set("A", "C"), "C" -> Set("B", "D"), "D" -> Set("C"))

    def neighbors(frontier: Set[String]): IO[Set[String]] = IO.pure(frontier.flatMap(n => adj.getOrElse(n, Set.empty)))

    KevinBaconDetails.bidirectionalBfs[IO](startActor = "A", targetActor = "D", maxDepth = 10, neighbors = neighbors)
      .map(result => assertEquals(result, Some(3)))
  }

  test("bidirectionalBfs respects maxDepth") {
    // A -- B -- C
    val adj: Map[String, Set[String]] = Map("A" -> Set("B"), "B" -> Set("A", "C"), "C" -> Set("B"))

    def neighbors(frontier: Set[String]): IO[Set[String]] = IO.pure(frontier.flatMap(n => adj.getOrElse(n, Set.empty)))

    KevinBaconDetails.bidirectionalBfs[IO](startActor = "A", targetActor = "C", maxDepth = 1, neighbors = neighbors)
      .map(result => assertEquals(result, None))
  }

  test("bidirectionalBfs returns None when disconnected") {
    val adj: Map[String, Set[String]] = Map("A" -> Set("B"), "B" -> Set("A"), "X" -> Set("Y"), "Y" -> Set("X"))

    def neighbors(frontier: Set[String]): IO[Set[String]] = IO.pure(frontier.flatMap(n => adj.getOrElse(n, Set.empty)))

    KevinBaconDetails.bidirectionalBfs[IO](startActor = "A", targetActor = "Y", maxDepth = 10, neighbors = neighbors)
      .map(result => assertEquals(result, None))
  }
