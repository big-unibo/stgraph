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
| `results/` | The results of every system, AeonG and HyGraph included: `dt_graph/` (`ingestion_time/ingestion_statistics.csv`, `query_time/query_statistics.csv`); the archives, not updated by the runs: `paper/` (the data of the article) and `rebuttal/` (the data of the article and the runs of the rebuttal, from the repository STGraph-evaluation) |
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
| AeonG | the Cypher dump of the graph of Neo4j (see below) | one `Temperature` node per sensor, updated by each event (one statement, one version per event) | those of its scripts                                                                                                                                                                 |
| HyGraph | its original evaluation | its original evaluation | its original evaluation                                                                                                                                                           |

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
| `defaults` | the parameters common to all the runs: modes, setups and threads of STGraph (only for the tests: in the evaluation its ingestion is single-threaded, and its Kotlin queries run with 16 threads with `conf-free/` of AsterixDB, the default, and 1 with `conf/`, unless the `evaluation` section or the run sets `threads`), `ingestion.iterations`, `queries` (`iterations`, `ids`), `download`, `deploy`, `label` (appended to the model of the results, e.g., `stgraph_4CPU`) |
| `stgraphRuns` | the runs of STGraph alone, by its tests (`./gradlew test`, the CI): each with its `datasets`, optionally `sizes` and `modes`, `threads`, `setups` (the experiments of STGraph: query modes, threads, scale-out) |
| `evaluation` | `systems`, `datasets`, optionally `sizes` and any key of `defaults`, all combined |

The sizes default to `datasets.<dataset>.sizes`. Each query has one time window per size, the same for every system:
`TimeWindows` (`src/test/kotlin/it/unibo/tests/smartbench/queries`), with a copy in HyGraph's `evaluate_hygraph.py`. From the repository root:

| Task | Section | What it runs |
|------|---------|--------------|
| `./gradlew evaluate` | `evaluation` | every combination of its parameters |
| `./gradlew evaluateIngestion`, `evaluateQueries` | `evaluation` | the same, only the ingestion or only the queries |

The queries of `evaluateQueries` run on the data already loaded (kept in the data
folders of the systems, see below), without downloading the datasets. AeonG keeps its database between the two phases:
`evaluateQueries` stops if the database is not the one of that dataset and size. HyGraph, which loads and queries in the
same container, is skipped by the tasks of a single phase.

On MIMIC-IV, the loaders read PostgreSQL once per size: the items and the events of the selected time series are saved,
gzipped, in `datasets/original/mimic/cache/<size>/` (`items.jsonl.gz`, `events.csv.gz`), and the next loads of every
system read that copy instead of PostgreSQL (not timed, as the reads from PostgreSQL). Delete the folder to read
PostgreSQL again (e.g., after changing the list of subjects or the PostgreSQL). The sizes are `xsmall` (the CI), `small`,
`medium` and `large` (1,692,200, 1,692,200, 16,922,000 and all the events): `xsmall` selects the subjects of
`mimic-iv_subjectids_tsids_short.csv`, the others those of `mimic-iv_subjectids_tsids.csv`
(the whole list, downloaded by `run.sh` if missing). To read PostgreSQL without loading any
system, `./gradlew mimicCache` writes the copies of the sizes of `datasets.mimic.sizes` (or of `--args="<size> ..."`).
To spare the other machines the query, copy the folder to the same path on them (`evaluation/push_datasets.sh`).
PeMS does the same, in `datasets/original/pems/cache/<size>/` (the stations, their versions, segments, roads,
incidents, and the events), written by the loaders or by `./gradlew pemsCache` (or `--args="<size> ..."`).

AeonG loads a Cypher dump with the graph of Neo4j and its own temporal model, written when missing (not timed) from
the data of the other systems: `datasets/dump/aeong/mimic/<size>/` (MimicIVAeonG) and
`datasets/dump/aeong/smartbench/<size>/` (SmartBenchAeonG), on every size. A node per time series, created by its first
event and updated by each next one: one statement (one transaction, one version of the node) per event, the SmartBench
events in the order of their timestamps. Its queries, those of Neo4j (`src/test/resources/queries/mimic/aeong/`,
`src/test/resources/queries/smartbench/aeong/`), read the history by transaction time (`TT FROM ... TO ...`), the
logical clock of AeonG, which advances with every transaction: its ingestion (`ingest_boundaries.py`) records the
transaction time of the first event at or after each bound of the time windows and of the last statement (not timed),
in `<size>.aeong` and `<limit>.aeong` next to the dump, with which the evaluation writes the queries (the whole history
for MIMIC-IV and for the queries without a window). Its queries run on one AeonG, started once for all of them and
their iterations (`run_queries.py`), as the other systems. Its results, recorded by its container in a temporary folder
with the number of rows of each query (from the report of its client; -1 for a query too long to name its report), are
added to `results/dt_graph/` as they come, as those of the other systems (`model` aeong, the configuration of
`resources.aeong.conf`: `conf/`, with `cpus: 1`, or `conf-free/`, the default).

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
files, set its `label` and run `./gradlew evaluateQueries`: each system is started
with the new files on its data and queried, and its results are recorded as `<model>_<label>`. Only the partitions of
AsterixDB and Graphix, the indexes and the data model change the data, and need a new ingestion. AeonG and HyGraph
write their results themselves, with the same model (`<system>_<label>`).

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

