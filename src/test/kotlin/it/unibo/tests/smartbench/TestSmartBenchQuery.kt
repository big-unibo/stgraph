package it.unibo.tests.smartbench

import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.query.QueryMode
import it.unibo.graph.utils.resetPort
import it.unibo.stats.TestConfig
import it.unibo.stats.TestConfig.Companion.logger
import it.unibo.stats.TestConfig.Companion.normalizeSize
import it.unibo.stats.runQuery
import it.unibo.tests.smartbench.queries.*
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.Test


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestSmartBenchQuery {
    @Test
    fun runEnvironmentCoverage() = TestConfig.runTest(dataset = "smartbench") { graph, size, mode ->
        EnvironmentCoverage(graph)
    }

    @Test
    fun runEnvironmentAggregate() = TestConfig.runTest(dataset = "smartbench") { graph, size, mode ->
        if (size.contains("large") && mode == QueryMode.NAIVE) null else EnvironmentAggregate(graph, size)
    }

    @Test
    fun runMaintenanceOwners() = TestConfig.runTest(dataset = "smartbench") { graph, size, mode ->
        if (size.contains("large") && mode == QueryMode.NAIVE) null else MaintenanceOwners(graph, size)
    }

    @Test
    fun runEnvironmentOutlier() = TestConfig.runTest(dataset = "smartbench") { graph, size, mode ->
        if (size.contains("large") && mode == QueryMode.NAIVE) null else EnvironmentOutlier(graph, size)
    }

    @Test
    fun runAgentOutlier() = TestConfig.runTest(dataset = "smartbench") { graph, size, mode ->
        if (size.contains("large") && mode == QueryMode.NAIVE) null else AgentOutlier(graph, size)
    }

    @Test
    fun runAgentHistory() = TestConfig.runTest(dataset = "smartbench") { graph, size, mode ->
        AgentHistory(graph)
    }
}