package it.unibo.tests.smartbench

import it.unibo.tests.smartbench.loaders.pgageGraph
import it.unibo.tests.smartbench.queries.TimeWindows
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
 * Each query is a template, src/test/resources/queries/smartbench/<system>/<queryId>.txt: {TSTAMPFROM_PARAM} and
 * {TSTAMPTO_PARAM} become the time window of the query on the size (TimeWindows, as the queries of STGraph).
 * The runners (connections, timing) are the ones of it.unibo.tests.systems, shared with MIMIC-IV.
 */
class TestSmartBenchCompetitorsQuery {
    companion object {
        /** The SmartBench queries, in the order of the Python evaluation */
        val queryIds = listOf("EnvironmentCoverage", "EnvironmentAggregate", "MaintenanceOwners", "EnvironmentOutlier", "AgentOutlier", "AgentHistory")

        /** The template of the query [queryId] of [system] (src/test/resources/queries/smartbench/<system>) */
        fun queryText(system: String, queryId: String): String {
            val path = "queries/smartbench/$system/$queryId.txt"
            return TestSmartBenchCompetitorsQuery::class.java.classLoader.getResource(path)?.readText()?.trim()
                ?: error("Query not found in the classpath: $path")
        }

        /** The text of the query [queryId] of [system] on [size], with its time window (TimeWindows) */
        fun queryText(system: String, queryId: String, size: String): String {
            val template = queryText(system, queryId)
            if (!TimeWindows.windowed(queryId)) return template
            val window = TimeWindows.of(queryId, size)
            return template.replace("{TSTAMPFROM_PARAM}", window.from.toString()).replace("{TSTAMPTO_PARAM}", window.to.toString())
        }
    }

    /** A query on the graph loaded by SmartBenchNeo4J, on an open [session] */
    class QNeo4J(
        override val queryId: String,
        size: String,
        session: Session
    ) : Neo4JQuery(session) {
        private val text = queryText("neo4j", queryId, size)

        override fun statement() = text
    }

    /** A query on the graph and the hypertable loaded by SmartBenchPGAge, on an open [connection] (see pgAgeConnection) */
    class QPGAge(
        override val queryId: String,
        size: String,
        connection: Connection
    ) : PGAgeQuery(connection) {
        // {GRAPH}: the graph of the size, whose schema holds the table measurements (see SmartBenchPGAge)
        private val text = queryText("pgage", queryId, size).replace("{GRAPH}", pgageGraph(size))

        override fun statement() = text
    }

    /** A query on the graph created by SmartBenchGraphix */
    class QGraphix(override val queryId: String, size: String, host: String = "localhost", port: Int = 29002) :
        GraphixQuery("smartbench_graphix_$size", host, port) {
        private val text = queryText("graphix", queryId, size)

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
