#!/bin/bash
set -exo

mkdir -p datasets/original/smartbench
mkdir -p datasets/original/mimic

DATASET_SIZE=$1
LINK="https://big.csr.unibo.it/downloads/stgraph/smartbench/stgraph/"
INTERNAL_LINK="137.204.74.24/downloads/stgraph/smartbench/stgraph/"
OUTPUT_DIR="${2:-"datasets/original/smartbench"}"
FILENAME="${DATASET_SIZE}.tar.gz"

mkdir -p "$OUTPUT_DIR"
cd "$OUTPUT_DIR" || exit 1

echo "Downloading dataset ..."
# curl -L -o "./${DATASET_SIZE}.tar" "${LINK}${DATASET_SIZE}.tar"
if ! wget --no-check-certificate --tries=3 "${INTERNAL_LINK}${FILENAME}"; then
    echo "Primary link failed, trying backup..."
    wget --no-check-certificate --tries=3 "${INTERNAL_LINK}${FILENAME}" || {
        echo "Error: failed to download from backup too!"
        exit 1
    }
fi

if [[ -f "${DATASET_SIZE}.tar.gz" ]]; then
    echo "Downloaded: ${DATASET_SIZE}.tar.gz"
    tar -xzvf "${DATASET_SIZE}.tar.gz"
    rm "${DATASET_SIZE}.tar.gz"
else
    echo "Error: Something went wrong while downloading dataset ${DATASET_SIZE}!"
    exit 1
fi


