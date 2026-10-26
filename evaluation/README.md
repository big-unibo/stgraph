# STGraph evaluation

This folder holds the evaluation of STGraph and of its competitors on SmartBench, for the paper:

- Francia, Matteo, Golfarelli Matteo, and Pasini Manuele. "STGraph - A something for someone". Submitted to **somewhere** (2025).

It was the STGraph-evaluation repository, imported with its history. Ingestion and queries of STGraph, Neo4j, PGAge and
Graphix are Kotlin code of this repository; AeonG and HyGraph run in their own containers.

## Organization

| Path | Content |
|------|---------|
| `evaluation/architectures/<system>/` | Deploy of each system: `docker-compose.yaml`, `Dockerfile`, configuration (`conf/`) |
| `src/test/kotlin/it/unibo/tests/evaluation/` | The evaluation (`Evaluation.kt`): configuration, download, deploy, progress log |
| `src/test/kotlin/it/unibo/tests/systems/` | Connections and timed queries of Neo4j, PGAge and Graphix, shared by SmartBench and MIMIC-IV |
| `src/test/kotlin/it/unibo/tests/smartbench/SmartBenchEvaluation.kt`, `src/test/kotlin/it/unibo/tests/synth/SynthEvaluation.kt` | Ingestion and queries of each system on SmartBench, and of STGraph on the synthetic dataset (on `DatasetEvaluation.kt`) |
| `src/test/kotlin/it/unibo/tests/smartbench/loaders/` | One loader per system on a shared skeleton (`SmartBenchLoader.kt`) |
| `src/test/resources/smartbench/queries/<system>/` | The queries of the competitors, as templates; `INCONSISTENCIES.txt`: their differences from STGraph and the differences of the loaders from the original Python code |
| `results/` | The results: `dt_graph/` (Kotlin runs), `aeong/`, `hygraph/`, and `paper/` (the data of the article, not updated by the runs) |
| `evaluation/postprocessing/STGraph.ipynb` | The figures, in `evaluation/figures/current/` (`evaluation/figures/paper/` for the data of the article) |
| `evaluation/stats.ipynb` | Exploration of the MIMIC-IV subjects and time series |

## Systems

Every system runs in a container limited to one core (`cpus: 1`): the database of STGraph, Graphix, Neo4j and PGAge,
whose client (the evaluation) runs outside; the whole evaluation of AeonG and HyGraph, client included. The JVMs see one
processor and use a single-threaded garbage collector. Each system is configured to use as little parallelism as it
allows.

| System | Version | Deploy | Configuration |
|--------|---------|--------|---------------|
| STGraph | in-memory graph store + AsterixDB 0.9.9 (Java 21, image `mp4sini/asterixdb`) | `docker-compose.yaml` (repository root), service `asterixdb` | `evaluation/architectures/stgraph/conf/cc.conf` |
| Graphix | 0.4.1-alpha, on AsterixDB 0.9.9-SNAPSHOT (Java 21) | `evaluation/architectures/graphix/` | `evaluation/architectures/graphix/conf/cc.conf` |
| Neo4j | 5.26, Community edition (image `neo4j:5.26`) | `evaluation/architectures/neo4j/` | `evaluation/architectures/neo4j/conf/neo4j.conf` |
| PGAge | PostgreSQL 16.4, TimescaleDB 2.17.0, PostGIS 3, Apache AGE (image `mp4sini/pgage`) | `evaluation/architectures/pgage/` | `evaluation/architectures/pgage/conf/postgresql.conf` |
| AeonG | image `mp4sini/aeong` (AeonG built from source) | `evaluation/architectures/aeong/` | its container (`t_smarbench_test.sh`) |
| HyGraph | `hygraph-core` 0.3.9.2, Python 3.12 | `evaluation/architectures/hygraph/` | its container (`scripts/run_evaluation.sh`) |

