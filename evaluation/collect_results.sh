#!/usr/bin/env bash
# Collects the results of the evaluation from the machines that ran it, then merges them (merge_results.py).
#
# Usage (from anywhere): evaluation/collect_results.sh <host> [<host> ...]
#   <host>: as for ssh/scp (e.g., node1 or user@node1); passwordless ssh keys avoid a password per machine (ssh-copy-id)
#   REMOTE_DIR: the repository on the machines (default: ~/stgraph)
#
# For each host, its results/ folder is copied into results/machines/<host>/, overwriting the previous copy (the
# evaluation only appends to its result files, so the last copy holds every row); then the statistics of all the copies
# are merged into results/merged/ (ingestion_statistics.csv and query_statistics.csv), with a column machine. A host
# that cannot be reached is reported and skipped, its previous copy kept.
set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <host> [<host> ...]   (REMOTE_DIR: the repository on the machines, default ~/stgraph)"
    exit 1
fi

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE_DIR="${REMOTE_DIR:-~/stgraph}"
MACHINES="$REPO/results/machines"
MERGED="$REPO/results/merged"

failed=()
for host in "$@"; do
    name="${host#*@}"   # the folder of the machine: its host name, without the user
    echo "== $host"
    mkdir -p "$MACHINES/$name"
    if ! scp -q -r "$host:$REMOTE_DIR/results/." "$MACHINES/$name/"; then
        echo "WARNING: $host not collected (its previous copy, if any, is kept)"
        failed+=("$host")
    fi
done

python3 "$REPO/evaluation/merge_results.py" "$MACHINES" "$MERGED"

if [[ ${#failed[@]} -gt 0 ]]; then
    echo "Not collected: ${failed[*]}"
    exit 1
fi
