#!/usr/bin/env bash

Bold='\033[1m'   # Bold
NC='\033[0m'     # No Color

S3_URL="https://lunatechassessments.s3-eu-west-1.amazonaws.com/imdb-docker"

# To change this banner, go to http://patorjk.com/software/taag/#p=display&f=Big&t=Lunatech%0AIMDb%0AAssessment
cat << "EOF"
  _                       _            _                  
 | |                     | |          | |                 
 | |    _   _ _ __   __ _| |_ ___  ___| |__               
 | |   | | | | '_ \ / _` | __/ _ \/ __| '_ \              
 | |___| |_| | | | | (_| | ||  __/ (__| | | |             
 |______\__,_|_|_|_|\__,_|\__\___|\___|_| |_|             
 |_   _|  \/  |  __ \| |                                  
   | | | \  / | |  | | |__                                
   | | | |\/| | |  | | '_ \                               
  _| |_| |  | | |__| | |_) |                          _   
 |___/\|_|  |_|_____/|_.__/                          | |  
    /  \   ___ ___  ___  ___ ___ _ __ ___   ___ _ __ | |_ 
   / /\ \ / __/ __|/ _ \/ __/ __| '_ ` _ \ / _ \ '_ \| __|
  / ____ \\__ \__ \  __/\__ \__ \ | | | | |  __/ | | | |_ 
 /_/    \_\___/___/\___||___/___/_| |_| |_|\___|_| |_|\__|


    This process will take from 20 to 30 minutes

EOF

echo -e "${Bold}Updating Apt-Get and installing WGet...${NC}"
apt-get -y -qq update && apt-get -y -qq install wget

echo -e "${Bold}Downloading TSV Dataset...${NC}"
FILES=('name.basics.tsv' 'title.basics.tsv' 'title.principals.tsv' 'title.ratings.tsv' 'title.crew.tsv')
for file in "${FILES[@]}"; do
    echo -e "  ${file}"
    WGET_ARGS="-q -O ${file}.gz ${S3_URL}/${file}.gz"
    if [ "$MINIMAL_DATASET" = true ] ; then
        WGET_ARGS="${WGET_ARGS}.minimal"
    fi
    echo $WGET_ARGS | xargs wget
done

echo -e "${Bold}Decompressing dataset...${NC}"
for file in "${FILES[@]}"; do
    gzip -d "${file}.gz"
done

echo -e "${Bold}Initializing Schema...${NC}"
psql --host=postgres --username=postgres -d lunatech_imdb -f ./schema.sql

echo -e "${Bold}Loading Name Basics...${NC}"
psql --host=postgres --username=postgres -d lunatech_imdb -c "\copy name_basics(nconst,primaryname,birthyear,deathyear,primaryprofession,knownfortitles) FROM 'name.basics.tsv' DELIMITER E'\t' NULL '\N' CSV HEADER"

echo -e "${Bold}Loading Title Basics...${NC}"
psql --host=postgres --username=postgres -d lunatech_imdb -c "\copy title_basics(tconst, titleType,primaryTitle,originalTitle,isAdult,startYear,endYear,runtimeMinutes,genres) FROM 'title.basics.tsv' DELIMITER E'\t' NULL '\N' CSV HEADER"

echo -e "${Bold}Loading Title Ratings...${NC}"
psql --host=postgres --username=postgres -d lunatech_imdb -c "\copy title_ratings(tconst, averageRating, numVotes) FROM 'title.ratings.tsv' DELIMITER E'\t' NULL '\N' CSV HEADER"

echo -e "${Bold}Loading Title Crew...${NC}"
psql --host=postgres --username=postgres -d lunatech_imdb -c "\copy title_crew(tconst, directors, writers) FROM 'title.crew.tsv' DELIMITER E'\t' NULL '\N' CSV HEADER"

echo -e "${Bold}Loading Title Principals...${NC}"
psql --host=postgres --username=postgres -d lunatech_imdb -c "\copy title_principals(tconst, ordering, nconst, category, job, characters) FROM 'title.principals.tsv' DELIMITER E'\t' NULL '\N' CSV HEADER"

# To change this banner, go to http://patorjk.com/software/taag/#p=display&f=Big&t=DB%20ready%20to%20use!
cat << "EOF"
  _____  ____                       _         _                         _ 
 |  __ \|  _ \                     | |       | |                       | |
 | |  | | |_) |  _ __ ___  __ _  __| |_   _  | |_ ___    _   _ ___  ___| |
 | |  | |  _ <  | '__/ _ \/ _` |/ _` | | | | | __/ _ \  | | | / __|/ _ \ |
 | |__| | |_) | | | |  __/ (_| | (_| | |_| | | || (_) | | |_| \__ \  __/_|
 |_____/|____/  |_|  \___|\__,_|\__,_|\__, |  \__\___/   \__,_|___/\___(_)
                                       __/ |                              
                                      |___/                               
EOF

echo -e "${Bold}Summary:${NC}"

echo -e "  ${Bold}Name basics:${NC}"
echo "  Input: $(wc -l name.basics.tsv | awk '{print $1}')"
echo "  Rows: $(psql --host=postgres --username=postgres -d lunatech_imdb -c 'select count(*) from name_basics' | head -3 | tail -1)"

echo -e "  ${Bold}Title basics:${NC}"
echo "  Input: $(wc -l title.basics.tsv | awk '{print $1}')"
echo "  Rows: $(psql --host=postgres --username=postgres -d lunatech_imdb -c 'select count(*) from title_basics' | head -3 | tail -1)"

echo -e "  ${Bold}Title ratings:${NC}"
echo "  Input: $(wc -l title.ratings.tsv | awk '{print $1}')"
echo "  Rows: $(psql --host=postgres --username=postgres -d lunatech_imdb -c 'select count(*) from title_ratings' | head -3 | tail -1)"

echo -e "  ${Bold}Title crew:${NC}"
echo "  Input: $(wc -l title.crew.tsv | awk '{print $1}')"
echo "  Rows: $(psql --host=postgres --username=postgres -d lunatech_imdb -c 'select count(*) from title_crew' | head -3 | tail -1)"

echo -e "  ${Bold}Title principals:${NC}"
echo "  Input: $(wc -l title.principals.tsv | awk '{print $1}')"
echo "  Rows: $(psql --host=postgres --username=postgres -d lunatech_imdb -c 'select count(*) from title_principals' | head -3 | tail -1)"
