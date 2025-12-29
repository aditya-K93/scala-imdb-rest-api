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

trait KevinBaconDetails[F[_]]:
  def getActorIdByName(actorName: String): F[Option[String]]
  def degreesOfSeparationKevinBacon(actorName: String): F[BaconNumber]
  def degreesOfSeparationKevinBaconWithPaths(actorName: String, maxPaths: Option[Int]): F[BaconNumberWithPaths]

/** Domain types for improved type safety */
type ActorId    = String
type ActorName  = String
type MovieTitle = String
type Depth      = Int

/** Configuration for graph search operations */
final case class SearchConfig(
    maxDepth: Int,
    coStarBatchSize: Int,
    coStarParallelism: Int,
    coStarParallelThreshold: Int
)

/** Parent tracking for path reconstruction */
type ParentMap = Map[ActorId, Set[ActorId]]

// =============================================================================
// Unified Bidirectional BFS Framework
// =============================================================================

/**
 * Typeclass abstracting BFS search state and operations.
 * Enables a single generic bidirectional BFS implementation.
 */
trait BfsState[S, R]:
  /** Initial state for a starting node */
  def initial(node: ActorId): S

  /** Check if node is already visited */
  def contains(state: S, node: ActorId): Boolean

  /** Get current depth from state */
  def depth(state: S): Depth

  /** Expand state with new frontier nodes */
  def expand(state: S, frontier: Set[ActorId], newNodes: Set[ActorId], nodeParents: List[(ActorId, Set[ActorId])]): S

  /** Check for meeting between two states, return result if found */
  def checkMeeting(stateA: S, stateB: S, frontierA: Set[ActorId], frontierB: Set[ActorId]): Option[R]

  /** Build result when meeting is detected after expansion */
  def buildResult(stateA: S, stateB: S, meetings: Set[ActorId]): R

/** State for distance-only BFS */
final case class DistanceState(visited: Map[ActorId, Depth], currentDepth: Depth)

/** State for path-tracking BFS */
final case class PathState(visited: Set[ActorId], parents: ParentMap, currentDepth: Depth)

/** Result of path-tracking BFS */
final case class BfsPathResult(
    forwardParents: ParentMap,
    backwardParents: ParentMap,
    meetingNodes: Set[ActorId]
)

object BfsState:
  /** BfsState instance for distance-only search */
  given distanceState: BfsState[DistanceState, Depth] with
    def initial(node: ActorId): DistanceState =
      DistanceState(Map(node -> 0), 0)

    def contains(state: DistanceState, node: ActorId): Boolean =
      state.visited.contains(node)

    def depth(state: DistanceState): Depth = state.currentDepth

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

    def buildResult(stateA: DistanceState, stateB: DistanceState, meetings: Set[ActorId]): Depth =
      meetings.iterator.flatMap { m =>
        for
          dA <- stateA.visited.get(m)
          dB <- stateB.visited.get(m)
        yield dA + dB
      }.minOption.getOrElse(stateA.currentDepth + stateB.currentDepth)

  /** BfsState instance for path-tracking search */
  given pathState: BfsState[PathState, BfsPathResult] with
    def initial(node: ActorId): PathState =
      PathState(Set(node), Map(node -> Set.empty), 0)

    def contains(state: PathState, node: ActorId): Boolean =
      state.visited.contains(node)

    def depth(state: PathState): Depth = state.currentDepth

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

    def checkMeeting(
        stateA: PathState,
        stateB: PathState,
        frontierA: Set[ActorId],
        frontierB: Set[ActorId]
    ): Option[BfsPathResult] =
      val meetings = frontierA.intersect(stateB.visited) ++ frontierB.intersect(stateA.visited)
      Option.when(meetings.nonEmpty)(BfsPathResult(stateA.parents, stateB.parents, meetings))

    def buildResult(stateA: PathState, stateB: PathState, meetings: Set[ActorId]): BfsPathResult =
      BfsPathResult(stateA.parents, stateB.parents, meetings)

