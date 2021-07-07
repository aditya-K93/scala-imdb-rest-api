#!/usr/local/bin/amm

import $ivy.`com.nrinaudo::kantan.csv:0.6.1`
import $ivy.`com.nrinaudo::kantan.csv-generic:0.6.1`

import kantan.csv._
import kantan.csv.generic._
import kantan.csv.ops._
import scala.io.Source
import java.io.File

case class TitleCrew(tconst: String, directors: String, writers: String)
case class TitlePrincipal(tconst: String, ordering: Int, nconst: String, category: String, job: String, characters: String)
case class TitleRating(tconst: String, averageRating: String, numVotes: String)
case class Name(nconst: String, primaryName: String, birthYear: String, deathYear: String, primaryProfession: String, knownForTitles: String)

lazy val names: Set[String] = {
    println("  Getting names")
    Source.fromFile(new File("just_names.fk")).getLines.toSet
}

lazy val titles: Set[String] = {
    println("  Getting titles")
    Source.fromFile(new File("just_titles.fk")).getLines.toSet
}

lazy val principals: Set[String] = {
    println("  1. Getting principals")
    Source.fromFile(new File("just_principals.fk")).getLines.toSet
}

def removeMissing[T: HeaderDecoder: HeaderEncoder](input: File, output: File, header: Seq[String])(predicate: T => Boolean) = {
    val reader = input.asCsvReader[T](rfc.withHeader.withCellSeparator('\t'))
    val cleaned = reader.filterResult(predicate).toIterator.collect {
        case Right(value) => value
    }

    println("  Writting cleaned file")
    output.writeCsv[T](cleaned, rfc.withHeader(header: _*).withCellSeparator('\t'))
    println(s"  Done cleaning [${input.getName}]")
}

@main
def removeCrew() = {
    val crew   = new File("title.crew.tsv")
    val output = new File("title.crew.cleaned")
    val header = Seq("tconst", "directors", "writers")
    
    removeMissing[TitleCrew](crew, output, header) { crew =>
        titles.contains(crew.tconst)
    }
}

@main
def removePrincipals() = {
    val principals = new File("title.principals.tsv")
    val output     = new File("title.principals.cleaned")
    val header     = Seq("tconst", "ordering", "nconst", "category", "job", "characters")
    
    removeMissing[TitlePrincipal](principals, output, header) { principal =>
        names.contains(principal.nconst) && titles.contains(principal.tconst)
    }
}

@main
def removeRatings() = {
    val ratings = new File("title.ratings.tsv")
    val output  = new File("title.ratings.cleaned")
    val header  = Seq("tconst", "averageRating", "numVotes")

    removeMissing[TitleRating](ratings, output, header) { rating =>
        titles.contains(rating.tconst)
    }
}

@main
def unnecessaryNames() = {
    val names  = new File("name.basics.tsv")
    val output = new File("name.basics.cleaned")
    val header = Seq("nconst", "primaryName", "birthYear", "deathYear", "primaryProfession", "knownForTitles")

    removeMissing[Name](names, output, header) { name =>
        principals.contains(name.nconst)
    }
} 
