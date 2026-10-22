package it.unibo.tests.smartbench

import it.unibo.graph.query.QueryMode
import it.unibo.graph.utils.getTime
import it.unibo.stats.QueryResultData
import it.unibo.stats.Querying
import it.unibo.stats.TestConfig
import it.unibo.stats.runQuery
import it.unibo.tests.graphix.GraphixQuery
import org.json.JSONArray
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import java.sql.DriverManager

/**
 * The SmartBench queries of the competitors (Neo4j, PGAge, Graphix), with the same query ids as STGraph
 * (TestSmartBenchQuery). Not tests, since the CI covers STGraph only: run [main].
 *
 * Each query is a template, src/test/resources/smartbench/queries/<system>/<queryId>.txt: as in the Python evaluation,
 * {TSTAMPFROM_PARAM} and {TSTAMPTO_PARAM} become the time window, here the one of STGraph (selectivity "increased",
 * first range of time_constraints.yaml).
 */
class TestSmartBenchCompetitorsQuery {
    companion object {
        /** The SmartBench queries, in the order of the Python evaluation */
        val queryIds = listOf("EnvironmentCoverage", "EnvironmentAggregate", "MaintenanceOwners", "EnvironmentOutlier", "AgentOutlier", "AgentHistory")

        /** The text of the query [queryId] of [system] on [size] */
        fun queryText(system: String, queryId: String, size: String): String {
            val path = "smartbench/queries/$system/$queryId.txt"
            val template = TestSmartBenchCompetitorsQuery::class.java.classLoader.getResource(path)?.readText()?.trim()
                ?: error("Query not found in the classpath: $path")
            if (!template.contains("{TSTAMPFROM_PARAM}") && !template.contains("{TSTAMPTO_PARAM}")) return template
            val window = TestConfig.loadTemporalRanges("increased", queryId, size)[0]!!
            return template.replace("{TSTAMPFROM_PARAM}", window.from.toString()).replace("{TSTAMPTO_PARAM}", window.to.toString())
        }
    }

    /** Timed: execution of the query and transfer of the whole result (as driver.execute_query in neo4j_query.py) */
    class QNeo4J(override val queryId: String, size: String, private val uri: String = "bolt://localhost:7687") : Querying {
        private val text = queryText("neo4j", queryId, size)

        override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
            GraphDatabase.driver(uri, AuthTokens.none()).use { driver ->
                driver.session().use { session ->
                    var rows = 0
                    val time = getTime { rows = session.run(text).list().size }
                    return QueryResultData(time, rows)
                }
            }
        }
    }

    /**
     * Timed: execution of the query and transfer of the whole result (cur.execute in pg_connector.py, which fetches
     * the rows); the session settings of pg_connector.py come first, not timed
     */
    class QPGAge(
        override val queryId: String,
        size: String,
        private val url: String = "jdbc:postgresql://localhost:5432/smartbench_eval",
        private val user: String = "postgres",
        private val pwd: String = "psw"
    ) : Querying {
        private val text = queryText("pgage", queryId, size)

        override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
            DriverManager.getConnection(url, user, pwd).use { conn ->
                conn.createStatement().use { st ->
                    st.execute("LOAD 'age';")
                    st.execute("SET search_path = ag_catalog, \"\$user\", public;")
                    st.execute("SET max_parallel_workers_per_gather = 0;")
                    st.execute("SET max_parallel_workers = 0;")
                    var rows = 0
                    val time = getTime { st.executeQuery(text).use { rs -> while (rs.next()) rows++ } }
                    return QueryResultData(time, rows)
                }
            }
        }
    }

    /** A query on the graph created by SmartBenchGraphix */
    class QGraphix(override val queryId: String, size: String) : GraphixQuery("smartbench_graphix_$size") {
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

/**
 * Runs the queries of the competitors for each SmartBench size of test_config.yml. The systems must be running, with
 * the data loaded (TestSmartBenchCompetitorsIngestion.kt): comment out the systems not under test
 */
fun main() {
    TestConfig.loadConfig().datasets["smartbench"]!!.sizes.forEach { rawSize ->
        val size = TestConfig.normalizeSize(rawSize)
        TestSmartBenchCompetitorsQuery.queryIds.forEach { queryId ->
            runQuery(TestSmartBenchCompetitorsQuery.QNeo4J(queryId, size), "neo4j", threads = 1, numMachines = 1, "smartbench", size, QueryMode.OPTIMIZED)
            runQuery(TestSmartBenchCompetitorsQuery.QPGAge(queryId, size), "pgage", threads = 1, numMachines = 1, "smartbench", size, QueryMode.OPTIMIZED)
            runQuery(TestSmartBenchCompetitorsQuery.QGraphix(queryId, size), "graphix", threads = 1, numMachines = 1, "smartbench", size, QueryMode.OPTIMIZED)
        }
    }
}
