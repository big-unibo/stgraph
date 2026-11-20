#!/usr/bin/env bash
# Starts the evaluation from scratch on the machines: on each one, moves the result statistics
# (results/dt_graph/{ingestion_time/ingestion_statistics.csv,query_time/query_statistics.csv}) to a backup next to
# them (<file>.bak-<date>), so that the next runs write new files; the same for their copies here
# (results/machines/<host>/, see collect_results.sh), so that results/merged/ holds only the new runs. Nothing is
# deleted.
#
# Usage (from anywhere): evaluation/reset_results.sh [-u <user>] <host> [<host> ...]
#   -u <user>: the user on the machines (a host given as user@host keeps its own user)
#   REMOTE_DIR: the repository on the machines (default: ~/stgraph)
#   e.g.: evaluation/reset_results.sh -u mfrancia 192.168.30.101 192.168.30.102
# Login with the ssh key (push-ssh-key.sh): no password. Run it when no evaluation is running on the machines.
set -uo pipefail

USAGE="Usage: $0 [-u <user>] <host> [<host> ...]"
USER_NAME=""
while getopts "u:h" opt; do
    case "$opt" in
        u) USER_NAME="$OPTARG" ;;
        *) echo "$USAGE"; exit 1 ;;
    esac
done
shift $((OPTIND - 1))
[[ $# -ge 1 ]] || { echo "$USAGE"; exit 1; }

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE_DIR="${REMOTE_DIR:-~/stgraph}"
STAMP="$(date +%Y%m%d-%H%M%S)"
FILES="dt_graph/ingestion_time/ingestion_statistics.csv dt_graph/query_time/query_statistics.csv"

failed=()
for host in "$@"; do
    dest="$host"; [[ "$host" == *@* || -z "$USER_NAME" ]] || dest="$USER_NAME@$host"
    echo "== $dest"
    ssh -n "$dest" "cd $REMOTE_DIR/results && for f in $FILES; do [ -f \$f ] && mv -n \$f \$f.bak-$STAMP && echo \"   \$f -> \$f.bak-$STAMP\"; done; true" \
        || { echo "WARNING: $dest not reset"; failed+=("$host"); continue; }
    # The copy here (collect_results.sh), so that the merge does not keep the old runs
    local_dir="$REPO/results/machines/${host#*@}"
    for f in $FILES; do
        [[ -f "$local_dir/$f" ]] && mv -n "$local_dir/$f" "$local_dir/$f.bak-$STAMP" && echo "   (here) $f -> $f.bak-$STAMP"
    done
done

[[ ${#failed[@]} -eq 0 ]] || { echo "Not reset: ${failed[*]}"; exit 1; }
echo "Done: the next runs start with empty statistics (backups: *.bak-$STAMP)"