### Running on other machines

From this machine (Git Bash on Windows), with the repository cloned in `~/stgraph` on each machine (`REMOTE_DIR`
otherwise) and the same `src/main/resources/mimic-iv_subjectids_tsids.csv` for the sizes without "x" (the caches are
read for its subjects and time series):

1. Copy the ssh key, to log in and copy without a password (asks each machine's password once; `-k` for another key,
   by default `~/.ssh/id_rsa.pub`):
   ```bash
   evaluation/push-ssh-key.sh -k ~/.ssh/id_rsa.pub -u mfrancia 192.168.30.101 192.168.30.102
   ```
   `scp` (steps 2 and 4) offers only the keys with the default names (`id_rsa`, `id_ed25519`, ...): with another name,
   add it to `~/.ssh/config`:
   ```
   Host 192.168.30.*
       User mfrancia
       IdentityFile ~/.ssh/id
       IdentitiesOnly yes
   ```
2. Copy the caches of the datasets (MIMIC-IV and PeMS, with the dumps their PostgreSQL restores): every system then
   writes its own data from them, without querying PostgreSQL:
   ```bash
   evaluation/push_datasets.sh -u mfrancia -p datasets/original/mimic -p datasets/original/pems 192.168.30.101 192.168.30.102
   ```
3. On each machine, check that the user is a sudoer and in the `docker` group, then run the evaluation (in `tmux` or
   with `nohup`: it takes hours). A Gradle daemon started before the user joined the `docker` group cannot reach
   Docker: stop it first.
   ```bash
   ssh mfrancia@192.168.30.101
   sudo -v                                   # the user is a sudoer
   groups | grep -qw docker || { sudo usermod -aG docker $USER; exit; }   # then log in again
   docker ps                                 # Docker without sudo
   cd ~/stgraph
   ./gradlew --stop
   nohup ./gradlew evaluate > evaluation.log 2>&1 &
   tail -f evaluation.log
   ```
   To give each machine its own systems and configuration without editing its `test_config.yml`, list them in
   `evaluation/machines.txt` (`<host> <systems> <limited|free> [label]`) and write and copy their configurations from
   the local one (`-n`: only print them; `-r`: also start `./gradlew evaluate` on each machine, after the checks above):
   ```bash
   evaluation/configure_machines.sh -u mfrancia -r evaluation/machines.txt
   ```
4. Collect the results while they run: every 5 minutes for 8 hours, into `results/machines/<host>/`, merged into
   `results/merged/` (Ctrl+C to stop):
   ```bash
   evaluation/collect_results.sh -l -i 300 -d 28800 -u mfrancia 192.168.30.101 192.168.30.102
   ```

### Scale-out

Scale-out concerns STGraph and Graphix, each on an AsterixDB cluster (for Graphix, with its extension) deployed following the
[official documentation](https://asterixdb.apache.org/docs/0.9.9/ncservice.html). Its setup in `test_config.yml` gives
the address of the cluster controller (`host`, and `port` of its query API) and the addresses of the node controllers
(`controllerIPs`), which receive the data feeds (`c1` in `test_config.example.yml`); the runs use it through `setups`,
with `systems: [ "stgraph" ]` or `[ "graphix" ]` (e.g., `setups: [ "c1", "c2", "c4" ]`). A run starts and stops the local
setup (for Graphix, its compose file, port 29002) and uses a cluster as it is; Graphix receives its data feed on the
first node controller only (its ingestion does not scale out). `numMachines` in the results is the
number of node controllers.

`./gradlew evaluateScaleOut`, from a manager of an existing Docker Swarm, also deploys these clusters: for each setup
that is not local, the stack of the system (`evaluation/architectures/stgraph/docker-stack.yaml`, or the one of
Graphix, image `bigunibo/graphix:0.4.1` or `GRAPHIX_IMAGE`) with the cluster controller on the node with the address
`host` and one node controller (two data partitions, the free configuration of `conf-scaleout/cc.conf`) on the node of
each address of `controllerIPs`, one per machine, up to four; host network, no data kept after the run. The nodes are
the ones of the Swarm with these addresses (`docker node inspect`). The run waits until every node controller is
active, then removes the stack and waits until its ports are free.

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
