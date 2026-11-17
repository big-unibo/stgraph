#!/usr/bin/env bash
# The PostgreSQL dump of a size of PeMS, e.g., the xsmall of the CI, from its local cache:
#   1. ./gradlew pemsCache --args="<size>": the cache (datasets/original/pems/cache/<size>), read from the PostgreSQL of
#      datasets.pems in test_config.yml (skipped if the cache exists);
#   2. loads the cache into the local mimic-postgres (docker-compose.yaml): database pems, schema pems_cleaned (replaced),
#      with the columns that AbstractPemsLoader reads, so that pemsCache on it writes the same cache;
#   3. dumps the schema into datasets/original/pems/<size>.dump (restored by mimic-postgres, see datasets/init-mimic-pg.sh).
# Then upload the dump to https://big.csr.unibo.it/downloads/stgraph/pems/.
# The values go back as the loader reads them: occupancy / 100 (+ 0.5 / 100, so that occupancy * 100 truncated is the
# cached value), timestamps in UTC, Long.MIN_VALUE and Long.MAX_VALUE as null, the incidents of zero minutes ([t, t + 1)
# in the cache) with time_to = time_from. Only the stations of the cache: on the dump, the loader picks them all.
#
# Usage (from anywhere): evaluation/pems_dump.sh [size] (default: xsmall)
set -euo pipefail
cd "$(dirname "$0")/.."

SIZE="${1:-xsmall}"
CACHE="datasets/original/pems/cache/$SIZE"
OUT="datasets/original/pems/$SIZE.dump"
CONTAINER="mimic-postgres"
DB="pems"
CSV="WITH (FORMAT csv, QUOTE E'\\x01', DELIMITER E'\\x02')" # a JSON line per row, as it is

# 1. The cache
./gradlew pemsCache --args="$SIZE"

# 2. The cache into the local PostgreSQL
docker compose up -d "$CONTAINER"
until docker exec "$CONTAINER" pg_isready -U postgres -q; do sleep 2; done
sql() { docker exec -i "$CONTAINER" psql -U postgres -v ON_ERROR_STOP=1 -q "$@"; }
sql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$DB'" | grep -q 1 || sql -d postgres -c "CREATE DATABASE $DB"
sql -d "$DB" <<'SQL'
DROP SCHEMA IF EXISTS pems_cleaned CASCADE;
DROP SCHEMA IF EXISTS stage CASCADE;
CREATE SCHEMA pems_cleaned;
CREATE TABLE pems_cleaned.station (station_id int, district_id int);
CREATE TABLE pems_cleaned.measurement (station_id int, lane int, ts timestamp, resolution_min int, flow double precision, occupancy double precision, speed double precision);
CREATE TABLE pems_cleaned.station_in_segment (station_id int, valid_from timestamp, valid_to timestamp, fwy text, dir text, mile int, abs_pm double precision, latitude double precision, longitude double precision, type text, lanes int, name text);
CREATE TABLE pems_cleaned.segment (fwy text, dir text, mile int, from_pm double precision, to_pm double precision);
CREATE TABLE pems_cleaned.road_in_district (district_id int, fwy text, dir text);
CREATE TABLE pems_cleaned.incident (incident_id bigint, time_from timestamp, time_to timestamp, fwy text, dir text, mile int, district_id int, severity text, description text);
CREATE SCHEMA stage;
CREATE TABLE stage.events (station int, lane int, measure int, ts bigint, value bigint);
CREATE TABLE stage.stations (j jsonb);
CREATE TABLE stage.versions (j jsonb);
CREATE TABLE stage.segments (j jsonb);
CREATE TABLE stage.roads (j jsonb);
CREATE TABLE stage.incidents (j jsonb);
-- A timestamp in ms as the loader reads it back (UTC; the bounds as null)
CREATE FUNCTION stage.ts(ms bigint) RETURNS timestamp LANGUAGE sql AS
  $$ SELECT CASE WHEN ms IN (-9223372036854775808, 9223372036854775807) THEN NULL ELSE to_timestamp(ms / 1000.0) AT TIME ZONE 'UTC' END $$;
SQL
gzip -dc "$CACHE/events.csv.gz" | sql -d "$DB" -c "\\copy stage.events FROM STDIN WITH (FORMAT csv)"
for f in stations versions segments roads incidents; do
  gzip -dc "$CACHE/$f.jsonl.gz" | sql -d "$DB" -c "\\copy stage.$f FROM STDIN $CSV"
done
sql -d "$DB" <<'SQL'
INSERT INTO pems_cleaned.station SELECT (j->>'stationId')::int, (j->>'districtId')::int FROM stage.stations;
-- One row per station, lane and hour: flow, occupancy, speed (measures 0, 1, 2 of the cache; null if not cached)
INSERT INTO pems_cleaned.measurement
  SELECT station, lane, stage.ts(ts), 60, max(value) FILTER (WHERE measure = 0),
         (max(value) FILTER (WHERE measure = 1) + 0.5) / 100.0, max(value) FILTER (WHERE measure = 2)
  FROM stage.events GROUP BY station, lane, ts;
INSERT INTO pems_cleaned.station_in_segment
  SELECT (j->>'stationId')::int, stage.ts((j->>'from')::bigint), stage.ts((j->>'to')::bigint), j->>'fwy', j->>'dir', (j->>'mile')::int,
         (j->>'absPm')::float8, (j->>'latitude')::float8, (j->>'longitude')::float8, j->>'type', (j->>'lanes')::int, j->>'name'
  FROM stage.versions;
INSERT INTO pems_cleaned.segment SELECT j->>'fwy', j->>'dir', (j->>'mile')::int, (j->>'fromPm')::float8, (j->>'toPm')::float8 FROM stage.segments;
INSERT INTO pems_cleaned.road_in_district SELECT (j->>'districtId')::int, j->>'fwy', j->>'dir' FROM stage.roads;
INSERT INTO pems_cleaned.incident
  SELECT (j->>'incidentId')::bigint, stage.ts((j->>'from')::bigint),
         stage.ts(CASE WHEN (j->>'to')::bigint = (j->>'from')::bigint + 1 THEN (j->>'from')::bigint ELSE (j->>'to')::bigint END),
         j->>'fwy', j->>'dir', (j->>'mile')::int, (j->>'districtId')::int, j->>'severity', j->>'description'
  FROM stage.incidents;
DROP SCHEMA stage CASCADE;
SQL

# 3. The dump, outside the container
docker exec "$CONTAINER" pg_dump -U postgres -Fc --no-owner --no-privileges -d "$DB" -n pems_cleaned > "$OUT"
echo "Done: $OUT ($(du -h "$OUT" | cut -f1))"
