#/bin/bash
set -xo

./gradlew test --tests it.unibo.tests.mimic.TestMimicIngestion
./gradlew test --tests it.unibo.tests.mimic.TestMimicQuery
# ./gradlew test --tests it.unibo.tests.smartbench.TestSmartBenchIngestion
# ./gradlew test --tests it.unibo.tests.smartbench.TestSmartBenchQuery

