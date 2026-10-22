# STGraph - Something for Someone

## Research papers

This folder contains the evaluation pipelines and results of the following research paper:

- Francia, Matteo, Golfarelli Matteo, and Pasini Manuele. "STGraph - A something for someone". Submitted to **somewhere** (2025).

It was the STGraph-evaluation repository, imported with its history. The ingestion and the queries of STGraph and of
its competitors (Neo4j, PGAge, Graphix) are Kotlin code of this repository; this folder holds the deploy of the
systems, the results and the post-processing.

## Getting Started
### Organization
- `architectures/<system>/`: compose file, Dockerfile and configuration of each competitor
  - `neo4j`, `pgage`, `graphix`: the database only, loaded and queried by the evaluation;
  - `aeong`, `hygraph`: self-contained containers that load the data and run the queries, configured by the evaluation;
- `figures/current/`: the figures of the current results; `figures/paper/`: the article figures;
- `postprocessing/STGraph.ipynb`: notebook to reproduce the figures from the results;
- `stats.ipynb`: exploration of the MIMIC-IV subjects and time series (`mimic-iv_subjectids_tsids.csv`);
- `requirements.txt`: the Python dependencies of the notebooks.

The code lives in the repository root:
- `src/test/kotlin/it/unibo/tests/evaluation/`: the evaluation (`Evaluation.kt`, run by `./gradlew evaluate`), and
  `src/test/kotlin/it/unibo/tests/smartbench/SmartBenchEvaluation.kt` for SmartBench;
- `src/test/kotlin/it/unibo/tests/smartbench/loaders/`: one loader per system on a shared skeleton
  (`SmartBenchLoader.kt`, as `MimicLoader.kt` for MIMIC): `SmartBenchSTGraph`, `SmartBenchNeo4J`, `SmartBenchPGAge`,
  `SmartBenchGraphix`. `SmartBenchDataLoader` stays the reference loader of STGraph (and of the CI);
- `src/test/resources/smartbench/queries/<system>/`: the queries of the competitors, as templates;
  `INCONSISTENCIES.txt` lists their differences from STGraph and the differences of the loaders from the original
  Python code.

### Results

Every run appends its rows to `results/` of the repository root, read by the notebook:
- `results/dt_graph/{ingestion_time,query_time}/`: the Kotlin runs (STGraph, its CI and the competitors), one row
  per run, with the system in the `model` column;
- `results/aeong/`, `results/hygraph/`: the runs of the self-contained containers;
- `results/paper/`: the archive with the experimental data of the article (formats of the old evaluation), not
  updated by the runs.

### Experiments reproducibility

The evaluation is configured by the `evaluation` section of `src/main/resources/test_config.yml` (copy it from
`test_config.example.yml`): systems, datasets, iterations, selectivities, queries, and whether to download the data
and to deploy the systems. The sizes are the ones of `datasets.<dataset>.sizes`, the time windows the ones of
`src/main/resources/time_constraints.yaml`. From the repository root:
```sh
./gradlew evaluate
```
(or the main of `Evaluation.kt` from the IDE, with the repository root as working directory). For each dataset, size
and system, one system at a time:
1. it downloads the missing data with `downloadDataset.sh` (`smartbench <size>`: JSON dataset of STGraph, PGAge and
   Graphix; `smartbench/neo4j <size>`: Cypher dumps of Neo4j), in Kotlin (no bash needed, e.g., on Windows);
2. it starts the system (`docker compose up -d`) and waits until it answers: the AsterixDB of the root
   `docker-compose.yaml` for STGraph, `architectures/<system>/docker-compose.yaml` for the others;
3. it loads the data (`ingestion.iterations` times) and runs every query `queries.iterations` times on every time
   window of each selectivity;
4. it stops the system (`docker compose stop`: the containers and their data are kept).

AeonG and HyGraph load and query inside their container, with the configuration passed as environment variables
(sizes of the old evaluation: small, medium -> large, large -> big). A system that fails is reported at the end, the
others go on. With `deploy: false` the systems must be already running:

| System  | Endpoint                                                               |
|---------|------------------------------------------------------------------------|
| STGraph | the setups of `test_config.yml` (AsterixDB on `localhost:19002`)        |
| Neo4j   | `bolt://localhost:7687`, no authentication                              |
| PGAge   | `localhost:5432`, database `smartbench_eval`, graph `smartbench_graph`  |
| Graphix | query API on `localhost:29002`, data feeds on 11001-11010               |

Each system runs on one core (`cpus: 1` and the settings of `PARALLELISM.md`). The ports of Graphix can be changed in
`architectures/graphix/.env` (from its `.env.example`). `architectures/pgage/conf/postgresql.conf` is tuned for the
evaluation server: on a smaller machine, lower `evaluation.pgage.sharedBuffers` (allocated at start-up, it must fit in
the memory of Docker) and `effectiveCacheSize` in `test_config.yml`. The CI does not run the evaluation: it runs the tests of STGraph
(`TestSmartBenchIngestion`, `TestSmartBenchQuery`, ...) through `run.sh`.

#### WARNING
Tests on the "large" dataset take a considerate amount of resources, in terms of both storage and time.
For the "large" dataset, make sure at least 300 GB of storage are available in the Docker data folder.
We reccomend to run such tests on different machines to parallelize the workload.

#### Reproducing scale-out tests

Scale-out concerns STGraph only, on an AsterixDB cluster that the evaluation does not deploy:
1. Deploy an AsterixDB cluster following the [official documentation](https://asterixdb.apache.org/docs/0.9.9/ncservice.html).
2. In `test_config.yml`, add a setup with the address of the cluster controller (`host`, and `port` of its query
   API) and the addresses of the node controllers (`controllerIPs`), which receive the data feeds: see `c1` in
   `test_config.example.yml`.
3. In the `evaluation` section, list it in `setups` (with the `threads` to test) and set `systems: [ "stgraph" ]`.
4. Run `./gradlew evaluate`: a local setup is started and stopped by the evaluation, a cluster is only used.
   `numMachines` in the results is the number of node controllers.

#### Reproducing articles figures

All figures within the article can be reproduced through the `postprocessing/STGraph.ipynb` notebook. The notebook requires Python > 3.12, a Jupyter kernel and the dependencies specified in the `requirements.txt` file; the figures use the Times New Roman font, which must be installed.

## References

Implementations for each of the evalued systems and benchmark dataset can be found at the following GitHub repositories:

- [STGraph](https://github.com/w4bo/dt-graph)
- [AeonG](https://github.com/ManuelePasini/AeonG)
- [HyGraph](https://github.com/dbs-leipzig/HyGraph-package)
- [Graphix](https://github.com/graphix-asterixdb/package)
- [SmartBench](https://github.com/ManuelePasini/smart_benchmark)
- The original Python ingestion and queries of PGAge and Neo4j: [digital_twins_architectures](https://github.com/ManuelePasini/digital_twins_architectures)
