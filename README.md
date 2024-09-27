# IMDB Search Rest API

- [x] Search movie titles + cast crew
- [x] Search top-rated movies for a genre
- [ ] Kevin Bacon Number (works up-to degree 3, times out after)

Purely Function Scala app to search movies by title, find top rated movies for a genre and get Bacon Number for an
actor/actress. Uses [Typelevel](https://typelevel.org/) scala fp ecosystem cats, cats-effect, http4s, circe, skunk and
scala 2.13 with Tagless-Final encoding.

Setup

- `docker-compose up` To start container to download imdb data and start postgres container on port 5432


- Set environment variables like described below:

  set postgres db_password
  `export SC_POSTGRES_PASSWORD=postgres`

  to set app environment
  `export SC_APP_ENV=Test`

Build And Run

- Download [SBT ](https://www.scala-sbt.org/download.html) to build the project
- `sbt run` to start the server on port 8080

```
[info] running Main 
01:50:30.818 [io-compute-3] INFO  Main - Loaded config AppConfig(PostgreSQLConfig(localhost,5432,postgres,Secret(afc848c),imdb,10),HttpServerConfig(0.0.0.0,8080))
01:50:31.346 [io-compute-11] INFO  Main - Connected to Postgres PostgreSQL 13.3 (Debian 13.3-1.pgdg100+1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 8.3.0-6) 8.3.0, 64-bit
01:50:31.927 [io-compute-2] INFO  o.h.ember.server.EmberServerBuilder - Ember-Server service bound to address: /0:0:0:0:0:0:0:0:8080
01:50:31.929 [io-compute-2] INFO  Main - 
  _   _   _        _ _
 | |_| |_| |_ _ __| | | ___
 | ' \  _|  _| '_ \_  _(_-<
 |_||_\__|\__| .__/ |_|/__/
             |_|
HTTP Server started at /0:0:0:0:0:0:0:0:8080

```

TroubleShooting

- Make sure there are no error during builds
- Environment variable `SC_POSTGRES_PASSWORD` and `SC_APP_ENV` is set as described above
- App tests postgres connection on start so look out for error logs in case of connection failure

Routes

- /GET movieByTitle `/v1/movies/The%20Dark%20Knight%20Rises`

```json
{
  "movie": {
    "movieId": "tt1345836",
    "titleType": "movie",
    "primaryTitle": "The Dark Knight Rises",
    "originalTitle": "The Dark Knight Rises",
    "ratedAdult": false,
    "yearReleased": 2012,
    "yearEnded": null,
    "runtimeInMinutes": 164,
    "genres": "Action,Adventure"
  },
  "castAndCrew": [
    {
      "role": "actor",
      "people": [
        "Christian Bale",
        "Tom Hardy",
        "Gary Oldman"
      ]
    },
    {
      "role": "actress",
      "people": [
        "Anne Hathaway"
      ]
    },
    {
      "role": "director",
      "people": [
        "Christopher Nolan"
      ]
    },
    {
      "role": "producer",
      "people": [
        "Emma Thomas",
        "Charles Roven"
      ]
    },
    {
      "role": "writer",
      "people": [
        "Bob Kane",
        "Jonathan Nolan",
        "David S. Goyer"
      ]
    }
  ]
}
```

- /GET moviesByGenre top250 `/v1/ratings/drama`

```json
[
  {
    "movie": {
      "movieId": "tt0111161",
      "titleType": "movie",
      "primaryTitle": "The Shawshank Redemption",
      "originalTitle": "The Shawshank Redemption",
      "ratedAdult": false,
      "yearReleased": 1994,
      "yearEnded": null,
      "runtimeInMinutes": 142,
      "genres": "Drama"
    },
    "averageRating": 9.3,
    "numOfVotes": 2328874
  },
  {
    "movie": {
      "movieId": "tt0137523",
      "titleType": "movie",
      "primaryTitle": "Fight Club",
      "originalTitle": "Fight Club",
      "ratedAdult": false,
      "yearReleased": 1999,
      "yearEnded": null,
      "runtimeInMinutes": 139,
      "genres": "Drama"
    },
    "averageRating": 8.8,
    "numOfVotes": 1844544
  }
]
```

- /GET moviesByGenre limit `/v1/ratings/drama?limit=1`


- /GET `v1/kevinBaconNumber/Brad%20Pitt`

```json
{
  "baconNumber": 1
}
```
