#!/usr/bin/env bash
# The reduced PostgreSQL dump of PeMS for the CI, as x1692200.dump for MIMIC-IV: datasets/original/pems/<size>.dump
# (default: xsmall), with only the rows of pems_cleaned that the ingestion of <size> reads, so that pemsCache on the dump
# writes the same cache as on the whole PeMS:
# - all the stations (the loader samples them, see AbstractPemsLoader.prepareCache);
# - the hourly measurements, the versions, the segments, the roads and the incidents of the stations of the cache.
# Steps:
#   1. ./gradlew pemsCache --args="xsmall"   (on the whole PeMS: datasets.pems of test_config.yml)
#   2. evaluation/pems_dump.sh [xsmall]      (needs psql and pg_dump, e.g., in the postgres:18 image)
#   3. upload datasets/original/pems/xsmall.dump to https://big.csr.unibo.it/downloads/stgraph/pems/
# Reads the PostgreSQL of PGHOST, PGPORT, PGUSER, PGPASSWORD (default: localhost, 5435, postgres, password), database
# PGDATABASE (default: pems), where it creates and then drops the scratch database pems_<size>. The tables are dumped
# without constraints and indexes (pre-data and data only): the rows are a subset, the foreign keys may not hold.
set -euo pipefail
cd "$(dirname "$0")/.."

SIZE="${1:-xsmall}"
export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5435}" PGUSER="${PGUSER:-postgres}" PGPASSWORD="${PGPASSWORD:-password}"
SOURCE="${PGDATABASE:-pems}"
SCRATCH="pems_${SIZE}"
S="pems_cleaned"
CACHE="datasets/original/pems/cache/$SIZE/stations.jsonl.gz"
OUT="datasets/original/pems/$SIZE.dump"

[[ -f "$CACHE" ]] || { echo "No $CACHE: run ./gradlew pemsCache --args=\"$SIZE\" first"; exit 1; }
IDS="$(gzip -dc "$CACHE" | grep -o '"stationId":[0-9]*' | cut -d: -f2 | paste -sd, -)"
[[ -n "$IDS" ]] || { echo "No stations in $CACHE"; exit 1; }
echo "Stations of $SIZE: $IDS"

psql -v ON_ERROR_STOP=1 -d postgres -c "DROP DATABASE IF EXISTS $SCRATCH" -c "CREATE DATABASE $SCRATCH"
# The schema and its tables, without constraints and indexes
pg_dump -d "$SOURCE" -n "$S" --section=pre-data --no-owner --no-privileges | psql -q -v ON_ERROR_STOP=1 -d "$SCRATCH"

# Copies the rows of [query] into the table [1] of the scratch database
copy() {
  echo "Copying $1"
  psql -v ON_ERROR_STOP=1 -d "$SOURCE" -c "\\copy ($2) TO STDOUT" | psql -v ON_ERROR_STOP=1 -d "$SCRATCH" -c "\\copy $S.$1 FROM STDIN"
}
IN_STATIONS="s.station_id IN ($IDS)"
copy station "SELECT * FROM $S.station"
copy measurement "SELECT * FROM $S.measurement WHERE station_id IN ($IDS) AND resolution_min = 60"
copy station_in_segment "SELECT * FROM $S.station_in_segment s WHERE $IN_STATIONS"
copy segment "SELECT * FROM $S.segment g WHERE EXISTS (SELECT 1 FROM $S.station_in_segment s WHERE s.fwy = g.fwy AND s.dir = g.dir AND s.mile = g.mile AND $IN_STATIONS)"
copy road_in_district "SELECT * FROM $S.road_in_district r WHERE EXISTS (SELECT 1 FROM $S.station_in_segment s WHERE s.fwy = r.fwy AND s.dir = r.dir AND $IN_STATIONS)"
copy incident "SELECT * FROM $S.incident i WHERE EXISTS (SELECT 1 FROM $S.station_in_segment s WHERE s.fwy = i.fwy AND s.dir = i.dir AND s.mile = i.mile AND $IN_STATIONS)"

pg_dump -Fc --no-owner --no-privileges -d "$SCRATCH" -f "$OUT"
psql -v ON_ERROR_STOP=1 -d postgres -c "DROP DATABASE $SCRATCH"
echo "Done: $OUT ($(du -h "$OUT" | cut -f1))"
