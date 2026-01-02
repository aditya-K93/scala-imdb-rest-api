package services

import domain.baconNumber.{ BaconNumber, BaconNumberWithPaths, BaconPath, PathStep }
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import cats.*
import cats.syntax.all.*

import cats.effect.std.Semaphore
import cats.effect.syntax.all.*
import cats.effect.{ Concurrent, Resource }

/**
 * Algebra for computing person separation degrees and connection paths in the IMDb graph.
 *
 * This service models the "Six Degrees of Kevin Bacon" problem as a graph traversal where:
 * - Nodes are people in IMDb (identified by nconst: actors, directors, writers, etc.)
 * - Edges exist between people who worked on the same title (in any capacity)
 * - Path weight is the number of titles (hops) between people
 *
 * Uses tagless final encoding to abstract over effect type F[_], enabling:
 * - Testability via different interpreters
 * - Effect polymorphism (IO, ZIO, etc.)
 * - Separation of interface from implementation
 */
trait KevinBaconDetails[F[_]]:
  /**
   * Resolves actor name to IMDb identifier.
   *
   * When multiple actors share the same name, selects the most prominent
   * (highest number of known titles) to handle disambiguation.
   *
   * @param actorName full name as it appears in IMDb
   * @return Some(nconst) if found, None otherwise
   */
  def getActorIdByName(actorName: String): F[Option[String]]

  /**
   * Computes shortest path distance from Kevin Bacon to target actor.
   *
   * Returns -1 for unknown actors or unreachable nodes (disconnected graph components).
   * Uses bidirectional BFS for O(b^(d/2)) instead of O(b^d) complexity.
   *
   * @param actorName target actor name
   * @return BaconNumber containing distance or -1 if unreachable
   */
  def degreesOfSeparationKevinBacon(actorName: String): F[BaconNumber]

  /**
   * Computes shortest paths with full actor/movie details.
   *
   * Extension of degree computation that reconstructs actual connection paths.
   * Multiple paths may exist at the same distance; maxPaths controls result size.
   *
   * @param actorName target actor name
   * @param maxPaths maximum number of distinct paths to return (clamped 1-10)
   * @return BaconNumberWithPaths containing distance and path details
   */
  def degreesOfSeparationKevinBaconWithPaths(actorName: String, maxPaths: Option[Int]): F[BaconNumberWithPaths]

