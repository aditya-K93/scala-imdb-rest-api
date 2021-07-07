#!/usr/bin/env bash

export JAVA_OPTS="-Xmx8g"

Bold='\033[1m'   # Bold
NC='\033[0m'     # No Color

# Check dependencies
if ! command -v wget &> /dev/null
then
    echo "This script requires 'wget' to work"
    exit 1
fi

if ! command -v amm &> /dev/null
then
    echo "This script requires Ammonite to work"
    exit 1
fi

CREW_LIMIT=5
FILES=('name.basics.tsv' 'title.basics.tsv' 'title.principals.tsv' 'title.ratings.tsv' 'title.crew.tsv')
VERBOSE=false
WITH_TV_EPISODES=false
MINIMAL_DATASET=false

function get_help() {
    cat << "EOF"
Lunatech IMDb Assessment Data CleanUp
Usage: data-cleanup.sh [OPTION]...

    -h,   --help                Prints this message
    -v,   --verbose             Be verbose (this is NOT the default)
    -wt,  --with-tv-episodes    Final dataset will include TV Episodes
    -m,   --minimal-dataset     Remove all titles that aren't movies
EOF
    exit 0
}

POSITIONAL=()
while [[ $# -gt 0 ]]
do
key="$1"

case $key in
    -h|--help)
    get_help
    ;;
    -v|--verbose)
    VERBOSE=true
    shift
    ;;
    -wt|--with-tv-episodes)
    WITH_TV_EPISODES=true
    shift
    ;;
    -m|--minimal-dataset)
    MINIMAL_DATASET=true
    shift
    ;;
    *)    # unknown option
    POSITIONAL+=("$1") # save it in an array for later
    shift # past argument
    ;;
esac
done
set -- "${POSITIONAL[@]}" # restore positional parameters

# Move to scripts folder
cd "$(dirname "$0")"

echo -e "${Bold}Downloading files from IMDb...${NC}"
for file in "${FILES[@]}"; do
    WGET_ARGS="-O ${file}.gz https://datasets.imdbws.com/${file}.gz"
    if [ "$VERBOSE" = false ] ; then
        WGET_ARGS="-q ${WGET_ARGS}"
    fi
    echo -e "  ${Bold}Getting ${file}.gz ${NC}"
    echo $WGET_ARGS | xargs wget
done

echo -e "${Bold}Uncompressing files...${NC}"
for file in "${FILES[@]}"; do
    GZIP_ARGS="-d ${file}.gz"
    if [ "$VERBOSE" = true ] ; then
        GZIP_ARGS="-v ${GZIP_ARGS}"
    fi
    echo -e "  ${Bold}Uncompressing ${file}.gz ${NC}"
    echo $GZIP_ARGS | xargs gzip
done

if [ "$MINIMAL_DATASET" = true ]; then # Remove all but movies, if applies
    echo -e "${Bold}Removing all but movies from titles...${NC}"
    grep "\tmovie\t" title.basics.tsv > title.basics.only_movies && \
        head -1 title.basics.tsv > title.basics.head && \
        cat title.basics.head title.basics.only_movies > title.basics.tsv && \
        rm title.basics.only_movies
elif [ "$WITH_TV_EPISODES" = false ]; then # Remove TV Episodes, if applies
    echo -e "${Bold}Removing TV Episodes from titles...${NC}"
    grep -v "\ttvEpisode\t" title.basics.tsv > title.basics.without_tv && \
        mv title.basics.without_tv title.basics.tsv
fi

# We need to replace double quotes to avoid issues during PSQL's copy command.
# Data is either inconsistent here or just mixed up.
echo -e "${Bold}Replacing double quotes...${NC}"
for file in "${FILES[@]}"; do
    sed -i .bak "s/\"/'/g" "$file"
done

# There are titles and names that don't appear in 'title.principals.tsv', so
# to avoid issues at import we need to remove missing values.
echo -e "${Bold}Checking Foreign Key Constraints...${NC} (this will take some time)"
awk '{print $1}' name.basics.tsv > just_names.fk
awk '{print $1}' title.basics.tsv > just_titles.fk

echo -e "  ${Bold}Removing missing principals...${NC}"
amm remove-missing.sc removePrincipals && mv title.principals.cleaned title.principals.tsv
echo -e "  ${Bold}Removing missing ratings...${NC}"
amm remove-missing.sc removeRatings && mv title.ratings.cleaned title.ratings.tsv

# There are 'directors' or 'writers' that exceed 3000 characters, which for our purposes
# we don't need. This will truncate both to a limit of 'CREW_LIMIT'
echo -e "${Bold}Truncating Crew size...${NC}"
nconst_length=$(tail -1 just_names.fk | awk '{ print length($0) }')
let "nconst_limit=((${nconst_length} + 1) * $CREW_LIMIT) - 1"
awk -F $'\t' -v limit="$nconst_limit" 'BEGIN {OFS = FS} { $2=substr($2,1,limit); $3=substr($3,1,limit); print }' title.crew.tsv > title.crew.tmp && \
    mv title.crew.tmp title.crew.tsv

echo -e "  ${Bold}Removing missing crew...${NC}"
amm remove-missing.sc removeCrew && mv title.crew.cleaned title.crew.tsv

echo -e "  ${Bold}Removing unnecessary names...${NC}"
awk '{print $3}' title.principals.tsv | sort | uniq > just_principals.fk
amm remove-missing.sc unnecessaryNames && mv name.basics.cleaned name.basics.tsv

echo -e "${Bold}Compressing files...${NC}"
for file in "${FILES[@]}"; do
    GZIP_ARGS="--best --keep ${file}"
    if [ "$VERBOSE" = true ] ; then
        GZIP_ARGS="-v ${GZIP_ARGS}"
    fi
    echo -e "  ${Bold}Compressing ${file} ${NC}"
    echo $GZIP_ARGS | xargs gzip
done

echo -e "${Bold}Cleaning workspace...${NC}"
rm *.bak *.fk *.head
