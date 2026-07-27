#!/bin/bash
set -euo pipefail

# -------------------------
# Validate environment variables
# -------------------------
: "${THREAD:?THREAD not set}"
: "${DATASET_SIZE:?DATASET_SIZE not set}"
: "${QUERY_SELECTIVITY:?QUERY_SELECTIVITY not set}"

IFS=',' read -r -a THREADS <<< "$THREAD"
IFS=',' read -r -a DATASET_SIZES <<< "$DATASET_SIZE"
IFS=',' read -r -a QUERY_SELECTIVITIES <<< "$QUERY_SELECTIVITY"

LOG_DIR="dt_graph/logs"
mkdir -p "$LOG_DIR"

# -------------------------
# Main loop: dataset -> selectivity -> threads
# -------------------------
for DSET in "${DATASET_SIZES[@]}"; do
    echo "=== Processing dataset: $DATASET ==="

    # Download dataset only if not present
    if [[ ! -d "dt_graph/datasets/dataset/smartbench/$DSET" ]]; then
        echo "Downloading dataset $DSET ..."
        ./scripts/download_dataset.sh "$DSET"
    else
        echo "Dataset $DSET already present, skipping download."
    fi

    export DATASET_SIZE="$DSET"

    for SELECTIVITY in "${QUERY_SELECTIVITIES[@]}"; do
        export QUERY_SELECTIVITY="$SELECTIVITY"
        for EVALTHREAD in "${THREADS[@]}"; do
            export THREAD="$EVALTHREAD"

            echo "Running ingestion test: dataset=$DATASET, threads=$THREAD, selectivity=$SELECTIVITY"
            ./gradlew test --tests TestIngestion --rerun-tasks \
                2>&1 | tee "$LOG_DIR/ingestion_size${DSET}_THREADS${THREAD}_SEL${SELECTIVITY}.log"

            echo "Running query test: dataset=$DSET, threads=$THREAD, selectivity=$SELECTIVITY"
            ./gradlew test --tests TestQuerySmartBench --rerun-tasks \
                2>&1 | tee -a "$LOG_DIR/query_size${DSET}_THREADS${THREAD}_SEL${SELECTIVITY}.log"
        done
    done
done

echo "All datasets processed."
