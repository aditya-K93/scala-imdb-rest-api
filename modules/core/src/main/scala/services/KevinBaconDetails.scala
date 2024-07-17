package services

import cats._
import cats.effect.{ Concurrent, Resource }
import skunk._
import skunk.codec.all._
import skunk.implicits._
import cats.syntax.all._
import domain.baconNumber.BaconNumber

trait KevinBaconDetails[F[_]] {
  def getActorIdByName(actorName: String): F[Option[String]]
  def degreesOfSeparationKevinBacon(actorName: String): F[BaconNumber]
}

case class Movie(tconst: String, primaryTitle: String)

object KevinBaconDetails {
  def make[F[_]: Concurrent](postgres: Resource[F, Session[F]]): KevinBaconDetails[F] = {
    new KevinBaconDetails[F] {
      import ActorMovieSQL._

      override def getActorIdByName(actorName: String): F[Option[String]] =
        postgres.use { session => session.prepare(getActorIdByNameSql).use { ps => ps.option(actorName) } }

      override def degreesOfSeparationKevinBacon(actorName: String): F[BaconNumber] = {

        def bfs(startActor: String, targetActor: String): F[Option[Int]] = {
          def loop(levelActors: Set[String], visited: Set[String], depth: Int): F[Option[Int]] = {
            if (levelActors.isEmpty) {
              Monad[F].pure(None)
            } else if (levelActors.contains(targetActor)) {
              Monad[F].pure(Some(depth))
            } else {
              for {
                coStars <- getCoStarsBatch(levelActors)
                newActors = coStars.filterNot(visited.contains)
                result <- loop(newActors, visited ++ levelActors, depth + 1)
              } yield result
            }
          }

          loop(Set(startActor), Set.empty, 0)
        }

        def getCoStarsBatch(actorIds: Set[String]): F[Set[String]] = {
          val query = getCoStarsSql(actorIds.map(x => s"""'$x'""").mkString(","))

          postgres.use { session =>
            session.prepare(query).use { ps => ps.stream(Void, 1024).compile.toList.map(_.flatten.toSet) }
          }
        }

        for {
          kevinBaconId     <- Monad[F].pure("nm0000102") // Kevin Bacon's ID
          targetActorIdOpt <- getActorIdByName(actorName)
          result <- targetActorIdOpt match {
            case Some(targetActorId) =>
              bfs(kevinBaconId, targetActorId)
                .map(optionDepth => BaconNumber(optionDepth.getOrElse(-1)))
            case None => Monad[F].pure(BaconNumber(-1)) // Actor not found
          }
        } yield result
      }
    }
  }
}

private object ActorMovieSQL {
  val textDecoder: Decoder[List[String]] = (varchar(10)).map(m => m.toString.split(",").toList)

  val getActorIdByNameSql: Query[String, String] =
    sql"""
         SELECT nconst
         FROM name_basics
         WHERE primaryName = $varchar
         AND birthYear IS NOT NULL
         AND knownForTitles IS NOT NULL
         AND knownForTitles != ''
         ORDER BY LENGTH(knownForTitles) - LENGTH(REPLACE(knownForTitles, ',', '')) DESC
         LIMIT 1;
       """.query(varchar(10))

  val getCoStarsSql: String => Query[Void, List[String]] = { actorIds: String =>
    sql"""
        SELECT DISTINCT tp2.nconst
        FROM title_principals tp1
        JOIN title_principals tp2 ON tp1.tconst = tp2.tconst
        WHERE tp1.nconst = ANY(ARRAY[#$actorIds])
        AND tp2.nconst  <> ALL(ARRAY[#$actorIds])
      """.query(textDecoder)
  }

}
