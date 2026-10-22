package it.unibo.tests.smartbench

import it.unibo.graph.query.QueryMode
import it.unibo.graph.utils.getTime
import it.unibo.stats.QueryResultData
import it.unibo.stats.Querying
import it.unibo.stats.TestConfig
import it.unibo.tests.graphix.GraphixQuery
import org.json.JSONArray
import org.neo4j.driver.Session
import java.sql.Connection

/**
 * The SmartBench queries of the competitors (Neo4j, PGAge, Graphix), with the same query ids as STGraph
 * (TestSmartBenchQuery). Not tests, since the CI covers STGraph only: they run in the evaluation (SmartBenchEvaluation).
 *
 * Each query is a template, src/test/resources/smartbench/queries/<system>/<queryId>.txt: as in the Python evaluation,
 * {TSTAMPFROM_PARAM} and {TSTAMPTO_PARAM} become the time window [rangeIndex] of [selectivity] in time_constraints.yaml.
 * The connections are opened by the caller, so that runQuery (which times the whole call) times only the query.
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

    /** Timed: execution of the query and transfer of the whole result (as driver.execute_query in neo4j_query.py) */
    class QNeo4J(
        override val queryId: String,
        size: String,
        private val session: Session,
        selectivity: String = "increased",
        rangeIndex: Int = 0
    ) : Querying {
        private val text = queryText("neo4j", queryId, size, selectivity, rangeIndex)

        override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
            var rows = 0
            val time = getTime { rows = session.run(text).list().size }
            return QueryResultData(time, rows)
        }
    }

    /**
     * Timed: execution of the query and transfer of the whole result (cur.execute in pg_connector.py, which fetches
     * the rows). [connection] must have the session settings of pg_connector.py (see [pgAgeSession])
     */
    class QPGAge(
        override val queryId: String,
        size: String,
        private val connection: Connection,
        selectivity: String = "increased",
        rangeIndex: Int = 0
    ) : Querying {
        private val text = queryText("pgage", queryId, size, selectivity, rangeIndex)

        override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
            var rows = 0
            val time = getTime {
                connection.createStatement().use { st -> st.executeQuery(text).use { rs -> while (rs.next()) rows++ } }
            }
            return QueryResultData(time, rows)
        }

        companion object {
            /** The settings that pg_connector.py applies before each query */
            fun pgAgeSession(connection: Connection): Connection = connection.apply {
                createStatement().use { st ->
                    st.execute("LOAD 'age';")
                    st.execute("SET search_path = ag_catalog, \"\$user\", public;")
                    st.execute("SET max_parallel_workers_per_gather = 0;")
                    st.execute("SET max_parallel_workers = 0;")
                }
            }
        }
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
