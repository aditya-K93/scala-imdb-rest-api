package services

import cats.Monad
import cats.effect.{ Concurrent, Resource }
import domain.actor.Actor
import skunk._
import skunk.codec.all._
import skunk.implicits._
import cats.syntax.all._
import domain.baconNumber.BaconNumber

trait KevinBaconDetails[F[_]] {
  def getAllMoviesByActor(actorId: String): F[Option[Actor]]
  def getActorIdByName(actorName: String): F[Option[String]]
  def getAllMoviesByActorsExcept(actorIdList: List[String], exceptActorIdList: List[String]): F[List[String]]
  def getAllActorsByMoviesExcept(movieIdList: List[String], exceptMovieIdList: List[String]): F[List[String]]
  def degreesOfSeparationKevinBacon(actorName: String): F[BaconNumber]
}

object KevinBaconDetails {
  def make[F[_]: Concurrent](postgres: Resource[F, Session[F]]): KevinBaconDetails[F] = {
    new KevinBaconDetails[F] {

      import ActorMovieSQL._

      def getAllMoviesByActor(actorId: String): F[Option[Actor]] =
        postgres.use { session =>
          session.prepare(selectAllMoviesByActorId).use { ps =>
            ps.option(actorId)
          }
        }

      def getActorIdByName(actorName: String): F[Option[String]] =
        postgres.use { session =>
          session.prepare(selectByActorId).use { ps =>
            ps.option(actorName)
          }
        }

      def getAllMoviesByActorsExcept(actorIdList: List[String], exceptActorIdList: List[String]): F[List[String]] = {
        // skunk doesn't allow inserting params in  queries like where foo in IN ('a' ,'b') without defining length first
        // s"""nm0000102""", s"""nm0000102""" so string interpolation workaround

        val query = selectByActorIdsExcept(
          actorIdList.map(x => s"""'$x'""").mkString(","),
          exceptActorIdList.map(x => s"""'$x'""").mkString(",")
        )
        postgres.use { session =>
          session
            .prepare(query)
            .use { ps =>
              ps.stream(Void, chunkSize = 1024).compile.toList
            }
        }
      }

      def getAllActorsByMoviesExcept(movieIdList: List[String], exceptMovieIdList: List[String]): F[List[String]] = {
        val query = selectByMovieIdsExcept(
          movieIdList.map(x => s"""'$x'""").mkString(","),
          exceptMovieIdList.map(x => s"""'$x'""").mkString(",")
        )
        postgres.use { session =>
          session
            .prepare(query)
            .use { ps =>
              ps.stream(Void, chunkSize = 1024).compile.toList
            }
        }
      }

      def degreesOfSeparationKevinBacon(actorName: String): F[BaconNumber] = {
        for {
          actorId    <- getActorIdByName(actorName)
          kevinBacon <- getAllMoviesByActor("nm0000102") // mah man bacon himself
          givenActor <- getAllMoviesByActor(actorId.getOrElse(""))

          isDegreeFound <- Monad[F].pure(
            actedInSameMovie(
              kevinBacon.getOrElse(Actor("", "", List.empty)),
              givenActor.fold(List.empty[String])(_.movieIdList)
            )
          )
//        need to fix type errors to let IO monad tailRecM without blowing stack ona WithDegreeMoreThanOne() call
//        works only for degree 1 or not found
//          isDegreeFound <- actedWithDegreeMoreThanOne(
//            List(actorId.getOrElse("")),
//            givenActor.fold(List.empty[String])(_.movieIdList),
//            kevinBacon,
//            1
//          )

        } yield BaconNumber(isDegreeFound.compareTo(false))

      }

      def actedInSameMovie(kevinBacon: Actor, givenActors: List[String]): Boolean = {
        if (kevinBacon.movieIdList.intersect(givenActors).nonEmpty) true else false
      }

      def actedWithDegreeMoreThanOne(
          alreadySeenActor: List[String],
          moviesByActorsToEvaluate: List[String],
          kevinBacon: Option[Actor],
          degreeCount: Int = 1
      ): F[Int] = {
        for {
          actors         <- getAllActorsByMoviesExcept(moviesByActorsToEvaluate, alreadySeenActor)
          movies         <- getAllMoviesByActorsExcept(actors, alreadySeenActor)
          didActTogether <- Monad[F].pure(actedInSameMovie(kevinBacon.getOrElse(Actor("", "", List.empty)), movies))

//          if (didActTogether) tailrecM(degreeCount+1) else degreeCount
        } yield didActTogether.compareTo(false)
      }
    }

  }
}

private object ActorMovieSQL {
  val decoder: skunk.Decoder[Actor] =
    (varchar(110) ~ varchar(10) ~ text)
      .map { case an ~ ai ~ mi => Actor(an, ai, mi.toString.split(",").toList) }

  val MovieListDecoder: skunk.Decoder[List[String]] = {
    println("As")
    (varchar(10)).map(m => m.toString.split(",").toList)
  }

  val selectAllMoviesByActorId: Query[String, Actor] =
    sql"""
          SELECT g.primaryname             AS actorName,
                 g.nconst                  AS actorId,
                 String_agg(s.tconst, ',') AS movieListId
          FROM   name_basics AS g
                 INNER JOIN title_principals AS s
                         ON g.nconst = s.nconst
                 INNER JOIN title_basics
                         ON title_basics.tconst = s.tconst
          WHERE  g.nconst = $varchar
                 AND title_basics.titletype = 'movie'
          GROUP   BY g.nconst  
       """.query(decoder)

  val selectByActorId: Query[String, String] =
    sql"""
          SELECT name_basics.nconst
          FROM   name_basics
          WHERE  primaryname ILIKE $varchar
          AND    primaryprofession ILIKE '%actor%'
          AND    birthyear notnull LIMIT 1        
      """.query(varchar(10))

  def selectByActorIdsExcept(actorIdListSql: String, actorIdExceptListSql: String): Query[Void, String] = {
    sql"""

          SELECT String_agg(movieid, ',')
          FROM   (SELECT title_basics.tconst AS movieId
                  FROM   title_principals
                         INNER JOIN title_basics
                                 ON title_basics.tconst = title_principals.tconst
                  WHERE  title_principals.nconst IN  ( #$actorIdListSql )
                         AND title_principals.nconst NOT IN  ( #$actorIdExceptListSql )  ) AS SUBQUERY
          GROUP  BY movieid
        """.query(text)
  }

  def selectByMovieIdsExcept(movieIdListSql: String, movieIdExceptListSql: String): Query[Void, String] = {
    sql"""
        SELECT String_agg(actorid, ',')
        FROM   (SELECT title_principals.nconst AS actorId
                FROM   title_principals
                       INNER JOIN title_basics
                               ON title_basics.tconst = title_principals.tconst
                WHERE  title_principals.tconst IN ( #$movieIdListSql )
                       AND title_principals.tconst NOT IN ( #$movieIdExceptListSql )) AS SUBQUERY
        GROUP  BY actorid  
        """.query(text)
  }

}