Graphix cannot run on the AsterixDB 0.9.9 release of STGraph: it needs Hyracks and Algebricks changes that never reached
a release (see [graphix-asterixdb/mirror](https://github.com/graphix-asterixdb/mirror)), and its extension does not
load on 0.9.9. Both AsterixDB versions store the data in the same default format (`ROW`, `snappy` compression), without
replication.

### Data model and indexes

| System | Graph | Time series | Indexes                                                                                                                                                                             |
|--------|-------|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| STGraph | in-memory graph store: one node per entity and per time series | AsterixDB dataset of the events | primary key `(tsid, _id)` of the events; R-tree on the event location, built after the ingestion                                                                                    |
| Graphix | one dataset per collection, and `TimeSeries` with one record per series; graph `SmartBench` defined as views over them and over `Observations`, with the model of STGraph: sensor `-[hasTemperature]->` series (`Temperature`) `-[hasTS]->` events (`TemperatureEvent`) | dataset `Observations`: the events of STGraph (same type and records), loaded through the same socket data feed; the vertices `TemperatureEvent` and the edges `hasTS` | primary key `id` of every entity dataset and of `TimeSeries`; primary key `(tsid, _id)` of `Observations`, as STGraph, used by the joins between vertices and edges (`graphix.evaluation.prefer-indexnl=true` and `graphix.evaluation.minimize-joins=true` in its `cc.conf`, see Query planning below); R-tree on `Observations.location`, built after the ingestion |
| Neo4j | the statements of the Cypher dump (`graph.cypher`) | one `Temperature` node per event, linked to its sensor | range index on `Temperature.timestamp`, built after the ingestion; the label and relationship-type lookup indexes that Neo4j creates by itself |
| PGAge | AGE graph `smartbench_graph`: one vertex per entity, and one per series (its first event) | TimescaleDB hypertable `public.measurements` | primary key `(time, sensor_id, type)` and the TimescaleDB index on `time`; GiST on `location`, built after the ingestion; none on the graph                                         |
| AeonG, HyGraph | their original evaluation | their original evaluation | their original evaluation                                                                                                                                                           |

The indexes built after the ingestion are timed as index time.

### Parallelism

| System | What limits it |
|--------|----------------|
| STGraph | one node controller with one I/O device, i.e., one data partition; `compiler.parallelism=1`, `compiler.sort.parallel=false`; one I/O worker per partition (`io.workers.per.partition=1`); one flush and one merge at a time per partition (`storage.max.concurrent.flushes.per.partition=1`, `storage.max.concurrent.merges.per.partition=1`); the query engine on the threads of the configuration (1) |
| Graphix | the AsterixDB configuration of STGraph (same keys in its `cc.conf`, plus the Graphix extension, see below); one data feed and one query at a time |
| Neo4j | Community edition: each query runs on one thread (no parallel runtime); `server.cypher.parallel.worker_limit=1`; one Bolt thread (`server.bolt.thread_pool_min_size=1`, `server.bolt.thread_pool_max_size=1`) and one HTTP worker thread (`server.threads.worker_count=1`); no page-cache prefetchers (`server.memory.pagecache.scan.prefetchers=0`); JVM with `-XX:ActiveProcessorCount=1`, `-XX:ParallelGCThreads=1`, `-XX:ConcGCThreads=1`; one session at a time |
| PGAge | no parallel workers (`max_parallel_workers_per_gather=0`, `max_parallel_workers=0`, `max_parallel_maintenance_workers=0`); `max_worker_processes=2` (TimescaleDB launcher and one background worker), `timescaledb.max_background_workers=1`, `autovacuum_max_workers=1`; no prefetching (`effective_io_concurrency=0`, `maintenance_io_concurrency=0`); the same parallel-worker settings on the session of the loader and of the queries; one connection at a time |
| AeonG, HyGraph | the container limited to one core, shared with their client; the other settings of their original evaluation scripts |

Memory: the defaults of each system, except the page cache of Neo4j (512 MB) and the `work_mem` and
`maintenance_work_mem` of PostgreSQL, from the original evaluation.

Query planning: the defaults of each system, except Graphix. Its `cc.conf` has the AsterixDB configuration of STGraph
plus the settings of the Graphix extension: the pattern semantics of its original configuration
(`graphix.semantics.pattern=isomorphism`, `graphix.semantics.navigation=no-repeat-anything`) and
two evaluation settings of Graphix, both default `false`, which the AsterixDB of STGraph does not have:
`graphix.evaluation.prefer-indexnl=true` (Graphix places index nested-loop hints on the joins between vertices and
edges, and so reads the events of a series through the primary key `(tsid, _id)` of `Observations`) and
`graphix.evaluation.minimize-joins=true` (no join between an edge and a vertex defined on the same records: an event
is read once, as the edge `hasTS` and as the vertex `TemperatureEvent`). By default every join is a hash join on
scans, and the cost-based optimizer of Graphix does not run (see `INCONSISTENCIES.txt`). The queries have no hints.

### Measurement

| Phase | What is timed |
|-------|---------------|
| Ingestion: graph | writing the entities (and, for STGraph and Graphix, the time-series nodes/records) to the system; reading and parsing the input files is not timed |
| Ingestion: time series | sending the events, one series at a time per worker, as STGraph (`SmartBenchDataLoader`): the flush of each series is not timed; after its last series, each worker waits until its last event is readable, without the last read; the time of the slowest worker |
| Ingestion: index | closing the data feeds (STGraph, Graphix) and building the indexes of the table above, until they are usable (Neo4j populates its index in the background: it is awaited) |
| Query | the whole call, from the request to the whole result in the client: for STGraph, the navigation of the graph and its queries on AsterixDB; for Neo4j, PGAge and Graphix, compilation, execution and transfer of the result, on a connection already open |

Every Kotlin loader shares these boundaries (`SmartBenchLoader.kt`). One difference is forced by the engine of Graphix:
its AsterixDB stores the last, partial frame of a data feed only when the feed stops (the AsterixDB of STGraph stores
it with the feed open), so each Graphix worker closes its feed before waiting for its last event, once AsterixDB has
read its whole stream; the close is still index time. The differences of the queries and of the loaders from STGraph
and from the original Python code are listed in `src/test/resources/smartbench/queries/INCONSISTENCIES.txt`.

### Endpoints

| System | Endpoint |
|--------|----------|
| STGraph | the setups of `test_config.yml` (AsterixDB query API on `localhost:19002`, data feeds on 10001-11000) |
| Graphix | query API on `localhost:29002`, data feeds on 11001-11010 (host ports in `evaluation/architectures/graphix/.env`, from its `.env.example`) |
| Neo4j | `bolt://localhost:7687`, no authentication |
| PGAge | `localhost:5432`, database `smartbench_eval`, graph `smartbench_graph` |

## Reproducing the results

The runs are configured in `src/main/resources/test_config.yml` (copied from `test_config.example.yml`):

| Section | Content |
|---------|---------|
| `datasets`, `setups` | the sizes of each dataset (for MIMIC-IV, also the PostgreSQL the data is read from: `postgres_endpoint`, `port`, `database`, `user`, `password`); the AsterixDB setups of STGraph |
| `defaults` | the parameters common to all the runs: modes, setups and threads of STGraph, `ingestion.iterations`, `queries` (`iterations`, `selectivities`, `ids`), `download`, `deploy` |
| `specificRuns.runs` | the runs to execute, each one with its `approaches` (systems), `datasets`, optionally `sizes` and any key of `defaults`; the tests of STGraph (CI) read the runs with `stgraph` |
| `evaluation` | `systems`, `datasets`, optionally `sizes` and any key of `defaults`, all combined |

The sizes default to `datasets.<dataset>.sizes`, the time windows are the ones of
`src/main/resources/time_constraints.yaml`. From the repository root:

| Task | Section | What it runs |
|------|---------|--------------|
| `./gradlew evaluate` | `evaluation` | every combination of its parameters |
| `./gradlew evaluateSpecificRuns` | `specificRuns` | its runs, one after the other |

For each dataset, size and system, one system at a time, a run:
1. downloads the missing data (`smartbench <size>`: JSON dataset of STGraph, PGAge and Graphix; `smartbench/neo4j <size>`:
   Cypher dumps of Neo4j), from the same URLs as `downloadDataset.sh`;
2. starts the system (`docker compose up -d`) and waits until it answers;
3. loads the data (`ingestion.iterations` times) and runs every query `queries.iterations` times on every time window of
   each selectivity;
4. stops the system (`docker compose stop`: the containers and their data are kept).

The datasets are SmartBench (every system) and the synthetic dataset (STGraph, with the loader and the queries of its
tests; `queries.ids` selects their families, e.g., `SynthFilter`). STGraph runs its reference loader (`SmartBenchDataLoader`) and queries; `stgraph-std` loads the data through the shared
loader skeleton (`SmartBenchSTGraph`), to compare the measurement boundaries. AeonG and HyGraph load and query inside
their container, with the parameters passed as environment variables and the sizes of the original evaluation (small,
medium -> large, large -> big). The progress (parameters, ingestion and queries of each system) is printed with the
prefix `[evaluation]`; a system that fails is reported at the end, the others go on.

Every run appends one row per ingestion and per query to `results/`, with the system in the `model` column. The CI does
not run the evaluation: it runs the tests of STGraph through `run.sh`.

The large sizes need at least 300 GB of storage in the Docker data folder.

### Scale-out

Scale-out concerns STGraph only, on an AsterixDB cluster deployed following the
[official documentation](https://asterixdb.apache.org/docs/0.9.9/ncservice.html). Its setup in `test_config.yml` gives
the address of the cluster controller (`host`, and `port` of its query API) and the addresses of the node controllers
(`controllerIPs`), which receive the data feeds (`c1` in `test_config.example.yml`); the runs use it through `setups`,
with `systems: [ "stgraph" ]`. A run starts and stops the local setup and uses a cluster as it is. `numMachines` in the
results is the number of node controllers.

### Figures

`evaluation/postprocessing/STGraph.ipynb` plots `results/` (`archive = None`) or the data of the article
(`archive = "paper"`). It needs Python > 3.12, a Jupyter kernel, the dependencies of `evaluation/requirements.txt` and
the Times New Roman font.

## References

- [STGraph](https://github.com/w4bo/dt-graph)
- [AeonG](https://github.com/ManuelePasini/AeonG)
- [HyGraph](https://github.com/dbs-leipzig/HyGraph-package)
- [Graphix](https://github.com/graphix-asterixdb/package)
- [SmartBench](https://github.com/ManuelePasini/smart_benchmark)
