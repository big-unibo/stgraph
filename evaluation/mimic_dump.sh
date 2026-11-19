#!/usr/bin/env bash
# The PostgreSQL dump of a size of MIMIC-IV, e.g., the x1692200 of the CI, from its local cache:
#   1. ./gradlew mimicCache --args="<size>": the cache (datasets/original/mimic/cache/<size>), read from the PostgreSQL
#      of datasets.mimic in test_config.yml, for the subjects of src/main/resources/mimic-iv_subjectids_tsids_short.csv
#      (an "x" size) or of mimic-iv_subjectids_tsids.csv (the others); skipped if the cache exists;
#   2. loads the cache into a temporary PostgreSQL container (postgres:18, removed at the end; not
#      mimic-postgres, which needs the dumps to start): database mimic-iv, tables d_items and
#      chartevents_unique, with the columns that AbstractMimicIVLoader reads, so that mimicCache on it writes
#      the same cache;
#   3. dumps the two tables into datasets/original/mimic/<size>.dump (restored by mimic-postgres, see
#      datasets/init-mimic-pg.sh).
# Then upload the dump to https://big.csr.unibo.it/downloads/stgraph/mimic/.
# The values go back as the loader reads them: the values rounded as in the cache, timestamps in UTC.
#
# Usage (from anywhere): evaluation/mimic_dump.sh [size] (default: x1692200)
set -euo pipefail
cd "$(dirname "$0")/.."

SIZE="${1:-x1692200}"
CACHE="datasets/original/mimic/cache/$SIZE" # named by the size (see mimicKey)
OUT="datasets/original/mimic/$SIZE.dump"
CONTAINER="stgraph-dump-mimic"
DB="mimic-iv"
CSV="WITH (FORMAT csv, QUOTE E'\\x01', DELIMITER E'\\x02')" # a JSON line per row, as it is

# 1. The cache
./gradlew mimicCache --args="$SIZE"

# 2. The cache into the local PostgreSQL
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --rm --name "$CONTAINER" -e POSTGRES_PASSWORD=password postgres:18 >/dev/null
trap 'docker stop "$CONTAINER" >/dev/null 2>&1 || true' EXIT
# Ready once it accepts TCP connections: only after its initialization
until docker exec "$CONTAINER" psql -U postgres -h 127.0.0.1 -tAc "SELECT 1" >/dev/null 2>&1; do sleep 2; done
sql() { docker exec -i "$CONTAINER" psql -U postgres -v ON_ERROR_STOP=1 -q "$@"; }
sql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$DB'" | grep -q 1 || sql -d postgres -c "CREATE DATABASE \"$DB\""
sql -d "$DB" <<'SQL'
DROP TABLE IF EXISTS public.d_items, public.chartevents_unique CASCADE;
DROP SCHEMA IF EXISTS stage CASCADE;
CREATE TABLE public.d_items (itemid int, label text, abbreviation text, category text, unitname text);
CREATE TABLE public.chartevents_unique (subject_id int, itemid int, charttime timestamp, valuenum double precision);
CREATE SCHEMA stage;
CREATE TABLE stage.items (j jsonb);
CREATE TABLE stage.events (subject_id int, itemid int, ts bigint, value bigint);
SQL
gzip -dc "$CACHE/items.jsonl.gz" | sql -d "$DB" -c "\\copy stage.items FROM STDIN $CSV"
gzip -dc "$CACHE/events.csv.gz" | sql -d "$DB" -c "\\copy stage.events FROM STDIN WITH (FORMAT csv)"
sql -d "$DB" <<'SQL'
INSERT INTO public.d_items SELECT (j->>'itemid')::int, j->>'label', j->>'abbreviation', j->>'category', j->>'unitname' FROM stage.items;
INSERT INTO public.chartevents_unique SELECT subject_id, itemid, to_timestamp(ts / 1000.0) AT TIME ZONE 'UTC', value FROM stage.events;
DROP SCHEMA stage CASCADE;
SQL

# 3. The dump, outside the container
docker exec "$CONTAINER" pg_dump -U postgres -Fc --no-owner --no-privileges -d "$DB" -t public.d_items -t public.chartevents_unique > "$OUT"
echo "Done: $OUT ($(du -h "$OUT" | cut -f1))"
