#!/bin/bash
set -ex

# The configuration: the example (CI), unless there is one already
[ -f src/main/resources/test_config.yml ] || cp src/main/resources/test_config.example.yml src/main/resources/test_config.yml
# The subjects and time series of MIMIC-IV: the "x" sizes (e.g., x1692200 of the CI) read the short list of the repository
# (mimic-iv_subjectids_tsids_short.csv), the other sizes the whole list, downloaded if missing
if [ ! -f src/main/resources/mimic-iv_subjectids_tsids.csv ]; then
  curl -k -fL --retry 3 -o "mimic-iv_subjectids_tsids.csv" "https://big.csr.unibo.it/downloads/stgraph/mimic/mimic-iv_subjectids_tsids.csv"
  mv mimic-iv_subjectids_tsids.csv src/main/resources
fi
./downloadDataset.sh smartbench xsmall.tar.gz
./downloadDataset.sh mimic x1692200.dump
./downloadDataset.sh pems xsmall.dump
docker compose up -d
sleep 150
./gradlew