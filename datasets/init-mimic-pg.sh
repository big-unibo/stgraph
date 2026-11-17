#!/usr/bin/env bash
set -euo pipefail

# The dumps of the datasets in the same database, each in its schemas: one per folder of /dump (/dump/mimic, required;
# /dump/pems, optional), the first custom-format dump found in it
DUMP_DIR="/dump"
DATABASE="${POSTGRES_DB:-mimic-iv}"

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
    echo "No PostgreSQL custom-format dump found in ${DIR}"
    [[ "$DIR" == "$DUMP_DIR/mimic" ]] && exit 1
    continue
  fi

  echo "Restoring ${DUMP_FILE} into database ${DATABASE}"

  pg_restore \
    --username="$POSTGRES_USER" \
    --dbname="$DATABASE" \
    --no-owner \
    --no-privileges \
    --verbose \
    "$DUMP_FILE"

  echo "Restore of ${DUMP_FILE} completed"
done