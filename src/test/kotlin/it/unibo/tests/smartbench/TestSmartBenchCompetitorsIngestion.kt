package it.unibo.tests.smartbench

import it.unibo.graph.utils.resetPort
import it.unibo.stats.TestConfig
import it.unibo.stats.loadDataset
import it.unibo.tests.smartbench.loaders.SmartBenchGraphix
import it.unibo.tests.smartbench.loaders.SmartBenchNeo4J
import it.unibo.tests.smartbench.loaders.SmartBenchPGAge
import it.unibo.tests.smartbench.loaders.SmartBenchSTGraph

/**
 * SmartBench ingestion outside the CI, which covers STGraph only (TestSmartBenchIngestion): comment out the systems
 * not under test, the others must be running (evaluation/architectures/<system>/docker-compose.yaml).
 * - "stgraph-std": STGraph through SmartBenchSTGraph, on the loader skeleton shared with the competitors, with the
 *   setups and threads of the ingestion section of test_config.yml (as TestSmartBenchIngestion), to compare it with
 *   the reference loader (SmartBenchDataLoader, model "stgraph");
 * - "neo4j", "pgage", "graphix": the competitors, single-threaded.
 */
fun main() {
    val config = TestConfig.loadConfig()
    val sizes = config.datasets["smartbench"]!!.sizes.map { TestConfig.normalizeSize(it) }

    sizes.forEach { size ->
        config.ingestion.setups.map { config.setups.getValue(it) }.forEach { setup ->
            config.ingestion.threads.forEach { threads ->
                resetPort()
                loadDataset(
                    SmartBenchSTGraph(size, threads = threads, host = setup.host, controllerIPs = setup.controllerIPs),
                    "stgraph-std", threads = threads, numMachines = setup.controllerIPs.toSet().size, "smartbench", size
                )
            }
        }
    }

    sizes.forEach { size ->
        loadDataset(SmartBenchNeo4J(size), "neo4j", threads = 1, numMachines = 1, "smartbench", size)
        loadDataset(SmartBenchPGAge(size), "pgage", threads = 1, numMachines = 1, "smartbench", size)
        loadDataset(SmartBenchGraphix(size), "graphix", threads = 1, numMachines = 1, "smartbench", size)
    }
}
