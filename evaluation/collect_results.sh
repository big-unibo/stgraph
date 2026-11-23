#!/usr/bin/env bash
# Collects the results of the evaluation from the machines that ran it, then merges them (merge_results.py); once, or
# again and again with -l, so that results/merged/ follows the evaluation of the machines.
#
# Usage (from anywhere): evaluation/collect_results.sh [-u <user>] [-l] [-i <seconds>] [-d <seconds>] [-s] <host> [<host> ...]
#   -u <user>: the user on the machines (a host given as user@host keeps its own user)
#   -l: in a loop, every INTERVAL seconds (-i, default 60) for DURATION seconds (-d, default 2 hours); a collection
#       that fails (e.g., a machine not reachable) is reported and the loop goes on. Stop it with Ctrl+C
#   -s: also the size of the data each system stores on each machine (see below), once per collection: run it when no
#       ingestion is running, not in a loop during the runs (it reads all the data folders)
#   REMOTE_DIR: the repository on the machines (default: ~/stgraph)
#   e.g.: evaluation/collect_results.sh -u mfrancia 192.168.30.103 192.168.30.104
#         evaluation/collect_results.sh -l -i 300 -d 28800 -u mfrancia 192.168.30.103   (every 5 minutes for 8 hours)
# Login with the ssh key (add-key.sh): no password.
#
# For each host, its results/ folder is copied with scp into results/machines/<host>/, overwriting the previous copy
# (the evaluation only appends to its result files, so the last copy holds every row); then the statistics of all the
# copies are merged into results/merged/ (ingestion_statistics.csv and query_statistics.csv), with a column machine.
# With -s, the size in bytes (the sum of the sizes of the files, without the logs: transaction logs and write-ahead
# logs, server logs) of the data of each system, as storage_statistics.csv
# of the machine (results/machines/<host>/), merged as the others: neo4j, pgage and aeong, their data folders
# datasets/volumes/<system>/<dataset>/<size> (component data); stgraph, its AsterixDB (component timeseries): its data
# folders datasets/volumes/asterixdb/<dataset>/<size>, otherwise its dataverses <dataset>_<size> of the Docker volume
# asterixdb-data, and its graph datasets/dump/<dataset>/<size> (component graph); graphix, its data folders
# datasets/volumes/graphix/<dataset>/<size>, otherwise its dataverses <dataset>_graphix_<size> of the Docker volume
# graphix-data (component data); hygraph none (in memory). The
# systems and the configuration (conf, conf-free) of each machine from evaluation/machines.txt (limited, free). Read from a container (busybox), as
# cleanVolumes.sh: no sudo for the files of the users of the containers.
set -uo pipefail

USAGE="Usage: $0 [-u <user>] [-l] [-i <seconds>] [-d <seconds>] [-s] <host> [<host> ...]"
USER_NAME=""
LOOP=false
INTERVAL=60
DURATION=7200
STORAGE=false
while getopts "u:li:d:sh" opt; do
    case "$opt" in
        u) USER_NAME="$OPTARG" ;;
        l) LOOP=true ;;
        i) INTERVAL="$OPTARG" ;;
        d) DURATION="$OPTARG" ;;
        s) STORAGE=true ;;
        *) echo "$USAGE"; exit 1 ;;
    esac
