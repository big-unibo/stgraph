#!/bin/bash
set -e

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
        echo "=== Processing dataset: $DATASET $DSET_SIZE ==="
        export DATASET_SIZE="$DSET_SIZE"

        if [[ "$DATASET" == "mimic" ]]; then
            # The CSV files of the import of Neo4j, mounted by the compose file (datasets/dump/neo4j/mimic)
            DATA="/home/hygraph/resources/datasets/mimic/$DATASET_SIZE"
            [[ -f "$DATA/person.csv" && -f "$DATA/measurement.csv" ]] || { echo "Missing the CSV files of Neo4j in $DATA"; exit 1; }
        else
            # The dataset downloaded by the evaluation, mounted by the compose file: the folder of the size of the
            # evaluation (RESULT_SIZE)
            DATA="/home/hygraph/resources/datasets/$DATASET/${RESULT_SIZE:-$DATASET_SIZE}"
            [[ -d "$DATA" ]] || { echo "Missing the dataset in $DATA"; exit 1; }
        fi

        for EVALTHREAD in "${THREADS[@]}"; do
            export THREAD="$EVALTHREAD"
            for SELECTIVITY in "${QUERY_SELECTIVITIES[@]}"; do
                export QUERY_SELECTIVITY="$SELECTIVITY"
                # The output of HyGraph goes to a log in results/hygraph/logs (the folder results of the repository),
                # printed only if the run fails
                LOG_DIR="results/hygraph/logs"
                mkdir -p "$LOG_DIR"
                LOG="$LOG_DIR/${DATASET}_${DATASET_SIZE}_${THREAD}_${QUERY_SELECTIVITY}.log"
                echo "Running HyGraph on $DATASET $DATASET_SIZE (threads $THREAD, selectivity $QUERY_SELECTIVITY), output in $LOG"
                if ! python3 "src/$DATASET/evaluate_hygraph.py" > "$LOG" 2>&1; then
                    echo "HyGraph failed, last lines of $LOG:"
                    tail -n 50 "$LOG"
                    exit 1
                fi
            done
        done
    done
done
