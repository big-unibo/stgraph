#!/bin/bash

set -e 
EVALUATION_SYSTEMS=$(grep -v '^#' .env | grep '^EVALUATION_SYSTEMS=' | cut -d '=' -f2-)
IFS=',' read -r -a SYSTEMS <<< "$EVALUATION_SYSTEMS"

echo "Running STGraph evaluation experiments..."

if [ ! -f .env ]; then
    cp .env.example .env
    echo ".env file missing, created from .env.example. Please update it with your configuration in case of different parameters. Otherwise, re-run this script to continue."
    exit 1
fi

run_evaluation() {
    local system=$1
    local compose_file="architectures/docker-compose_${system}.yaml"
    
    echo "Starting $system container..."
    docker compose -f "$compose_file" up
    docker compose -f "$compose_file" down
    echo "Evaluation of $system completed, results available in the results/${system} directory."
}

for system in "${SYSTEMS[@]}"; do
    run_evaluation "$system"
done

echo "All evaluations completed."