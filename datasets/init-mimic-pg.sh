#!/usr/bin/env bash
set -euo pipefail

# The dumps of the datasets in the same PostgreSQL, one database each: the first custom-format dump of /dump/mimic
# (required) in POSTGRES_DB (mimic-iv), the one of /dump/pems (optional) in the database pems
DUMP_DIR="/dump"

for DIR in "$DUMP_DIR"/mimic "$DUMP_DIR"/pems; do
  if [[ "$DIR" == "$DUMP_DIR/mimic" ]]; then DATABASE="${POSTGRES_DB:-mimic-iv}"; else DATABASE="pems"; fi
  DUMP_FILE="$(
    find "$DIR" \
      -maxdepth 1 \
      -type f \
      \( -name "*.dump" -o -name "*.backup" -o -name "*.bin" \) \
      -print \
      -quit 2>/dev/null || true
  )"

  if [[ -z "$DUMP_FILE" ]]; then
    echo "No PostgreSQL custom-format dump found in ${DIR}"
    [[ "$DIR" == "$DUMP_DIR/mimic" ]] && exit 1
    continue
  fi

  echo "Restoring ${DUMP_FILE} into database ${DATABASE}"
  psql --username="$POSTGRES_USER" --dbname=postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '${DATABASE}'" | grep -q 1 \
    || psql --username="$POSTGRES_USER" --dbname=postgres -c "CREATE DATABASE \"${DATABASE}\""

  pg_restore \
    --username="$POSTGRES_USER" \
    --dbname="$DATABASE" \
    --no-owner \
    --no-privileges \
    --verbose \
    "$DUMP_FILE"

  echo "Restore of ${DUMP_FILE} completed"
done