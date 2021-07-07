package services

import cats.effect.{ Concurrent, Resource }
import domain.movie.Movie
import domain.rating._
import skunk._
import skunk.codec.all._
import skunk.implicits._

trait Ratings[F[_]] {
  def findByGenre(genreName: String)(limit: Int = 250): F[List[Rating]]
}

object Ratings {
  def make[F[_]: Concurrent](postgres: Resource[F, Session[F]]): Ratings[F] =
    new Ratings[F] {
      import RatingSQL._

      def findByGenre(genreName: String)(limit: Int): F[List[Rating]] =
        postgres.use { session =>
          session
            .prepare(selectById)
            .use { ps =>
              ps.stream((genreName, limit), chunkSize = 1024).compile.toList
            }
        }

    }

}

private object RatingSQL {

  val decoder: skunk.Decoder[Rating] =
    (varchar(10) ~ varchar(20).opt ~ varchar(500).opt ~ varchar(500).opt ~ bool.opt ~ int4.opt ~
      int4.opt ~ int4.opt ~ varchar(200).opt ~ float8 ~ int4)
      .map {
        case tc ~ tt ~ pt ~ ot ~ ra ~ yr ~ ye ~ rm ~ gr ~ ar ~ nv =>
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
          WHERE      title_basics.genres ILIKE $varchar
          AND        numvotes >25000 
          AND        titletype = 'movie'
          ORDER BY   averagerating DESC
           LIMIT $int4
       """.query(decoder)

}
