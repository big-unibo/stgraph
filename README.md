# STGraph

STGraph is a Kotlin/JVM library and evaluation project for querying spatio-temporal graphs whose graph topology is connected to time-series data.

The codebase contains:

- graph abstractions for nodes, relationships, properties, paths, and time-series managers;
- in-memory, persistent in-memory, RocksDB, and AsterixDB-backed implementations;
- a query engine with temporal filtering, joins, spatial predicates, aggregation, and time-series pushdown;
- loaders and JUnit workloads for synthetic, SmartBench, and MIMIC-IV datasets;
- Docker and scripts used to run AsterixDB-backed evaluations.

## Repository Structure

```text
src/main/kotlin/it/unibo/graph/
  interfaces/        Core graph, element, property, path, TS, and TSManager APIs
  query/             Query model, filters, comparisons, aggregation, and execution
  inmemory/          In-memory graph and time-series implementations
  rocksdb/           RocksDB-backed graph and time-series implementations
  asterixdb/         AsterixDB HTTP client, syntax parser, and TS managers
  utils/             Constants, labels, encoding helpers, and shared utilities

src/main/kotlin/it/unibo/stats/
  Loader.kt          Common ingestion and benchmark statistics helpers
  Querying.kt        Query execution/statistics helpers
  TestConfig.kt      YAML-driven benchmark matrix configuration

src/main/resources/
  config.properties          AsterixDB and ingestion defaults
  test_config.yml            Active benchmark matrix
  test_config.example.yml    Small example configuration
  labels.yaml                Domain label definitions
  time_constraints.yaml      Temporal ranges used by benchmark queries

src/test/kotlin/it/unibo/tests/
  ci/               Fast CI/regression tests for graph, TS, and query behavior
  smartbench/       SmartBench ingestion and query workloads
  mimic/            MIMIC-IV ingestion and query workloads
  synth/            Synthetic ingestion and query workloads

scripts/           Dataset download and evaluation helper scripts
results/           Committed experiment output CSVs
```

## Requirements

- JDK 17
- Gradle wrapper from this repository (`./gradlew` or `gradlew.bat`)
- Docker, when running tests or workloads that use AsterixDB
- Enough local memory for benchmark runs. The Gradle test task is configured with a `64g` max heap.

The project uses Kotlin `2.3.20` and targets JVM 17.

## Build

On Linux/macOS:

```bash
./gradlew build
```

On Windows PowerShell:

```powershell
.\gradlew.bat build
```

The default Gradle task is `clean build check`.

## Running Tests

The CI suite starts AsterixDB and runs the `it.unibo.tests.ci` tests:

```bash
docker compose -f docker-compose_asterix.yaml up -d
./wait-for-it.sh localhost:19006 -t 30
./gradlew test --tests "it.unibo.tests.ci*"
```

Individual benchmark suites can be launched with JUnit filters:

```bash
./gradlew test --tests it.unibo.tests.smartbench.TestSmartBenchIngestion
./gradlew test --tests it.unibo.tests.smartbench.TestSmartBenchQuery
./gradlew test --tests it.unibo.tests.mimic.TestMimicIngestion
./gradlew test --tests it.unibo.tests.mimic.TestMimicQuery
./gradlew test --tests it.unibo.tests.synth.TestSynthIngestion
./gradlew test --tests it.unibo.tests.synth.TestSynthQuery
```

The `runTests.sh` script currently runs the MIMIC ingestion/query tests and keeps the SmartBench commands commented as examples.

## Configuration

Benchmark runs are driven by `src/main/resources/test_config.yml`.

The configuration defines:

- `datasets`: available dataset names and sizes;
- `setups`: AsterixDB host/controller IP layouts;
- `defaults`: default query modes, setups, and thread counts;
- `runs`: query benchmark matrices;
- `ingestion`: ingestion benchmark matrix and enable flag.

Use `src/main/resources/test_config.example.yml` as a smaller starting point when running locally.

`src/main/resources/config.properties` contains AsterixDB defaults such as the dataverse name, controller port, and ingestion attribute names.

## Datasets

Large datasets are not committed to the repository.

Expected local dataset/output paths include:

```text
datasets/original/
datasets/dump/
results/
```

The helper script downloads and extracts SmartBench archives:

```bash
./scripts/download_dataset.sh small
```

Dataset-dependent tests expect the dataset files and graph dumps referenced by `test_config.yml` to exist locally.

## Docker

Start only AsterixDB:

```bash
docker compose -f docker-compose_asterix.yaml up -d
```

Run the combined AsterixDB + STGraph compose file:

```bash
cp .env.example .env
docker compose up
```

The STGraph service mounts `./results` into the container so benchmark CSVs can be collected on the host.

## Query Model

Queries are built in Kotlin with `Step`, `EdgeStep`, `Filter`, `Compare`, and `Aggregate`.

The query engine supports:

- graph pattern search over node/edge paths;
- traversal between graph nodes and time-series nodes through `HasTS`;
- temporal validity with `from`/`to` ranges;
- spatial comparisons through JTS geometries;
- joins across multiple patterns;
- grouping and aggregation over graph or time-series properties;
- `NAIVE` and `OPTIMIZED` query modes, where the optimized mode pushes applicable filters and aggregations into time-series access.

See `src/test/kotlin/it/unibo/tests/ci/TestKotlin.kt` for compact executable examples of the core API.

## Results

Experiment outputs are committed under `results/`, including ingestion and query statistics CSVs for DTGraph/STGraph evaluations.

Generated heavyweight datasets, dumps, and local AsterixDB data should remain outside version control.
