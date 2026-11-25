#!/usr/bin/env bash
# The runs of STGraph alone (stgraphRuns of src/main/resources/test_config.yml: query modes, threads, scale-out), on the
# AsterixDB of docker-compose.yaml with a single folder for every dataset and size (each in its dataverse
# <dataset>_<size>), apart from the folders of the evaluation: first the ingestion, then the queries.
# Usage: evaluation/stgraph_runs.sh [ingestion|queries] (default: both)
# ASTERIXDB_DATA: the folder (default: datasets/volumes/asterixdb/runs); ASTERIXDB_CONF: the configuration (default:
# conf-free). The setups that are not local (c1, c2, c4) use their own clusters, deployed beforehand.
set -euo pipefail
cd "$(dirname "$0")/.."
export ASTERIXDB_DATA="${ASTERIXDB_DATA:-datasets/volumes/asterixdb/runs}"
mkdir -p "$ASTERIXDB_DATA"
docker compose up -d asterixdb
until curl -sf -o /dev/null --data-urlencode "statement=SELECT VALUE 1;" http://localhost:19002/query/service; do sleep 5; done
# --rerun: the tests run again even if Gradle finds them up to date
case "${1:-}" in ingestion | "") ./gradlew test --rerun --tests 'it.unibo.tests.*.Test*Ingestion' ;; esac
# The queries of the runs (not TestSynthQuery, which does not read stgraphRuns, nor those of the competitors)
case "${1:-}" in queries | "") ./gradlew test --rerun --tests '*.TestSmartBenchQuery' --tests '*.TestMimicQuery' --tests '*.TestPemsQuery' ;; esac
