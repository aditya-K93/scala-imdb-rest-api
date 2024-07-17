//import cats.effect.{ Concurrent, Resource, _ }
//import domain.movieDetail.MovieDetail
////import io.circe.{ Json, JsonObject }
//import org.http4s.EntityDecoder
//import services.{ CastAndCrew, KevinBaconDetails, Movies, Ratings }
////import io.circe.Encoder, io.circe.Decoder
//import skunk._
//import skunk.implicits._
//import skunk.codec.all._
//import natchez.Trace.Implicits.noop
//import io.circe.generic.auto._, io.circe.syntax._
//import org.http4s.circe._
//
//import services.Movies._
//object Main extends IOApp {
//
//  val session: Resource[IO, Session[IO]] =
//    Session.single( // (2)
//      host = "localhost",
//      port = 5432,
//      user = "postgres",
//      database = "imdb",
//      password = Some("postgres")
//    )
//
//  case class Crew(role: Option[String], people: List[String])
//
//  case class Movie(
//      movieId: String,
//      titleType: String,
//      primaryTitle: String,
//      OriginalTitle: String,
//      ratedAdult: Boolean,
//      yearReleased: Option[Int],
//      yearEnded: Option[Int],
//      runtimeInMinutes: Option[Int],
//      genres: String
//  )
//
////  )
//
//  val movie: skunk.Decoder[Movie] =
//    (varchar(10) ~ varchar(20) ~ varchar(500) ~ varchar(500) ~ bool ~ int4.opt ~ int4.opt ~ int4.opt ~ varchar(200))
//      .gmap[Movie]
//
//  val getMovieByTitleId: Query[String ~ String, Movie] =
//    sql"""
//          SELECT *  FROM public.title_basics
//          WHERE (title_basics.primarytitle ILIKE $varchar OR  title_basics.originaltitle ILIKE $varchar) AND titletype = 'movie'
//          """
//      .query(movie)
//
//  def doMovie(s: Session[IO]): IO[List[Movie]] =
//    s.prepare(getMovieByTitleId).use { ps =>
//      ps.stream(("%Dark Knight", "%Dark Knight"), 32).compile.toList
//    }
//
//  val getCrewByTitleId: Query[String, Crew] =
//    sql"""
//          SELECT tp.category,
//                 String_agg(nb.primaryname, ',') AS actors
//          FROM   name_basics AS nb
//                 JOIN title_principals AS tp
//                   ON nb.nconst = tp.nconst
//          WHERE  tp.tconst = $varchar
//          GROUP  BY tp.category
//       """
//      .query(varchar(100) ~ text)
//      .map { case n ~ p => Crew(Option(n), p.toString.split(",").toList) }
//
//  def doExtended(s: Session[IO]): IO[List[Crew]] =
//    s.prepare(getCrewByTitleId).use { ps =>
//      ps.stream("tt0468569", 32).compile.toList
//    }
//
//  def run(args: List[String]): IO[ExitCode] =
//    session.use { s => // (3)
//      for {
//        d  <- s.unique(sql"select current_date".query(date)) // (4)
//        z  <- doExtended(s)
//        zz <- doMovie(s)
////        p  <- IO.pure(z.headOption.fold(List.empty[String])(_.members))
////        m  <- IO.pure(zz.headOption.fold({}.asJson)(_.asJson))
//
//        crew  <- IO.pure(z.asJson)
//        movie <- IO.pure(zz.asJson)
//        mm <- Movies
//          .make[IO](session)
//          .findByTitle("The Dark Knight", "The Dark Knight")
//
//        xxx <- Ratings
//          .make[IO](session)
//          .findByGenre("drama")(1)
//
//        xxxy <- IO.pure(xxx.asJson)
//
//        ll <- CastAndCrew
//          .make[IO](session)
//          .findByTitleId(mm.fold("")(_.movieId))
//        llm <- IO.pure(ll.asJson)
//
//        mmm <- IO.pure(mm.asJson)
//        pp <- Movies
//          .make[IO](session)
//          .findByTitleId("tt0468569")
//        mmn <- IO.pure(pp.asJson)
//
//        actorID <- KevinBaconDetails
//          .make[IO](session)
//          .getActorIdByName("Matt Damon")
//        baconMovies <- KevinBaconDetails
//          .make[IO](session)
//          .getAllMoviesByActor("nm0000102")
//        allActors <- KevinBaconDetails
//          .make[IO](session)
//          .getAllActorsByMoviesExcept(
//            baconMovies.fold(List.empty[String])(_.movieIdList),
//            baconMovies.fold(List.empty[String])(x => List(x.actorId))
//          )
//        bn <- KevinBaconDetails.make[IO](session).degreesOfSeparationKevinBacon("Matt Damon")
////
////        actorAll <- KevinBaconDetails
////          .make[IO](session)
////          .getAllActorsByMoviesExcept(List("tt11295262", "tt1735313"), List("tt11295262"))
////
////        actorAll <- KevinBaconDetails
////          .make[IO](session)
////          .degreesOfSeparationKevinBacon("Brad Pitt")
//
////        yolopure   <- IO.pure(act.asJson)
////        movAllpure <- IO.pure(movAll.asJson)
//
//        details <- IO.pure(MovieDetail(mm, ll).asJson)
////        _       <- IO.println(s"The current date is $d.")
////        _       <- IO.println(s"The Crew result is a $z")
////        _       <- IO.println(s"The Movie re sult is a $zz")
////        _       <- IO.println(s" Crew $crew")
////        _       <- IO.println(s" Movie $mmn")
////        _       <- IO.println(s" Movie $mmm")
////        _       <- IO.println(s" rating $xxxy")
////        _       <- IO.println(s"  actorid $yolopure")
////        _ <- IO.println(s" allMovies $movAll" )
//        _ <- IO.println(s" given actor $actorID")
////        _             <- IO.println(s" bacon movies $baconMovies")
////        _ <- IO.println(s" all actors $allActors")
//        _ <- IO.println(s" bacon no $bn ")
////        isDegreeFound <- if (allActors.getOrElse(List.empty).contains("nm0000093")) IO.pure(1) else IO.pure(0)
////        _             <- IO.println(s" bacon no $isDegreeFound ")
////        actlst        <- IO.pure(allActors)
////        _             <- IO.println(s" list $actlst ")
//      } yield ExitCode.Success
//    }
//
//}
