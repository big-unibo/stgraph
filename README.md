# STGraph

[![Artifact reproduction](https://github.com/big-unibo/stgraph/actions/workflows/build.yml/badge.svg)](https://github.com/big-unibo/stgraph/actions/workflows/build.yml)

STGraph is multistore for spatio-temporal graphs including time-series. Its implementation and evaluation are based on Kotlin.

## Artifact Reproduction

The paper's artifact is reproduced in two ways:

- [Reproducing The Artifact Locally](#reproducing-the-artifact-locally): `./run.sh`, the recipe of GitHub Actions (`.github/workflows/build.yml`), checks that STGraph works on compact SmartBench and MIMIC-IV inputs. It is the reference path for the VLDB artifact evaluation badge.
- [Reproducing The Results](#reproducing-the-results): `./gradlew evaluate`, the evaluation of STGraph and of its competitors.

The repository contains:

- core graph abstractions for nodes, relationships, properties, paths, labels, and time-series managers;
- in-memory, persistent in-memory, RocksDB, and AsterixDB-backed implementations;
- a Kotlin query model with temporal filtering, joins, spatial predicates, aggregation, and time-series pushdown;
- ingestion and query workloads for CI, SmartBench, MIMIC-IV, and synthetic datasets;
- Docker deploys of AsterixDB and of the PostgreSQL of MIMIC-IV, and the evaluation against the competitors (`evaluation/`);
- committed CSV outputs from selected experiment runs.

## Repository Structure

```text
.
|-- build.gradle.kts                    Gradle build, Kotlin/JVM 17 toolchain, dependencies, test settings
|-- settings.gradle.kts                 Gradle project name and build scan configuration
|-- Dockerfile                          Development/evaluation image that builds the project without tests
|-- docker-compose.yaml                 Local AsterixDB and PostgreSQL services used by tests/workloads
|-- downloadDataset.sh                  SmartBench and MIMIC-IV dataset downloader/extractor
|-- run.sh                              CI/artifact reproduction script
|-- datasets/
|   `-- init-mimic-pg.sh                PostgreSQL init script that restores a local MIMIC-IV dump
|-- evaluation/                         SmartBench evaluation of the competitors: deploys, results, notebooks
|-- results/                            Committed benchmark result CSVs (results/paper: data of the article)
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
`-- src/test/kotlin/it/unibo/tests/
    |-- ci/                             Fast regression tests for graph, TS, temporal, and query behavior
    |-- evaluation/                     Evaluation against the competitors (./gradlew evaluate)
    |-- smartbench/                     SmartBench workloads and loaders (STGraph and competitors)
    |-- mimic/                          MIMIC-IV ingestion/query workloads and loaders
    |-- systems/                        Connections and timed queries of the competitors (Neo4j, PGAge, Graphix)
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

`run.sh` runs the artifact reproduction sequence used by GitHub Actions (see [Reproducing The Artifact Locally](#reproducing-the-artifact-locally)).

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
- `defaults`: fallback modes, setups, and thread counts (and the parameters of the evaluation);
- `specificRuns.runs`: the runs to execute; the tests ingest and query STGraph on the datasets of the runs with `stgraph` among their `approaches`, `./gradlew evaluateSpecificRuns` executes all of them;
- `evaluation`: every combination of its parameters, `./gradlew evaluate` (see [Reproducing The Results](#reproducing-the-results)).

Temporal benchmark ranges are loaded from `src/main/resources/time_constraints.yaml`. Label metadata is loaded from `src/main/resources/labels.yaml`.

## Reproducing The Artifact Locally

`./run.sh` checks that STGraph works, as GitHub Actions does (`.github/workflows/build.yml`). On Linux (or Git Bash on Windows):

```bash
chmod +x ./gradlew *.sh
./run.sh
```

It:

1. creates `src/main/resources/test_config.yml` from `test_config.example.yml` (SmartBench `xsmall`, MIMIC-IV `x1692200`, synthetic `full`) and the list of MIMIC-IV subjects from `mimic-iv_subjectids_tsids_short.csv`;
2. downloads SmartBench `xsmall` and the MIMIC-IV `x1692200` dump;
3. starts `docker-compose.yaml`: the AsterixDB of STGraph (configuration: `evaluation/architectures/stgraph/conf/cc.conf`) and the PostgreSQL of MIMIC-IV, which restores the downloaded dump on initialization (`datasets/init-mimic-pg.sh`);
4. runs `./gradlew` (`clean`, `build`, `check`): the regression tests of `it.unibo.tests.ci`, and ingestion and queries of STGraph on the synthetic dataset, SmartBench `xsmall` and MIMIC-IV `x1692200`; on these two sizes, the SmartBench and MIMIC-IV queries check their cardinalities.

## Reproducing The Results

The evaluation compares STGraph with Neo4j, PGAge (PostgreSQL with TimescaleDB, PostGIS and Apache AGE), Graphix, AeonG and HyGraph on SmartBench. Every system runs in a container limited to one core (for AeonG and HyGraph, shared with their client) and is configured with the least parallelism it allows. One system at a time, the evaluation downloads the data, starts the system, loads the data, runs the queries on the time windows of `time_constraints.yaml` and stops the system:

```bash
./gradlew evaluate               # every combination of the evaluation section of test_config.yml
./gradlew evaluateSpecificRuns   # the runs of the specificRuns section
```

The results are appended to `results/` and plotted by `evaluation/postprocessing/STGraph.ipynb`. The setup of every system (versions, configuration files, data model and indexes, parallelism) and the reproduction steps are described in [`evaluation/README.md`](evaluation/README.md).

## Datasets

Datasets and graph dumps are not committed; they have been uploaded into a specific cloud storage and will be accordingly downloaded during the evaluation phase following the size parameter specified in test setup.

Workloads expect local paths such as:

```text
datasets/original/
datasets/dump/
results/
```

Complete [SmartBench](https://damslabumbc.github.io/publication/vldb2-2020/) datasets should be generated with the original SmartBench generator. A simplified generation procedure containing also the generation parameters is provided in [this repository](https://github.com/ManuelePasini/smart_benchmark). To generate data, update the .env DATASET_SIZE variable to the desired size and run "docker compose up" from the project directory.

Full [MIMIC-IV](https://physionet.org/content/mimiciv/) must be obtained from the official PhysioNet page because access requires satisfying its privacy and credentialing constraints before download.

The dataset helper downloads inputs into `datasets/original/<dataset>` and extracts `.tar.gz` files:

```bash
./downloadDataset.sh smartbench xsmall.tar.gz
./downloadDataset.sh mimic x1692200.dump
```

The evaluation downloads the inputs it needs by itself (also the Cypher dumps of Neo4j, `smartbench/neo4j <size>`).

Query benchmarks read graph dumps from:

```text
datasets/dump/<dataset>/<size>/
```

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

Committed experiment outputs live under `results/`, including ingestion and query statistics CSVs. `results/paper/` archives the experimental data of the article; the notebook `evaluation/postprocessing/STGraph.ipynb` plots either the current results or that archive (see `evaluation/README.md`).

Generated heavyweight datasets, graph dumps, local benchmark configs, and AsterixDB runtime data should remain outside version control.
