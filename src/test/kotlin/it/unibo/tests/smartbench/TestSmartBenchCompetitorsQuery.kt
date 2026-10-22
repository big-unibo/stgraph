package it.unibo.tests.smartbench

import it.unibo.stats.TestConfig
import it.unibo.tests.systems.GraphixQuery
import it.unibo.tests.systems.Neo4JQuery
import it.unibo.tests.systems.PGAgeQuery
import org.json.JSONArray
import org.neo4j.driver.Session
import java.sql.Connection

/**
 * The SmartBench queries of the competitors (Neo4j, PGAge, Graphix), with the same query ids as STGraph
 * (TestSmartBenchQuery). Not tests, since the CI covers STGraph only: they run in the evaluation (SmartBenchEvaluation).
 *
 * Each query is a template, src/test/resources/smartbench/queries/<system>/<queryId>.txt: as in the Python evaluation,
 * {TSTAMPFROM_PARAM} and {TSTAMPTO_PARAM} become the time window [rangeIndex] of [selectivity] in time_constraints.yaml.
 * The runners (connections, timing) are the ones of it.unibo.tests.systems, shared with MIMIC-IV.
 */
class TestSmartBenchCompetitorsQuery {
    companion object {
        /** The SmartBench queries, in the order of the Python evaluation */
        val queryIds = listOf("EnvironmentCoverage", "EnvironmentAggregate", "MaintenanceOwners", "EnvironmentOutlier", "AgentOutlier", "AgentHistory")

        /** The text of the query [queryId] of [system] on [size], with the time window [rangeIndex] of [selectivity] */
        fun queryText(system: String, queryId: String, size: String, selectivity: String = "increased", rangeIndex: Int = 0): String {
            val path = "smartbench/queries/$system/$queryId.txt"
            val template = TestSmartBenchCompetitorsQuery::class.java.classLoader.getResource(path)?.readText()?.trim()
                ?: error("Query not found in the classpath: $path")
            if (!template.contains("{TSTAMPFROM_PARAM}") && !template.contains("{TSTAMPTO_PARAM}")) return template
            val window = TestConfig.loadTemporalRanges(selectivity, queryId, size)[rangeIndex]
                ?: error("No time window $rangeIndex for $queryId on $size ($selectivity)")
            return template.replace("{TSTAMPFROM_PARAM}", window.from.toString()).replace("{TSTAMPTO_PARAM}", window.to.toString())
        }
    }

    /** A query on the graph loaded by SmartBenchNeo4J, on an open [session] */
    class QNeo4J(
        override val queryId: String,
        size: String,
        session: Session,
        selectivity: String = "increased",
        rangeIndex: Int = 0
    ) : Neo4JQuery(session) {
        private val text = queryText("neo4j", queryId, size, selectivity, rangeIndex)

        override fun statement() = text
    }

    /** A query on the graph and the hypertable loaded by SmartBenchPGAge, on an open [connection] (see pgAgeConnection) */
    class QPGAge(
        override val queryId: String,
        size: String,
        connection: Connection,
        selectivity: String = "increased",
        rangeIndex: Int = 0
    ) : PGAgeQuery(connection) {
        private val text = queryText("pgage", queryId, size, selectivity, rangeIndex)

        override fun statement() = text
    }

    /** A query on the graph created by SmartBenchGraphix */
    class QGraphix(override val queryId: String, size: String, selectivity: String = "increased", rangeIndex: Int = 0) :
        GraphixQuery("smartbench_graphix_$size") {
        private val text = queryText("graphix", queryId, size, selectivity, rangeIndex)

        override fun statement() = text

        // As in STGraph, the threshold of EnvironmentOutlier on the average is applied to the result, outside the timed query
        override fun cardinality(rows: JSONArray): Int =
            if (queryId == "EnvironmentOutlier") {
                (0 until rows.length()).count { rows.getJSONObject(it).optDouble("avgValue", Double.NaN) > 50.5 }
            } else {
                rows.length()
            }
    }
}
