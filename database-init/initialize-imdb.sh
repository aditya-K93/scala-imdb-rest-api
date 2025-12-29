#!/usr/bin/env bash

set -euo pipefail

Bold='\033[1m'   # Bold
NC='\033[0m'     # No Color

S3_URL="https://lunatechassessments.s3-eu-west-1.amazonaws.com/imdb-docker"

DB_HOST="postgres"
DB_USER="postgres"
DB_NAME="imdb"

CACHE_DIR="/imdb-data"
SENTINEL_FILE="${CACHE_DIR}/.imdb_initialized"
IMDB_LOADER_MODE="${IMDB_LOADER_MODE:-update}"

cat << "EOF"
        
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

mkdir -p "${CACHE_DIR}"
cd "${CACHE_DIR}"

function psql_cmd() {
    psql --host="${DB_HOST}" --username="${DB_USER}" -d "${DB_NAME}" "$@"
}

function table_count() {
    local table="$1"
    psql_cmd -tAc "SELECT count(*) FROM public.${table}" | tr -d '[:space:]'
}

function table_is_empty() {
    local table="$1"
    [[ "$(table_count "${table}")" == "0" ]]
}

echo -e "${Bold}Ensuring schema + metadata table...${NC}"
psql_cmd -f /schema.sql

psql_cmd -v ON_ERROR_STOP=1 -c "
CREATE TABLE IF NOT EXISTS public.imdb_load_state (
    dataset_key   TEXT PRIMARY KEY,
    source_url    TEXT NOT NULL,
    local_file    TEXT NOT NULL,
    sha256        TEXT NOT NULL,
    downloaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    imported_at   TIMESTAMPTZ
);
"

function already_loaded_anything() {
    # If we already have substantial data, we consider the DB initialized.
    # This is used only for friendly logging; per-file state below governs work.
    local cnt
    cnt=$(psql_cmd -tAc "SELECT COALESCE((SELECT count(*) FROM public.title_basics), 0)")
    [[ "${cnt}" != "0" ]]
}

DB_HAS_DATA=false
if already_loaded_anything; then
    DB_HAS_DATA=true
    echo -e "${Bold}Database already has data; will only download/import changes (if any).${NC}"
else
    echo -e "${Bold}Database appears empty; performing initial load.${NC}"
fi

# One-shot behavior:
# - In init mode: if we've already completed an initialization successfully, exit immediately.
#   This keeps re-runs of the init container effectively no-op.
if [[ "${IMDB_LOADER_MODE}" == "init" && -f "${SENTINEL_FILE}" ]]; then
    # The sentinel lives in the downloads cache volume. If a user wipes ONLY the Postgres
    # volume (or Compose doesn't remove the cache volume), we must not skip initialization
    # based on a stale sentinel.
    if [[ "${DB_HAS_DATA}" == "true" ]]; then
        load_state_rows=$(psql_cmd -tAc "SELECT count(*) FROM public.imdb_load_state" | tr -d '[:space:]')
        if [[ "${load_state_rows}" != "0" ]]; then
            echo -e "${Bold}Init sentinel present (${SENTINEL_FILE}) and DB has data; init already completed. Exiting.${NC}"
            exit 0
        fi
    fi

    echo -e "${Bold}Init sentinel present (${SENTINEL_FILE}) but DB appears empty/uninitialized; continuing with initialization.${NC}"
fi

echo -e "${Bold}Downloading TSV dataset (cached) ...${NC}"
FILES=('name.basics.tsv' 'title.basics.tsv' 'title.ratings.tsv' 'title.crew.tsv' 'title.principals.tsv')

