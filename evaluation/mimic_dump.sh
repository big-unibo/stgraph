#!/usr/bin/env bash
# The reduced PostgreSQL dump of MIMIC-IV for the CI, as evaluation/pems_dump.sh for PeMS:
# datasets/original/mimic/<size>.dump (default: x1692200), with only the rows that the ingestion of <size> reads, so
# that mimicCache on the dump writes the same cache as on the whole MIMIC-IV:
# - all the items (d_items);
# - the events (chartevents_unique) of the time series (subject, item) of the cache.
# Steps:
#   1. ./gradlew mimicCache --args="x1692200"   (on the whole MIMIC-IV: datasets.mimic of test_config.yml, with the
#      subjects of src/main/resources/mimic-iv_subjectids_tsids.csv, the short list in the CI, see run.sh)
#   2. evaluation/mimic_dump.sh [x1692200]      (needs psql and pg_dump, e.g., in the postgres:18 image)
#   3. upload datasets/original/mimic/x1692200.dump to https://big.csr.unibo.it/downloads/stgraph/mimic/
# Reads the PostgreSQL of PGHOST, PGPORT, PGUSER, PGPASSWORD (default: localhost, 5434, postgres, password), database
# PGDATABASE (default: mimic-iv), where it creates and then drops the scratch database mimic_<size>. The two tables are
# dumped as plain tables (also if views in the source), without constraints and indexes, in the schema public.
set -euo pipefail
cd "$(dirname "$0")/.."

SIZE="${1:-x1692200}"
export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5434}" PGUSER="${PGUSER:-postgres}" PGPASSWORD="${PGPASSWORD:-password}"
SOURCE="${PGDATABASE:-mimic-iv}"
SCRATCH="mimic_${SIZE}"
CACHE="datasets/original/mimic/cache/${SIZE#x}/events.csv.gz" # the cache is named by the limit (see mimicLimit)
OUT="datasets/original/mimic/$SIZE.dump"

[[ -f "$CACHE" ]] || { echo "No $CACHE: run ./gradlew mimicCache --args=\"$SIZE\" first"; exit 1; }
# The time series of the cache, as ('subject', 'item') values
PAIRS="$(gzip -dc "$CACHE" | cut -d, -f1,2 | sort -u | sed "s/\([0-9]*\),\([0-9]*\)/('\1','\2')/" | paste -sd, -)"
[[ -n "$PAIRS" ]] || { echo "No events in $CACHE"; exit 1; }
echo "Time series of $SIZE: $(echo "$PAIRS" | tr -cd '(' | wc -c)"

psql -v ON_ERROR_STOP=1 -d postgres -c "DROP DATABASE IF EXISTS \"$SCRATCH\"" -c "CREATE DATABASE \"$SCRATCH\""

# Creates the table [1] in the scratch database with the columns of [1] in the source, then copies the rows of [2].
# The commands go through stdin (no limit on the length of the list of time series)
copy() {
  echo "Copying $1"
  local columns
  columns="$(psql -v ON_ERROR_STOP=1 -At -d "$SOURCE" -c "SELECT string_agg(quote_ident(attname) || ' ' || format_type(atttypid, atttypmod), ', ' ORDER BY attnum) FROM pg_attribute WHERE attrelid = '$1'::regclass AND attnum > 0 AND NOT attisdropped")"
  psql -v ON_ERROR_STOP=1 -q -d "$SCRATCH" -c "CREATE TABLE public.$1 ($columns)"
  printf '%s\n' "\\copy ($2) TO STDOUT" | psql -v ON_ERROR_STOP=1 -q -d "$SOURCE" | psql -v ON_ERROR_STOP=1 -q -d "$SCRATCH" -c "\\copy public.$1 FROM STDIN"
}
copy d_items "SELECT * FROM d_items"
copy chartevents_unique "SELECT * FROM chartevents_unique WHERE (subject_id::text, itemid::text) IN (VALUES $PAIRS)" # as text: whatever the type of the ids

pg_dump -Fc --no-owner --no-privileges -d "$SCRATCH" -t public.d_items -t public.chartevents_unique -f "$OUT"
psql -v ON_ERROR_STOP=1 -d postgres -c "DROP DATABASE \"$SCRATCH\""
echo "Done: $OUT ($(du -h "$OUT" | cut -f1))"
