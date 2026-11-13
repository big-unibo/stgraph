#!/bin/bash
set -ex

cp src/main/resources/test_config.example.yml src/main/resources/test_config.yml
# The subjects and time series of MIMIC-IV to load: ./run.sh, the short local list (CI); ./run.sh <any argument>, the
# whole list, downloaded
if [ $# -eq 0 ]; then
  cp src/main/resources/mimic-iv_subjectids_tsids_short.csv src/main/resources/mimic-iv_subjectids_tsids.csv
else
  curl -k -fL --retry 3 -o "mimic-iv_subjectids_tsids.csv" "https://big.csr.unibo.it/downloads/stgraph/mimic/mimic-iv_subjectids_tsids.csv"
  mv mimic-iv_subjectids_tsids.csv src/main/resources
fi
./downloadDataset.sh smartbench xsmall.tar.gz
./downloadDataset.sh mimic x1692200.dump
docker compose up -d
sleep 150
./gradlew