#!/usr/bin/env bash
# Collects the results of the evaluation from the machines that ran it, then merges them (merge_results.py).
#
# Usage (from anywhere): evaluation/collect_results.sh [-u <user>] [-t <time>] <host> [<host> ...]
#   -u <user>: the user on the machines (default: SSH_USER, otherwise the one of ~/.ssh/config or the local one); a host
#              given as user@host keeps its own user
#   -t <time>: how long the connections stay open after the first login (default: 4h, as ControlPersist of ssh)
#   REMOTE_DIR: the repository on the machines (default: ~/stgraph)
#
# Login: the password is asked at most once, and only if some machine has no open connection: if sshpass is installed,
# the same password is used for every machine (one prompt), otherwise ssh asks once per machine. The connections stay
# open (ssh multiplexing, ControlPersist) for -t, so the next runs within that time ask nothing. ssh keys
# (ssh-copy-id) avoid the password altogether. Close the connections: ssh -O exit -o ControlPath=~/.ssh/stgraph-cm/%C
# <host>, or wait for -t.
#
# For each host, its results/ folder is copied into results/machines/<host>/, overwriting the previous copy (the
# evaluation only appends to its result files, so the last copy holds every row); then the statistics of all the copies
# are merged into results/merged/ (ingestion_statistics.csv and query_statistics.csv), with a column machine. A host
# that cannot be reached is reported and skipped, its previous copy kept.
set -euo pipefail

usage() { echo "Usage: $0 [-u <user>] [-t <time, e.g. 4h>] <host> [<host> ...]   (REMOTE_DIR: default ~/stgraph)"; exit 1; }

USER_NAME="${SSH_USER:-}"
PERSIST="4h"
while getopts "u:t:h" opt; do
    case "$opt" in
        u) USER_NAME="$OPTARG" ;;
        t) PERSIST="$OPTARG" ;;
        *) usage ;;
    esac
done
shift $((OPTIND - 1))
[[ $# -ge 1 ]] || usage

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE_DIR="${REMOTE_DIR:-~/stgraph}"
MACHINES="$REPO/results/machines"
MERGED="$REPO/results/merged"

# One shared connection per machine (ssh multiplexing), kept open for PERSIST after the first login
CONTROL_DIR="$HOME/.ssh/stgraph-cm"
mkdir -p "$CONTROL_DIR" && chmod 700 "$CONTROL_DIR"
SSH_OPTS=(-o ControlMaster=auto -o "ControlPath=$CONTROL_DIR/%C" -o "ControlPersist=$PERSIST"
          -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)

target() { if [[ "$1" == *@* || -z "$USER_NAME" ]]; then echo "$1"; else echo "$USER_NAME@$1"; fi; }

# Opens the connections not open yet: with sshpass, one password prompt for all of them
closed=()
for host in "$@"; do
    ssh "${SSH_OPTS[@]}" -O check "$(target "$host")" 2>/dev/null || closed+=("$host")
done
if [[ ${#closed[@]} -gt 0 ]]; then
    if command -v sshpass >/dev/null; then
        read -r -s -p "Password for ${closed[*]} (the same for all; empty: ssh keys or one prompt per machine): " password
        echo
    else
        password=""
        echo "sshpass not installed: ssh asks the password of each machine once (then kept for $PERSIST)"
    fi
    for host in "${closed[@]}"; do
        if [[ -n "$password" ]]; then
            SSHPASS="$password" sshpass -e ssh "${SSH_OPTS[@]}" -fN "$(target "$host")" || echo "WARNING: login to $host failed"
        else
            ssh "${SSH_OPTS[@]}" -fN "$(target "$host")" || echo "WARNING: login to $host failed"
        fi
    done
    unset password
fi

failed=()
for host in "$@"; do
    name="${host#*@}"   # the folder of the machine: its host name, without the user
    echo "== $(target "$host")"
    mkdir -p "$MACHINES/$name"
    if ! scp "${SSH_OPTS[@]}" -q -r "$(target "$host"):$REMOTE_DIR/results/." "$MACHINES/$name/"; then
        echo "WARNING: $host not collected (its previous copy, if any, is kept)"
        failed+=("$host")
    fi
done

python "$REPO/evaluation/merge_results.py" "$MACHINES" "$MERGED"

if [[ ${#failed[@]} -gt 0 ]]; then
    echo "Not collected: ${failed[*]}"
    exit 1
fi
