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
| `src/test/resources/queries/<dataset>/<system>/` | The queries of the competitors, as templates; `INCONSISTENCIES.txt`: their differences from STGraph and the differences of the loaders from the original Python code |
| `results/` | The results: `dt_graph/` (Kotlin runs), `aeong/`, `hygraph/`; the archives, not updated by the runs: `paper/` (the data of the article) and `rebuttal/` (the data of the article and the runs of the rebuttal, from the repository STGraph-evaluation) |
| `evaluation/postprocessing/STGraph.ipynb` | The figures and tables, of the archive set by `archive` (default: `rebuttal`; `None` for the current runs), in `evaluation/figures/<archive>/` (`current/` for the current runs) |
| `evaluation/stats.ipynb` | Exploration of the MIMIC-IV subjects and time series |
| `evaluation/.devcontainer/` | The devcontainer of the notebooks (Python, `evaluation/requirements.txt`), opening the folder `evaluation` (the whole repository is mounted in `/home`); `.devcontainer/` in the repository root is the one of STGraph (Java, Gradle) |

## Systems

Every system runs in a container: the database of STGraph, Graphix, Neo4j and PGAge, whose client (the evaluation)
runs outside, without CPU or memory limits unless the `resources` section of `test_config.yml` sets them (the
single-core comparison of the paper: `cpus: 1` and `conf/` for each; see below); the whole evaluation of AeonG and
HyGraph, client included, in their container, with no CPU or memory limit either (unless the `resources` section sets
them). By default each system runs with its free configuration (`conf-free/`, its defaults); with `conf/`, it uses as
little parallelism as it allows. The two configurations of a system differ only in parallelism (and in the partitions
of AsterixDB and Graphix); the JVMs see the CPUs of their container, with no cap of their own.

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
| Graphix | one dataset per collection (the WKT locations stored as 2D geometries, while STGraph keeps them as strings, see `INCONSISTENCIES.txt`), and `TimeSeries` with one record per series; graph `SmartBench` defined as views over them and over `Observations`, with the model of STGraph: sensor `-[hasTemperature]->` series (`Temperature`) `-[hasTS]->` events (`TemperatureEvent`) | dataset `Observations`: the events of STGraph (same type and records), loaded through the same socket data feed; the vertices `TemperatureEvent` and the edges `hasTS` | primary key `id` of every entity dataset and of `TimeSeries`; primary key `(tsid, _id)` of `Observations`, as STGraph, used by the joins between vertices and edges (`graphix.evaluation.prefer-indexnl`, see Query planning below); R-tree on `Observations.location`, built after the ingestion |
| Neo4j | the JSON dataset of the other systems: one node per entity, one relationship `has<Field>` per reference (through the driver on xsmall and small, in batches of 10 events; CSV files imported with `neo4j-admin` from medium on, and on every size in the CI, where `CI=true`) | one `Temperature` node per event, linked to its sensor | range index on `Temperature.timestamp`, built after the ingestion; the label and relationship-type lookup indexes that Neo4j creates by itself |
| PGAge | AGE graph `smartbench_<size>`: one vertex per entity, and one per series (its first event) | TimescaleDB hypertable `smartbench_<size>.measurements` (in the schema of the graph): `INSERT`s in batches of 10 rows on xsmall and small, `COPY` in batches of 20,000 from medium on and on every size in the CI (`CI=true`) | the TimescaleDB index on `time` (no primary key, as the range index of Neo4j) and GiST on `location`, both built after the ingestion; none on the graph                                         |
| AeonG, HyGraph | their original evaluation | their original evaluation | their original evaluation                                                                                                                                                           |

The indexes built after the ingestion are timed as index time.

### Parallelism