done
shift $((OPTIND - 1))
[[ $# -ge 1 ]] || { echo "$USAGE"; exit 1; }

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE_DIR="${REMOTE_DIR:-~/stgraph}"
MACHINES="$REPO/results/machines"

# The size of the data of the systems on a machine (-s), run there: lines "system<TAB>dataset<TAB>size<TAB>component<TAB>bytes"
STORAGE_SCRIPT='
cd '"$REMOTE_DIR"' 2>/dev/null || exit 0
# The size of the files of a folder ($d), without the logs of the systems: AsterixDB (txnlog, logs), Neo4j
# (transactions), PostgreSQL (pg_wal, log), AeonG (wal)
SUM="find \"\$d\" \( -type d \( -iname \"*txn*\" -o -name pg_wal -o -name log -o -name logs -o -name transactions -o -name wal \) -prune \) -o -type f -exec stat -c %s {} + 2>/dev/null | awk \"{s+=\\\$1} END {print s+0}\""
folders() {  # the folders <dataset>/<size> of a folder ($1), as "dataset/size<TAB>bytes"
    [ -d "$1" ] || return 0
    docker run --rm -v "$(cd "$1" && pwd):/v:ro" busybox:1.36 sh -c "for d in /v/*/*; do [ -d \"\$d\" ] || continue; echo \"\${d#/v/}	\$($SUM)\"; done"
}
dataverses() {  # the dataverses of the Docker volume of an AsterixDB ($1: its compose volume), as "dataverse<TAB>bytes"
    for volume in $(docker volume ls -q --filter "label=com.docker.compose.volume=$1"); do
        docker run --rm -v "$volume:/v:ro" busybox:1.36 sh -c "for p in \$(find /v -type d -name \"partition_*\"); do for d in \"\$p\"/*; do [ -d \"\$d\" ] && echo \"\$(basename \"\$d\")	\$($SUM)\"; done; done"
    done | awk -F"\t" "{s[\$1]+=\$2} END {for (k in s) print k \"\t\" s[k]}"
}
for system in neo4j pgage aeong; do
    folders "datasets/volumes/$system" | while IFS="	" read -r path bytes; do echo "$system	${path%%/*}	${path#*/}	data	$bytes"; done
done
# STGraph and Graphix: their data folders (the volumes section: an AsterixDB per dataset and size), otherwise their
# Docker volume (one AsterixDB, a dataverse per dataset and size)
if [ -d datasets/volumes/asterixdb ]; then
    folders "datasets/volumes/asterixdb" | while IFS="	" read -r path bytes; do echo "stgraph	${path%%/*}	${path#*/}	timeseries	$bytes"; done
else
    dataverses asterixdb-data | while IFS="	" read -r dv bytes; do
        case "$dv" in mimic_*|pems_*|smartbench_*|synth_*) echo "stgraph	${dv%%_*}	${dv#*_}	timeseries	$bytes" ;; esac
    done
fi
folders "datasets/dump" | while IFS="	" read -r path bytes; do
    case "${path%%/*}" in mimic|pems|smartbench|synth) echo "stgraph	${path%%/*}	${path#*/}	graph	$bytes" ;; esac
done
if [ -d datasets/volumes/graphix ]; then
    folders "datasets/volumes/graphix" | while IFS="	" read -r path bytes; do echo "graphix	${path%%/*}	${path#*/}	data	$bytes"; done
else
    dataverses graphix-data | while IFS="	" read -r dv bytes; do
        case "$dv" in *_graphix_*) echo "graphix	${dv%%_graphix_*}	${dv#*_graphix_}	data	$bytes" ;; esac
    done
fi
true
'

# Writes the storage_statistics.csv of a host ($1: as given, $2: its folder here)
storage() {
    local dest="$1" dir="$2" configuration profile systems rows
    # Its systems and configuration in machines.txt: only those systems (the data of others, e.g., of earlier runs with
    # another configuration, would be recorded with the wrong one); all of them if the machine is not there
    read -r systems profile <<< "$(awk -v h="${dest#*@}" '$1 == h || $1 ~ "@" h "$" { print $2, $3; exit }' "$REPO/evaluation/machines.txt" 2>/dev/null)"
    configuration=$([[ "$profile" == limited ]] && echo conf || { [[ "$profile" == free ]] && echo conf-free; })
    rows=$(ssh -n "$dest" "bash -c $(printf '%q' "$STORAGE_SCRIPT")") || { echo "WARNING: $dest storage not read"; return 1; }
    { echo "model,configuration,dataset,datasetSize,component,bytes,timestamp"
      [[ -n "$rows" ]] && echo "$rows" | awk -F'\t' -v c="$configuration" -v t="$(date +%s)000" -v only=",${systems:-},"  \
          'NF == 5 && (only == ",," || index(only, "," $1 ",")) { print $1 "," c "," $2 "," $3 "," $4 "," $5 "," t }'
    } > "$dir/storage_statistics.csv"
    echo "   storage: $(($(wc -l < "$dir/storage_statistics.csv") - 1)) row(s)"
}

# One collection: copies the results of every host (and with -s the size of its data), then merges them; fails if some
# host was not collected
collect() {
    local failed=() host name dest
    for host in "$@"; do
        name="${host#*@}"
        dest="$host"; [[ "$host" == *@* || -z "$USER_NAME" ]] || dest="$USER_NAME@$host"
        echo "== $dest"
        mkdir -p "$MACHINES/$name"
        scp -q -r "$dest:$REMOTE_DIR/results/." "$MACHINES/$name/" < /dev/null || { echo "WARNING: $dest not collected"; failed+=("$host"); }
        if $STORAGE; then storage "$dest" "$MACHINES/$name" || failed+=("$host"); fi
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
