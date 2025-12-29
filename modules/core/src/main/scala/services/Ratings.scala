package services

import cats.*
import cats.effect.{ Concurrent, Resource }
import cats.syntax.all.*
import domain.movie.Movie
import domain.rating.*
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

trait Ratings[F[_]]:
  def findByGenre(genreName: String)(limit: Int = 250): F[List[Rating]]

object Ratings:

  def make[F[_]: Concurrent](postgres: Resource[F, Session[F]]): Ratings[F] = new Ratings[F]:
    import RatingSQL.*

    def findByGenre(genreName: String)(limit: Int): F[List[Rating]] = postgres.use { session =>
      session.prepare(selectById)
        .flatMap(ps => ps.stream((genreName.replaceAll("\\s", "") ++ "%", limit), chunkSize = 1024).compile.toList)
    }

private object RatingSQL:

  val decoder: skunk.Decoder[Rating] =
    (varchar(10) ~ varchar(20).opt ~ varchar(500).opt ~ varchar(500).opt ~ bool.opt ~ int4.opt ~ int4.opt ~ int4.opt ~
      varchar(200).opt ~ float8 ~ int4).map { case tc ~ tt ~ pt ~ ot ~ ra ~ yr ~ ye ~ rm ~ gr ~ ar ~ nv =>
      Rating(Movie(tc, tt, pt, ot, ra, yr, ye, rm, gr), ar, nv)
    }

  val selectById: Query[String ~ Int, Rating] =
    //   numvotes >25000 to match imdb protocol i.e min 25k votes for rating to appear in top 250 lists
    //  LIMIT to 250 can also add query param ?limit250
    sql"""
           SELECT    title_ratings.tconst,
                     titletype,
                     primarytitle,
                     originaltitle,
                     isadult,
                     startyear,
                     endyear,
                     runtimeminutes,
                     genres,
                     averagerating,
                     numvotes
          FROM       title_basics
          INNER JOIN title_ratings
          ON         title_ratings.tconst = title_basics.tconst 
          WHERE      title_basics.genres ILIKE  $varchar
          AND        numvotes >25000 
          AND        titletype = 'movie'
          ORDER BY   averagerating DESC
           LIMIT $int4
       """.query(decoder)
