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
  - `neo4j`, `pgage`, `graphix`: the database only, loaded and queried by the Kotlin mains;
  - `aeong`, `hygraph`: self-contained containers that load the data and run the queries (`run_tests.sh`);
- `data/`: experimental data mentioned in the article;
- `figures/`: the article figures;
- `postprocessing/STGraph.ipynb`: notebook to reproduce the article figures from data;
- `stats.ipynb`: exploration of the MIMIC-IV subjects and time series (`mimic-iv_subjectids_tsids.csv`);
- `requirements.txt`: the Python dependencies of the notebooks.

The code lives in the repository root:
- `src/test/kotlin/it/unibo/tests/smartbench/loaders/`: one loader per system on a shared skeleton
  (`SmartBenchLoader.kt`, as `MimicLoader.kt` for MIMIC): `SmartBenchSTGraph`, `SmartBenchNeo4J`, `SmartBenchPGAge`,
  `SmartBenchGraphix`. `SmartBenchDataLoader` stays the reference loader of STGraph (and of the CI);
- `src/test/resources/smartbench/queries/<system>/`: the queries of the competitors, as templates;
  `INCONSISTENCIES.txt` lists their differences from STGraph and the differences of the loaders from the original
  Python code.

### Experiments reproducibility

All the Kotlin runs read `src/main/resources/test_config.yml` (sizes of `smartbench`, setups, threads) and the time
windows of `src/main/resources/time_constraints.yaml`. Results are appended to `results/dt_graph/ingestion_time/` and
`results/dt_graph/query_time/` of the repository root, one row per run, with the system in the `model` column.

#### Data

From the repository root:
```sh
./downloadDataset.sh smartbench <size>         # JSON dataset: STGraph, PGAge, Graphix
./downloadDataset.sh smartbench/neo4j <size>   # Cypher dumps: Neo4j
```

#### STGraph

The tests of `TestSmartBenchIngestion` and `TestSmartBenchQuery`, run by the CI (`run.sh`), on the AsterixDB of the
root `docker-compose.yaml`.

#### Neo4j, PGAge, Graphix

1. Deploy the database (for Graphix, first `cp .env.example .env` in its folder):
   ```sh
   docker compose -f evaluation/architectures/<system>/docker-compose.yaml up -d
   ```
   | System  | Endpoint                                                     |
   |---------|--------------------------------------------------------------|
   | Neo4j   | `bolt://localhost:7687`, no authentication                    |
   | PGAge   | `localhost:5432`, database `smartbench_eval`, graph `smartbench_graph` |
   | Graphix | query API on `localhost:29002`, data feeds on 11001-11010     |
2. Run, from the IDE, the main of `TestSmartBenchCompetitorsIngestion.kt` and then the one of
   `TestSmartBenchCompetitorsQuery.kt` (comment out the systems not under test). The ingestion main also loads
   STGraph through `SmartBenchSTGraph` (model `stgraph-std`), to compare the shared measurement boundaries with the
   reference loader (model `stgraph`).

Each system runs on one core (`cpus: 1` and the settings of `PARALLELISM.md`).

#### AeonG, HyGraph

Configured by `.env` (created from `.env.example` at the first run). From this folder:
```sh
./run_tests.sh
```
Results are appended to `data/<system>/`.

#### WARNING
Tests on the "large" dataset take a considerate amount of resources, in terms of both storage and time.
For the "large" dataset, make sure at least 300 GB of storage are available in the Docker data folder.
We reccomend to run such tests on different machines to parallelize the workload.

#### Reproducing scale-out tests

Deploy an AsterixDB cluster following the [official documentation](https://asterixdb.apache.org/docs/0.9.9/ncservice.html)
and add a setup with its cluster controller and node controllers to `test_config.yml` (`setups`, then `ingestion` and
`runs`).

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