| System | What limits it |
|--------|----------------|
| STGraph | one node controller with one I/O device, i.e., one data partition; `compiler.parallelism=1`, `compiler.sort.parallel=false`; one I/O worker per partition (`io.workers.per.partition=1`); one flush and one merge at a time per partition (`storage.max.concurrent.flushes.per.partition=1`, `storage.max.concurrent.merges.per.partition=1`); the query engine on the threads of the configuration (1) |
| Graphix | the AsterixDB configuration of STGraph (same keys in its `cc.conf`, plus the Graphix extension, see below); one data feed and one query at a time |
| Neo4j | Community edition: each query runs on one thread (no parallel runtime); `server.cypher.parallel.worker_limit=1`; at most 4 Bolt threads (`server.bolt.thread_pool_min_size=1`, `server.bolt.thread_pool_max_size=4`, as a connection may be opened right after the previous one is closed, before Neo4j releases its thread; one session, hence one query, at a time) and one HTTP worker thread (`server.threads.worker_count=1`); no page-cache prefetchers (`server.memory.pagecache.scan.prefetchers=0`); one session at a time |
| PGAge | no parallel workers (`max_parallel_workers_per_gather=0`, `max_parallel_workers=0`, `max_parallel_maintenance_workers=0`); `max_worker_processes=2` (TimescaleDB launcher and one background worker), `timescaledb.max_background_workers=1`, `autovacuum_max_workers=1`; no prefetching (`effective_io_concurrency=0`, `maintenance_io_concurrency=0`); one connection at a time |
| AeonG, HyGraph | the container shared with their client, with no CPU or memory limit by default; the other settings of their original evaluation scripts |

Memory: the defaults of each system, except the page cache of Neo4j (512 MB) and the `work_mem` and
`maintenance_work_mem` of PostgreSQL, from the original evaluation.

Query planning: the defaults of each system, except Graphix. Its `cc.conf` (in `conf/` and in `conf-free/`) has the AsterixDB configuration of STGraph
plus the settings of the Graphix extension: the pattern semantics of its original configuration
(`graphix.semantics.pattern=isomorphism`, `graphix.semantics.navigation=no-repeat-anything`) and
two evaluation settings of Graphix, both default `false`, which the AsterixDB of STGraph does not have:
`graphix.evaluation.minimize-joins=true` (no join between an edge and a vertex defined on the same records, so an
event is read once, as the edge `hasTS` and as the vertex `TemperatureEvent`, instead of twice and joined on
`(tsid, _id)`) and `graphix.evaluation.prefer-indexnl=true` (index nested-loop joins between vertices and edges, so
the events of a series are read through the primary key `(tsid, _id)` of `Observations`). Without them, every join is
a hash join on scans: the optimizer of the last release of Graphix has no cost-based optimizer, despite its
documentation (its rule set leaves out the CBO rules of AsterixDB), so `prefer-indexnl` applies to every join between
vertices and edges, whatever its cost (see `INCONSISTENCIES.txt`). The queries have no hints.

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
and from the original Python code are listed in `src/test/resources/queries/smartbench/INCONSISTENCIES.txt`.

### Endpoints

| System | Endpoint |
|--------|----------|
| STGraph | the setups of `test_config.yml` (AsterixDB query API on `localhost:19002`, data feeds on 10001-11000) |
| Graphix | query API on `localhost:29002`, data feeds on 11001-11010 (host ports in `evaluation/architectures/graphix/.env`, from its `.env.example`) |
| Neo4j | `bolt://localhost:7687`, no authentication |
| PGAge | `localhost:5432`, database `smartbench_eval`, graph `smartbench_<size>` |

## Reproducing the results

The runs are configured in `src/main/resources/test_config.yml` (copied from `test_config.example.yml`):

