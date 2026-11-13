#!/usr/bin/env bash
# Runs collect_results.sh again and again: every INTERVAL seconds (default 60) for DURATION seconds (default 2 hours),
# so that results/merged/ follows the evaluation of the machines. A run that fails (e.g., a machine not reachable) is
# reported and the loop goes on. Stop it with Ctrl+C.
#
# Usage: evaluation/collect_loop.sh [-i <seconds>] [-d <seconds>] [collect_results.sh options and hosts]
#   e.g.: evaluation/collect_loop.sh -u mfrancia 192.168.30.103 192.168.30.104
#         evaluation/collect_loop.sh -i 300 -d 28800 -u mfrancia 192.168.30.103   (every 5 minutes for 8 hours)
set -uo pipefail

INTERVAL=60
DURATION=7200
# The options of this script first; the rest goes to collect_results.sh
while [[ $# -gt 0 ]]; do
    case "$1" in
        -i) INTERVAL="$2"; shift 2 ;;
        -d) DURATION="$2"; shift 2 ;;
        *) break ;;
    esac
done
[[ $# -ge 1 ]] || { echo "Usage: $0 [-i <seconds>] [-d <seconds>] [-u <user>] <host> [<host> ...]"; exit 1; }

DIR="$(cd "$(dirname "$0")" && pwd)"
end=$(( $(date +%s) + DURATION ))
run=0
while (( $(date +%s) < end )); do
    run=$((run + 1))
    start=$(date +%s)
    echo "=== collection $run, $(date '+%Y-%m-%d %H:%M:%S')"
    "$DIR/collect_results.sh" "$@" || echo "collection $run: some machines not collected, retrying at the next one"
    # The next collection INTERVAL seconds after the start of this one (at once, if this one took longer)
    wait_for=$(( start + INTERVAL - $(date +%s) ))
    (( $(date +%s) + wait_for < end )) || break
    (( wait_for > 0 )) && sleep "$wait_for"
done
echo "=== done: $run collection(s), results in $(cd "$DIR/.." && pwd)/results/merged"
