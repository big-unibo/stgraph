#!/bin/bash

# -------------------------
# Validate environment variables
# -------------------------
: "${THREAD:?THREAD not set}"
: "${DATASET:?DATASET not set}"
: "${DATASET_SIZE:?DATASET_SIZE not set}"
: "${QUERY_SELECTIVITY:?QUERY_SELECTIVITY not set}"

IFS=',' read -r -a THREADS <<< "$THREAD"
IFS=',' read -r -a DATASET_SIZES <<< "$DATASET_SIZE"
IFS=',' read -r -a QUERY_SELECTIVITIES <<< "$QUERY_SELECTIVITY"
IFS=',' read -r -a DSETS <<< "$DATASET"

for DSET in "${DSETS[@]}"; do
    export DATASET=$DSET
    for DSET_SIZE in "${DATASET_SIZES[@]}"; do
        echo "=== Processing dataset: $DATASET ==="
        export DATASET_SIZE="$DSET_SIZE"

        ./scripts/download_dataset.sh $DATASET_SIZE $DSET

        for EVALTHREAD in "${THREADS[@]}"
        do
            export THREAD="$EVALTHREAD"
            for SELECTIVITY in "${QUERY_SELECTIVITIES[@]}"; do
                export QUERY_SELECTIVITY="$SELECTIVITY"
                python3 evaluate_hygraph.py
            done
        done
done
