#!/usr/bin/env bash
# Collects the results of the evaluation from the machines that ran it, then merges them (merge_results.py).
#
# Usage (from anywhere): evaluation/collect_results.sh [-u <user>] <host> [<host> ...]
#   -u <user>: the user on the machines (a host given as user@host keeps its own user)
#   REMOTE_DIR: the repository on the machines (default: ~/stgraph)
# Login with the ssh key (add-key.sh): no password.
#
# For each host, its results/ folder is copied with scp into results/machines/<host>/, overwriting the previous copy
# (the evaluation only appends to its result files, so the last copy holds every row); then the statistics of all the
# copies are merged into results/merged/ (ingestion_statistics.csv and query_statistics.csv), with a column machine.
set -euo pipefail

USER_NAME=""
while getopts "u:h" opt; do
    case "$opt" in
        u) USER_NAME="$OPTARG" ;;
        *) echo "Usage: $0 [-u <user>] <host> [<host> ...]"; exit 1 ;;
    esac
done
shift $((OPTIND - 1))
[[ $# -ge 1 ]] || { echo "Usage: $0 [-u <user>] <host> [<host> ...]"; exit 1; }

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE_DIR="${REMOTE_DIR:-~/stgraph}"
MACHINES="$REPO/results/machines"

failed=()
for host in "$@"; do
    name="${host#*@}"
    dest="$host"; [[ "$host" == *@* || -z "$USER_NAME" ]] || dest="$USER_NAME@$host"
    echo "== $dest"
    mkdir -p "$MACHINES/$name"
    scp -q -r "$dest:$REMOTE_DIR/results/." "$MACHINES/$name/" || { echo "WARNING: $dest not collected"; failed+=("$host"); }
done

python3 "$REPO/evaluation/merge_results.py" "$MACHINES" "$REPO/results/merged"

[[ ${#failed[@]} -eq 0 ]] || { echo "Not collected: ${failed[*]}"; exit 1; }
