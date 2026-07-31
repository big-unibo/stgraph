#/bin/bash
set -xo

cp src/main/resources/test_config.example.yml src/main/resources/test_config.yml
cp src/main/resources/mimic-iv_subjectids_tsids_short.csv src/main/resources/mimic-iv_subjectids_tsids.csv
./downloadDataset.sh smartbench small.tar.gz
./downloadDataset.sh mimic 1692200.dump
docker compose up -d
sleep 150
./gradlew