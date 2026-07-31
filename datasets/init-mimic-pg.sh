#!/usr/bin/env bash
set -euo pipefail

DUMP_DIR="/dump"
DATABASE="${POSTGRES_DB:-mimic-iv}"

DUMP_FILE="$(
  find "$DUMP_DIR" \
    -maxdepth 1 \
    -type f \
    \( -name "*.dump" -o -name "*.backup" -o -name "*.bin" \) \
    -print \
    -quit
)"

if [[ -z "$DUMP_FILE" ]]; then
  echo "No PostgreSQL custom-format dump found in ${DUMP_DIR}"
  exit 1
fi

echo "Restoring ${DUMP_FILE} into database ${DATABASE}"

pg_restore \
  --username="$POSTGRES_USER" \
  --dbname="$DATABASE" \
  --no-owner \
  --no-privileges \
  --verbose \
  "$DUMP_FILE"

echo "Restore completed"