# STGraph - Something for Someone

## Research papers

This repository contains the evaluation pipelines and results of the following research paper:

- Francia, Matteo, Golfarelli Matteo, and Pasini Manuele. "STGraph - A something for someone". Submitted to **somewhere** (2025).

## Getting Started
### Repository organization
- `/data`: contains experimental data mentioned in the article;
- `/figures`: contains the article figures;
- `/architectures`: contains the implementations of STGraph and its competitors;
- `STGraph.ipynb`: Python notebook to reproduce articles figures from data;
- `requirements.txt`: highlights the mandatory dependencies to run the postprocessing scripts.

### Experiments reproducibility

All data and figures are reproducible through Docker.

To reproduce the experiments, open a shell within the project folder and run:
   ```sh
   ./run_tests.sh
   ```
All the configurations required to run the experiments are specified in the .env.example file.
The above command needs to be run twice, the first one creates the .env from the .env.example, the second one runs the experiments through Docker.
The .env file can be modified accordingly w.r.t. the configuration to be tested.

Tests' results data which will then be available in the `/data` folder and appended to previous experiments' results.

#### WARNING
Tests on the "large" dataset take a considerate amount of resources, in terms of both storage and time.
For the "large" dataset, make sure at least 300 GB of storage are available in the Docker data folder.
We reccomend to run such tests on different machines to parallelize the workload.

#### Reproducing scale-out tests

Scale-out tests are not directly reproducible through Docker.
To reproduce scale-out experiments, follow the following steps:
1. Deploy an AsterixDB cluster following the [official documentation](https://asterixdb.apache.org/docs/0.9.9/ncservice.html).
2. In the .env, update the `ASTERIXDB_CC_HOST` and `ASTERIXDB_NC_POOL` accordingly. Node controllers IPs must be separated by a comma (e.g., `ASTERIXDB_NC_POOL=192.160.30.102,192.168.30.103`)
3. In the .env, set EVALUATION_SYSTEMS variable to 'dtgraph'
4. Configure tests configuration accordingly in the .env (e.g., number of threads, dataset size).
5. Run
   ```sh
   ./run_tests.sh
   ```

#### Reproducing articles figures

All figures within the article can be reproduced through the `STGraph.ipynb`notebook. The notebook requires Python > 3.12 and the dependencies specified in the `requirements.txt` file.
A .devcontainer has been prepared to avoid installing the mandatory libraries in the host machine. Open the project repository within an IDE of your choice (e.g., Visual Studio Code) and build and open the project.

## References

Implementations for each of the evalued systems and benchmark dataset can be found at the following GitHub repositories:

- [STGraph](https://github.com/w4bo/dt-graph)
- [AeonG](https://github.com/ManuelePasini/AeonG)
- [HyGraph](https://github.com/dbs-leipzig/HyGraph-package)
- [PGAge, Neo4J](https://github.com/ManuelePasini/digital_twins_architectures)
- [SmartBench](https://github.com/ManuelePasini/smart_benchmark)
