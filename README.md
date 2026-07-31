# STGraph

[![Artifact reproduction](https://github.com/big-unibo/stgraph/actions/workflows/build.yml/badge.svg)](https://github.com/big-unibo/stgraph/actions/workflows/build.yml)

STGraph is a Kotlin/JVM library and evaluation project for spatio-temporal graphs whose topology is connected to time-series data.

## Artifact Reproduction

This repository is set up to fully reproduce the paper's artifact through GitHub Actions using the small dataset versions of SmartBench and MIMIC-IV. The workflow in `.github/workflows/build.yml` contains the complete executable recipe: it prepares the benchmark configuration, copies the short MIMIC subject/time-series mapping, downloads the SmartBench and MIMIC-IV small inputs, starts the Docker services, and runs the CI experiment suite.

The same steps can be run locally on Linux:

```bash
chmod +x ./gradlew
cp src/main/resources/test_config.example.yml src/main/resources/test_config.yml
cp src/main/resources/mimic-iv_subjectids_tsids_short.csv src/main/resources/mimic-iv_subjectids_tsids.csv
./downloadDataset.sh smartbench small.tar.gz
./downloadDataset.sh mimic 1692200.dump
docker compose up -d
sleep 120
./gradlew
```

The workflow is intentionally the authoritative reproduction path for the VLDB artifact evaluation badge: any reviewer can inspect the action to see every setup, data download, service startup, and experiment command needed to run the artifact.

The repository contains:

- core graph abstractions for nodes, relationships, properties, paths, labels, and time-series managers;
- in-memory, persistent in-memory, RocksDB, and AsterixDB-backed implementations;
- a Kotlin query model with temporal filtering, joins, spatial predicates, aggregation, and time-series pushdown;
- ingestion and query workloads for CI, SmartBench, MIMIC-IV, and synthetic datasets;
- Docker and helper scripts for AsterixDB/PostgreSQL-backed experiments;
- committed CSV outputs from selected experiment runs.

## Repository Structure

```text
.
|-- build.gradle.kts                    Gradle build, Kotlin/JVM 17 toolchain, dependencies, test settings
|-- settings.gradle.kts                 Gradle project name and build scan configuration
|-- Dockerfile                          Development/evaluation image that builds the project without tests
|-- docker-compose.yaml                 Local AsterixDB and PostgreSQL services used by tests/workloads
|-- downloadDataset.sh                  SmartBench and MIMIC-IV dataset downloader/extractor
|-- runTests.sh                         Convenience script for MIMIC and SmartBench benchmark tests
|-- datasets/
|   `-- init-mimic-pg.sh                PostgreSQL init script that restores a local MIMIC-IV dump
|-- results/                            Committed benchmark result CSVs
|-- src/main/deploy/asterixdb/          Cluster-oriented AsterixDB/data-source compose files
|-- src/main/kotlin/it/unibo/graph/
|   |-- interfaces/                     Graph, element, property, path, label, TS, and TSManager APIs
|   |-- query/                          Query steps, filters, comparisons, aggregation, and execution model
|   |-- inmemory/                       In-memory graph and time-series implementations
|   |-- rocksdb/                        RocksDB-backed graph and time-series implementations
|   |-- asterixdb/                      AsterixDB HTTP client, syntax parser, TS, and TS manager
|   `-- utils/                          Constants, encoders, time ranges, ports, and shared helpers
|-- src/main/kotlin/it/unibo/stats/
|   |-- Loader.kt                       Common ingestion/statistics helpers
|   |-- Querying.kt                     Query execution/statistics helpers
|   `-- TestConfig.kt                   YAML-driven benchmark matrix configuration
|-- src/main/resources/
|   |-- labels.yaml                     Domain label definitions loaded by Label.kt
|   |-- logback.xml                     Logging configuration
|   |-- test_config.example.yml         Example benchmark matrix; copy to test_config.yml before benchmark runs
|   |-- time_constraints.yaml           Temporal ranges used by benchmark queries
|   `-- mimic-iv_subjectids_tsids_short.csv
`-- src/test/kotlin/it/unibo/tests/
    |-- ci/                             Fast regression tests for graph, TS, temporal, and query behavior
    |-- smartbench/                     SmartBench ingestion/query workloads and loader
    |-- mimic/                          MIMIC-IV ingestion/query workloads and loaders
    `-- synth/                          Synthetic ingestion/query workloads, loaders, and query definitions
```

## Requirements

- JDK 17. The Gradle Foojay resolver can provision a matching toolchain when configured locally.
- Gradle wrapper from this repository: `./gradlew` on Linux/macOS or `.\gradlew.bat` on Windows.
- Docker, when running AsterixDB-backed tests or workloads.
- A large heap for benchmark runs. The Gradle test task sets `maxHeapSize = "64g"`.

The project currently uses Kotlin `2.3.20`, JUnit 6, RocksDB JNI, JTS, Jackson, SnakeYAML, PostgreSQL, and the Neo4j Java driver.

## Build

Linux/macOS:

```bash
./gradlew build
```

Windows PowerShell:

```powershell
.\gradlew.bat build
```

The default Gradle tasks are `clean`, `build`, and `check`.

To build the Docker image:

```bash
docker build -t stgraph .
```

## Running Tests

Run all tests:

```bash
./gradlew test
```

Run the fast CI/regression package:

```bash
./gradlew test --tests "it.unibo.tests.ci.*"
```

Start the local AsterixDB service before tests or workloads that use the AsterixDB backend:

```bash
docker compose up -d
./gradlew
```

Useful benchmark filters:

```bash
./gradlew test --tests it.unibo.tests.smartbench.TestSmartBenchIngestion
./gradlew test --tests it.unibo.tests.smartbench.TestSmartBenchQuery
./gradlew test --tests it.unibo.tests.mimic.TestMimicIngestion
./gradlew test --tests it.unibo.tests.mimic.TestMimicQuery
./gradlew test --tests it.unibo.tests.synth.TestSynthIngestion
./gradlew test --tests it.unibo.tests.synth.TestSynthQuery
```

`run.sh` runs the MIMIC and SmartBench ingestion/query tests in sequence.

## Configuration

Benchmark matrix execution is implemented in `src/main/kotlin/it/unibo/stats/TestConfig.kt`.

By default, benchmark helpers load:

```text
src/main/resources/test_config.yml
```

That file is intentionally local and is not present in a fresh checkout. Create it from the committed example before running benchmark workloads:

```bash
cp src/main/resources/test_config.example.yml src/main/resources/test_config.yml
```

The config defines:

- `datasets`: dataset names and sizes;
- `setups`: AsterixDB hosts, ports, and controller IPs;
- `defaults`: fallback modes, setups, and thread counts;
- `runs`: query benchmark matrices;
- `ingestion`: ingestion benchmark matrix and enable flag.

Temporal benchmark ranges are loaded from `src/main/resources/time_constraints.yaml`. Label metadata is loaded from `src/main/resources/labels.yaml`.

## Reproducing The Artifact Locally

The GitHub Actions workflow is the reference for artifact reproduction. It runs the small SmartBench input and the short MIMIC-IV input used for reproducible CI-scale experiments:

```bash
cp src/main/resources/test_config.example.yml src/main/resources/test_config.yml
cp src/main/resources/mimic-iv_subjectids_tsids_short.csv src/main/resources/mimic-iv_subjectids_tsids.csv
./downloadDataset.sh smartbench small.tar.gz
./downloadDataset.sh mimic 1692200.dump
docker compose up -d
sleep 120
./gradlew
```

This is the same sequence used by `.github/workflows/build.yml`.

## Datasets

Large datasets and graph dumps are not committed.

Workloads expect local paths such as:

```text
datasets/original/
datasets/dump/
results/
```

The dataset helper downloads inputs into `datasets/original/<dataset>` and extracts `.tar.gz` files:

```bash
./downloadDataset.sh smartbench small.tar.gz
./downloadDataset.sh mimic 1692200.dump
```

The helper accepts either a size, which is expanded to `<size>.tar.gz`, or an explicit filename:

```bash
./downloadDataset.sh smartbench small
./downloadDataset.sh smartbench small.tar.gz
```

Query benchmarks read graph dumps from:

```text
datasets/dump/<dataset>/<size>/
```

MIMIC-IV workloads expect `src/main/resources/mimic-iv_subjectids_tsids.csv`. For the reproducible small artifact run, create it from the committed short version:

```bash
cp src/main/resources/mimic-iv_subjectids_tsids_short.csv src/main/resources/mimic-iv_subjectids_tsids.csv
```

## Docker And Deployment Files

For local AsterixDB:

```bash
docker compose up -d
```

The local compose file also starts PostgreSQL for MIMIC-IV experiments. The PostgreSQL service mounts `datasets/original/mimic` as `/dump` and `datasets/` as `/docker-entrypoint-initdb.d`, so `datasets/init-mimic-pg.sh` restores the downloaded dump on container initialization.

For cluster/data-source experiments, see:

```text
src/main/deploy/asterixdb/data-source-single-machine-compose.yaml
src/main/deploy/asterixdb/data-source-two-machine-compose.yaml
```

Those deployment compose files assume external infrastructure such as an `asterix-network`, NFS-backed volumes, Redis, and host placement constraints.

## Query Model

Queries are built in Kotlin with `Step`, `Filter`, `Compare`, and `Aggregate`.

The query engine supports:

- graph pattern search over node/edge paths;
- traversal between graph nodes and time-series nodes through `HasTS`;
- temporal validity with `from`/`to` ranges;
- spatial comparisons through JTS geometries;
- joins across multiple patterns;
- grouping and aggregation over graph or time-series properties;
- `NAIVE` and `OPTIMIZED` query modes, where optimized mode pushes applicable filters and aggregations into time-series access.

See `src/test/kotlin/it/unibo/tests/ci/TestKotlin.kt` for compact executable examples of the core API.

## Results

Committed experiment outputs live under `results/`, including ingestion and query statistics CSVs.

Generated heavyweight datasets, graph dumps, local benchmark configs, and AsterixDB runtime data should remain outside version control.
