#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
    echo "Usage: $0 <dataset> <dataset_size_or_filename>"
    echo "Example:"
    echo "  $0 smartbench small"
    echo "  $0 smartbench medium.tar.gz"
    exit 1
fi

DATASET="$1"
DATASET_SIZE="$2"

BASE_URL="https://big.csr.unibo.it/downloads/stgraph"
OUTPUT_DIR="datasets/original/${DATASET}"

mkdir -p "$OUTPUT_DIR"
cd "$OUTPUT_DIR"

# Determine filename
if [[ "$DATASET_SIZE" == *.* ]]; then
    FILENAME="$DATASET_SIZE"
else
    FILENAME="${DATASET_SIZE}.tar.gz"
fi

URL="${BASE_URL}/${DATASET}/${FILENAME}"

wget --no-check-certificate --tries=3 -O "$FILENAME" "$URL"

if [[ ! -f "$FILENAME" ]]; then
    echo "Error: failed to download ${URL}"
    exit 1
fi

echo "Downloaded: $FILENAME"

if [[ "$FILENAME" == *.tar.gz ]]; then
    echo "Extracting..."
    tar -xzvf "$FILENAME"
    rm "$FILENAME"
fi

echo "Done."