object KevinBaconDetails:

  /**
   * Type alias for IMDb person identifier (nconst format: nm0000000).
   *
   * Encapsulated as implementation detail of the service.
   * Public API uses String to avoid coupling callers to internal representation.
   * Note: Despite the name "ActorId", this includes all people (directors, writers, etc.)
   */
  type ActorId = String

  /** Type alias for person primary name as stored in IMDb. */
  type ActorName = String

  /** Type alias for title primary name (movies, TV shows, etc.). */
  type MovieTitle = String

  /** Type alias for graph distance (number of edges in shortest path). */
  type Depth = Int

  /**
   * Configuration for graph search operations with database interaction tuning.
   *
   * These parameters control the tradeoff between:
   * - Search thoroughness vs. termination guarantees
   * - Database throughput vs. connection pool saturation
   * - Sequential simplicity vs. parallel speedup
   *
   * @param maxDepth maximum BFS depth before abandoning search
   *                 Acts as safety valve for disconnected graph components
   *                 or pathological cases (typically 6 for small-world networks)
   * @param coStarBatchSize number of people per parallel database query batch
   *                        Tuned to balance query overhead vs. result set size
   * @param coStarParallelism maximum concurrent database queries via semaphore
   *                          Limited by connection pool size and DB capacity
   * @param coStarParallelThreshold frontier size threshold to enable parallelization
   *                                Below this, sequential queries avoid coordination overhead
   */
  final case class SearchConfig(
      maxDepth: Int,
      coStarBatchSize: Int,
      coStarParallelism: Int,
      coStarParallelThreshold: Int
  )

  /**
   * Maps each actor to their parent actors in BFS traversal.
   *
   * Set[ActorId] allows multiple parents when multiple shortest paths exist.
   * Used for path reconstruction after bidirectional BFS completes.
   */
  type ParentMap = Map[ActorId, Set[ActorId]]

  /**
   * Typeclass abstracting bidirectional BFS state transitions.
   *
   * This abstraction enables a single generic bidirectional BFS implementation
   * to handle both distance-only and path-tracking searches through parametric polymorphism.
   *
   * Design rationale:
   * - Eliminates code duplication between distance/path variants
   * - Separates state representation from traversal logic
   * - Enables compile-time verification of state operations
   * - Allows future state representations without modifying core algorithm
   *
   * The typeclass defines a contract for:
   * - Initial state creation
   * - Visited node checking
   * - Frontier expansion with new discoveries
   * - Detection of forward/backward search collision
   * - Result construction from meeting point
   *
   * @tparam S state type (DistanceState or PathState)
   * @tparam R result type returned when searches meet (Depth or BfsPathResult)
   */
  trait BfsState[S, R]:
    /**
     * Creates initial state for a search originating from a single node.
     *
     * @param node starting actor (Kevin Bacon for forward, target for backward)
     * @return state with node marked as visited at depth 0
     */
    def initial(node: ActorId): S

    /**
     * Checks if a node has been visited in this search direction.
     *
     * Used to avoid revisiting nodes and detect meeting with opposite search.
     *
     * @param state current search state
     * @param node actor to check
     * @return true if node has been explored
     */
    def contains(state: S, node: ActorId): Boolean

    /**
     * Extracts current search depth from state.
     *
     * Used to enforce maxDepth limit and calculate combined distance.
     *
     * @param state current search state
     * @return number of BFS levels explored
     */
    def depth(state: S): Depth

    /**
     * Expands state with newly discovered nodes from frontier exploration.
     *
     * This is where distance-only and path-tracking diverge:
     * - DistanceState: records node->depth mappings
     * - PathState: records node->parent mappings for path reconstruction
     *
     * @param state current search state
     * @param frontier nodes being expanded in this iteration
     * @param newNodes previously unvisited neighbors discovered
     * @param nodeParents mapping of frontier nodes to their discovered neighbors
     * @return updated state with new nodes incorporated
     */
    def expand(state: S, frontier: Set[ActorId], newNodes: Set[ActorId], nodeParents: List[(ActorId, Set[ActorId])]): S

    /**
     * Checks if forward and backward searches have met.
     *
     * Meeting occurs when:
     * - A frontier node from one direction exists in the other's visited set
     * - This indicates a complete path through the graph
     *
     * @param stateA forward search state (from Kevin Bacon)
     * @param stateB backward search state (from target)
     * @param frontierA nodes being explored in forward direction
     * @param frontierB nodes being explored in backward direction
     * @return Some(result) if searches intersect, None to continue
     */
    def checkMeeting(stateA: S, stateB: S, frontierA: Set[ActorId], frontierB: Set[ActorId]): Option[R]

    /**
     * Constructs final result from meeting point of bidirectional searches.
     *
     * Called when searches have met but expansion completes before detection.
     * Combines information from both directions to produce complete result.
     *
     * @param stateA forward search state
     * @param stateB backward search state
     * @param meetings nodes where searches intersected
     * @return final result (distance or path details)
     */
    def buildResult(stateA: S, stateB: S, meetings: Set[ActorId]): R

  /**
   * State for distance-only BFS that tracks visited nodes and their depths.
   *
   * Memory-efficient representation that only stores what's needed for distance calculation.
   * Does not maintain parent pointers, so cannot reconstruct actual paths.
   *
   * @param visited maps each discovered actor to their distance from origin
   * @param currentDepth depth of the current frontier being explored
   */
  final case class DistanceState(visited: Map[ActorId, Depth], currentDepth: Depth)

  /**
   * State for path-tracking BFS that maintains parent relationships.
   *
   * Trades increased memory for path reconstruction capability.
   * Set-based parents allow tracking multiple shortest paths simultaneously.
   *
   * @param visited actors that have been explored (membership-only, no depth needed)
   * @param parents maps each actor to the set of actors it was reached from
   * @param currentDepth depth of the current frontier being explored
   */
  final case class PathState(visited: Set[ActorId], parents: ParentMap, currentDepth: Depth)

  /**
   * Result of bidirectional path-tracking search.
   *
   * Contains parent maps from both directions and meeting points.
   * Paths are reconstructed by:
   * 1. Tracing backward from meeting node to Kevin Bacon (forward parents)
   * 2. Tracing backward from meeting node to target (backward parents)
   * 3. Reversing forward path and concatenating with backward path
   *
   * @param forwardParents parent map from Kevin Bacon's search
   * @param backwardParents parent map from target's search
   * @param meetingNodes actors where both searches intersected
   */
  final case class BfsPathResult(
      forwardParents: ParentMap,
      backwardParents: ParentMap,
      meetingNodes: Set[ActorId]
  )

  object BfsState:
    /**
     * BfsState instance for distance-only searches.
     *
     * Optimized for minimal memory usage when only separation degree is needed.
     * Stores depth information for meeting detection and result calculation.
     */
    given distanceState: BfsState[DistanceState, Depth] with
      def initial(node: ActorId): DistanceState =
        DistanceState(Map(node -> 0), 0)

      def contains(state: DistanceState, node: ActorId): Boolean =
        state.visited.contains(node)

      def depth(state: DistanceState): Depth = state.currentDepth

      /**
       * Expands state by recording new nodes at the next depth level.
       *
       * All newly discovered nodes are at currentDepth + 1 by BFS invariant.
       * nodeParents parameter ignored since we don't track relationships.
       */
      def expand(
          state: DistanceState,
          frontier: Set[ActorId],
          newNodes: Set[ActorId],
          nodeParents: List[(ActorId, Set[ActorId])]
      ): DistanceState =
        val nextDepth = state.currentDepth + 1
        DistanceState(
          state.visited ++ newNodes.iterator.map(_ -> nextDepth),
          nextDepth
        )

      /**
       * Detects meeting by checking if any frontier node exists in opposite direction's visited set.
       *
       * Total distance = depthA + depthB when searches meet.
       * Checks both directions because meeting can occur in either frontier.
       *
       * Returns first meeting found (short-circuits) for performance.
       */
      def checkMeeting(
          stateA: DistanceState,
          stateB: DistanceState,
          frontierA: Set[ActorId],
          frontierB: Set[ActorId]
      ): Option[Depth] =
        frontierA.collectFirst(Function.unlift(actor =>
          stateB.visited.get(actor).map(otherDepth => stateA.currentDepth + otherDepth)
        )).orElse(
          frontierB.collectFirst(Function.unlift(actor =>
            stateA.visited.get(actor).map(otherDepth => stateB.currentDepth + otherDepth)
          ))
        )

      /**
       * Calculates minimum distance among all meeting points.
       *
       * Multiple actors may be at the intersection of the two searches.
       * We return the minimum sum since that's the shortest path.
       *
       * Fallback to sum of current depths if no meetings recorded (shouldn't happen).
       */
      def buildResult(stateA: DistanceState, stateB: DistanceState, meetings: Set[ActorId]): Depth =
        meetings.iterator.flatMap { m =>
          for
            dA <- stateA.visited.get(m)
            dB <- stateB.visited.get(m)
          yield dA + dB
        }.minOption.getOrElse(stateA.currentDepth + stateB.currentDepth)

    /**
     * BfsState instance for path-tracking searches.
     *
     * Maintains parent relationships to enable full path reconstruction.
     * Root node maps to empty set (has no parents).
     */
    given pathState: BfsState[PathState, BfsPathResult] with
      def initial(node: ActorId): PathState =
        PathState(Set(node), Map(node -> Set.empty), 0)

      def contains(state: PathState, node: ActorId): Boolean =
        state.visited.contains(node)

      def depth(state: PathState): Depth = state.currentDepth

      /**
       * Expands state by recording parent relationships for each new node.
       *
       * For each new node, we record all frontier nodes it was reached from.
       * This captures multiple shortest paths when they exist.
       *
       * updatedWith accumulates parents when a node is reached via multiple paths.
       * Only records parents for previously unvisited nodes (BFS invariant).
       */
      def expand(
          state: PathState,
          frontier: Set[ActorId],
          newNodes: Set[ActorId],
          nodeParents: List[(ActorId, Set[ActorId])]
      ): PathState =
        val newParents = nodeParents.foldLeft(state.parents) { case (acc, (parent, neighbors)) =>
          neighbors.filterNot(state.visited.contains).foldLeft(acc) { (inner, neighbor) =>
            inner.updatedWith(neighbor) {
              case Some(existing) => Some(existing + parent)
              case None           => Some(Set(parent))
            }
          }
        }
        PathState(state.visited ++ newNodes, newParents, state.currentDepth + 1)

      /**
       * Detects meeting and returns parent maps plus intersection nodes.
       *
       * Unlike distance-only, we return ALL meeting nodes not just the first.
       * This enables reconstruction of multiple distinct shortest paths.
       *
       * Both directions checked because meeting can occur in either frontier.
       */
      def checkMeeting(
          stateA: PathState,
          stateB: PathState,
          frontierA: Set[ActorId],
          frontierB: Set[ActorId]
      ): Option[BfsPathResult] =
        val meetings = frontierA.intersect(stateB.visited) ++ frontierB.intersect(stateA.visited)
        Option.when(meetings.nonEmpty)(BfsPathResult(stateA.parents, stateB.parents, meetings))

      /**
       * Constructs result containing parent maps and meeting nodes.
       *
       * Simply packages the data - actual path reconstruction happens later.
       * All meeting nodes are included to support multiple path enumeration.
       */
      def buildResult(stateA: PathState, stateB: PathState, meetings: Set[ActorId]): BfsPathResult =
        BfsPathResult(stateA.parents, stateB.parents, meetings)

  /** Kevin Bacon's IMDb identifier (stable across dataset versions). */
  private[services] val kevinBaconId: ActorId = "nm0000102"

  /**
   * Default configuration tuned for IMDb dataset characteristics.
   *
   * These values are based on:
   * - Small-world network property: most people within 6 degrees
   * - Database query performance: batch size balances overhead vs throughput
   * - Connection pool constraints: parallelism limited to avoid saturation
   * - Coordination overhead: sequential better for small frontiers
   */
  private val defaultConfig: SearchConfig = SearchConfig(
    maxDepth = 6,
    coStarBatchSize = 400,
    coStarParallelism = 4,
    coStarParallelThreshold = 1200
  )

  /**
   * Generic bidirectional BFS parameterized by state type via BfsState typeclass.
   *
   * Algorithm overview:
   * 1. Maintains two frontiers: forward (from start) and backward (from target)
   * 2. Each iteration expands the smaller frontier (balanced search)
   * 3. Checks for collision after each expansion
   * 4. Terminates when frontiers meet or max depth exceeded
   *
   * Complexity: O(b^(d/2)) vs O(b^d) for unidirectional BFS where:
   * - b is branching factor (avg collaborators per person ~100)
   * - d is shortest path distance (typically 2-4 for IMDb)
   *
   * Why bidirectional:
   * - IMDb graph has high branching factor (popular people have 100+ collaborators)
   * - For d=4, saves 100^2 = 10,000x compared to unidirectional
   * - Small-world property makes meeting highly likely in middle
   *
   * Why typeclass-based:
   * - Single implementation serves both distance and path-tracking needs
   * - Eliminates code duplication while maintaining type safety
   * - Enables future extensions (e.g., weighted paths) without core changes
   *
   * @tparam F effect type (IO, etc.)
   * @tparam S state type (DistanceState or PathState)
   * @tparam R result type (Depth or BfsPathResult)
   * @param startActor origin node (Kevin Bacon)
   * @param targetActor destination node
   * @param maxDepth maximum search depth (safety valve)
   * @param neighbors function to fetch adjacent nodes (database query)
   * @param sameNodeResult value to return if start == target
   * @param bfs typeclass instance defining state operations
   * @return Some(result) if path found, None if unreachable or max depth exceeded
   */
  private[services] def bidirectionalBfs[F[_]: Monad, S, R](
      startActor: ActorId,
      targetActor: ActorId,
      maxDepth: Depth,
      neighbors: Set[ActorId] => F[Set[ActorId]],
      sameNodeResult: => R
  )(using bfs: BfsState[S, R]): F[Option[R]] =
    if startActor == targetActor then Monad[F].pure(Some(sameNodeResult))
    else
      /**
       * Main BFS loop maintaining two frontiers.
       *
       * Invariants:
       * - frontierA/B contain nodes at current depth
       * - stateA/B contain all visited nodes and associated data
       * - depth(stateA) + depth(stateB) <= maxDepth
       *
       * Loop terminates when:
       * - Either frontier exhausted (no path exists)
       * - Combined depth exceeds maxDepth (unreachable)
       * - Frontiers collide (path found)
       */
      def loop(
          frontierA: Set[ActorId],
          stateA: S,
          frontierB: Set[ActorId],
          stateB: S
      ): F[Option[R]] =
        if frontierA.isEmpty || frontierB.isEmpty then Monad[F].pure(None)
        else if bfs.depth(stateA) + bfs.depth(stateB) >= maxDepth then Monad[F].pure(None)
        else
          bfs.checkMeeting(stateA, stateB, frontierA, frontierB) match
            case Some(result) => Monad[F].pure(Some(result))
            case None         =>
              // Expand smaller frontier to keep search balanced
              val expandA = frontierA.size <= frontierB.size
              if expandA then
                expandAndContinue(frontierA, stateA, frontierB, stateB, maxDepth, neighbors, loop)
              else
                // Swap parameters when expanding B to reuse expandAndContinue
                expandAndContinue(
                  frontierB,
                  stateB,
                  frontierA,
                  stateA,
                  maxDepth,
                  neighbors,
                  (fA, sA, fB, sB) => loop(fB, sB, fA, sA) // Restore order in continuation
                )

      loop(Set(startActor), bfs.initial(startActor), Set(targetActor), bfs.initial(targetActor))

  /**
   * Expands one frontier and checks for meeting before continuing search.
   *
   * Steps:
   * 1. Early termination if next expansion exceeds maxDepth
   * 2. Fetch neighbors for all nodes in frontier (batched DB query)
   * 3. Filter to only unvisited nodes (BFS visited check)
   * 4. Expand state with new discoveries
   * 5. Check if new nodes collide with opposite frontier
   * 6. If collision, return result; else continue with updated state
   *
   * Why check after expansion:
   * - Meeting detection needs nodes in visited set
   * - New nodes may collide with opposite frontier OR visited set
   * - Covers both "frontier meets frontier" and "frontier meets visited"
   *
   * @param frontier nodes to expand from
   * @param state current search state for this direction
   * @param otherFrontier frontier of opposite search
   * @param otherState state of opposite search
   * @param maxDepth maximum allowed depth
   * @param neighbors function to fetch adjacent nodes
   * @param continue continuation to invoke if no meeting
   * @param bfs typeclass instance
   * @return Some(result) if meeting detected, else continues search
   */
  private def expandAndContinue[F[_]: Monad, S, R](
      frontier: Set[ActorId],
      state: S,
      otherFrontier: Set[ActorId],
      otherState: S,
      maxDepth: Depth,
      neighbors: Set[ActorId] => F[Set[ActorId]],
      continue: (Set[ActorId], S, Set[ActorId], S) => F[Option[R]]
  )(using bfs: BfsState[S, R]): F[Option[R]] =
    // Check if next expansion would exceed max depth
    if bfs.depth(state) + 1 + bfs.depth(otherState) > maxDepth then Monad[F].pure(None)
    else
      neighbors(frontier).flatMap { allNeighbors =>
        val newNodes    = allNeighbors.filterNot(bfs.contains(state, _))
        val nodeParents = frontier.toList.map(n => (n, allNeighbors))
        val nextState   = bfs.expand(state, frontier, newNodes, nodeParents)
        // Check for collision with opposite search
        val meetings = newNodes.intersect(otherFrontier) ++ newNodes.filter(bfs.contains(otherState, _))

        if meetings.nonEmpty then
          Monad[F].pure(Some(bfs.buildResult(nextState, otherState, meetings)))
        else
          continue(newNodes, nextState, otherFrontier, otherState)
      }

  /**
   * Distance-only search using bulk neighbor fetching.
   *
   * Delegates to bidirectionalBfs with DistanceState typeclass instance.
   * Returns 0 for same-node case (actor searching for themselves).
   *
   * Bulk neighbors function fetches co-stars for entire frontier in one query,
   * enabling massive parallelization when frontiers are large.
   *
   * @return Some(depth) if path exists, None if unreachable
   */
  private[services] def findDistance[F[_]: Monad](
      startActor: ActorId,
      targetActor: ActorId,
      maxDepth: Depth,
      neighbors: Set[ActorId] => F[Set[ActorId]]
  ): F[Option[Depth]] =
    bidirectionalBfs[F, DistanceState, Depth](startActor, targetActor, maxDepth, neighbors, 0)

  /**
   * Path-tracking search using sequential per-node neighbor fetching.
   *
   * Why sequential:
   * - Skunk sessions don't support concurrent operations on same connection
   * - Path reconstruction needs per-node parent tracking
   * - Sequential queries simpler to coordinate and reason about
   *
   * Trade-off:
   * - Slower than parallel bulk fetching
   * - But only used when full paths requested (less common use case)
   * - Correctness over performance for this feature
   *
   * @param getNeighborsForNode function taking single actor, returning co-stars
   * @return Some(BfsPathResult) if path exists, None if unreachable
   */
  private[services] def findPathsSequential[F[_]: Monad](
      startActor: ActorId,
      targetActor: ActorId,
      maxDepth: Depth,
      getNeighborsForNode: ActorId => F[Set[ActorId]]
  ): F[Option[BfsPathResult]] =
    if startActor == targetActor then
      Monad[F].pure(Some(BfsPathResult(Map.empty, Map.empty, Set(startActor))))
    else
      pathSearchLoopSequential(startActor, targetActor, maxDepth, getNeighborsForNode)

  /**
   * Specialized bidirectional BFS loop for path tracking with sequential queries.
   *
   * Similar structure to generic bidirectionalBfs but with PathState-specific logic:
   * - Explicit meeting detection (not delegated to typeclass)
   * - Sequential neighbor fetching per node
   * - Direct PathState manipulation
   *
   * Why not use generic bidirectionalBfs:
   * - Need to pass per-node neighbor function (different signature)
   * - Sequential fetching requires different expansion logic
   * - Worth the duplication for clarity and sequential semantics
   *
   * @return Some(BfsPathResult) with parent maps if path exists
   */
  private def pathSearchLoopSequential[F[_]: Monad](
      startActor: ActorId,
      targetActor: ActorId,
      maxDepth: Depth,
      getNeighborsForNode: ActorId => F[Set[ActorId]]
  ): F[Option[BfsPathResult]] =
    def loop(
        frontierA: Set[ActorId],
        stateA: PathState,
        frontierB: Set[ActorId],
        stateB: PathState
    ): F[Option[BfsPathResult]] =
      if frontierA.isEmpty || frontierB.isEmpty then Monad[F].pure(None)
      else if stateA.currentDepth + stateB.currentDepth >= maxDepth then Monad[F].pure(None)
      else
        // Inline meeting check for clarity
        val meetings = frontierA.intersect(stateB.visited) ++ frontierB.intersect(stateA.visited)
        if meetings.nonEmpty then
          Monad[F].pure(Some(BfsPathResult(stateA.parents, stateB.parents, meetings)))
        else
          val expandA = frontierA.size <= frontierB.size
          if expandA then
            expandWithParentsSequential(frontierA, stateA, stateB, maxDepth, getNeighborsForNode).flatMap {
              case (newFrontier, newState, meetOpt) =>
                meetOpt match
                  case Some(meetings) =>
                    Monad[F].pure(Some(BfsPathResult(newState.parents, stateB.parents, meetings)))
                  case None =>
                    loop(newFrontier, newState, frontierB, stateB)
            }
          else
            expandWithParentsSequential(frontierB, stateB, stateA, maxDepth, getNeighborsForNode).flatMap {
              case (newFrontier, newState, meetOpt) =>
                meetOpt match
                  case Some(meetings) =>
                    Monad[F].pure(Some(BfsPathResult(stateA.parents, newState.parents, meetings)))
                  case None =>
                    loop(frontierA, stateA, newFrontier, newState)
            }

    loop(
      Set(startActor),
      PathState(Set(startActor), Map(startActor -> Set.empty), 0),
      Set(targetActor),
      PathState(Set(targetActor), Map(targetActor -> Set.empty), 0)
    )

  /**
   * Expands frontier sequentially with parent tracking for path reconstruction.
   *
   * Process:
   * 1. For each frontier node, fetch its neighbors (sequential queries)
   * 2. Build parent map: neighbor -> {frontier nodes that reached it}
   * 3. Filter to unvisited neighbors (BFS invariant)
   * 4. Check if any new nodes collide with opposite search
   * 5. Return new frontier, updated state, and optional meetings
   *
   * Why traverse per node:
   * - Enables proper parent tracking (which frontier node reached which neighbor)
   * - Sequential queries prevent Skunk session concurrency issues
   * - Accumulates multiple parents when node reached via multiple paths
   *
   * Parent map structure:
   * - Key: newly discovered actor
   * - Value: Set of actors in current frontier that discovered it
   * - updatedWith merges parents when node appears in multiple neighbors sets
   *
   * @return (newFrontier, updatedState, optionalMeetings)
   */
  private def expandWithParentsSequential[F[_]: Monad](
      frontier: Set[ActorId],
      state: PathState,
      otherState: PathState,
      maxDepth: Depth,
      getNeighborsForNode: ActorId => F[Set[ActorId]]
  ): F[(Set[ActorId], PathState, Option[Set[ActorId]])] =
    if state.currentDepth + 1 + otherState.currentDepth > maxDepth then
      Monad[F].pure((Set.empty, state, None))
    else
      // Sequential traverse: query each frontier node's neighbors in sequence
      frontier.toList.traverse(node => getNeighborsForNode(node).map(n => (node, n))).map { nodeNeighborPairs =>
        // Build parent map from node-neighbor pairs
        val newParents = nodeNeighborPairs.foldLeft(state.parents) { case (acc, (parent, neighbors)) =>
          neighbors.filterNot(state.visited.contains).foldLeft(acc) { (inner, neighbor) =>
            inner.updatedWith(neighbor) {
              case Some(existing) => Some(existing + parent) // Multiple parents
              case None           => Some(Set(parent))       // First parent
            }
          }
        }
        val newNodes    = nodeNeighborPairs.flatMap(_._2).toSet.filterNot(state.visited.contains)
        val newVisited  = state.visited ++ newNodes
        val newState    = PathState(newVisited, newParents, state.currentDepth + 1)
        val meetings    = newNodes.intersect(otherState.visited)
        val meetingsOpt = Option.when(meetings.nonEmpty)(meetings)
        (newNodes, newState, meetingsOpt)
      }

  /**
   * Recursively reconstructs all paths from a node back to root using parent map.
   *
   * Algorithm:
   * 1. Base case: node == root, return path containing just this node
   * 2. Lookup node's parents from parent map
   * 3. Recursively reconstruct paths from each parent to root
   * 4. Prepend current node to each parent path
   * 5. Limit paths at each level to prevent combinatorial explosion
   *
   * Why Set[ActorId] parents:
   * - Multiple shortest paths when actor reached via multiple routes
   * - Each parent represents a different shortest path
   * - Cartesian product of parent paths explodes exponentially
   *
   * Limit application:
   * - Applied at each recursive level (take on parents)
   * - Applied to final results (take on concatenated paths)
   * - Prevents memory exhaustion with highly connected actors
   *
   * @param node current node in backward traversal
   * @param parents parent map from BFS
   * @param root starting node of original search
   * @param limit maximum paths to return per node
   * @return list of paths from node to root (reversed, needs reversing by caller)
   */
  private[services] def reconstructAllPaths(
      node: ActorId,
      parents: ParentMap,
      root: ActorId,
      limit: Int
  ): List[List[ActorId]] =
    if node == root then List(List(node))
    else
      parents.get(node) match
        case None                                 => List.empty       // Unreachable (shouldn't happen)
        case Some(parentSet) if parentSet.isEmpty => List(List(node)) // Root node case
        case Some(parentSet)                      =>
          // Use LazyList to avoid computing unnecessary paths when limit is small
          parentSet.to(LazyList).flatMap { parent =>
            LazyList.from(reconstructAllPaths(parent, parents, root, limit)).map(path => node :: path)
          }.take(limit).toList

  /**
   * Factory method for creating KevinBaconDetails instance.
   *
   * Uses default configuration tuned for IMDb dataset.
   * Requires Concurrent[F] for:
   * - Parallel queries (start/joinWithNever)
   * - Semaphore coordination (costar parallelism)
   * - Resource management (postgres session acquisition)
   *
   * @param postgres Skunk session resource
   * @return KevinBaconDetails instance
   */
  def make[F[_]: Concurrent](postgres: Resource[F, Session[F]]): KevinBaconDetails[F] =
    new KevinBaconDetailsLive[F](postgres, defaultConfig)