| Section | Content |
|---------|---------|
| `datasets`, `setups` | the sizes of each dataset (for MIMIC-IV, also the PostgreSQL the data is read from: `postgres_endpoint`, `port`, `database`, `user`, `password`); the AsterixDB setups of STGraph |
| `volumes` | the folders on the host where the systems started by the evaluation keep their data (`asterixdb`, `graphix`, `neo4j`, `pgage`, `aeong`; default: a Docker volume of each system); `{size}` in a folder stands for the size of the run |
| `resources` | the resources and configuration of the same systems: `cpus`, `cpuset` (the cores), `memory`, `shm` (PGAge) and `conf` (the folder of the configuration); default: those of the compose files, i.e., no CPU or memory limit and the configuration in `conf/` (see below) |
| `defaults` | the parameters common to all the runs: modes, setups and threads of STGraph (only for the tests: in the evaluation its ingestion is single-threaded, and its Kotlin queries run with 16 threads with `conf-free/` of AsterixDB, the default, and 1 with `conf/`, unless the `evaluation` section or the run sets `threads`), `ingestion.iterations`, `queries` (`iterations`, `selectivities`, `ids`), `download`, `deploy`, `label` (appended to the model of the results, e.g., `stgraph_4CPU`) |
| `specificRuns.runs` | the runs to execute, each one with its `approaches` (systems), `datasets`, optionally `sizes` and any key of `defaults`; the tests of STGraph (CI) read the runs with `stgraph` |
| `evaluation` | `systems`, `datasets`, optionally `sizes` and any key of `defaults`, all combined |

The sizes default to `datasets.<dataset>.sizes`, the time windows are the ones of
`src/main/resources/time_constraints.yaml`. From the repository root:

| Task | Section | What it runs |
|------|---------|--------------|
| `./gradlew evaluate` | `evaluation` | every combination of its parameters |
| `./gradlew evaluateIngestion`, `evaluateQueries` | `evaluation` | the same, only the ingestion or only the queries |
| `./gradlew evaluateSpecificRuns` | `specificRuns` | its runs, one after the other |
| `./gradlew evaluateSpecificRunsIngestion`, `evaluateSpecificRunsQueries` | `specificRuns` | the same, only the ingestion or only the queries |

The queries of `evaluateQueries` and `evaluateSpecificRunsQueries` run on the data already loaded (kept in the data
folders of the systems, see below), without downloading the datasets. AeonG keeps its database between the two phases:
`evaluateQueries` stops if the database is not the one of that dataset and size. HyGraph, which loads and queries in the
same container, is skipped by the tasks of a single phase.

On MIMIC-IV, AeonG loads the Cypher dump of `datasets/dump/aeong/mimic/<limit>/`, written by MimicIVAeonG when missing.
Its query files `q0.txt`-`q3.txt` go in `evaluation/architectures/aeong/queries/mimic/`, mounted on its container as the
SmartBench ones: until all of them are there, its container only runs the ingestion.

For each dataset, size and system, one system at a time, a run:
1. downloads the missing data (`smartbench <size>`: the JSON dataset of every system), from the same URLs as
   `downloadDataset.sh`;
2. starts the system (`docker compose up -d`) and waits until it answers;
3. loads the data (`ingestion.iterations` times) and runs every query `queries.iterations` times on every time window of
   each selectivity;
4. stops the system (`docker compose stop`: the containers and their data are kept).

The data of the AsterixDB of STGraph, Graphix, Neo4j and PGAge are in a Docker volume of each system, so they are
kept also when the container is recreated (e.g., `docker compose down`, or a change of its compose file). The
`volumes` section of `test_config.yml` sets a folder on the host for each system instead (e.g., a disk with room for
the large sizes): the evaluation passes them to the compose files (`ASTERIXDB_DATA`, `GRAPHIX_DATA`, `NEO4J_DATA`,
`PGAGE_DATA`, `AEONG_DATA`; to start a system by hand on the same folder, set the same variable). Every ingestion drops and
reloads its data: with `ingestion.iterations: 0`, a run only queries the data already loaded (STGraph also needs its
graph, dumped by its loader in `datasets/dump/smartbench/<size>`). The AsterixDB of STGraph and Graphix keep a
dataverse per size, Neo4j and PGAge a single database (the last size loaded): with `{size}` in their folders
(e.g., `neo4j: /data/neo4j/{size}`), each size has its own and stays queryable. After a change of the partitions in a
`cc.conf`, the data of that AsterixDB must be emptied (or kept in another folder).

