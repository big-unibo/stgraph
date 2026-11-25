#!/usr/bin/env bash
set -euo pipefail

# The dumps of the datasets in the same PostgreSQL, one database each, named after the dataset and the dump (its size):
# the first custom-format dump of /dump/mimic (required) in mimic-iv-<dump>, the one of /dump/pems (required) in
# pems-<dump> (e.g., xsmall.dump of the CI: mimic-iv-xsmall, pems-xsmall). A database with only the data of a size, never
# taken for the whole dataset (mimic-iv, pems): the loaders would build the local copies of the other sizes from it
DUMP_DIR="/dump"

for DIR in "$DUMP_DIR"/mimic "$DUMP_DIR"/pems; do
  DUMP_FILE="$(
    find "$DIR" \
      -maxdepth 1 \
      -type f \
      \( -name "*.dump" -o -name "*.backup" -o -name "*.bin" \) \
      -print \
      -quit 2>/dev/null || true
  )"

  if [[ -z "$DUMP_FILE" ]]; then
    echo "No PostgreSQL custom-format dump found in ${DIR}" # downloaded by run.sh (and by MimicEvaluation)
    exit 1
  fi

  NAME="$(basename "$DUMP_FILE")"
  if [[ "$DIR" == "$DUMP_DIR/mimic" ]]; then DATABASE="mimic-iv-${NAME%.*}"; else DATABASE="pems-${NAME%.*}"; fi
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