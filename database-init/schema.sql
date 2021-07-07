
-- ===========================================================================
-- name_basics - Contains the following information for names:
-- ===========================================================================
--     nconst (string) - alphanumeric unique identifier of the name/person
--     primaryName (string)– name by which the person is most often credited
--     birthYear – in YYYY format
--     deathYear – in YYYY format if applicable, else '\N'
--     primaryProfession (array of strings)– the top-3 professions of the person
--     knownForTitles (array of tconsts) – titles the person is known for
CREATE TABLE IF NOT EXISTS name_basics (
    nconst	            VARCHAR(10) CONSTRAINT PK_NAME_BASICS PRIMARY KEY,
    primaryName	        VARCHAR(110),
    birthYear           INTEGER,
    deathYear	        INTEGER,
    primaryProfession   VARCHAR(200),
    knownForTitles      VARCHAR(100)
);

-- ===========================================================================
-- title_basics - Contains the following information for titles
-- ===========================================================================
--   tconst (string) - alphanumeric unique identifier of the title
--   titleType (string) – the type/format of the title (e.g. movie, short, tvseries, tvepisode, video, etc)
--   primaryTitle (string) – the more popular title / the title used by the filmmakers on promotional materials at the point of release
--   originalTitle (string) - original title, in the original language
--   isAdult (boolean) - false: non-adult title; true: adult title
--   startYear (YYYY) – represents the release year of a title. In the case of TV Series, it is the series start year
--   endYear (YYYY) – TV Series end year.
--   runtimeMinutes – primary runtime of the title, in minutes
--   genres (string array) – includes up to three genres associated with the title
CREATE TABLE IF NOT EXISTS title_basics (
    tconst          VARCHAR(10) CONSTRAINT PK_TITLE_BASICS PRIMARY KEY,
    titleType       VARCHAR(20),
    primaryTitle    VARCHAR(500),
    originalTitle   VARCHAR(500),
    isAdult         BOOLEAN,
    startYear       INTEGER,
    endYear         INTEGER,
    runtimeMinutes  INTEGER,
    genres          VARCHAR(200)
);

-- ===========================================================================
-- title_ratings – Contains the IMDb rating and votes information for titles
-- ===========================================================================
--   tconst (string) - alphanumeric unique identifier of the title
--   averageRating – weighted average of all the individual user ratings
--   numVotes - number of votes the title has received
CREATE TABLE IF NOT EXISTS title_ratings (
    tconst          VARCHAR(10) CONSTRAINT PK_TITLE_RATINGS PRIMARY KEY,
    averageRating   DOUBLE PRECISION,
    numVotes        INTEGER,
    FOREIGN KEY (tconst) REFERENCES title_basics(tconst)
);

-- ===========================================================================
-- title_principals - Contains the principal cast/crew for titles
-- ===========================================================================
--   tconst (string) - alphanumeric unique identifier of the title
--   ordering (integer) – a number to uniquely identify rows for a given titleId
--   nconst (string) - alphanumeric unique identifier of the name/person
--   category (string) - the category of job that person was in
--   job (string) - the specific job title if applicable
--   characters (string) - the name of the character played if applicable
CREATE TABLE IF NOT EXISTS title_principals (
    tconst          VARCHAR(10),
    ordering        INTEGER,
    nconst          VARCHAR(10),
    category        VARCHAR(100),
    job             VARCHAR(300),
    characters      VARCHAR(500),
    PRIMARY KEY (tconst, ordering, nconst),
    FOREIGN KEY (tconst) REFERENCES title_basics(tconst),
    FOREIGN KEY (nconst) REFERENCES name_basics(nconst)
);

-- ===========================================================================
-- title_basics - Contains the director and writer information for all the titles in IMDb
-- ===========================================================================
--   tconst (string) - alphanumeric unique identifier of the title
--   directors (array of nconsts) - director(s) of the given title
--   writers (array of nconsts) – writer(s) of the given title
CREATE TABLE IF NOT EXISTS title_crew (
    tconst      VARCHAR(10) CONSTRAINT PK_TITLE_CREW PRIMARY KEY,
    directors   VARCHAR(500),
    writers     VARCHAR(500),
    FOREIGN KEY (tconst) REFERENCES title_basics(tconst)
);

