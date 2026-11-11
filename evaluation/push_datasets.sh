#!/usr/bin/env bash
# Copies the datasets downloaded on this machine (datasets/original: SmartBench, the AeonG dumps, the MIMIC-IV dump and
# the local copies of MIMIC-IV in mimic/cache) to the same folder of the repository on the other machines, with scp.
#
# Usage (from anywhere): evaluation/push_datasets.sh [-u <user>] [-p <folder>]... <host> [<host> ...]
#   -u <user>: the user on the machines (a host given as user@host keeps its own user)
#   -p <folder>: a folder to copy, relative to the repository (repeatable; default: datasets/original)
#   REMOTE_DIR: the repository on the machines (default: ~/stgraph)
# Login with the ssh key (add-key.sh): no password. scp copies every file again and deletes nothing on the machines.
set -euo pipefail

USER_NAME=""
FOLDERS=()
while getopts "u:p:h" opt; do
    case "$opt" in
        u) USER_NAME="$OPTARG" ;;
        p) FOLDERS+=("${OPTARG%/}") ;;
        *) echo "Usage: $0 [-u <user>] [-p <folder>]... <host> [<host> ...]"; exit 1 ;;
    esac
done
shift $((OPTIND - 1))
[[ $# -ge 1 ]] || { echo "Usage: $0 [-u <user>] [-p <folder>]... <host> [<host> ...]"; exit 1; }
[[ ${#FOLDERS[@]} -gt 0 ]] || FOLDERS=("datasets/original")

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE_DIR="${REMOTE_DIR:-~/stgraph}"

failed=()
for host in "$@"; do
    dest="$host"; [[ "$host" == *@* || -z "$USER_NAME" ]] || dest="$USER_NAME@$host"
    echo "== $dest"
    for folder in "${FOLDERS[@]}"; do
        ssh "$dest" "mkdir -p $REMOTE_DIR/$folder" && scp -q -r "$REPO/$folder/." "$dest:$REMOTE_DIR/$folder/" \
            || { echo "WARNING: $folder not copied to $dest"; failed+=("$host"); }
    done
done

[[ ${#failed[@]} -eq 0 ]] || { echo "Not copied: ${failed[*]}"; exit 1; }
