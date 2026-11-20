#!/usr/bin/env bash
# Collects the results of the evaluation from the machines that ran it, then merges them (merge_results.py); once, or
# again and again with -l, so that results/merged/ follows the evaluation of the machines.
#
# Usage (from anywhere): evaluation/collect_results.sh [-u <user>] [-l] [-i <seconds>] [-d <seconds>] <host> [<host> ...]
#   -u <user>: the user on the machines (a host given as user@host keeps its own user)
#   -l: in a loop, every INTERVAL seconds (-i, default 60) for DURATION seconds (-d, default 2 hours); a collection
#       that fails (e.g., a machine not reachable) is reported and the loop goes on. Stop it with Ctrl+C
#   REMOTE_DIR: the repository on the machines (default: ~/stgraph)
#   e.g.: evaluation/collect_results.sh -u mfrancia 192.168.30.103 192.168.30.104
#         evaluation/collect_results.sh -l -i 300 -d 28800 -u mfrancia 192.168.30.103   (every 5 minutes for 8 hours)
# Login with the ssh key (add-key.sh): no password.
#
# For each host, its results/ folder is copied with scp into results/machines/<host>/, overwriting the previous copy
# (the evaluation only appends to its result files, so the last copy holds every row); then the statistics of all the
# copies are merged into results/merged/ (ingestion_statistics.csv and query_statistics.csv), with a column machine.
set -uo pipefail

USAGE="Usage: $0 [-u <user>] [-l] [-i <seconds>] [-d <seconds>] <host> [<host> ...]"
USER_NAME=""
LOOP=false
INTERVAL=60
DURATION=7200
while getopts "u:li:d:h" opt; do
    case "$opt" in
        u) USER_NAME="$OPTARG" ;;
        l) LOOP=true ;;
        i) INTERVAL="$OPTARG" ;;
        d) DURATION="$OPTARG" ;;
        *) echo "$USAGE"; exit 1 ;;
    esac
done
shift $((OPTIND - 1))
[[ $# -ge 1 ]] || { echo "$USAGE"; exit 1; }

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE_DIR="${REMOTE_DIR:-~/stgraph}"
MACHINES="$REPO/results/machines"

# One collection: copies the results of every host, then merges them; fails if some host was not collected
collect() {
    local failed=() host name dest
    for host in "$@"; do
        name="${host#*@}"
        dest="$host"; [[ "$host" == *@* || -z "$USER_NAME" ]] || dest="$USER_NAME@$host"
        echo "== $dest"
        mkdir -p "$MACHINES/$name"
        scp -q -r "$dest:$REMOTE_DIR/results/." "$MACHINES/$name/" || { echo "WARNING: $dest not collected"; failed+=("$host"); }
    done
    python "$REPO/evaluation/merge_results.py" "$MACHINES" "$REPO/results/merged" || return 1
    [[ ${#failed[@]} -eq 0 ]] || { echo "Not collected: ${failed[*]}"; return 1; }
}

$LOOP || { collect "$@"; exit; }

end=$(( $(date +%s) + DURATION ))
run=0
while (( $(date +%s) < end )); do
    run=$((run + 1))
    start=$(date +%s)
    echo "=== collection $run, $(date '+%Y-%m-%d %H:%M:%S')"
    collect "$@" || echo "collection $run: some machines not collected, retrying at the next one"
    # The next collection INTERVAL seconds after the start of this one (at once, if this one took longer)
    wait_for=$(( start + INTERVAL - $(date +%s) ))
    (( $(date +%s) + wait_for < end )) || break
    (( wait_for > 0 )) && sleep "$wait_for"
done
echo "=== done: $run collection(s), results in $REPO/results/merged"