function sha256_file() {
    # macOS: shasum -a 256; Linux: sha256sum.
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

function download_if_newer() {
    local file="$1"        # e.g. name.basics.tsv
    local url_base="${S3_URL}/${file}.gz"
    local url="${url_base}"
    local out_gz="${file}.gz"

    if [ "${MINIMAL_DATASET}" = true ] ; then
        url="${url_base}.minimal"
    fi

    echo -e "  ${file}"
    # Best-effort caching:
    # - if file exists, do conditional GET via curl (-z)
    # - otherwise download it
    if [[ -f "${out_gz}" ]]; then
        curl -fsSL -z "${out_gz}" -o "${out_gz}" "${url}"
    else
        curl -fsSL -o "${out_gz}" "${url}"
    fi
}

function maybe_import_file() {
    local file="$1"        # e.g. name.basics.tsv
    local table="$2"       # e.g. name_basics
    local stage="$3"       # e.g. name_basics_stage
    local key="$4"         # dataset key stored in imdb_load_state
    local copy_cols="$5"   # columns list for \copy
    local insert_cols="$6" # columns list for INSERT
    local conflict_cols="$7" # conflict target
    local update_set="$8"  # update set clause for DO UPDATE

    local gz="${file}.gz"

    if [[ ! -f "${gz}" ]]; then
        echo "    Missing ${gz}; skipping."
        return 0
    fi

    local sha
    sha=$(sha256_file "${gz}")

    local prev_sha
    prev_sha=$(psql_cmd -tAc "SELECT sha256 FROM public.imdb_load_state WHERE dataset_key='${key}'" || true)
    prev_sha=$(echo "${prev_sha}" | tr -d '[:space:]')

    if [[ -n "${prev_sha}" && "${prev_sha}" == "${sha}" ]]; then
        echo "    Unchanged (${key}); skipping import."
        return 0
    fi

    echo "    Importing ${key} (changed or first time) ..."

    # IMPORTANT (disk usage):
    # - We stream from the .gz via psql's client-side \copy FROM PROGRAM.
    # - For the very first load (empty table), we COPY directly into the base table.
    # - For subsequent loads, we COPY into an UNLOGGED staging table, UPSERT, then DROP the stage.
    #   Dropping the stage prevents permanent bloat if a load fails mid-way.

    if table_is_empty "${table}" && [[ -z "${prev_sha}" ]]; then
        echo "    Base table ${table} is empty; doing direct COPY (no staging/upsert)."
        psql_cmd -v ON_ERROR_STOP=1 -c "\\copy public.${table}(${copy_cols}) FROM PROGRAM 'gzip -dc ${CACHE_DIR}/${gz}' DELIMITER E'\\t' NULL '\\N' CSV HEADER"
    else
        # Drop/recreate stage each time to avoid retaining huge relation files across retries.
        psql_cmd -v ON_ERROR_STOP=1 -c "DROP TABLE IF EXISTS public.${stage}; CREATE UNLOGGED TABLE public.${stage} (LIKE public.${table});"
        psql_cmd -v ON_ERROR_STOP=1 -c "\\copy public.${stage}(${copy_cols}) FROM PROGRAM 'gzip -dc ${CACHE_DIR}/${gz}' DELIMITER E'\\t' NULL '\\N' CSV HEADER"
        psql_cmd -v ON_ERROR_STOP=1 -c "
            INSERT INTO public.${table}(${insert_cols})
            SELECT ${insert_cols} FROM public.${stage}
            ON CONFLICT (${conflict_cols}) DO UPDATE
            SET ${update_set};
        "
        psql_cmd -v ON_ERROR_STOP=1 -c "DROP TABLE IF EXISTS public.${stage};"
    fi

    psql_cmd -v ON_ERROR_STOP=1 -c "
        INSERT INTO public.imdb_load_state(dataset_key, source_url, local_file, sha256, downloaded_at, imported_at)
        VALUES ('${key}', '${S3_URL}', '${gz}', '${sha}', now(), now())
        ON CONFLICT (dataset_key) DO UPDATE
        SET source_url = EXCLUDED.source_url,
            local_file = EXCLUDED.local_file,
            sha256 = EXCLUDED.sha256,
            downloaded_at = EXCLUDED.downloaded_at,
            imported_at = EXCLUDED.imported_at;
    "
}

for file in "${FILES[@]}"; do
    download_if_newer "${file}"
done

echo -e "${Bold}Loading data (upsert) ...${NC}"

maybe_import_file \
  "name.basics.tsv" \
  "name_basics" \
    "name_basics_stage" \
  "name.basics" \
  "nconst,primaryname,birthyear,deathyear,primaryprofession,knownfortitles" \
  "nconst,primaryname,birthyear,deathyear,primaryprofession,knownfortitles" \
  "nconst" \
  "primaryname=EXCLUDED.primaryname,birthyear=EXCLUDED.birthyear,deathyear=EXCLUDED.deathyear,primaryprofession=EXCLUDED.primaryprofession,knownfortitles=EXCLUDED.knownfortitles"

maybe_import_file \
  "title.basics.tsv" \
  "title_basics" \
    "title_basics_stage" \
  "title.basics" \
  "tconst,titletype,primarytitle,originaltitle,isadult,startyear,endyear,runtimeminutes,genres" \
  "tconst,titletype,primarytitle,originaltitle,isadult,startyear,endyear,runtimeminutes,genres" \
  "tconst" \
  "titletype=EXCLUDED.titletype,primarytitle=EXCLUDED.primarytitle,originaltitle=EXCLUDED.originaltitle,isadult=EXCLUDED.isadult,startyear=EXCLUDED.startyear,endyear=EXCLUDED.endyear,runtimeminutes=EXCLUDED.runtimeminutes,genres=EXCLUDED.genres"

maybe_import_file \
  "title.ratings.tsv" \
  "title_ratings" \
    "title_ratings_stage" \
  "title.ratings" \
  "tconst,averagerating,numvotes" \
  "tconst,averagerating,numvotes" \
  "tconst" \
  "averagerating=EXCLUDED.averagerating,numvotes=EXCLUDED.numvotes"

maybe_import_file \
  "title.crew.tsv" \
  "title_crew" \
    "title_crew_stage" \
  "title.crew" \
  "tconst,directors,writers" \
  "tconst,directors,writers" \
  "tconst" \
  "directors=EXCLUDED.directors,writers=EXCLUDED.writers"

maybe_import_file \
  "title.principals.tsv" \
  "title_principals" \
    "title_principals_stage" \
  "title.principals" \
  "tconst,ordering,nconst,category,job,characters" \
  "tconst,ordering,nconst,category,job,characters" \
  "tconst,ordering,nconst" \
  "category=EXCLUDED.category,job=EXCLUDED.job,characters=EXCLUDED.characters"

# Mark successful initialization.
if [[ "${IMDB_LOADER_MODE}" == "init" ]]; then
        date -u +"%Y-%m-%dT%H:%M:%SZ" > "${SENTINEL_FILE}"
fi

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
echo "  Rows: $(psql --host=postgres --username=postgres -d imdb -c 'select count(*) from name_basics' | head -3 | tail -1)"

echo -e "  ${Bold}Title basics:${NC}"
echo "  Rows: $(psql --host=postgres --username=postgres -d imdb -c 'select count(*) from title_basics' | head -3 | tail -1)"

echo -e "  ${Bold}Title ratings:${NC}"
echo "  Rows: $(psql --host=postgres --username=postgres -d imdb -c 'select count(*) from title_ratings' | head -3 | tail -1)"

echo -e "  ${Bold}Title crew:${NC}"
echo "  Rows: $(psql --host=postgres --username=postgres -d imdb -c 'select count(*) from title_crew' | head -3 | tail -1)"

echo -e "  ${Bold}Title principals:${NC}"
echo "  Rows: $(psql --host=postgres --username=postgres -d imdb -c 'select count(*) from title_principals' | head -3 | tail -1)"
