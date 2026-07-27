package it.unibo.tests.smartbench

import it.unibo.stats.TestConfig
import it.unibo.tests.smartbench.loaders.SmartBenchDataLoader
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