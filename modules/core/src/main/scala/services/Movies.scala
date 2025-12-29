package services

import cats.*
import cats.effect.{ Concurrent, Resource }
import cats.syntax.all.*
import domain.movie.*
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

trait Movies[F[_]]:
  def findByTitleId(titleId: String): F[Option[Movie]]
  def findByTitle(primaryTitleName: String, OriginalTitleName: String): F[Option[Movie]]

object Movies:

  def make[F[_]: Concurrent](postgres: Resource[F, Session[F]]): Movies[F] = new Movies[F]:
    import MovieSQL.*

    def findByTitle(primaryTitleName: String, OriginalTitleName: String): F[Option[Movie]] = postgres
      .use(session => session.prepare(SelectByTitle).flatMap(ps => ps.option((primaryTitleName, OriginalTitleName))))

    def findByTitleId(titleId: String): F[Option[Movie]] = postgres
      .use(session => session.prepare(selectById).flatMap(ps => ps.option(titleId)))

private object MovieSQL:

  val decoder: skunk.Decoder[Movie] =
    (varchar(10) *: varchar(20).opt *: varchar(500).opt *: varchar(500).opt *: bool.opt *: int4.opt *: int4.opt *:
      int4.opt *: varchar(200).opt).to[Movie]

  val SelectByTitle: Query[String ~ String, Movie] = sql"""
          SELECT title_basics.tconst,titletype, primarytitle, originaltitle, isadult, startyear, endyear, runtimeminutes, genres
          FROM   PUBLIC.title_basics
          WHERE  (
                        title_basics.primarytitle ILIKE $varchar
                 OR     title_basics.originaltitle ILIKE $varchar)
          AND    titletype = 'movie'
          LIMIT 1
       """.query(decoder)

  val selectById: Query[String, Movie] = sql"""
          SELECT *
          FROM   PUBLIC.title_basics
          WHERE  tconst = $varchar  
       """.query(decoder)
