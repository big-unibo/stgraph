#!/usr/bin/env bash
# Collects the size of the data each system stores on each machine of the machines file (see configure_machines.sh),
# into results/storage_statistics.csv (rewritten at each run: a snapshot), one row per machine, system, dataset, size
# and component, in bytes (the sum of the sizes of the files):
#   - neo4j, pgage, aeong: their data folders, datasets/volumes/<system>/<dataset>/<size> (the volumes section of
#     test_config.yml), component "data";
#   - stgraph: the dataverses <dataset>_<size> of its AsterixDB (the Docker volume asterixdb-data), component
#     "timeseries", and its graph, datasets/dump/<dataset>/<size>, component "graph";
#   - graphix: the dataverses <dataset>_graphix_<size> of its AsterixDB (the Docker volume graphix-data), "data";
#   - hygraph: nothing (in memory).
# The folders and volumes are read from a container (busybox), as cleanVolumes.sh: no sudo for the files of the
# containers' users. Run it when no ingestion is running.
#
# Usage (from anywhere): evaluation/collect_storage.sh [-u <user>] [<machines file>] (default: evaluation/machines.txt)
#   REMOTE_DIR: the repository on the machines (default: ~/stgraph)
set -uo pipefail

USAGE="Usage: $0 [-u <user>] [<machines file>]"
USER_NAME=""
while getopts "u:h" opt; do
    case "$opt" in
        u) USER_NAME="$OPTARG" ;;
        *) echo "$USAGE"; exit 1 ;;
    esac
done
shift $((OPTIND - 1))
REPO="$(cd "$(dirname "$0")/.." && pwd)"
MACHINES="${1:-$REPO/evaluation/machines.txt}"
[[ -f "$MACHINES" ]] || { echo "$USAGE"; exit 1; }
REMOTE_DIR="${REMOTE_DIR:-~/stgraph}"
OUT="$REPO/results/storage_statistics.csv"
STAMP="$(date +%s)000"

# What runs on a machine, for its systems ($1, comma-separated): lines "system<TAB>dataset<TAB>size<TAB>component<TAB>bytes"
REMOTE='
cd '"$REMOTE_DIR"' 2>/dev/null || exit 0
SUM="find \"\$d\" -type f -exec stat -c %s {} + 2>/dev/null | awk \"{s+=\\\$1} END {print s+0}\""
# The size of the folders <dataset>/<size> of a folder ($1) mounted on /v, as "dataset/size<TAB>bytes"
folders() {
    [ -d "$1" ] || return 0
    docker run --rm -v "$(cd "$1" && pwd):/v:ro" busybox:1.36 sh -c "for d in /v/*/*; do [ -d \"\$d\" ] || continue; echo \"\${d#/v/}	\$($SUM)\"; done"
}
# The size of the dataverses of the Docker volume of an AsterixDB ($1: the compose volume), as "dataverse<TAB>bytes"
dataverses() {
    for volume in $(docker volume ls -q --filter "label=com.docker.compose.volume=$1"); do
        docker run --rm -v "$volume:/v:ro" busybox:1.36 sh -c "for p in \$(find /v -type d -name \"partition_*\"); do for d in \"\$p\"/*; do [ -d \"\$d\" ] && echo \"\$(basename \"\$d\")	\$($SUM)\"; done; done"
    done | awk -F"\t" "{s[\$1]+=\$2} END {for (k in s) print k \"\t\" s[k]}"
}
for system in $(echo "$1" | tr , " "); do
    case "$system" in
        neo4j|pgage|aeong)
            folders "datasets/volumes/$system" | while IFS="	" read -r path bytes; do echo "$system	${path%%/*}	${path#*/}	data	$bytes"; done ;;
        stgraph)
            dataverses asterixdb-data | while IFS="	" read -r dv bytes; do
                case "$dv" in mimic_*|pems_*|smartbench_*|synth_*) echo "stgraph	${dv%%_*}	${dv#*_}	timeseries	$bytes" ;; esac
            done
            folders "datasets/dump" | while IFS="	" read -r path bytes; do
                case "${path%%/*}" in mimic|pems|smartbench|synth) echo "stgraph	${path%%/*}	${path#*/}	graph	$bytes" ;; esac
            done ;;
        graphix)
            dataverses graphix-data | while IFS="	" read -r dv bytes; do
                case "$dv" in *_graphix_*) echo "graphix	${dv%%_graphix_*}	${dv#*_graphix_}	data	$bytes" ;; esac
            done ;;
    esac
done
true
'

mapfile -t LINES < <(grep -v '^\s*\(#\|$\)' "$MACHINES")
echo "machine,model,configuration,dataset,datasetSize,component,bytes,timestamp" > "$OUT"
failed=()
for line in "${LINES[@]}"; do
    read -r host systems profile _ <<< "$line"
    configuration=$([[ "$profile" == limited ]] && echo conf || echo conf-free)
    dest="$host"; [[ "$host" == *@* || -z "$USER_NAME" ]] || dest="$USER_NAME@$host"
    echo "== $dest: $systems"
    rows=$(ssh -n "$dest" "bash -c $(printf '%q' "$REMOTE") -- $(printf '%q' "$systems")") || { echo "WARNING: $dest not read"; failed+=("$host"); continue; }
    [[ -z "$rows" ]] && { echo "   (no data)"; continue; }
    echo "$rows" | awk -F'\t' -v m="${host#*@}" -v c="$configuration" -v t="$STAMP" 'NF == 5 { print m "," $1 "," c "," $2 "," $3 "," $4 "," $5 "," t; printf "   %s %s %s %s: %d bytes\n", $1, $2, $3, $4, $5 > "/dev/stderr" }' >> "$OUT"
done

echo "Written: $OUT"
[[ ${#failed[@]} -eq 0 ]] || { echo "Not read: ${failed[*]}"; exit 1; }