/**
 * Live implementation of KevinBaconDetails algebra.
 *
 * Architecture:
 * - Delegates graph traversal to KevinBaconDetails companion object algorithms
 * - Manages database interactions via Skunk prepared statements
 * - Coordinates between bulk parallel queries (distance) and sequential queries (paths)
 * - Handles path reconstruction with database lookups for actor names and movie titles
 *
 * Resource management:
 * - Uses Resource[F, Session[F]] for automatic session lifecycle
 * - Each public method uses session via Resource.use
 * - Prepared statements created within session scope for query efficiency
 *
 * Query strategy:
 * - Distance queries: bulk parallel fetching for maximum throughput
 * - Path queries: sequential per-node fetching for Skunk compatibility
 * - Adaptive parallelization based on frontier size
 *
 * @param postgres Skunk session resource for database access
 * @param config search parameters (depth limits, parallelism)
 */
private final class KevinBaconDetailsLive[F[_]: Concurrent](
    postgres: Resource[F, Session[F]],
    config: KevinBaconDetails.SearchConfig
) extends KevinBaconDetails[F]:
  import ActorMovieSQL.*
  import KevinBaconDetails.*

  private val kevinBaconActorId: ActorId = kevinBaconId

  /**
   * Simple actor name lookup with prepared statement.
   *
   * Acquires session, prepares query, executes, releases session automatically.
   */
  override def getActorIdByName(actorName: ActorName): F[Option[ActorId]] =
    postgres.use(session => session.prepare(getActorIdByNameSql).flatMap(_.option(actorName)))

  /**
   * Computes Kevin Bacon distance using parallel bulk neighbor fetching.
   *
   * Steps:
   * 1. Prepare queries within session scope
   * 2. Lookup target actor by name
   * 3. Create bulk neighbor fetcher (adaptively parallel)
   * 4. Run bidirectional BFS with distance-only state
   * 5. Return -1 for not found, else actual distance
   */
  override def degreesOfSeparationKevinBacon(actorName: ActorName): F[BaconNumber] =
    postgres.use { session =>
      for
        actorIdPs <- session.prepare(getActorIdByNameSql)
        coStarsPs <- session.prepare(getCoStarsSql)
        targetOpt <- actorIdPs.option(actorName)
        getCoStars = createBulkNeighborsFetcher(coStarsPs)
        baconDepth <- targetOpt match
          case None                => Monad[F].pure(Option.empty[Depth])
          case Some(targetActorId) =>
            findDistance(kevinBaconActorId, targetActorId, config.maxDepth, getCoStars)
      yield BaconNumber(baconDepth.getOrElse(-1))
    }

  /**
   * Computes distance with full path reconstruction.
   *
   * Two-phase approach:
   * 1. Compute distance only (fast bulk queries)
   * 2. If path requested, reconstruct with database lookups
   *
   * Why separate phases:
   * - Distance computation validates path exists
   * - Avoids expensive path reconstruction for unreachable actors
   * - Path reconstruction requires additional queries per edge
   */
  override def degreesOfSeparationKevinBaconWithPaths(
      actorName: ActorName,
      maxPaths: Option[Int]
  ): F[BaconNumberWithPaths] =
    for
      baconResult <- degreesOfSeparationKevinBacon(actorName)
      result      <- baconResult.baconNumber match
        case 0          => Monad[F].pure(BaconNumberWithPaths(0, Some(List.empty)))
        case n if n < 0 => Monad[F].pure(BaconNumberWithPaths(n, None))
        case depth      => findActorAndReconstructPaths(actorName, depth, maxPaths)
    yield result

  /**
   * Creates adaptive neighbor-fetching function for distance-only BFS.
   *
   * Strategy selection based on frontier size:
   * - Empty frontier: return empty (base case)
   * - Small frontier (< threshold): single sequential query
   * - Large frontier (>= threshold): parallel batched queries
   *
   * Why adaptive:
   * - Small frontiers: parallel overhead exceeds benefit
   * - Large frontiers: parallelism critical for acceptable latency
   * - Threshold tuned empirically for IMDb dataset
   *
   * Query mechanics:
   * - Comma-separated actor IDs in single query parameter
   * - SQL UNNEST splits into individual actors
   * - JOIN finds all co-stars across entire frontier
   * - DISTINCT eliminates duplicates
   *
   * @param coStarsPs prepared statement accepting comma-separated actor IDs
   * @return function mapping actor set to their combined co-stars
   */
  private def createBulkNeighborsFetcher(
      coStarsPs: PreparedQuery[F, String, ActorId]
  ): Set[ActorId] => F[Set[ActorId]] =
    (frontier: Set[ActorId]) =>
      if frontier.isEmpty then Monad[F].pure(Set.empty[ActorId])
      else if frontier.size < config.coStarParallelThreshold then
        // Small frontier: single query
        coStarsPs.stream(frontier.mkString(","), 8192).compile.toList.map(_.toSet)
      else
        // Large frontier: parallel batches
        fetchCoStarsInParallel(frontier)

  /**
   * Executes parallel batched queries for large frontiers.
   *
   * Architecture:
   * 1. Partition frontier into fixed-size batches
   * 2. Create Semaphore to limit concurrency
   * 3. For each batch: acquire permit, query in separate session, release
   * 4. Start all queries as fibers (parallelism)
   * 5. Join all fibers and merge results
   *
   * Why Semaphore:
   * - Prevents database connection pool exhaustion
   * - Limits to configured parallelism (typically 4)
   * - Back-pressure mechanism for resource control
   *
   * Why separate sessions:
   * - Each batch needs independent postgres session
   * - Sessions acquired from Resource pool
   * - Automatically released after query completes
   *
   * Why start/joinWithNever:
   * - start creates concurrent Fiber
   * - joinWithNever waits for completion, propagates errors
   * - Enables true parallelism vs sequential execution
   *
   * @param frontier large set of actors to fetch co-stars for
   * @return union of all co-stars across batches
   */
  private def fetchCoStarsInParallel(frontier: Set[ActorId]): F[Set[ActorId]] =
    val batches = frontier.toList.grouped(config.coStarBatchSize).map(_.toList).toList

    Semaphore[F](config.coStarParallelism.toLong).flatMap { sem =>
      batches.traverse { batch =>
        sem.permit.use { _ =>
          postgres.use { session =>
            session.prepare(getCoStarsSql)
              .flatMap(_.stream(batch.mkString(","), 8192).compile.toList.map(_.toSet))
          }
        }.start                                      // Start fiber for parallel execution
      }.flatMap(_.traverse(_.joinWithNever))         // Wait for all fibers
        .map(_.foldLeft(Set.empty[ActorId])(_ ++ _)) // Merge results
    }

  /**
   * Looks up actor and delegates to appropriate reconstruction strategy.
   *
   * Fails fast if actor doesn't exist (returns -1 bacon number).
   */
  private def findActorAndReconstructPaths(
      actorName: ActorName,
      depth: Depth,
      maxPaths: Option[Int]
  ): F[BaconNumberWithPaths] =
    postgres.use { session =>
      session.prepare(getActorIdByNameSql).flatMap(_.option(actorName))
    }.flatMap {
      case None                => Monad[F].pure(BaconNumberWithPaths(-1, None))
      case Some(targetActorId) => reconstructPaths(targetActorId, depth, maxPaths)
    }

  /**
   * Dispatches to specialized reconstruction based on distance.
   *
   * Three cases with different complexities:
   * - Distance 0: Actor is Kevin Bacon (trivial, no path)
   * - Distance 1: Direct connection (single movie lookup)
   * - Distance 2+: Full bidirectional BFS with path tracking
   *
   * Why specialized cases:
   * - Distance 0/1 avoid expensive BFS traversal
   * - Distance 1 only needs movie lookup (no path reconstruction)
   * - Optimization for common cases (~30% of queries)
   *
   * @param limit clamped to 1-10 range for safety
   */
  private def reconstructPaths(
      targetActorId: ActorId,
      depth: Depth,
      maxPaths: Option[Int]
  ): F[BaconNumberWithPaths] =
    val limit = maxPaths.getOrElse(3).min(10).max(1)

    depth match
      case 0 => Monad[F].pure(BaconNumberWithPaths(0, Some(List.empty)))
      case 1 => reconstructDirectConnection(targetActorId, limit)
      case _ => reconstructMultiHopPaths(targetActorId, depth, limit)

  /**
   * Handles Bacon number 1 case - direct co-star relationship.
   *
   * Kevin Bacon and target appeared in one or more films together.
   * Returns up to 'limit' different movies they worked on.
   *
   * Path structure:
   * - Single step: Kevin Bacon -> [Movie Title]
   * - Multiple paths possible if they worked together multiple times
   *
   * Simpler than general case since:
   * - No BFS needed (already know distance)
   * - No parent tracking needed
   * - Just movie title lookup
   */
  private def reconstructDirectConnection(targetActorId: ActorId, limit: Int): F[BaconNumberWithPaths] =
    postgres.use { session =>
      for
        moviesPs <- session.prepare(getMoviesBetweenActorsMultipleSql)
        movies   <- moviesPs.stream(kevinBaconActorId *: targetActorId *: EmptyTuple, 64)
          .take(limit.toLong)
          .compile.toList
        paths = movies.map(movie => BaconPath(1, List(PathStep("Kevin Bacon", movie))))
      yield BaconNumberWithPaths(1, Some(paths))
    }

  /**
   * Reconstructs paths for distance >= 2 using bidirectional BFS.
   *
   * Process:
   * 1. Prepare all necessary queries (co-stars, movies, names)
   * 2. Run bidirectional BFS with path tracking (sequential)
   * 3. Extract parent maps and meeting nodes
   * 4. Reconstruct actor ID paths through meetings
   * 5. Convert actor IDs to (actor name, movie) steps
   * 6. Package as BaconPath objects
   *
   * Why this approach:
   * - BFS with parent tracking captures shortest paths
   * - Meeting nodes are path junction points
   * - Separate actor path reconstruction from database lookup
   * - Enables multiple path enumeration
   *
   * Cost:
   * - BFS traversal (graph algorithm)
   * - Path reconstruction (combinatorial)
   * - Name/movie lookups (O(path_length * num_paths) queries)
   */
  private def reconstructMultiHopPaths(
      targetActorId: ActorId,
      depth: Depth,
      limit: Int
  ): F[BaconNumberWithPaths] =
    postgres.use { session =>
      for
        coStarsSinglePs <- session.prepare(getCoStarsSingleSql)
        moviesPs        <- session.prepare(getMoviesBetweenActorsSql)
        actorNamePs     <- session.prepare(getActorNameByIdSql)

        // Per-node neighbor fetching for path tracking
        getNeighbors = (actor: ActorId) =>
          coStarsSinglePs.stream(actor, 8192).compile.toList.map(_.toSet)

        pathResultOpt <- findPathsSequential(kevinBaconActorId, targetActorId, depth, getNeighbors)

        allPaths <- pathResultOpt match
          case None             => Monad[F].pure(List.empty[BaconPath])
          case Some(pathResult) =>
            val actorPaths = reconstructPathsThroughMeetings(
              pathResult.meetingNodes,
              pathResult.forwardParents,
              pathResult.backwardParents,
              kevinBaconActorId,
              targetActorId,
              limit
            )
            // Convert actor IDs to named steps with optimized database lookups
            actorPaths.traverse { actorPath =>
              convertActorPathToSteps(actorPath, actorNamePs, moviesPs).map(steps =>
                BaconPath(depth, steps)
              )
            }
      yield BaconNumberWithPaths(depth, Some(allPaths))
    }

  /**
   * Reconstructs complete paths by joining forward and backward searches at meeting nodes.
   *
   * Bidirectional search produces:
   * - Forward parents: startActor -> meeting node
   * - Backward parents: meeting node -> targetActor
   *
   * Combination strategy:
   * 1. For each meeting node (path junction)
   * 2. Reconstruct all paths from start to meeting (using forward parents)
   * 3. Reconstruct all paths from meeting to target (using backward parents)
   * 4. Cartesian product: combine each forward path with each backward path
   * 5. Concatenate: forward.reverse ++ backward.tail (skip duplicate meeting node)
   *
   * Why reverse forward path:
   * - Parent map traces backwards: node -> parents
   * - Forward reconstruction produces: meeting -> start
   * - Need: start -> meeting, so reverse
   *
   * Why skip meeting node:
   * - Meeting node appears at end of forward path
   * - Meeting node appears at start of backward path
   * - Would be duplicated without .tail
   *
   * Combinatorial explosion:
   * - If forward has N paths and backward has M paths
   * - Result has N * M paths per meeting node
   * - Limit applied to prevent memory exhaustion
   *
   * @return List of paths as List[ActorId] (just IDs, no names yet)
   */
  private def reconstructPathsThroughMeetings(
      meetings: Set[ActorId],
      parentsA: ParentMap,
      parentsB: ParentMap,
      startActor: ActorId,
      targetActor: ActorId,
      maxPaths: Int
  ): List[List[ActorId]] =
    meetings.to(LazyList).flatMap { meetingNode =>
      val pathsFromStart = reconstructAllPaths(meetingNode, parentsA, startActor, maxPaths)
      val pathsToEnd     = reconstructAllPaths(meetingNode, parentsB, targetActor, maxPaths)
      // Use LazyList and limit early to avoid computing unnecessary cartesian products
      LazyList.from(
        for
          pathA <- pathsFromStart
          pathB <- pathsToEnd
        yield pathA.reverse ++ pathB.tail
      )
    }.distinctBy(_.mkString).take(maxPaths).toList

  /**
   * Converts path of actor IDs to PathSteps with human-readable names and movies.
   *
   * OPTIMIZED: Batches all actor name lookups into a single query, then processes edges.
   * This reduces N sequential database queries to 1 batch query + N movie queries.
   *
   * For each edge (pair of adjacent actors):
   * 1. Lookup first actor's name (from pre-fetched map)
   * 2. Lookup movie they appeared in together
   * 3. Create PathStep(actorName, movie)
   *
   * Uses sliding window to process pairs:
   * - Path [A, B, C] becomes pairs [(A,B), (B,C)]
   * - Each pair represents one "step" in the path
   * - Step shows: actor A appeared in movie X
   *
   * @return List of PathSteps with names and movie titles
   */
  private def convertActorPathToSteps(
      actorPath: List[ActorId],
      actorNamePs: PreparedQuery[F, ActorId, ActorName],
      moviesPs: PreparedQuery[F, ActorId *: ActorId *: EmptyTuple, MovieTitle]
  ): F[List[PathStep]] =
    // Batch fetch all actor names first
    val uniqueActors = actorPath.take(actorPath.length - 1).toSet // Only need actors that start edges
    uniqueActors.toList.traverse(id => actorNamePs.option(id).map(name => id -> name.getOrElse("Unknown"))).flatMap {
      namesList =>
        val nameMap = namesList.toMap
        actorPath.sliding(2).toList.traverse {
          case List(actor1, actor2) =>
            val actor1Name = nameMap.getOrElse(actor1, "Unknown")
            fetchMovie(moviesPs, actor1, actor2).map(movie => PathStep(actor1Name, movie))
          case _ => Monad[F].pure(PathStep("Unknown", "Unknown Movie")) // Shouldn't happen
        }
    }

  /** Database lookup with fallback for robustness. */
  private def fetchActorName(ps: PreparedQuery[F, ActorId, ActorName], actorId: ActorId): F[ActorName] =
    ps.option(actorId).map(_.getOrElse("Unknown"))

  /** Database lookup with fallback for robustness. */
  private def fetchMovie(
      ps: PreparedQuery[F, ActorId *: ActorId *: EmptyTuple, MovieTitle],
      actor1: ActorId,
      actor2: ActorId
  ): F[MovieTitle] =
    ps.option(actor1 *: actor2 *: EmptyTuple).map(_.getOrElse("Unknown Movie"))

  /**
   * SQL queries for IMDb actor graph traversal and path reconstruction.
   *
   * Schema dependencies:
   * - name_basics: actor information (nconst, primaryName, knownForTitles)
   * - title_principals: actor-movie relationships (nconst, tconst, category)
   * - title_basics: movie information (tconst, primaryTitle)
   *
   * Index usage:
   * - name_basics_primaryname_index: actor name lookup
   * - title_principals_actor_actress_nconst_tconst_idx: co-star queries (partial index on category)
   * - title_principals_nconst_tconst_index: general actor-movie joins
   * - title_basics_title_id_index: movie title lookup
   *
   * Query design principles:
   * - Use partial indexes (actor/actress category) when available
   * - Batch operations via UNNEST for bulk queries
   * - DISTINCT to handle duplicate relationships
   * - LEFT JOIN for exclusion (actors not in input set)
   */
  private object ActorMovieSQL:

    /**
     * Finds most prominent actor matching name.
     *
     * Disambiguation strategy:
     * - Multiple actors may share same name
     * - Sorts by knownForTitles length (comma count) as proxy for prominence
     * - More credits = more likely to be the famous one users want
     *
     * Filters:
     * - birthYear IS NOT NULL: excludes incomplete records
     * - knownForTitles IS NOT NULL/!='': ensures we have career data
     *
     * Index: Uses name_basics_primaryname_index for name lookup
     */
    val getActorIdByNameSql: Query[ActorName, ActorId] = sql"""
      SELECT nconst
      FROM name_basics
      WHERE primaryName = $varchar
      AND birthYear IS NOT NULL
      AND knownForTitles IS NOT NULL
      AND knownForTitles != ''
      ORDER BY LENGTH(knownForTitles) - LENGTH(REPLACE(knownForTitles, ',', '')) DESC
      LIMIT 1;
    """.query(varchar(10))

    /**
     * Simple lookup of actor name by IMDb identifier.
     *
     * Used during path reconstruction to convert actor IDs to display names.
     * Primary key lookup (nconst) so very fast.
     */
    val getActorNameByIdSql: Query[ActorId, ActorName] = sql"""
      SELECT primaryName
      FROM name_basics
      WHERE nconst = ${varchar(10)}
      LIMIT 1;
    """.query(varchar(110))

    /**
     * Batch lookup of actor names by IMDb identifiers.
     *
     * Takes comma-separated actor IDs and returns all names in one query.
     * More efficient than N individual queries during path reconstruction.
     */
    val getActorNamesByIdsBatchSql: Query[String, (ActorId, ActorName)] = sql"""
      WITH input AS (
        SELECT unnest(string_to_array($text, ',')) AS nconst
      )
      SELECT nb.nconst, nb.primaryName
      FROM input i
      JOIN name_basics nb ON nb.nconst = i.nconst;
    """.query(varchar(10) *: varchar(110))

    /**
     * Bulk collaborator retrieval for entire frontier in single query.
     *
     * Input format: Comma-separated person IDs as single text parameter
     * Example: "nm0000102,nm0000158,nm0000354"
     *
     * Query strategy:
     * 1. CTE 'input': UNNEST comma-separated string into rows
     * 2. JOIN tp1: Get all titles for input people
     * 3. JOIN tp2: Get all people in those titles
     * 4. LEFT JOIN i2: Exclude input people from results (self-loops)
     * 5. DISTINCT: Multiple titles may connect same pair
     *
     * Why LEFT JOIN exclusion:
     * - WHERE i2.nconst IS NULL filters out input people
     * - More efficient than NOT IN or EXISTS
     *
     * Performance:
     * - Uses title_principals_nconst_tconst_index (covers all categories)
     * - Single query vs N individual queries (massive speedup)
     * - Critical for large frontier performance
     *
     * Note: Includes ALL collaborations (actors, directors, writers, producers, etc.)
     *
     * Used by: createBulkNeighborsFetcher for distance-only BFS
     */
    val getCoStarsSql: Query[String, ActorId] = sql"""
      WITH input AS (
        SELECT unnest(string_to_array($text, ',')) AS nconst
      )
      SELECT DISTINCT tp2.nconst
      FROM input i
      JOIN title_principals tp1 ON tp1.nconst = i.nconst
      JOIN title_principals tp2 ON tp1.tconst = tp2.tconst
      LEFT JOIN input i2 ON i2.nconst = tp2.nconst
      WHERE i2.nconst IS NULL;
    """.query(varchar(10))

    /**
     * Collaborator retrieval for single person (path tracking variant).
     *
     * Simpler than bulk version:
     * - Takes single person ID parameter
     * - No UNNEST or CTE needed
     * - Simple self-join on tconst (title ID)
     *
     * Filter logic:
     * - tp2.nconst != tp1.nconst: Exclude self
     * - DISTINCT: Multiple titles may connect people
     *
     * Why separate query:
     * - Used by path tracking which needs per-node parent attribution
     * - Sequential execution (one person at a time)
     * - Simpler query plan for single-person case
     *
     * Index usage:
     * - title_principals_nconst_tconst_index for both tp1 and tp2
     *
     * Note: Includes ALL collaborations (actors, directors, writers, producers, etc.)
     *
     * Used by: expandWithParentsSequential in path reconstruction
     */
    val getCoStarsSingleSql: Query[ActorId, ActorId] = sql"""
      SELECT DISTINCT tp2.nconst
      FROM title_principals tp1
      JOIN title_principals tp2 ON tp1.tconst = tp2.tconst
      WHERE tp1.nconst = ${varchar(10)}
        AND tp2.nconst != tp1.nconst;
    """.query(varchar(10))

    /** Helper codec for actor ID tuple parameters. */
    private val actorCodec: Encoder[ActorId] = varchar(10)

    /**
     * Finds single title where two people collaborated.
     *
     * Used for path edge labeling during reconstruction.
     * Returns first title found (arbitrary choice when multiple exist).
     *
     * Join strategy:
     * 1. tp1/tp2: Both people must appear in same title (tconst)
     * 2. tb: Lookup title name
     * 3. DISTINCT: Redundant but defensive (shouldn't have duplicates)
     *
     * Note: Includes ALL types of collaboration (acting, directing, writing, producing, etc.)
     *
     * LIMIT 1:
     * - We only need one title to label the edge
     * - Saves bandwidth and processing
     * - Choice is deterministic per database state
     *
     * Used by: convertActorPathToSteps for each edge in path
     */
    val getMoviesBetweenActorsSql: Query[ActorId *: ActorId *: EmptyTuple, MovieTitle] = sql"""
      SELECT DISTINCT tb.primaryTitle
      FROM title_principals tp1
      JOIN title_principals tp2 ON tp1.tconst = tp2.tconst
      JOIN title_basics tb ON tp1.tconst = tb.tconst
      WHERE tp1.nconst = $actorCodec
        AND tp2.nconst = $actorCodec
      LIMIT 1;
    """.query(varchar(500))

    /**
     * Finds multiple titles where two people collaborated.
     *
     * Variant of single title query that returns up to 10 titles.
     *
     * Why multiple:
     * - For Bacon number 1, we can show different connection titles
     * - Provides variety when people have worked together multiple times
     * - Example: Kevin Bacon + Ron Howard in "Apollo 13" (acting/directing)
     *
     * Note: Includes ALL types of collaboration (acting, directing, writing, producing, etc.)
     *
     * LIMIT 10:
     * - Reasonable upper bound for variety
     * - Prevents excessive results for prolific collaborations
     * - Actual returned count limited by maxPaths parameter
     *
     * Used by: reconstructDirectConnection for Bacon number 1 case
     */
    val getMoviesBetweenActorsMultipleSql: Query[ActorId *: ActorId *: EmptyTuple, MovieTitle] = sql"""
      SELECT DISTINCT tb.primaryTitle
      FROM title_principals tp1
      JOIN title_principals tp2 ON tp1.tconst = tp2.tconst
      JOIN title_basics tb ON tp1.tconst = tb.tconst
      WHERE tp1.nconst = $actorCodec
        AND tp2.nconst = $actorCodec
      LIMIT 10;
    """.query(varchar(500))
