# Troubleshooting
The assessment template should work out of the box, however sometimes something goes wrong, we didn't foresee a particular setup, or we made a mistake.

*Before checking this guide, if you edited any of the files that you got from this template, please rollback the changes.*

## Data import
#### Error `/usr/bin/env: 'bash\r': No such file or directory`
**Solutions**: You're probably using Windows and Cygwin (or a similar terminal), try the following:
    1. Try using [Docker Desktop](https://www.docker.com/products/docker-desktop) instead of the terminal to start `docker-compose`. If this doesn't work,
    2. Check [Manual Import](##-Manual-Import)

## Connecting to the database
#### Database not found
When trying to connect to the database, I get `Database not found`, assuming that the data import procedure worked properly (ie. you saw a 'DB ready to use message' with no errors), please check the following:
1. There is no other process using the port `5432`, even another PostgreSQL instance. Alternatively, you can change the port our Postgres is bridging to.
2. If nothing of this works, Check [Manual Import](##-Manual-Import)

## Manual Import
In case nothing works when performing the data import, we can try to issue the commands manually:
1. Go to the assessment folder, and start **only** postgres.
2. Obtain the Container ID of the Postgres instance.
3. Start a bash session using that Container Id.
4. Connect to Postgres using `psql`, using `postgres` as username, and `lunatech_imdb` as database parameters.
5. Create the schema using the contents of the `schema.sql`.
6. Exit `psql`, and then install WGet by executing the command:
```
apt-get -y update && \
  apt-get -y install wget && \
  export MINIMAL_DATASET=true \
         S3_URL="https://lunatechassessments.s3-eu-west-1.amazonaws.com/imdb-docker" \
         FILES=('name.basics.tsv' 'title.basics.tsv' 'title.principals.tsv' 'title.ratings.tsv' 'title.crew.tsv')
```

7. Download the TSV files and import them in the database:
```
for file in "${FILES[@]}"; do
   wget -O "${file}.gz" "${S3_URL}/${file}.gz.minimal" && gzip -d "${file}.gz"
done

psql -U postgres -d lunatech_imdb -c "\copy name_basics(nconst,primaryname,birthyear,deathyear,primaryprofession,knownfortitles) FROM 'name.basics.tsv' DELIMITER E'\t' NULL '\N' CSV HEADER"

psql -U postgres -d lunatech_imdb -c "\copy title_basics(tconst, titleType,primaryTitle,originalTitle,isAdult,startYear,endYear,runtimeMinutes,genres) FROM 'title.basics.tsv' DELIMITER E'\t' NULL '\N' CSV HEADER"

psql -U postgres -d lunatech_imdb -c "\copy title_ratings(tconst, averageRating, numVotes) FROM 'title.ratings.tsv' DELIMITER E'\t' NULL '\N' CSV HEADER"

psql -U postgres -d lunatech_imdb -c "\copy title_crew(tconst, directors, writers) FROM 'title.crew.tsv' DELIMITER E'\t' NULL '\N' CSV HEADER"

psql -U postgres -d lunatech_imdb -c "\copy title_principals(tconst, ordering, nconst, category, job, characters) FROM 'title.principals.tsv' DELIMITER E'\t' NULL '\N' CSV HEADER"
```
8. After completing all these steps, you should have a populated database.
