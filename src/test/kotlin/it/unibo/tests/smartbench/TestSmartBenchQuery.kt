package it.unibo.tests.smartbench

import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.query.QueryMode
import it.unibo.graph.utils.resetPort
import it.unibo.stats.QueryResultData
import it.unibo.stats.Querying
import it.unibo.stats.TestConfig
import it.unibo.stats.TestConfig.Companion.logger
import it.unibo.stats.TestConfig.Companion.normalizeSize
import it.unibo.stats.runQuery
import it.unibo.tests.smartbench.queries.*
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestSmartBenchQuery {
    /** Expected cardinalities on xsmall, the dataset used by the CI (the same as on small) */
    private val expectedXSmall = mapOf(
        "EnvironmentCoverage" to 1,
        "EnvironmentAggregate" to 8,
        "MaintenanceOwners" to 8,
        "EnvironmentOutlier" to 4,
        "AgentOutlier" to 8,
        "AgentHistory" to 1
    )

    /** On xsmall, fails the test if [query] does not return the expected cardinality (any mode and number of threads) */
    private fun checked(size: String, query: Querying?): Querying? =
        if (query == null || size != "xsmall") {
            query
        } else {
            object : Querying {
                override val queryId = query.queryId
                override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData =
                    query.runQuery(threads, queryMode).also {
                        assertEquals(expectedXSmall.getValue(queryId), it.card, "Cardinality of $queryId on $size ($queryMode, $threads threads)")
                    }
            }
        }

    @Test
    fun runEnvironmentCoverage() = TestConfig.runTest(dataset = "smartbench") { graph, size, mode ->
        checked(size, EnvironmentCoverage(graph))
    }

    @Test
    fun runEnvironmentAggregate() = TestConfig.runTest(dataset = "smartbench") { graph, size, mode ->
        checked(size, if (size.contains("large") && mode == QueryMode.NAIVE) null else EnvironmentAggregate(graph, size))
    }

    @Test
    fun runMaintenanceOwners() = TestConfig.runTest(dataset = "smartbench") { graph, size, mode ->
        checked(size, if (size.contains("large") && mode == QueryMode.NAIVE) null else MaintenanceOwners(graph, size))
    }

    @Test
    fun runEnvironmentOutlier() = TestConfig.runTest(dataset = "smartbench") { graph, size, mode ->
        checked(size, if (size.contains("large") && mode == QueryMode.NAIVE) null else EnvironmentOutlier(graph, size))
    }

    @Test
    fun runAgentOutlier() = TestConfig.runTest(dataset = "smartbench") { graph, size, mode ->
        checked(size, if (size.contains("large") && mode == QueryMode.NAIVE) null else AgentOutlier(graph, size))
    }

    @Test
    fun runAgentHistory() = TestConfig.runTest(dataset = "smartbench") { graph, size, mode ->
        checked(size, AgentHistory(graph))
    }
}