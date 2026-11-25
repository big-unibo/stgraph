#!/bin/bash
set -ex

# The configuration: the example (CI), unless there is one already
[ -f src/main/resources/test_config.yml ] || cp src/main/resources/test_config.example.yml src/main/resources/test_config.yml
# The subjects and time series of MIMIC-IV: xsmall (the CI) reads the short list of the repository
# (mimic-iv_subjectids_tsids_short.csv), the other sizes the whole list, downloaded if missing
if [ ! -f src/main/resources/mimic-iv_subjectids_tsids.csv ]; then
  curl -k -fL --retry 3 -o "mimic-iv_subjectids_tsids.csv" "https://big.csr.unibo.it/downloads/stgraph/mimic/mimic-iv_subjectids_tsids.csv"
  mv mimic-iv_subjectids_tsids.csv src/main/resources
fi
# The xsmall datasets (the CI), downloaded if missing
[ -d datasets/original/smartbench/xsmall ] || ./downloadDataset.sh smartbench xsmall.tar.gz
[ -f datasets/original/mimic/xsmall.dump ] || ./downloadDataset.sh mimic xsmall.dump
[ -f datasets/original/pems/xsmall.dump ] || ./downloadDataset.sh pems xsmall.dump
# AsterixDB on its Docker volume (one for every dataset and size, apart from the folders of the evaluation; ASTERIXDB_DATA:
# another one) and the PostgreSQL of MIMIC-IV and PeMS: waits until both answer (the dumps restored)
docker compose up -d
until curl -sf -o /dev/null --data-urlencode "statement=SELECT VALUE 1;" http://localhost:19002/query/service; do sleep 5; done
until docker compose exec -T mimic-postgres pg_isready -q -h localhost -U postgres; do sleep 5; done
./gradlew