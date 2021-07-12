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
  def getAllMoviesByActorsExcept(actorIdList: List[String], exceptActorIdList: List[String]): F[Option[List[String]]]
  def getAllActorsByMoviesExcept(movieIdList: List[String], exceptMovieIdList: List[String]): F[Option[List[String]]]
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

      def getAllMoviesByActorsExcept(
          actorIdListSql: List[String],
          exceptMovieListSql: List[String]
      ): F[Option[List[String]]] = {
        // skunk doesn't allow inserting params in  queries like where foo in IN ('a' ,'b') without defining length first
        // s"""nm0000102""", s"""nm0000102""" so string interpolation workaround

        val query = selectMoviesExcept(
          actorIdListSql.map(x => s"""'$x'""").mkString(","),
          exceptMovieListSql.map(x => s"""'$x'""").mkString(",")
        )
        postgres.use { session =>
          session
            .prepare(query)
            .use { ps =>
              ps.option(Void)
            }
        }
      }

      def getAllActorsByMoviesExcept(
          movieIdList: List[String],
          exceptActorIdList: List[String]
      ): F[Option[List[String]]] = {
        val query = selectActorsExcept(
          movieIdList.map(x => s"""'$x'""").mkString(","),
          exceptActorIdList.map(x => s"""'$x'""").mkString(",")
        )
        postgres.use { session =>
          session
            .prepare(query)
            .use { ps =>
              ps.option(Void)
            }
        }
      }

      def degreesOfSeparationKevinBacon(actorName: String): F[BaconNumber] = {
        for {
          actorId     <- getActorIdByName(actorName)
          searchActor <- getAllMoviesByActor(actorId.getOrElse(""))
          all_actors <- getAllActorsByMoviesExcept(
            searchActor.fold(List(""))(_.movieIdList),
            searchActor.fold(List(""))(x => List(x.actorId))
          )
          kevinBacon <- getAllMoviesByActor("nm0000102") // man bacon himself
          degreeSeparation <- if (searchActor.isEmpty) Monad[F].pure(BaconNumber(-1))
          else if (searchActor.fold("")(x => x.actorId) == kevinBacon.fold("")(x => x.actorId))
            Monad[F].pure(BaconNumber(0))
          else if (all_actors.getOrElse(List.empty).contains(kevinBacon.fold("")(x => x.actorId)))
            Monad[F].pure(BaconNumber(1))
          else
            actedWithDegreeMoreThanOne(
              all_actors.getOrElse(List("")),
              searchActor.fold(List(""))(_.movieIdList),
              all_actors.getOrElse(List("")),
              kevinBacon.fold("")(x => x.actorId),
              2
            )

        } yield degreeSeparation

      }

      def actedWithDegreeMoreThanOne(
          second_degree_or_higher_actors: List[String],
          filter_movies: List[String],
          filterActors: List[String],
          baconActorId: String,
          count: Int
      ): F[BaconNumber] = {
        for {
          movies <- getAllMoviesByActorsExcept(second_degree_or_higher_actors, filter_movies)
          actors <- getAllActorsByMoviesExcept(movies.getOrElse(List("")), filterActors)
          didActTogether <- if (actors.getOrElse(List.empty).contains(baconActorId)) Monad[F].pure(BaconNumber(count))
          else if (count > 6) Monad[F].pure(BaconNumber(-1))
          else
            actedWithDegreeMoreThanOne(
              actors.getOrElse(List("")),
              filter_movies ++ movies.getOrElse(List("")),
              filterActors ++ actors.getOrElse(List("")),
              baconActorId,
              count + 1
            )

        } yield didActTogether
      }
    }

  }
}

private object ActorMovieSQL {
  val decoder: skunk.Decoder[Actor] =
    (varchar(110) ~ varchar(10) ~ text)
      .map { case an ~ ai ~ mi => Actor(an, ai, mi.toString.split(",").toList) }

  val MovieListDecoder: skunk.Decoder[List[String]] = {
    (varchar(10)).map(m => m.toString.split(",").toList)
  }

  val textDecoder: skunk.Decoder[List[String]] = {
    (text.opt).map(m => m.toString.split(",").toList)
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
                 AND title_basics.titletype =   'movie'   
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

  def selectActorsExcept(movieIdListSql: String, exceptActorListSql: String): Query[Void, List[String]] = {
    sql"""
         SELECT DISTINCT String_agg(actorid, ',')
                FROM   (SELECT title_principals.nconst AS actorId
                    FROM   title_principals
                    WHERE  title_principals.tconst IN ( #$movieIdListSql )
                         AND title_principals.nconst NOT IN ( #$exceptActorListSql  ) 
                         AND  category = 'actor' ) AS SUBQUERY	 
        """.query(textDecoder)
  }

  def selectMoviesExcept(actorIdListSql: String, exceptMovieListSql: String): Query[Void, List[String]] = {
    sql"""
          SELECT String_agg(movieid, ',')
          FROM   (SELECT  DISTINCT title_principals.tconst AS movieId
            FROM   title_principals
            WHERE  title_principals.nconst IN  ( #$actorIdListSql )
             AND title_principals.tconst NOT IN  ( #$exceptMovieListSql ) 
             AND  category = 'actor'  ) AS SUBQUERY
          """.query(textDecoder)
  }

}
