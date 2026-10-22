#!/bin/bash
set -exo

DATASET_SIZE=$1
DATASET=$2
LINK="https://big.csr.unibo.it/downloads/stgraph/$DATASET/stgraph/"
INTERNAL_LINK="137.204.74.24/downloads/stgraph/$DATASET/stgraph/"
OUTPUT_DIR="/pgage/src/main/resources/dataset/$DATASET"
FILENAME="${DATASET_SIZE}.tar.gz"

mkdir -p "$OUTPUT_DIR"
cd "$OUTPUT_DIR" || exit 1

echo "Downloading dataset ..."
# curl -L -o "./${DATASET_SIZE}.tar" "${LINK}${DATASET_SIZE}.tar"
if ! wget --no-check-certificate --tries=3 "${LINK}${FILENAME}"; then
    echo "Primary link failed, trying backup..."
    wget --no-check-certificate --tries=3 "${INTERNAL_LINK}${FILENAME}" || {
        echo "Error: failed to download from backup too!"
        exit 1
    }
fi

if [[ -f "${FILENAME}" ]]; then
    echo "Downloaded: ${FILENAME}"
    tar -xzvf "${FILENAME}"
    rm "${FILENAME}"
else
    echo "Error: Something went wrong while downloading dataset ${DATASET_SIZE}!"
    exit 1
fi