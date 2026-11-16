package it.unibo.tests.pems

import it.unibo.stats.TestConfig
import it.unibo.tests.pems.loaders.PemsSTGraph
import org.junit.jupiter.api.Test

class TestPemsIngestion {
    @Test
    fun `ingest PeMS`() {
        TestConfig.runIngestion("pems") { size, threads, host, controllerIPs ->
            PemsSTGraph(size, threads, host, controllerIPs, TestConfig.loadConfig().datasets.getValue("pems").postgres)
        }
    }
}