To keep the data folders small, both configurations of Neo4j keep only the latest transaction log
(`db.tx_log.rotation.retention_policy=keep_none`, in files of 64 MB) and both configurations of PGAge let the WAL
shrink to its minimum after a checkpoint (`min_wal_size = 32MB`): these settings change no measured operation, and
the logs of a folder loaded before them are pruned at its next checkpoint (e.g., at the end of its next run).

To compare configurations of the systems on the same data (CPU and memory limits in the compose files, the settings
of their configuration files, the threads of STGraph), load each size once, then for each configuration change the
files, set its `label` and run `./gradlew evaluateQueries` (or `evaluateSpecificRunsQueries`): each system is started
with the new files on its data and queried, and its results are recorded as `<model>_<label>`. Only the partitions of
AsterixDB and Graphix, the indexes and the data model change the data, and need a new ingestion. AeonG and HyGraph
write their results themselves, without the label.

The `resources` section sets the CPU and memory limits and the configuration folder of each system without editing the
compose files (the evaluation passes them as `<SYSTEM>_CPUS`, `_CPUSET`, `_MEMORY`, `_SHM`, `_CONF`). Without it, the
systems run with no CPU or memory limit (free) and the configuration in `conf/`; the single-core comparison of the
paper is `{ cpus: 1 }` for each system.

Each system has two configurations (the blocks of the example of `test_config.yml`):

| | Resources | Configuration |
|---|---|---|
| Limited | one core (`cpus: 1`), no memory limit | `conf/`: the least parallelism the system allows (see above) |
| Free | 20 cores (`cpus: 20`, `cpuset: "0-19"`), no memory limit | `conf-free/`: the defaults of the system, no setting chosen by us or by a tuning tool |

The free configuration of each system:

| System | `conf-free` |
|--------|-------------|
| STGraph | the `cc.conf` of the image as one node with two data partitions (`iodevices=data/io0,data/io1`) instead of its two nodes with one partition each: the same parallelism (one per partition) in one JVM, without exchanges between nodes on localhost, with one heap (60% of the memory it sees) instead of two |
| Graphix | the same, with the Graphix extension and the semantics of the patterns; `minimize-joins` and `prefer-indexnl` on, as in `conf/` (without them Graphix scans all the events at every query) |
| Neo4j | `conf/` without its least parallelism and its page cache: the configuration of the distribution, on which the image sets a 512 MB page cache (its default whatever the memory) |
| PGAge | the `postgresql.conf.sample` of PostgreSQL 16 in the image (with its TimescaleDB library and listen address) and the settings of initdb: 128 MB of shared buffers, two parallel workers per query; the locks, the memory per operation and the WAL sizes of `conf/` (`max_locks_per_transaction = 1024`, `work_mem = 32944kB`, `maintenance_work_mem = 2047MB`, `wal_buffers = 16MB`, `min_wal_size = 512MB`, `max_wal_size = 1GB`), so that it is never more limited than `conf/`; `shm: 1g` removes the 64 MB limit of Docker on the shared memory of the parallel workers |

Without a memory limit, the memory of the machine is a parameter of the runs (run every system on the same node, and
report its RAM), and the systems use it as their defaults do: the heap of AsterixDB grows with the memory it sees,
while Neo4j and PostgreSQL keep their small caches and rely on the cache of the operating system. The JVM of STGraph
(outside Docker) takes the default heap of the JVM. The two partitions of AsterixDB and Graphix are not those of
`conf/`: their data are loaded in another folder (e.g., `volumes` per partitioning).

The datasets are SmartBench (every system) and the synthetic dataset (STGraph, with the loader and the queries of its
tests; `queries.ids` selects their families, e.g., `SynthFilter`). STGraph runs its reference loader (`SmartBenchDataLoader`) and queries. AeonG and HyGraph load and query inside
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
