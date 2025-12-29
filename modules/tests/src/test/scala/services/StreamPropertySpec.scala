package services

import cats.effect.{ IO, Ref }
import cats.syntax.all.*
import domain.movie.Movie
import domain.rating.Rating
import fs2.Stream
import munit.{ CatsEffectSuite, ScalaCheckSuite }
import org.scalacheck.Prop.*
import org.scalacheck.effect.PropF
import org.scalacheck.{ Arbitrary, Gen }

/**
 * Property-based tests for streaming operations.
 *
 * Demonstrates:
 * - FS2 stream property testing
 * - Stream transformation laws
 * - Resource safety
 */
class StreamPropertySpec extends CatsEffectSuite with ScalaCheckSuite:

  // ========================================================================
  // Generators
  // ========================================================================

  val genMovieId: Gen[String] =
    for
      prefix <- Gen.const("tt")
      number <- Gen.choose(1000000, 9999999)
    yield s"$prefix$number"

  implicit val arbMovie: Arbitrary[Movie] = Arbitrary {
    for
      id            <- genMovieId
      titleType     <- Gen.option(Gen.oneOf("movie", "tvSeries"))
      primaryTitle  <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
      originalTitle <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
      adult         <- Gen.option(Gen.oneOf(true, false))
      yearReleased  <- Gen.option(Gen.choose(1888, 2025))
      yearEnded     <- Gen.option(Gen.choose(1888, 2025))
      runtime       <- Gen.option(Gen.choose(1, 600))
      genres        <- Gen.option(Gen.const("Action,Drama"))
    yield Movie(
      movieId = id,
      titleType = titleType,
      primaryTitle = primaryTitle,
      originalTitle = originalTitle,
      ratedAdult = adult,
      yearReleased = yearReleased,
      yearEnded = yearEnded,
      runtimeInMinutes = runtime,
      genres = genres
    )
  }

  implicit val arbRating: Arbitrary[Rating] = Arbitrary {
    for
      movie  <- arbMovie.arbitrary
      rating <- Gen.choose(0.0, 10.0)
      votes  <- Gen.choose(0, 1000000)
    yield Rating(movie = movie, averageRating = rating, numOfVotes = votes)
  }

  // ========================================================================
  // Stream Properties
  // ========================================================================

  test("Stream.emit produces single element list") {
    PropF.forAllF { (movie: Movie) =>
      Stream.emit[IO, Movie](movie).compile.toList.map(result => assertEquals(result, List(movie)))
    }
  }

  test("Stream append is associative") {
    PropF.forAllF { (m1: Movie, m2: Movie, m3: Movie) =>
      val s1 = Stream.emit[IO, Movie](m1)
      val s2 = Stream.emit[IO, Movie](m2)
      val s3 = Stream.emit[IO, Movie](m3)

      val left  = ((s1 ++ s2) ++ s3).compile.toList
      val right = (s1 ++ (s2 ++ s3)).compile.toList

      (left, right).mapN((l, r) => assertEquals(l, r))
    }
  }

  test("Stream map composition law") {
    PropF.forAllF { (movies: List[Movie]) =>
      val stream = Stream.emits[IO, Movie](movies)
      val f      = (m: Movie) => m.movieId
      val g      = (id: String) => id.length

      val left  = stream.map(f).map(g).compile.toList
      val right = stream.map(f andThen g).compile.toList

      (left, right).mapN((l, r) => assertEquals(l, r))
    }
  }

  test("Stream filter preserves list order") {
    PropF.forAllF { (ratings: List[Rating]) =>
      val stream    = Stream.emits[IO, Rating](ratings)
      val threshold = 7.0

      stream.filter(_.averageRating >= threshold).compile.toList.map { filtered =>
        val expected = ratings.filter(_.averageRating >= threshold)
        assertEquals(filtered, expected)
      }
    }
  }

  test("Stream take respects element limit") {
    PropF.forAllF { (movies: List[Movie]) =>
      val n      = if movies.isEmpty then 0 else Gen.choose(0, movies.length).sample.getOrElse(0)
      val stream = Stream.emits[IO, Movie](movies)

      stream.take(n.toLong).compile.toList.map { result =>
        assertEquals(result.length <= n, true)
        assertEquals(result, movies.take(n))
      }
    }
  }

  test("Stream bracket ensures resource cleanup") {
    PropF.forAllF { (movie: Movie) =>
      Ref[IO].of(false).flatMap { released =>
        val stream = Stream.bracket(IO.pure(movie))(_ => released.set(true))

        stream.compile.drain >> released.get.map(wasReleased => assertEquals(wasReleased, true))
      }
    }
  }

  object StreamLaw extends munit.Tag("StreamLaw")
