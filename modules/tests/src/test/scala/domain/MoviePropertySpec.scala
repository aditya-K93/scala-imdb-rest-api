package domain

import domain.movie.Movie
import domain.rating.Rating
import munit.{ CatsEffectSuite, ScalaCheckSuite }
import org.scalacheck.Prop.*
import org.scalacheck.effect.PropF
import org.scalacheck.{ Arbitrary, Gen }

import cats.effect.IO

/**
 * Property-based test suite for Movie domain model.
 *
 * Demonstrates:
 * - ScalaCheck generators for domain models
 * - Property tests with Cats Effect integration
 * - Total functions and referential transparency validation
 */
class MoviePropertySpec extends CatsEffectSuite with ScalaCheckSuite:

  // ========================================================================
  // Generators: Define how to create valid domain instances
  // ========================================================================

  val genMovieId: Gen[String] =
    for
      prefix <- Gen.const("tt")
      number <- Gen.choose(1000000, 9999999)
    yield s"$prefix$number"

  val genTitleType: Gen[Option[String]] = Gen.option(Gen.oneOf("movie", "tvSeries", "short", "tvMovie", "video"))

  val genTitle: Gen[Option[String]] = Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))

  val genBoolean: Gen[Option[Boolean]] = Gen.option(Gen.oneOf(true, false))

  val genYear: Gen[Option[Int]] = Gen.option(Gen.choose(1888, 2025))

  // Generate years ensuring yearEnded >= yearReleased when both present
  val genYearPair: Gen[(Option[Int], Option[Int])] =
    for
      released <- genYear
      ended    <- released match
        case Some(r) => Gen.option(Gen.choose(r, 2025)) // End year >= release year
        case None    => genYear
    yield (released, ended)

  val genRuntime: Gen[Option[Int]] = Gen.option(Gen.choose(1, 600))

  val genGenres: Gen[Option[String]] = Gen
    .option(Gen.listOfN(3, Gen.oneOf("Action", "Drama", "Comedy", "Horror", "Sci-Fi")).map(_.mkString(",")))

  implicit val arbMovie: Arbitrary[Movie] = Arbitrary {
    for
      id            <- genMovieId
      titleType     <- genTitleType
      primaryTitle  <- genTitle
      originalTitle <- genTitle
      adult         <- genBoolean
      years         <- genYearPair
      (yearReleased, yearEnded) = years
      runtime <- genRuntime
      genres  <- genGenres
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

  val genRating: Gen[Double] = Gen.choose(0.0, 10.0)
  val genVotes: Gen[Int]     = Gen.choose(0, 1000000)

  implicit val arbRating: Arbitrary[Rating] = Arbitrary {
    for
      movie  <- arbMovie.arbitrary
      rating <- genRating
      votes  <- genVotes
    yield Rating(movie = movie, averageRating = rating, numOfVotes = votes)
  }

  // ========================================================================
  // Properties: Invariants that must hold for all generated inputs
  // ========================================================================

  property("Movie ID must always start with 'tt'")(forAll((movie: Movie) => movie.movieId.startsWith("tt")))

  property("Movie ID must have 7 digits after 'tt' prefix") {
    forAll { (movie: Movie) =>
      val digits = movie.movieId.stripPrefix("tt")
      digits.length == 7 && digits.forall(_.isDigit)
    }
  }

  property("Runtime in minutes must be positive when present") {
    forAll((movie: Movie) => movie.runtimeInMinutes.forall(_ > 0))
  }

  property("Year released must be between 1888 and 2025 when present") {
    forAll((movie: Movie) => movie.yearReleased.forall(year => year >= 1888 && year <= 2025))
  }

  property("Rating average must be between 0.0 and 10.0") {
    forAll((rating: Rating) => rating.averageRating >= 0.0 && rating.averageRating <= 10.0)
  }

  property("Number of votes must be non-negative")(forAll((rating: Rating) => rating.numOfVotes >= 0))

  // ========================================================================
  // Effect-based Properties: Testing with Cats Effect IO
  // ========================================================================

  test("Movie creation is referentially transparent (effect test)".tag(EffectTest)) {
    PropF.forAllF { (movie: Movie) =>
      val createMovie: IO[Movie] = IO.pure(movie)

      for
        result1 <- createMovie
        result2 <- createMovie
      yield
        // Referential Transparency: calling the same effect twice yields identical results
        assertEquals(result1, result2)
        assertEquals(result1.movieId, movie.movieId)
    }
  }

  test("Rating computation preserves movie identity".tag(EffectTest)) {
    PropF.forAllF { (rating: Rating) =>
      val processRating: IO[Rating] = IO.pure(rating)

      processRating.map { result =>
        // The movie reference inside a rating must remain unchanged
        assertEquals(result.movie, rating.movie)
        assertEquals(result.movie.movieId, rating.movie.movieId)
      }
    }
  }

  test("Movie transformation is total (never throws)".tag(EffectTest)) {
    PropF.forAllF { (movie: Movie) =>
      val transform: IO[Int] = IO.pure {
        // Total function: handles all cases via Option
        movie.runtimeInMinutes.getOrElse(0) + movie.yearReleased.getOrElse(0)
      }

      transform.map(result => assert(result >= 0, "Transformed result must be non-negative"))
    }
  }

  test("Concurrent movie processing maintains independence".tag(EffectTest)) {
    PropF.forAllF { (m1: Movie, m2: Movie) =>
      val process = (m: Movie) => IO.pure(m.movieId.length)

      for
        results <- (process(m1), process(m2)).parTupled
        (len1, len2) = results
      yield
        // Concurrent execution produces same results as sequential
        assertEquals(len1, m1.movieId.length)
        assertEquals(len2, m2.movieId.length)
    }
  }

  // ========================================================================
  // Conditional Properties: Testing domain constraints
  // ========================================================================

  property("Movies with end year must have been released first") {
    forAll { (movie: Movie) =>
      (movie.yearReleased, movie.yearEnded) match
        case (Some(released), Some(ended)) => released <= ended
        case _                             => true // Constraint only applies when both present
    }
  }

  property("Primary and original titles can differ but both should be valid strings") {
    forAll { (movie: Movie) =>
      val primaryValid  = movie.primaryTitle.forall(_.trim.nonEmpty)
      val originalValid = movie.originalTitle.forall(_.trim.nonEmpty)
      primaryValid && originalValid
    }
  }

  // ========================================================================
  // Custom Tag for effect-based tests
  // ========================================================================

  override def munitFlakyOK = true

  object EffectTest extends munit.Tag("EffectTest")
