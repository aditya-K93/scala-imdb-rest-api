package services

import cats.*
import cats.effect.std.Semaphore
import cats.effect.syntax.all.*
import cats.effect.{ Concurrent, Resource }
import cats.syntax.all.*
import domain.baconNumber.BaconNumber
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

trait KevinBaconDetails[F[_]]:
  def getActorIdByName(actorName: String): F[Option[String]]
  def degreesOfSeparationKevinBacon(actorName: String): F[BaconNumber]

case class Movie(tconst: String, primaryTitle: String)

object KevinBaconDetails:

  /**
   * Kevin Bacon's `nconst` in the IMDb dataset.
   *
   * This is stable across dataset releases.
   */
  private val kevinBaconId: String = "nm0000102"

  /**
   * A safety valve to prevent runaway graph expansion.
   *
   * Note: the actor graph is huge; naive BFS can explode after a few hops.
   */
  private val defaultMaxDepth: Int = 6

  // Parallelism knobs (used only when the frontier gets large).
  // These are deliberately conservative to avoid saturating Postgres.
  private val coStarBatchSize: Int         = 400
  private val coStarParallelism: Int       = 4
  private val coStarParallelThreshold: Int = 1200

  /**
   * Bidirectional BFS to find the shortest path length in an (implicit) undirected graph.
   *
   * This drastically reduces the search space versus one-sided BFS on large graphs.
   */
  private[services] def bidirectionalBfs[F[_]: Monad](
    startActor: String,
    targetActor: String,
    maxDepth: Int,
    neighbors: Set[String] => F[Set[String]]
  ): F[Option[Int]] =
    if startActor == targetActor then Monad[F].pure(Some(0))
    else
      def loop(
        frontierA: Set[String],
        visitedA: Map[String, Int],
        frontierB: Set[String],
        visitedB: Map[String, Int]
      ): F[Option[Int]] =
        if frontierA.isEmpty || frontierB.isEmpty then Monad[F].pure(None)
        else
          val currentMinA = frontierA.iterator.map(visitedA).minOption.getOrElse(0)
          val currentMinB = frontierB.iterator.map(visitedB).minOption.getOrElse(0)

          // If even the shallowest possible meeting is beyond maxDepth, stop.
          if currentMinA + currentMinB > maxDepth then Monad[F].pure(None)
          else
            val expandA = frontierA.size <= frontierB.size
            if expandA then
              val nextDepth = currentMinA + 1
              if nextDepth > maxDepth then Monad[F].pure(None)
              else
                for
                  neigh    <- neighbors(frontierA)
                  nextFront = neigh.filterNot(visitedA.contains)
                  hit       = nextFront.collectFirst(Function.unlift(n => visitedB.get(n).map(db => nextDepth + db)))
                  result   <- hit match
                                case Some(d) if d <= maxDepth => Monad[F].pure(Some(d))
                                case _                        =>
                                  val nextVisited = visitedA ++ nextFront.iterator.map(_ -> nextDepth)
                                  loop(nextFront, nextVisited, frontierB, visitedB)
                yield result
            else
              val nextDepth = currentMinB + 1
              if nextDepth > maxDepth then Monad[F].pure(None)
              else
                for
                  neigh    <- neighbors(frontierB)
                  nextFront = neigh.filterNot(visitedB.contains)
                  hit       = nextFront.collectFirst(Function.unlift(n => visitedA.get(n).map(da => nextDepth + da)))
                  result   <- hit match
                                case Some(d) if d <= maxDepth => Monad[F].pure(Some(d))
                                case _                        =>
                                  val nextVisited = visitedB ++ nextFront.iterator.map(_ -> nextDepth)
                                  loop(frontierA, visitedA, nextFront, nextVisited)
                yield result

      loop(
        frontierA = Set(startActor),
        visitedA = Map(startActor -> 0),
        frontierB = Set(targetActor),
        visitedB = Map(targetActor -> 0)
      )

  def make[F[_]: Concurrent](postgres: Resource[F, Session[F]]): KevinBaconDetails[F] = new KevinBaconDetails[F]:
    import ActorMovieSQL.*

    override def getActorIdByName(actorName: String): F[Option[String]] = postgres
      .use(session => session.prepare(getActorIdByNameSql).flatMap(ps => ps.option(actorName)))

    override def degreesOfSeparationKevinBacon(actorName: String): F[BaconNumber] =
      // Keep a single session open for the whole search to reuse prepared statements.
      postgres.use { session =>
        for
          actorIdPs  <- session.prepare(getActorIdByNameSql)
          coStarsPs  <- session.prepare(getCoStarsSql)
          targetOpt  <- actorIdPs.option(actorName)
          // Fetch co-stars for a frontier. For small frontiers we use the single-session prepared statement.
          // For very large frontiers, we split into batches and query in parallel using multiple pooled sessions.
          getCoStars  = (frontier: Set[String]) =>
                          if frontier.isEmpty then Monad[F].pure(Set.empty[String])
                          else if frontier.size < coStarParallelThreshold then
                            coStarsPs.stream(frontier.mkString(","), 8192).compile.toList.map(_.toSet)
                          else getCoStarsParallel(frontier)
          baconDepth <- targetOpt match
                          case None                => Monad[F].pure(Option.empty[Int])
                          case Some(targetActorId) => KevinBaconDetails.bidirectionalBfs(
                              startActor = kevinBaconId,
                              targetActor = targetActorId,
                              maxDepth = defaultMaxDepth,
                              neighbors = getCoStars
                            )
        yield BaconNumber(baconDepth.getOrElse(-1))
      }

    private def getCoStarsParallel(frontier: Set[String]): F[Set[String]] =
      val batches: List[List[String]] = frontier.toList.grouped(coStarBatchSize).map(_.toList).toList

      // Simple bounded parallel traversal using fibers + a semaphore.
      Semaphore[F](coStarParallelism.toLong).flatMap { sem =>
        batches.traverse { batch =>
          sem.permit.use { _ =>
            postgres.use { s =>
              s.prepare(ActorMovieSQL.getCoStarsSql)
                .flatMap(ps => ps.stream(batch.mkString(","), 8192).compile.toList.map(_.toSet))
            }
          }.start
        }.flatMap(_.traverse(_.joinWithNever)).map(_.foldLeft(Set.empty[String])(_ ++ _))
      }

private object ActorMovieSQL:

  val getActorIdByNameSql: Query[String, String] = sql"""
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
   * Given a frontier of actor ids encoded as a comma-separated string, returns the set of their
   * co-stars (actor/actress only).
   *
   * We intentionally keep this parameterized (no SQL string interpolation). Using a single text
   * parameter avoids requiring an array codec.
   */
  val getCoStarsSql: Query[String, String] = sql"""
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
