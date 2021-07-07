package services

import cats.effect.{ Concurrent, Resource }
import domain.castCrew._
import skunk._
import skunk.codec.all._
import skunk.implicits._

trait CastAndCrew[F[_]] {
  def findByTitleId(titleId: String): F[List[Crew]]
}

object CastAndCrew {
  def make[F[_]: Concurrent](postgres: Resource[F, Session[F]]): CastAndCrew[F] =
    new CastAndCrew[F] {
      import CastCrewSQL._

      def findByTitleId(titleId: String): F[List[Crew]] =
        postgres.use { session =>
          session
            .prepare(selectById)
            .use { ps =>
              ps.stream(titleId, chunkSize = 1024).compile.toList
            }
        }

    }

}

private object CastCrewSQL {

  val decoder: skunk.Decoder[Crew] =
    (varchar(100) ~ text)
      .map { case n ~ p => Crew(Option(n), p.toString.split(",").toList) }

  val selectById: Query[String, Crew] =
    sql"""
          SELECT tp.category,
                 String_agg(nb.primaryname, ',') AS actors
          FROM   name_basics AS nb
                 JOIN title_principals AS tp
                   ON nb.nconst = tp.nconst
          WHERE  tp.tconst = $varchar
          GROUP  BY tp.category            
       """.query(decoder)

}
