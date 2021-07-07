# Lunatech IMDb Assessment Instuctions

We’d like to invite you to do a code assessment as discussed during your
interview. Please read these instructions carefully to ensure that you implement
everything as expected. *Be sure to do your work on a separate branch and when
you are ready for you code to be reviewed, open a pull request to the main
branch*. This is important as it will signify to us that you've completed the
assessment. After completing the assessment, developers from Lunatech will
review your code and you'll be given the chance to "hand off" the application to
them. You'll give a demo of your app, explain your technical choices, and
discuss the application with them.

The code assessment is not a puzzle; it's not meant to be tricky or confusing.
If you have any questions do not hesitate to contact us, we'll be glad to answer
them.

## Guidelines

  - Approach this assessment as you were developing a real world project, we
    want to see how you conduct your engineering practice (project structure,
    code quality, readability, documentation and so on). GitHub Actions are
    enabled for this repo, so feel free to use it.
  - Please stick to the language of the position you applied for. For example if
      you've applied for the Scala Developer position, then we'll want to take a
      look at your Scala. The same goes for Java. At Lunatech we primarily work
      on the JVM, and you may end up working on various languages on the JVM,
      but the sake of this assessment, we'll stick to the one you applied for.
  - Apart from the language, the rest of the stack is up to you. Just make sure
      it makes sense given the domain you're working in.
  - The one week limit is to be able to give rapid feedback, you can have more
    time, just send us an email. It’s OK if you haven’t completed all the
    requirements, the exercise is meant to start a discussion. Remember:
    Quality over Quantity.
  - When you're complete, make sure all of your code is pushed up to this repo
    and let us know.
  - Try not to truncate the dataset, it’s possible to hold all the
    information in a single computer (It’s okay to use a Database).

## The Details
This assessment is based on the popular website [IMDb](https://www.imdb.com/)
which offers movie and TV show information. They have kindly made their dataset
publicly available at [IMDb Datasets](https://www.imdb.com/interfaces/). Your
mission, should you choose to accept it, is to write a web application that can
fulfil the following requirements:

### Requirement #1 (easy):

IMDb copycat: Present the user with endpoint for allowing them to search by
movie’s primary title or original title. The outcome should be related
information to that title, including cast and crew.

### Requirement #2 (easy):

Top rated movies: Given a query by the user, you must provide what are the top
rated movies for a genre (If the user searches horror, then it should show a
list of top rated horror movies).

### Requirement #3 (difficult):

[Six degrees of Kevin
Bacon](https://en.wikipedia.org/wiki/Six_Degrees_of_Kevin_Bacon): Given a query
by the user, you must provide what’s the degree of separation between the person
(e.g. actor or actress) the user has entered and Kevin Bacon. 

## Setup

To speed up the assessment development, we have provided a `docker-compose` file that
will run a [PostgreSQL](https://www.postgresql.org/) instance with all the data loaded,
which you can start with the following command: `docker-compose up`. You can connect to
this instance from your application with the following values:

```
JDBC URL = jdbc:postgresql://localhost:5432/lunatech_imdb
Username = postgres
Password = postgres
```

The schema is defined within the file `schema.cql` in the `database-init` folder. There
you'll find what each column represents on every table, this is taken from
[IMDb interfaces](https://www.imdb.com/interfaces/).

Running this Docker Compose will download the dataset and insert the values in the DB.
The process should take roughly 20 to 30 minutes. We've truncated the original dataset,
removing 'tvEpisodes', to make the whole process faster. If you want to take a look at
how that's done, see the file `data-cleanup.sh` (you'll need `wget` and `ammonite` to
run the script).

After the import process is done, you'll see a message with a summary of the inserted
data (small differences between the Input and Row counts are fine)

### Data import takes too long

If you experience a longer import time than 30 minutes, you can try to use a minimal
dataset. Edit the `docker-compose.yaml` file, and set the environment variable `MINIMAL_DATASET`
to `true` (Remember to remove all previous Docker containers and volumes).

### Troubleshooting

If you experience other issues, please be sure to check the [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md)
file.