object KevinBaconDetails:

  /**
   * Kevin Bacon's `nconst` in the IMDb dataset.
   * This is stable across dataset releases.
   */
  private[services] val kevinBaconId: ActorId = "nm0000102"

  /**
   * Default search configuration with conservative limits to prevent
   * graph explosion and database saturation.
   */
  private val defaultConfig: SearchConfig = SearchConfig(
    maxDepth = 6,                  // Safety valve for runaway expansion
    coStarBatchSize = 400,         // Batch size for parallel queries
    coStarParallelism = 4,         // Max concurrent DB queries
    coStarParallelThreshold = 1200 // Threshold to trigger parallelization
  )

  // =============================================================================
  // Unified Bidirectional BFS
  // =============================================================================

  /**
   * Generic bidirectional BFS parameterized by state type.
   *
   * This unified implementation handles both distance-only and path-tracking
   * variants through the BfsState typeclass.
   *
   * @tparam S State type (DistanceState or PathState)
   * @tparam R Result type (Depth or BfsPathResult)
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
              val expandA = frontierA.size <= frontierB.size
              if expandA then
                expandAndContinue(frontierA, stateA, frontierB, stateB, maxDepth, neighbors, loop)
              else
                expandAndContinue(
                  frontierB,
                  stateB,
                  frontierA,
                  stateA,
                  maxDepth,
                  neighbors,
                  (fA, sA, fB, sB) => loop(fB, sB, fA, sA)
                )

      loop(Set(startActor), bfs.initial(startActor), Set(targetActor), bfs.initial(targetActor))

  /**
   * Expands one side of the search and continues.
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
    if bfs.depth(state) + 1 + bfs.depth(otherState) > maxDepth then Monad[F].pure(None)
    else
      neighbors(frontier).flatMap { allNeighbors =>
        val newNodes    = allNeighbors.filterNot(bfs.contains(state, _))
        val nodeParents = frontier.toList.map(n => (n, allNeighbors)) // Simplified for distance
        val nextState   = bfs.expand(state, frontier, newNodes, nodeParents)
        val meetings    = newNodes.intersect(otherFrontier) ++ newNodes.filter(bfs.contains(otherState, _))

        if meetings.nonEmpty then
          Monad[F].pure(Some(bfs.buildResult(nextState, otherState, meetings)))
        else
          continue(newNodes, nextState, otherFrontier, otherState)
      }

  /**
   * Bidirectional BFS for distance-only search.
   */
  private[services] def findDistance[F[_]: Monad](
      startActor: ActorId,
      targetActor: ActorId,
      maxDepth: Depth,
      neighbors: Set[ActorId] => F[Set[ActorId]]
  ): F[Option[Depth]] =
    bidirectionalBfs[F, DistanceState, Depth](startActor, targetActor, maxDepth, neighbors, 0)

  /**
   * Bidirectional BFS with path tracking using sequential neighbor fetching.
   *
   * NOTE: Uses sequential fetching because Skunk sessions don't support
   * concurrent operations on the same connection.
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
   * Path search with proper parent tracking per node (sequential version).
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
   * Expand frontier with proper parent tracking for path reconstruction (sequential).
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
      frontier.toList.traverse(node => getNeighborsForNode(node).map(n => (node, n))).map { nodeNeighborPairs =>
        val newParents = nodeNeighborPairs.foldLeft(state.parents) { case (acc, (parent, neighbors)) =>
          neighbors.filterNot(state.visited.contains).foldLeft(acc) { (inner, neighbor) =>
            inner.updatedWith(neighbor) {
              case Some(existing) => Some(existing + parent)
              case None           => Some(Set(parent))
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

  // =============================================================================
  // Path Reconstruction Helpers
  // =============================================================================

  /**
   * Reconstructs all paths from a node back to the root using parent map.
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
        case None                                 => List.empty
        case Some(parentSet) if parentSet.isEmpty => List(List(node))
        case Some(parentSet)                      =>
          parentSet.toList.take(limit).flatMap { parent =>
            reconstructAllPaths(parent, parents, root, limit).map(path => node :: path)
          }.take(limit)

  // =============================================================================
  // Implementation Factory
  // =============================================================================

  def make[F[_]: Concurrent](postgres: Resource[F, Session[F]]): KevinBaconDetails[F] =
    new KevinBaconDetailsLive[F](postgres, defaultConfig)

/**
 * Live implementation of KevinBaconDetails.
 *
 * Uses the unified BFS framework from KevinBaconDetails companion object.
 * Separates concerns into:
 * - Database operations (via SQL queries)
 * - Graph traversal (delegated to unified BFS)
 * - Path reconstruction (converting actor IDs to named paths)
 */
