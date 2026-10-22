package it.unibo.tests.smartbench

import it.unibo.stats.TestConfig
import it.unibo.stats.loadDataset
import it.unibo.tests.smartbench.loaders.SmartBenchDataLoader
import it.unibo.tests.smartbench.loaders.SmartBenchGraphixLoader
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestSmartBenchIngestion {
    @Test
    fun `ingest SmartBench`() {
        TestConfig.runIngestion("smartbench") { size, threads, host, controllerIPs ->
            SmartBenchDataLoader(size, threads = threads, host = host, controllerIPs = controllerIPs)
        }
    }
}

/** Ingestion in the competitors (Graphix): not a test, since the CI covers STGraph only. Graphix must be running. */
fun main() {
    TestConfig.loadConfig().datasets["smartbench"]!!.sizes.forEach { size ->
        loadDataset(SmartBenchGraphixLoader(size.toString()), "graphix", threads = 1, numMachines = 1, "smartbench", size.toString())
    }
}
