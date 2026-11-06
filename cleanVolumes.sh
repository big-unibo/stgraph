#!/usr/bin/env bash
# Removes the data of the systems of the evaluation, so that the next run starts from empty databases (e.g., after
# switching between conf/ and conf-free/, whose partitions differ). For each compose file it removes the containers and
# their named volumes (asterixdb-data, graphix-data, neo4j-data, pgage-data), then the host data folders of the volumes
# section of src/main/resources/test_config.yml (e.g., datasets/volumes/neo4j, datasets/volumes/pgage).
# The PostgreSQL of MIMIC-IV (mimic-postgres) is kept unless --mimic: its data is the restored dump, slow to rebuild.
# Not touched: the datasets (datasets/original), the graph dumps of STGraph (datasets/dump), the results.
#
# Usage: ./cleanVolumes.sh [-y|--yes] [--mimic] [--dry-run]
set -euo pipefail
cd "$(dirname "$0")"

YES=false
MIMIC=false
DRY_RUN=false
for arg in "$@"; do
    case "$arg" in
        -y|--yes) YES=true ;;
        --mimic) MIMIC=true ;;
        --dry-run) DRY_RUN=true ;;
        -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $arg (see --help)" >&2; exit 1 ;;
    esac
done

run() {
    echo "+ $*"
    if ! $DRY_RUN; then "$@"; fi
}

ROOT_COMPOSE="docker-compose.yaml"
COMPOSE_FILES=("$ROOT_COMPOSE" evaluation/architectures/*/docker-compose.yaml)
CONFIG="src/main/resources/test_config.yml"

# The host data folders of the volumes section of test_config.yml: each path up to its first placeholder ({dataset},
# {size}), so every dataset and size. Only relative folders inside the repository, never the repository itself.
host_folders() {
    [[ -f "$CONFIG" ]] || return 0
    awk '
        /^volumes:/ { inside = 1; next }
        inside && /^[^ #]/ { exit }
        inside && /^  [A-Za-z0-9_-]+:/ {
            sub(/#.*/, ""); sub(/^  [A-Za-z0-9_-]+:[ \t]*/, ""); gsub(/[ \t"\047]/, "")
            if ($0 != "") print
        }' "$CONFIG" | while read -r path; do
        local folder="${path%%\{*}"
        folder="${folder%/}"
        if [[ -z "$folder" || "$folder" == /* || "$folder" == "." || "$folder" == *..* ]]; then
            echo "Skipped the volume folder \"$path\" of $CONFIG: only relative folders inside the repository" >&2
            continue
        fi
        echo "$folder"
    done
}

mapfile -t FOLDERS < <(host_folders)

echo "This removes:"
for f in "${COMPOSE_FILES[@]}"; do
    if [[ "$f" == "$ROOT_COMPOSE" ]] && ! $MIMIC; then
        echo "  - $f: the asterixdb container and its volume (mimic-postgres kept, --mimic to remove it too)"
    else
        echo "  - $f: its containers and volumes"
    fi
done
for folder in "${FOLDERS[@]}"; do echo "  - the folder $folder"; done
if ! $YES && ! $DRY_RUN; then
    read -r -p "Continue? [y/N] " answer
    [[ "$answer" == [yY]* ]] || { echo "Aborted."; exit 1; }
fi

for f in "${COMPOSE_FILES[@]}"; do
    project=$(docker compose -f "$f" config 2>/dev/null | sed -n 's/^name: //p' | head -n 1)
    if [[ "$f" == "$ROOT_COMPOSE" ]] && ! $MIMIC; then
        # Only the AsterixDB of STGraph: "down -v" would also drop the data of mimic-postgres (an anonymous volume)
        run docker compose -f "$f" rm --stop --force asterixdb
        volumes=(asterixdb-data)
    else
        run docker compose -f "$f" down --volumes --remove-orphans
        mapfile -t volumes < <(docker compose -f "$f" config --volumes 2>/dev/null)
    fi
    # down --volumes skips the volumes of containers already removed: remove them by their compose labels
    for volume in "${volumes[@]}"; do
        [[ -n "$volume" ]] || continue
        for id in $(docker volume ls -q --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.volume=$volume"); do
            run docker volume rm "$id"
        done
    done
done

# The host folders are written by the users of the containers (e.g., postgres 1000, neo4j 7474): removed from a
# container, so that no sudo is needed
for folder in "${FOLDERS[@]}"; do
    [[ -e "$folder" ]] || continue
    parent="$(cd "$(dirname "$folder")" && pwd)"
    run docker run --rm -v "$parent:/parent" busybox:1.36 rm -rf "/parent/$(basename "$folder")"
done

echo "Done."
