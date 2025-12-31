package services

import munit.CatsEffectSuite

import cats.effect.IO

class BaconNumberSearchSpec extends CatsEffectSuite:

  // ============================================================================
  // Tests for findDistance (distance-only search via unified BFS)
  // ============================================================================

  test("findDistance returns 0 when start == target") {
    KevinBaconDetails
      .findDistance[IO](startActor = "A", targetActor = "A", maxDepth = 6, neighbors = _ => IO.pure(Set.empty))
      .map(result => assertEquals(result, Some(0)))
  }

  test("findDistance finds the shortest path length") {
    // A -- B -- C -- D
    val adj: Map[String, Set[String]] =
      Map("A" -> Set("B"), "B" -> Set("A", "C"), "C" -> Set("B", "D"), "D" -> Set("C"))

    def neighbors(frontier: Set[String]): IO[Set[String]] = IO.pure(frontier.flatMap(n => adj.getOrElse(n, Set.empty)))

    KevinBaconDetails.findDistance[IO](startActor = "A", targetActor = "D", maxDepth = 10, neighbors = neighbors)
      .map(result => assertEquals(result, Some(3)))
  }

  test("findDistance respects maxDepth") {
    // A -- B -- C
    val adj: Map[String, Set[String]] = Map("A" -> Set("B"), "B" -> Set("A", "C"), "C" -> Set("B"))

    def neighbors(frontier: Set[String]): IO[Set[String]] = IO.pure(frontier.flatMap(n => adj.getOrElse(n, Set.empty)))

    KevinBaconDetails.findDistance[IO](startActor = "A", targetActor = "C", maxDepth = 1, neighbors = neighbors)
      .map(result => assertEquals(result, None))
  }

  test("findDistance returns None when disconnected") {
    val adj: Map[String, Set[String]] = Map("A" -> Set("B"), "B" -> Set("A"), "X" -> Set("Y"), "Y" -> Set("X"))

    def neighbors(frontier: Set[String]): IO[Set[String]] = IO.pure(frontier.flatMap(n => adj.getOrElse(n, Set.empty)))

    KevinBaconDetails.findDistance[IO](startActor = "A", targetActor = "Y", maxDepth = 10, neighbors = neighbors)
      .map(result => assertEquals(result, None))
  }

  test("findDistance handles complex graphs with multiple paths") {
    // A -- B -- D -- F
    //  \   |   /
    //   -- C --
    //      |
    //      E
    val adj: Map[String, Set[String]] = Map(
      "A" -> Set("B", "C"),
      "B" -> Set("A", "C", "D"),
      "C" -> Set("A", "B", "D", "E"),
      "D" -> Set("B", "C", "F"),
      "E" -> Set("C"),
      "F" -> Set("D")
    )

    def neighbors(frontier: Set[String]): IO[Set[String]] = IO.pure(frontier.flatMap(n => adj.getOrElse(n, Set.empty)))

    KevinBaconDetails.findDistance[IO](startActor = "A", targetActor = "F", maxDepth = 10, neighbors = neighbors)
      .map(result => assertEquals(result, Some(3)))
  }

  // ============================================================================
  // Tests for findPathsSequential (path reconstruction)
  // ============================================================================

  test("findPathsSequential returns empty when start == target") {
    KevinBaconDetails.findPathsSequential[IO](
      startActor = "A",
      targetActor = "A",
      maxDepth = 6,
      getNeighborsForNode = _ => IO.pure(Set.empty)
    ).map(result => assertEquals(result, Some(KevinBaconDetails.BfsPathResult(Map.empty, Map.empty, Set("A")))))
  }

  test("findPathsSequential finds paths and meeting points") {
    // A -- B -- C -- D
    val adj: Map[String, Set[String]] =
      Map("A" -> Set("B"), "B" -> Set("A", "C"), "C" -> Set("B", "D"), "D" -> Set("C"))

    def getNeighborsForNode(node: String): IO[Set[String]] = IO.pure(adj.getOrElse(node, Set.empty))

    KevinBaconDetails.findPathsSequential[IO](
      startActor = "A",
      targetActor = "D",
      maxDepth = 3,
      getNeighborsForNode = getNeighborsForNode
    ).map { result =>
      assert(result.isDefined, "Should find a path")
      val pathResult = result.get
      assert(pathResult.meetingNodes.nonEmpty, "Should have meeting points")
      // Meeting can happen at B, C, or D depending on search order
      assert(
        pathResult.meetingNodes.exists(n => n == "B" || n == "C" || n == "D"),
        s"Meeting nodes should be in the path, got: ${pathResult.meetingNodes}"
      )

      // Verify parent relationships exist
      assert(
        pathResult.forwardParents.nonEmpty || pathResult.backwardParents.nonEmpty,
        "Should have parent information"
      )
    }
  }

  test("findPathsSequential respects maxDepth") {
    // A -- B -- C -- D
    val adj: Map[String, Set[String]] =
      Map("A" -> Set("B"), "B" -> Set("A", "C"), "C" -> Set("B", "D"), "D" -> Set("C"))

    def getNeighborsForNode(node: String): IO[Set[String]] = IO.pure(adj.getOrElse(node, Set.empty))

    KevinBaconDetails.findPathsSequential[IO](
      startActor = "A",
      targetActor = "D",
      maxDepth = 2, // Too shallow to reach D
      getNeighborsForNode = getNeighborsForNode
    ).map(result => assertEquals(result, None))
  }

  test("findPathsSequential returns None when disconnected") {
    val adj: Map[String, Set[String]] = Map("A" -> Set("B"), "B" -> Set("A"), "X" -> Set("Y"), "Y" -> Set("X"))

    def getNeighborsForNode(node: String): IO[Set[String]] = IO.pure(adj.getOrElse(node, Set.empty))

    KevinBaconDetails.findPathsSequential[IO](
      startActor = "A",
      targetActor = "Y",
      maxDepth = 10,
      getNeighborsForNode = getNeighborsForNode
    ).map(result => assertEquals(result, None))
  }

  test("findPathsSequential tracks parent relationships correctly") {
    // Diamond graph:
    // A -- B -- D
    //  \   |   /
    //   -- C --
    val adj: Map[String, Set[String]] =
      Map("A" -> Set("B", "C"), "B" -> Set("A", "D"), "C" -> Set("A", "D"), "D" -> Set("B", "C"))

    def getNeighborsForNode(node: String): IO[Set[String]] = IO.pure(adj.getOrElse(node, Set.empty))

    KevinBaconDetails.findPathsSequential[IO](
      startActor = "A",
      targetActor = "D",
      maxDepth = 2,
      getNeighborsForNode = getNeighborsForNode
    ).map { result =>
      assert(result.isDefined, "Should find paths")
      val pathResult = result.get

      // Verify that we can trace back from meeting nodes to start
      assert(
        pathResult.forwardParents.nonEmpty || pathResult.backwardParents.nonEmpty,
        "Should have parent information"
      )

      // Meeting should happen at B or C (distance 1 from each side)
      assert(
        pathResult.meetingNodes.exists(n => n == "B" || n == "C"),
        s"Meeting nodes should be B or C, got: ${pathResult.meetingNodes}"
      )
    }
  }

  test("findPathsSequential handles graphs with high branching factor") {
    // Star graph: A connects to B1..B10, all connect to C
    val branches                      = (1 to 10).map(i => s"B$i").toSet
    val adj: Map[String, Set[String]] = Map("A" -> branches) ++ branches.map(b => b -> (Set("A", "C"))).toMap ++
      Map("C" -> branches)

    def getNeighborsForNode(node: String): IO[Set[String]] = IO.pure(adj.getOrElse(node, Set.empty))

    KevinBaconDetails.findPathsSequential[IO](
      startActor = "A",
      targetActor = "C",
      maxDepth = 2,
      getNeighborsForNode = getNeighborsForNode
    ).map { result =>
      assert(result.isDefined, "Should find path through star graph")
      val pathResult = result.get
      // Meeting should occur at one of the B nodes
      assert(
        pathResult.meetingNodes.exists(_.startsWith("B")),
        s"Meeting nodes should include B nodes, got: ${pathResult.meetingNodes}"
      )
    }
  }

  test("findPathsSequential works with single-step path") {
    // A -- B
    val adj: Map[String, Set[String]] = Map("A" -> Set("B"), "B" -> Set("A"))

    def getNeighborsForNode(node: String): IO[Set[String]] = IO.pure(adj.getOrElse(node, Set.empty))

    KevinBaconDetails.findPathsSequential[IO](
      startActor = "A",
      targetActor = "B",
      maxDepth = 1,
      getNeighborsForNode = getNeighborsForNode
    ).map { result =>
      assert(result.isDefined, "Should find single-step path")
      val pathResult = result.get
      assert(
        pathResult.meetingNodes.contains("B") || pathResult.meetingNodes.contains("A"),
        "Meeting should occur at A or B"
      )
    }
  }
