# IMDB Search Rest API

- [x] Search movie titles + cast crew
- [x] Search top-rated movies for a genre
- [x] Kevin Bacon Number

Purely Function Scala app to search movies by title, find top rated movies for a genre and get Bacon Number for a celebrity. Uses [Typelevel](https://typelevel.org/) scala fp ecosystem cats, cats-effect, http4s, circe, skunk and
scala 3 with Tagless-Final encoding.

## Setup

### Prerequisites

- [Docker](https://www.docker.com/get-started/) (with Docker Compose v2)
- [SBT](https://www.scala-sbt.org/download.html) (for building/running the app)
- (optional) [Make](https://www.gnu.org/software/make/) (for Makefile commands)
- Java 21+ JDK (e.g. [Adoptium Temurin](https://adoptium.net/))

### Getting Started

- `make db-init` (first-time Postgres + IMDb data loader)
- `make run` (starts the API server 8080 on localhost)
- `curl -s "http://127.0.0.1:8080/v1/kevinBaconNumber/Jon%20Hamm?maxPaths=2"` (to test the API)

## Development

### Postgres + IMDb Data Loader

This project uses a Postgres container(`arm64v8/postgres:18.1-alpine`.) plus a one-shot IMDb loader

### Start Postgres (normal dev)

- `docker compose up -d` starts **only Postgres** on port 5432 (fast; no loader runs).

Or using the Makefile:

- `make docker-up`

### First-time database initialization (run once)

Run the loader explicitly via the `init` profile:

- `docker compose --profile init up -d --build postgres-init`

Or:

- `make db-init`

The loader writes a sentinel file into the downloads volume, so future init runs will immediately exit.

### Incremental updates (on demand)

To refresh the dataset later, run the `update` profile:

- `docker compose --profile update up -d --build postgres-update`

Or:

- `make db-update`

The update job caches downloads and only imports when the downloaded `.gz` content changed.

### Persistence

- `docker compose down` stops containers but **keeps** the Postgres data + download cache.
- `docker compose down -v` deletes volumes (full reset).

Makefile equivalents:

- `make docker-down`
- `make docker-reset`

> Note: profiles require Docker Compose v2 (`docker compose ...`). If you only have the legacy `docker-compose` command, install/enable Compose v2.

### Common SBT commands

The repo includes a `Makefile` with common build targets:

- `make compile`
- `make test`
- `make fmt` / `make fmt-check`
- `make lint` / `make lint-fix`
- `make run` (starts the API in foreground)
- `make start` (start API with auto-restart on code changes, use `BG=1` for background)
- `make stop` (stop API via sbt-revolver)
- `make restart` (restart API with auto-restart on code changes, use `BG=1` for background)
- `make status` (check if API is running)
- `make github-workflow-generate` / `make github-workflow-check`

### Development with sbt-revolver

For fast development turnaround, use sbt-revolver which automatically loads configuration from `.env` and `.jvmopts`:

**Using Makefile (recommended):**

```bash
# Watch mode (default) - auto-restart on code changes with live logs
make start    # Start with auto-restart enabled (Ctrl-C to stop)
make restart  # Restart with auto-restart enabled
make stop     # Stop the application  
make status   # Check if running

# Background mode - app runs detached (use BG=1)
make start BG=1     # Start in background
make restart BG=1   # Restart in background
```

**Using sbt directly:**

```bash
# First, ensure sbt server is running (run once per session)
sbt

# Then in another terminal, use --client flag:
sbt --client "core/reStart"   # Start
sbt --client "core/reStop"    # Stop
sbt --client restart          # Restart (using alias)
sbt --client "core/reStatus"  # Check status
```

**Configuration files:**

- `.env` - Environment variables (SC_APP_ENV, SC_POSTGRES_PASSWORD, etc.)
- `.jvmopts` - JVM options (memory, GC settings, etc.)

Both files are automatically loaded when using `reStart`.

- Set environment variables like described below:

  set postgres db_password
  `export SC_POSTGRES_PASSWORD=postgres`

  to set app environment
  `export SC_APP_ENV=Test`

Build And Run

- Download [SBT](https://www.scala-sbt.org/download.html) to build the project
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

- /GET moviesByGenre top250 `/v1/ratings/drama` or (`/v1/ratings/drama?limit=2`)

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

- /GET `/v1/kevinBaconNumber/Max%20Schreck?maxPaths=2`

```json
{
  "baconNumber": 3,
  "paths": [
    {
      "baconNumber": 3,
      "path": [
        {
          "actorName": "Kevin Bacon",
          "movieTitle": "The 63rd Annual Golden Globe Awards"
        },
        {
          "actorName": "Candice Bergen",
          "movieTitle": "The 50th Annual Directors Guild of America Awards"
        },
        {
          "actorName": "David Carradine",
          "movieTitle": "Nosferatu: The First Vampire"
        }
      ]
    },
    {
      "baconNumber": 3,
      "path": [
        {
          "actorName": "Kevin Bacon",
          "movieTitle": "The Air Up There"
        },
        {
          "actorName": "Paul Michael Glaser",
          "movieTitle": "Jealousy"
        },
        {
          "actorName": "David Carradine",
          "movieTitle": "Nosferatu: The First Vampire"
        }
      ]
    }
  ]
}

```