private final class KevinBaconDetailsLive[F[_]: Concurrent](
    postgres: Resource[F, Session[F]],
    config: SearchConfig
) extends KevinBaconDetails[F]:
  import ActorMovieSQL.*
  import KevinBaconDetails.*

  // Local reference to Kevin Bacon's IMDb ID
  private val kevinBaconActorId: ActorId = kevinBaconId

  override def getActorIdByName(actorName: ActorName): F[Option[ActorId]] =
    postgres.use(session => session.prepare(getActorIdByNameSql).flatMap(_.option(actorName)))

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

  // ===========================================================================
  // Private: Database Operations
  // ===========================================================================

  /**
   * Creates a bulk neighbor-fetching function for distance-only BFS.
   * Handles both small and large frontiers with adaptive parallelization.
   */
  private def createBulkNeighborsFetcher(
      coStarsPs: PreparedQuery[F, String, ActorId]
  ): Set[ActorId] => F[Set[ActorId]] =
    (frontier: Set[ActorId]) =>
      if frontier.isEmpty then Monad[F].pure(Set.empty[ActorId])
      else if frontier.size < config.coStarParallelThreshold then
        coStarsPs.stream(frontier.mkString(","), 8192).compile.toList.map(_.toSet)
      else
        fetchCoStarsInParallel(frontier)

  /**
   * Fetches co-stars for large frontiers using parallel batched queries.
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
        }.start
      }.flatMap(_.traverse(_.joinWithNever))
        .map(_.foldLeft(Set.empty[ActorId])(_ ++ _))
    }

  // ===========================================================================
  // Private: Path Reconstruction
  // ===========================================================================

  /**
   * Finds actor ID and reconstructs paths to Kevin Bacon.
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
   * Reconstructs multiple shortest paths between Kevin Bacon and target actor.
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
   * Special case: Direct connection to Kevin Bacon (Bacon number 1).
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
   * Multi-hop paths: Use unified bidirectional BFS with path tracking.
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

        getNeighbors = (actor: ActorId) =>
          coStarsSinglePs.stream(actor, 8192).compile.toList.map(_.toSet)

        // Use unified BFS framework for path finding (sequential for Skunk compatibility)
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
            actorPaths.traverse { actorPath =>
              convertActorPathToSteps(actorPath, actorNamePs, moviesPs).map(steps =>
                BaconPath(depth, steps)
              )
            }
      yield BaconNumberWithPaths(depth, Some(allPaths))
    }

  /**
   * Reconstructs paths through meeting nodes from bidirectional search.
   */
  private def reconstructPathsThroughMeetings(
      meetings: Set[ActorId],
      parentsA: ParentMap,
      parentsB: ParentMap,
      startActor: ActorId,
      targetActor: ActorId,
      maxPaths: Int
  ): List[List[ActorId]] =
    meetings.toList.flatMap { meetingNode =>
      val pathsFromStart = reconstructAllPaths(meetingNode, parentsA, startActor, maxPaths)
      val pathsToEnd     = reconstructAllPaths(meetingNode, parentsB, targetActor, maxPaths)
      for
        pathA <- pathsFromStart.take(maxPaths)
        pathB <- pathsToEnd.take(maxPaths)
      yield pathA.reverse ++ pathB.tail // Combine, skip duplicate meeting node
    }.distinct.take(maxPaths)

  /**
   * Converts a path of actor IDs to PathSteps with names and movies.
   */
  private def convertActorPathToSteps(
      actorPath: List[ActorId],
      actorNamePs: PreparedQuery[F, ActorId, ActorName],
      moviesPs: PreparedQuery[F, ActorId *: ActorId *: EmptyTuple, MovieTitle]
  ): F[List[PathStep]] =
    actorPath.sliding(2).toList.traverse {
      case List(actor1, actor2) =>
        for
          actor1Name <- fetchActorName(actorNamePs, actor1)
          movie      <- fetchMovie(moviesPs, actor1, actor2)
        yield PathStep(actor1Name, movie)
      case _ => Monad[F].pure(PathStep("Unknown", "Unknown Movie"))
    }

  /**
   * Fetches actor name with fallback.
   */
  private def fetchActorName(ps: PreparedQuery[F, ActorId, ActorName], actorId: ActorId): F[ActorName] =
    ps.option(actorId).map(_.getOrElse("Unknown"))

  /**
   * Fetches movie between two actors with fallback.
   */
  private def fetchMovie(
      ps: PreparedQuery[F, ActorId *: ActorId *: EmptyTuple, MovieTitle],
      actor1: ActorId,
      actor2: ActorId
  ): F[MovieTitle] =
    ps.option(actor1 *: actor2 *: EmptyTuple).map(_.getOrElse("Unknown Movie"))

