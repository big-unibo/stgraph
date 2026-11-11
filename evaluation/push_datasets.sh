#!/usr/bin/env bash
# Copies the datasets downloaded on this machine to the other machines of the evaluation, so that they neither download
# them again nor query PostgreSQL for MIMIC-IV: by default datasets/original (the SmartBench sizes with their gzipped
# JSON files, the Cypher dumps of AeonG, the MIMIC-IV dump and the local copies of MIMIC-IV in mimic/cache), into the
# same folder of the repository on each machine.
#
# Usage (from anywhere): evaluation/push_datasets.sh [-u <user>] [-t <time>] [-p <folder>]... <host> [<host> ...]
#   -u <user>: the user on the machines (default: SSH_USER, otherwise the one of ~/.ssh/config or the local one); a host
#              given as user@host keeps its own user
#   -t <time>: how long the ssh connections stay open after the first login (default: 4h, as collect_results.sh)
#   -p <folder>: a folder to copy, relative to the repository (repeatable; default: datasets/original), e.g.
#                -p datasets/original/mimic/cache to copy only the copies of MIMIC-IV
#   REMOTE_DIR: the repository on the machines (default: ~/stgraph)
#
# With rsync (on both sides), only what is missing or changed is sent, and an interrupted copy resumes; otherwise scp
# copies everything again. Nothing is deleted on the machines. Partial files (downloads and copies in progress: .part,
# .tmp, .extracting) are not sent. With ssh keys (add-key.sh) no password is asked; otherwise ssh asks once per machine,
# then the connection stays open for -t.
set -euo pipefail

usage() { echo "Usage: $0 [-u <user>] [-t <time, e.g. 4h>] [-p <folder>]... <host> [<host> ...]   (REMOTE_DIR: default ~/stgraph)"; exit 1; }

USER_NAME="${SSH_USER:-}"
PERSIST="4h"
FOLDERS=()
while getopts "u:t:p:h" opt; do
    case "$opt" in
        u) USER_NAME="$OPTARG" ;;
        t) PERSIST="$OPTARG" ;;
        p) FOLDERS+=("${OPTARG%/}") ;;
        *) usage ;;
    esac
done
shift $((OPTIND - 1))
[[ $# -ge 1 ]] || usage
[[ ${#FOLDERS[@]} -gt 0 ]] || FOLDERS=("datasets/original")

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE_DIR="${REMOTE_DIR:-~/stgraph}"

# The connections of collect_results.sh (same folder): one shared connection per machine, kept open for PERSIST
CONTROL_DIR="$HOME/.ssh/stgraph-cm"
mkdir -p "$CONTROL_DIR" && chmod 700 "$CONTROL_DIR"
SSH_OPTS=(-o ControlMaster=auto -o "ControlPath=$CONTROL_DIR/%C" -o "ControlPersist=$PERSIST"
          -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)

target() { if [[ "$1" == *@* || -z "$USER_NAME" ]]; then echo "$1"; else echo "$USER_NAME@$1"; fi; }

for folder in "${FOLDERS[@]}"; do
    [[ -d "$REPO/$folder" ]] || { echo "No folder $REPO/$folder: nothing to copy"; exit 1; }
    echo "$folder: $(du -sh "$REPO/$folder" | cut -f1)"
done

failed=()
for host in "$@"; do
    dest="$(target "$host")"
    echo "== $dest"
    ok=true
    for folder in "${FOLDERS[@]}"; do
        if ! ssh "${SSH_OPTS[@]}" "$dest" "mkdir -p $REMOTE_DIR/$folder"; then ok=false; break; fi
        if command -v rsync >/dev/null && ssh "${SSH_OPTS[@]}" "$dest" "command -v rsync" >/dev/null 2>&1; then
            rsync -a --partial --info=progress2 \
                --exclude='*.part' --exclude='*.tmp' --exclude='.*.extracting' \
                -e "ssh ${SSH_OPTS[*]}" "$REPO/$folder/" "$dest:$REMOTE_DIR/$folder/" || { ok=false; break; }
        else
            scp "${SSH_OPTS[@]}" -q -r "$REPO/$folder/." "$dest:$REMOTE_DIR/$folder/" || { ok=false; break; }
        fi
    done
    if $ok; then echo "$dest: done"; else echo "WARNING: $dest not (fully) copied"; failed+=("$host"); fi
done

if [[ ${#failed[@]} -gt 0 ]]; then
    echo "Not copied: ${failed[*]}"
    exit 1
fi
