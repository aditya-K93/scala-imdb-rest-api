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

/** Computes Kevin Bacon numbers and (optionally) shortest connection paths. */
trait KevinBaconDetails[F[_]]:
  /** Resolve an actor name to an IMDb person id (best-effort disambiguation). */
  def getActorIdByName(actorName: String): F[Option[String]]

  /** Return the Kevin Bacon number for the given actor name (or -1 if unknown/unreachable). */
  def degreesOfSeparationKevinBacon(actorName: String): F[BaconNumber]

  /** Like degreesOfSeparationKevinBacon, but also returns up to maxPaths shortest paths. */
  def degreesOfSeparationKevinBaconWithPaths(actorName: String, maxPaths: Option[Int]): F[BaconNumberWithPaths]

object KevinBaconDetails:

  /** IMDb person identifier (nconst: nm0000000). */
  type ActorId = String

  /** Type alias for person primary name as stored in IMDb. */
  type ActorName = String

  /** Type alias for title primary name (movies, TV shows, etc.). */
  type MovieTitle = String

  /** Type alias for graph distance (number of edges in shortest path). */
  type Depth = Int

  /** Tuning parameters for graph search + DB access. */
  final case class SearchConfig(
      maxDepth: Int,
      coStarBatchSize: Int,
      coStarParallelism: Int,
      coStarParallelThreshold: Int
  )

  /** Parent pointers for shortest-path reconstruction (multiple parents == indicates multiple shortest paths). */
  type ParentMap = Map[ActorId, Set[ActorId]]

  /** Operations required by the generic bidirectional BFS loop (distance-only vs. path-tracking). */
  trait BfsState[S, R]:
    /** Initial state for a search starting at node. */
    def initial(node: ActorId): S

    /** Whether node has been seen in this direction. */
    def contains(state: S, node: ActorId): Boolean

    /** Current frontier depth for this direction. */
    def depth(state: S): Depth

    /** Update state after expanding frontier (and optionally record parent pointers). */
    def expand(state: S, frontier: Set[ActorId], newNodes: Set[ActorId], nodeParents: List[(ActorId, Set[ActorId])]): S

    /** Check whether the two searches intersect at this level. */
    def checkMeeting(stateA: S, stateB: S, frontierA: Set[ActorId], frontierB: Set[ActorId]): Option[R]

    /** Build a result from a set of meeting nodes. */
    def buildResult(stateA: S, stateB: S, meetings: Set[ActorId]): R

  /** Distance-only BFS state. */
  final case class DistanceState(visited: Map[ActorId, Depth], currentDepth: Depth)

  /** Path-tracking BFS state (parents capture multiple shortest paths). */
  final case class PathState(visited: Set[ActorId], parents: ParentMap, currentDepth: Depth)

  /** Parent maps and meeting nodes from a bidirectional path search. */
  final case class BfsPathResult(
      forwardParents: ParentMap,
      backwardParents: ParentMap,
      meetingNodes: Set[ActorId]
  )

  object BfsState:
    /** BfsState instance for distance-only searches. */
    given distanceState: BfsState[DistanceState, Depth] with
      def initial(node: ActorId): DistanceState =
        DistanceState(Map(node -> 0), 0)

      def contains(state: DistanceState, node: ActorId): Boolean =
        state.visited.contains(node)

      def depth(state: DistanceState): Depth = state.currentDepth

      /** Record newly discovered nodes at depth+1. */
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

        /** Return distance when the searches meet (short-circuits on first hit). */
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

      /** Choose the shortest meeting distance among recorded meeting nodes. */
      def buildResult(stateA: DistanceState, stateB: DistanceState, meetings: Set[ActorId]): Depth =
        meetings.iterator.flatMap { m =>
          for
            dA <- stateA.visited.get(m)
            dB <- stateB.visited.get(m)
          yield dA + dB
        }.minOption.getOrElse(stateA.currentDepth + stateB.currentDepth)

    /** BfsState instance for path-tracking searches. */
    given pathState: BfsState[PathState, BfsPathResult] with
      def initial(node: ActorId): PathState =
        PathState(Set(node), Map(node -> Set.empty), 0)

      def contains(state: PathState, node: ActorId): Boolean =
        state.visited.contains(node)

      def depth(state: PathState): Depth = state.currentDepth

      /** Record parent pointers for newly discovered nodes (supports multiple parents). */
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

        /** Detect intersection and keep all meeting nodes (to enumerate multiple shortest paths). */
      def checkMeeting(
          stateA: PathState,
          stateB: PathState,
          frontierA: Set[ActorId],
          frontierB: Set[ActorId]
      ): Option[BfsPathResult] =
        val meetings = frontierA.intersect(stateB.visited) ++ frontierB.intersect(stateA.visited)
        Option.when(meetings.nonEmpty)(BfsPathResult(stateA.parents, stateB.parents, meetings))

      /** Package parents + meeting nodes; reconstruction happens later. */
      def buildResult(stateA: PathState, stateB: PathState, meetings: Set[ActorId]): BfsPathResult =
        BfsPathResult(stateA.parents, stateB.parents, meetings)

  /** Kevin Bacon's IMDb identifier (stable across dataset versions). */
  private[services] val kevinBaconId: ActorId = "nm0000102"

  /** Default tuning for the IMDb-sized dataset. */
  private val defaultConfig: SearchConfig = SearchConfig(
    maxDepth = 6,
    coStarBatchSize = 400,
    coStarParallelism = 4,
    coStarParallelThreshold = 1200
  )

  /** Generic bidirectional BFS parameterized by a BfsState (distance vs. paths). */
  private[services] def bidirectionalBfs[F[_]: Monad, S, R](
      startActor: ActorId,
      targetActor: ActorId,
      maxDepth: Depth,
      neighbors: Set[ActorId] => F[Set[ActorId]],
      sameNodeResult: => R
  )(using bfs: BfsState[S, R]): F[Option[R]] =
    bidirectionalBfsWithPairs(
      startActor,
      targetActor,
      maxDepth,
      frontier => neighbors(frontier).map(ns => frontier.toList.map(_ -> ns)),
      sameNodeResult
    )

  /** Like bidirectionalBfs, but keeps per-frontier-node neighbor sets (needed for parent tracking). */
  private[services] def bidirectionalBfsWithPairs[F[_]: Monad, S, R](
      startActor: ActorId,
      targetActor: ActorId,
      maxDepth: Depth,
      neighbors: Set[ActorId] => F[List[(ActorId, Set[ActorId])]],
      sameNodeResult: => R
  )(using bfs: BfsState[S, R]): F[Option[R]] =
    if startActor == targetActor then Monad[F].pure(Some(sameNodeResult))
    else
      def expandFrontier(
          frontier: Set[ActorId],
          state: S,
          otherFrontier: Set[ActorId],
          otherState: S
      ): F[Option[(Set[ActorId], S, Set[ActorId])]] =
        if bfs.depth(state) + 1 + bfs.depth(otherState) > maxDepth then Monad[F].pure(None)
        else
          neighbors(frontier).map { nodeNeighborPairs =>
            val newNodes  = nodeNeighborPairs.iterator.flatMap(_._2).toSet.filterNot(bfs.contains(state, _))
            val nextState = bfs.expand(state, frontier, newNodes, nodeNeighborPairs)
            val meetings  = newNodes.intersect(otherFrontier) ++ newNodes.filter(bfs.contains(otherState, _))
            Some((newNodes, nextState, meetings))
          }

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
                expandFrontier(frontierA, stateA, frontierB, stateB).flatMap {
                  case None                                      => Monad[F].pure(None)
                  case Some((newFrontierA, newStateA, meetings)) =>
                    if meetings.nonEmpty then
                      Monad[F].pure(Some(bfs.buildResult(newStateA, stateB, meetings)))
                    else
                      loop(newFrontierA, newStateA, frontierB, stateB)
                }
              else
                expandFrontier(frontierB, stateB, frontierA, stateA).flatMap {
                  case None                                      => Monad[F].pure(None)
                  case Some((newFrontierB, newStateB, meetings)) =>
                    if meetings.nonEmpty then
                      Monad[F].pure(Some(bfs.buildResult(stateA, newStateB, meetings)))
                    else
                      loop(frontierA, stateA, newFrontierB, newStateB)
                }

      loop(Set(startActor), bfs.initial(startActor), Set(targetActor), bfs.initial(targetActor))

  /** Distance-only bidirectional BFS (fast, bulk neighbor fetch). */
  private[services] def findDistance[F[_]: Monad](
      startActor: ActorId,
      targetActor: ActorId,
      maxDepth: Depth,
      neighbors: Set[ActorId] => F[Set[ActorId]]
  ): F[Option[Depth]] =
    bidirectionalBfs[F, DistanceState, Depth](startActor, targetActor, maxDepth, neighbors, 0)

  /** Path-tracking bidirectional BFS (sequential per-node neighbor fetch to keep parent pointers). */
  private[services] def findPathsSequential[F[_]: Monad](
      startActor: ActorId,
      targetActor: ActorId,
      maxDepth: Depth,
      getNeighborsForNode: ActorId => F[Set[ActorId]]
  ): F[Option[BfsPathResult]] =
    bidirectionalBfsWithPairs[F, PathState, BfsPathResult](
      startActor,
      targetActor,
      maxDepth,
      frontier => frontier.toList.traverse(node => getNeighborsForNode(node).map(node -> _)),
      BfsPathResult(Map.empty, Map.empty, Set(startActor))
    )

  /** Enumerate up to limit shortest paths from node back to root using the parent map. */
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

  /** Build a live KevinBaconDetails backed by Postgres. */
  def make[F[_]: Concurrent](postgres: Resource[F, Session[F]]): KevinBaconDetails[F] =
    new KevinBaconDetailsLive[F](postgres, defaultConfig)