// =============================================================================
// SQL Queries
// =============================================================================

/**
 * SQL queries for actor and movie database operations.
 *
 * Queries are optimized to use available indexes:
 * - name_basics_primaryname_index
 * - title_principals_actor_actress_nconst_tconst_idx
 * - title_principals_nconst_tconst_index
 * - title_basics_title_id_index
 */
private object ActorMovieSQL:

  /**
   * Finds the most prominent actor by name.
   * Uses: name_basics_primaryname_index
   *
   * Sorts by number of known titles (more titles = more prominent)
   * to handle actors with the same name.
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
   * Retrieves actor name by ID.
   * Simple lookup by primary key (nconst).
   */
  val getActorNameByIdSql: Query[ActorId, ActorName] = sql"""
    SELECT primaryName
    FROM name_basics
    WHERE nconst = ${varchar(10)}
    LIMIT 1;
  """.query(varchar(110))

  /**
   * Retrieves co-stars for multiple actors in bulk.
   * Uses: title_principals_actor_actress_nconst_tconst_idx (partial index)
   *
   * Input: Comma-separated list of actor IDs
   * Output: Distinct set of co-star IDs
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
    WHERE i2.nconst IS NULL
      AND tp1.category IN ('actor', 'actress')
      AND tp2.category IN ('actor', 'actress');
  """.query(varchar(10))

  /**
   * Retrieves co-stars for a single actor.
   * Uses: title_principals_actor_actress_nconst_tconst_idx
   *       title_principals_nconst_tconst_index
   *
   * Used for path reconstruction where we query one actor at a time.
   */
  val getCoStarsSingleSql: Query[ActorId, ActorId] = sql"""
    SELECT DISTINCT tp2.nconst
    FROM title_principals tp1
    JOIN title_principals tp2 ON tp1.tconst = tp2.tconst
    WHERE tp1.nconst = ${varchar(10)}
      AND tp2.nconst != tp1.nconst
      AND tp1.category IN ('actor', 'actress')
      AND tp2.category IN ('actor', 'actress');
  """.query(varchar(10))

  /**
   * Finds a single movie where two actors worked together.
   * Uses: title_principals_nconst_tconst_index
   *       title_basics_title_id_index
   */
  private val actorCodec: Encoder[ActorId] = varchar(10)

  val getMoviesBetweenActorsSql: Query[ActorId *: ActorId *: EmptyTuple, MovieTitle] = sql"""
    SELECT DISTINCT tb.primaryTitle
    FROM title_principals tp1
    JOIN title_principals tp2 ON tp1.tconst = tp2.tconst
    JOIN title_basics tb ON tp1.tconst = tb.tconst
    WHERE tp1.nconst = $actorCodec
      AND tp2.nconst = $actorCodec
      AND tp1.category IN ('actor', 'actress')
      AND tp2.category IN ('actor', 'actress')
    LIMIT 1;
  """.query(varchar(500))

  /**
   * Finds multiple movies where two actors worked together.
   * Used when maxPaths > 1 for Bacon number 1 actors to show different connection movies.
   */
  val getMoviesBetweenActorsMultipleSql: Query[ActorId *: ActorId *: EmptyTuple, MovieTitle] = sql"""
    SELECT DISTINCT tb.primaryTitle
    FROM title_principals tp1
    JOIN title_principals tp2 ON tp1.tconst = tp2.tconst
    JOIN title_basics tb ON tp1.tconst = tb.tconst
    WHERE tp1.nconst = $actorCodec
      AND tp2.nconst = $actorCodec
      AND tp1.category IN ('actor', 'actress')
      AND tp2.category IN ('actor', 'actress')
    LIMIT 10;
  """.query(varchar(500))