/** Live Postgres-backed implementation. */
private final class KevinBaconDetailsLive[F[_]: Concurrent](
    postgres: Resource[F, Session[F]],
    config: KevinBaconDetails.SearchConfig
) extends KevinBaconDetails[F]:
  import ActorMovieSQL.*
  import KevinBaconDetails.*

  private val kevinBaconActorId: ActorId = kevinBaconId

  private def lookupActorId(session: Session[F], actorName: ActorName): F[Option[ActorId]] =
    session.prepare(getActorIdByNameSql).flatMap(_.option(actorName))

  /** Resolve an actor name to an id via a prepared query. */
  override def getActorIdByName(actorName: ActorName): F[Option[ActorId]] =
    postgres.use(session => lookupActorId(session, actorName))

  /** Compute the Kevin Bacon number (fast path: bulk neighbor fetch + distance-only BFS). */
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

  /** Compute the Bacon number and (when reachable) reconstruct up to maxPaths shortest paths. */
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

  /** Neighbor fetcher that switches to parallel batched queries for large frontiers. */
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

  /** Parallel batched co-star fetch for large frontiers (bounded by a semaphore). */
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

  /** Lookup target actor id, then reconstruct paths. */
  private def findActorAndReconstructPaths(
      actorName: ActorName,
      depth: Depth,
      maxPaths: Option[Int]
  ): F[BaconNumberWithPaths] =
    postgres.use(session => lookupActorId(session, actorName)).flatMap {
      case None                => Monad[F].pure(BaconNumberWithPaths(-1, None))
      case Some(targetActorId) => reconstructPaths(targetActorId, depth, maxPaths)
    }

  /** Choose a reconstruction strategy based on distance (0, 1, or 2+). */
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

  /** Bacon number 1: return movies Kevin Bacon shared with the target. */
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

  /** Bacon number 2+: bidirectional BFS with parent tracking + meeting-node reconstruction. */
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
            actorPaths.traverse { actorPath =>
              convertActorPathToSteps(actorPath, actorNamePs, moviesPs).map(steps =>
                BaconPath(depth, steps)
              )
            }
      yield BaconNumberWithPaths(depth, Some(allPaths))
    }

  /** Join forward/backward parent maps at meeting nodes to produce complete actor-id paths. */
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

  /** Convert an actor-id path to PathSteps by looking up names and an edge-label movie per hop. */
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

  /** Actor-name lookup with a fallback. */
  private def fetchActorName(ps: PreparedQuery[F, ActorId, ActorName], actorId: ActorId): F[ActorName] =
    ps.option(actorId).map(_.getOrElse("Unknown"))

  /** Movie-title lookup with a fallback. */
  private def fetchMovie(
      ps: PreparedQuery[F, ActorId *: ActorId *: EmptyTuple, MovieTitle],
      actor1: ActorId,
      actor2: ActorId
  ): F[MovieTitle] =
    ps.option(actor1 *: actor2 *: EmptyTuple).map(_.getOrElse("Unknown Movie"))

  /** SQL used for lookups, co-star expansion, and edge-label movie titles. */
  private object ActorMovieSQL:

    /** Name -> id lookup (best-effort disambiguation via knownForTitles). */
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

    /** Id -> name lookup. */
    val getActorNameByIdSql: Query[ActorId, ActorName] = sql"""
      SELECT primaryName
      FROM name_basics
      WHERE nconst = ${varchar(10)}
      LIMIT 1;
    """.query(varchar(110))

    /** Batch (id -> name) lookup via a comma-separated input list. */
    val getActorNamesByIdsBatchSql: Query[String, (ActorId, ActorName)] = sql"""
      WITH input AS (
        SELECT unnest(string_to_array($text, ',')) AS nconst
      )
      SELECT nb.nconst, nb.primaryName
      FROM input i
      JOIN name_basics nb ON nb.nconst = i.nconst;
    """.query(varchar(10) *: varchar(110))

    /** Bulk co-star expansion for a whole frontier (comma-separated input list). */
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

    /** Single-node co-star expansion (used when we need per-node parent attribution). */
    val getCoStarsSingleSql: Query[ActorId, ActorId] = sql"""
      SELECT DISTINCT tp2.nconst
      FROM title_principals tp1
      JOIN title_principals tp2 ON tp1.tconst = tp2.tconst
      WHERE tp1.nconst = ${varchar(10)}
        AND tp2.nconst != tp1.nconst;
    """.query(varchar(10))

    /** Helper codec for actor ID tuple parameters. */
    private val actorCodec: Encoder[ActorId] = varchar(10)

    /** One movie title that connects actor1 and actor2 (for edge labeling). */
    val getMoviesBetweenActorsSql: Query[ActorId *: ActorId *: EmptyTuple, MovieTitle] = sql"""
      SELECT DISTINCT tb.primaryTitle
      FROM title_principals tp1
      JOIN title_principals tp2 ON tp1.tconst = tp2.tconst
      JOIN title_basics tb ON tp1.tconst = tb.tconst
      WHERE tp1.nconst = $actorCodec
        AND tp2.nconst = $actorCodec
      LIMIT 1;
    """.query(varchar(500))

    /** Up to 10 movie titles connecting actor1 and actor2 (used for Bacon #1 responses). */
    val getMoviesBetweenActorsMultipleSql: Query[ActorId *: ActorId *: EmptyTuple, MovieTitle] = sql"""
      SELECT DISTINCT tb.primaryTitle
      FROM title_principals tp1
      JOIN title_principals tp2 ON tp1.tconst = tp2.tconst
      JOIN title_basics tb ON tp1.tconst = tb.tconst
      WHERE tp1.nconst = $actorCodec
        AND tp2.nconst = $actorCodec
      LIMIT 10;
    """.query(varchar(500))
