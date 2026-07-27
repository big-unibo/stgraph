package it.unibo.tests.mimic

import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.interfaces.Graph
import it.unibo.graph.query.*
import it.unibo.graph.utils.LABEL
import it.unibo.graph.utils.Person
import it.unibo.graph.utils.VALUE
import it.unibo.graph.utils.getTime
import it.unibo.stats.QueryResultData
import it.unibo.stats.Querying
import it.unibo.stats.TestConfig
import it.unibo.stats.runQuery
import it.unibo.tests.mimic.loaders.limitToPort
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test

const val mimicFilter = 100L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestMimicQuery {

    abstract class Q : Querying {
        override val queryId = this::class.simpleName.toString()
        override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
            var res: List<Any> = listOf()
            val time = getTime {
                res = query(threads, queryMode)
            }
            if (res.isEmpty()) throw IllegalArgumentException("$queryId: Empty result")
            return QueryResultData(time, res.size)
        }

        abstract fun query(threads: Int, queryMode: QueryMode): List<Any>
    }

    abstract class QNeo4J(val uri: String = "bolt://localhost:7687", val user: String = "neo4j", val pwd: String = "password") : Querying {
        override val queryId = this::class.simpleName.toString()
        override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
            val res = mutableListOf<List<Any?>>()
            val time: Long
            GraphDatabase.driver(uri, AuthTokens.basic(user, pwd)).use { driver ->
                driver.session().use { session ->
                     time = getTime {
                        val result = session.run(query())
                        // Iterate over rows
                        while (result.hasNext()) {
                            val record = result.next()
                            // Get all values in the row as Any
                            val rowValues = record.keys().map { key ->
                                val value = record[key]
                                when {
                                    value.isNull -> null
                                    else -> value.asObject()
                                }
                            }
                            res.add(rowValues)
                        }
                    }
                }
            }
            return QueryResultData(time, res.size)
        }

        abstract fun query(): String
    }

    abstract class QPgAge(
        val graph: String,
        val url: String = "jdbc:postgresql://192.168.30.108:5435/mimic-iv",
        val user: String = "postgres",
        val pwd: String = "password"
    ) : Querying {

        override val queryId = this::class.simpleName.toString()

        override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
            val res = mutableListOf<List<Any?>>()
            val time: Long
            DriverManager.getConnection(url, user, pwd).use { conn ->
                conn.createStatement().use { stmt ->
                    time = getTime {
                        val sql = query()
                        stmt.execute("LOAD 'age'")
                        stmt.execute("SET search_path = ag_catalog, public")
                        val rs = stmt.executeQuery(sql)
                        val meta = rs.metaData
                        val colCount = meta.columnCount
                        while (rs.next()) {
                            val row = ArrayList<Any?>(colCount)
                            for (i in 1..colCount) {
                                val value = rs.getObject(i)
                                row.add(value)
                            }
                            res.add(row)
                        }
                    }
                }
            }
            return QueryResultData(time, res.size)
        }
        abstract fun query(): String
    }

    class Q1(val graph: Graph, pId: Long = Long.MIN_VALUE, c: Long = Long.MIN_VALUE) : Q() {
        override val queryId: String = super.queryId + (if (pId != Long.MIN_VALUE) "_${pId}_$c" else "")
        override fun query(threads: Int, queryMode: QueryMode): List<Any> {
            return query(
                graph,
                listOf(
                    Step(Person, properties = listOf(Filter("c", Operators.GT, 25000))),
                    null,
                    Step("TimeSeries"),
                    null,
                    Step(alias = "m", properties = listOf(Filter(VALUE, Operators.GT, mimicFilter)))
                ),
                threads = threads,
                mode = queryMode
            )
        }
    }

    class Q1Neo4J(uri: String) : QNeo4J(uri) {
        override fun query(): String = "MATCH (p)-->(t)-->(m) WHERE toFloat(p.c) > 25000 AND toFloat(m.value) > $mimicFilter RETURN p, t, m"
    }

    class Q1PGAge(graph: String) : QPgAge(graph) {
        override fun query(): String = """
            SELECT *
            FROM (
                SELECT *
                FROM cypher('$graph'::name, $$
                    MATCH (p)--(t)
                    WHERE p.c > 25000
                    RETURN DISTINCT p, t, id(t)
                $$) AS (p agtype, t agtype, tsid bigint)
            ) t
            JOIN $graph.measurements_csv m ON m.ts_id = t.tsid
            WHERE m.value > $mimicFilter;
        """.trimIndent()
    }

    @Test
    fun `get measurements`() = TestConfig.runTest(dataset = "mimic") { graph, size, mode ->
        if (mode == QueryMode.NAIVE && size == "full") null else Q1(graph)
    }

    class Q2(val graph: Graph, pId: Long = Long.MIN_VALUE, c: Long = Long.MIN_VALUE) : Q() {
        override val queryId: String = super.queryId + (if (pId != Long.MIN_VALUE) "_${pId}_$c" else "")
        override fun query(threads: Int, queryMode: QueryMode): List<Any> {
            return query(
                graph,
                listOf(
                    Step(Person, properties = listOf(Filter("c", Operators.GT, 25000))),
                    null,
                    Step("TimeSeries"),
                    null,
                    Step(alias = "m")
                ),
                by = listOf(Aggregate("m", LABEL), Aggregate("m", VALUE, operator = AggOperator.AVG)),
                threads = threads,
                mode = queryMode
            )
        }
    }

    class Q2Neo4J(uri: String) : QNeo4J(uri) {
        override fun query(): String = "MATCH (p)-->(t)-->(m) WHERE toFloat(p.c) > 25000 RETURN m.label, avg(toFloat(m.value))"
        // override fun query(): String = "MATCH (p)-->(t)-->(m) WHERE toFloat(p.c) > 25000 UNWIND labels(m) AS label RETURN label, avg(toFloat(m.value))" same timing
    }

    class Q2PGAge(graph: String) : QPgAge(graph) {
        override fun query(): String = """
                SELECT m.label, avg(m.value)
                FROM (
                    SELECT *
                    FROM cypher('$graph'::name, $$
                        MATCH (p)--(t)
                        WHERE p.c > 25000
                        RETURN DISTINCT id(t)
                    $$) AS (tsid bigint)
                ) t
                JOIN $graph.measurements_csv m ON m.ts_id = t.tsid
                GROUP BY m.label
        """.trimIndent()
    }

    @Test
    fun `get average measurements`() = TestConfig.runTest(dataset = "mimic") { graph, size, mode ->
        if (mode == QueryMode.NAIVE && size == "full") null else Q2(graph)
    }

    class Q3(val graph: Graph) : Q() {
        override fun query(threads: Int, queryMode: QueryMode): List<Any> {
            return query(
                graph,
                listOf(
                    Step(Person, properties = listOf(Filter("c", Operators.GT, 25000))),
                    null,
                    Step("TimeSeries"),
                    null,
                    Step(alias = "m", properties = listOf(Filter(VALUE, Operators.GT, mimicFilter)))
                ),
                by = listOf(Aggregate("m", LABEL), Aggregate("m", VALUE, operator = AggOperator.AVG)),
                threads = threads,
                mode = queryMode
            )
        }
    }

    class Q3Neo4J(uri: String) : QNeo4J(uri) {
        override fun query(): String = "MATCH (p)-->(t)-->(m) WHERE toFloat(p.c) > 25000 AND toFloat(m.value) > $mimicFilter RETURN m.abbreviation, avg(toFloat(m.value))"
    }

    class Q3PGAge(graph: String) : QPgAge(graph) {
        override fun query(): String = """
                SELECT m.label, avg(m.value)
                FROM (
                    SELECT *
                    FROM cypher('$graph'::name, $$
                        MATCH (p)--(t)
                        WHERE p.c > 25000
                        RETURN DISTINCT id(t)
                    $$) AS (tsid bigint)
                ) t
                JOIN $graph.measurements_csv m ON m.ts_id = t.tsid
                WHERE m.value > $mimicFilter
                GROUP BY m.label
        """.trimIndent()
    }

    @Test
    fun `filter and group by measurements`() = TestConfig.runTest(dataset = "mimic") { graph, size, mode ->
        if (mode == QueryMode.NAIVE && size == "full") null else Q3(graph)
    }

    @Test
    fun testMode() {
        val dataset = "mimic"
        val size = "full" // "full" // "1692200"
        val path = "datasets/dump/$dataset/$size/"
        val graph = MemoryGraphACID.readFromDisk(path)
        val tsm = AsterixDBTSM.createDefault(
            graph,
            host = "192.168.30.110",
            controllerIps = listOf("192.168.30.110"),
            dataverse = "${dataset}_$size",
            multiTs = false // I only need to read the data
        )
        graph.tsm = tsm
        File("src/main/resources/mimic-iv_subjectids_tsids.csv").useLines { lines ->
            lines
                .toList()
                .map { it.trim().split(",") }
                .map { Triple(it[0].toLong(), it[1].toLong(), it[2].toLong()) }
                .groupBy { it.first }
                .map { Pair(it.key, it.value.sumOf { it.third }) }
                .filterIndexed { index, _ -> index % 10 == 0 }
                .take(100)
                .forEach { (subject_id, c) ->
                    listOf(1, 2).forEach {
                        QueryMode.entries.forEach { mode ->
                            runQuery(Q1(graph, subject_id, c), "stgraph", threads = 1, numMachines = 1, dataset, size = size, mode = mode)
                            runQuery(Q2(graph, subject_id, c), "stgraph", threads = 1, numMachines = 1, dataset, size = size, mode = mode)
                        }
                    }
                }
        }
        graph.close()
    }
}

fun main() {
    TestConfig.loadConfig().datasets["mimic"]!!.sizes.forEach { size ->
        listOf(
            TestMimicQuery.Q1Neo4J("bolt://localhost:${limitToPort(size.toString().toLong())}"),
            TestMimicQuery.Q2Neo4J("bolt://localhost:${limitToPort(size.toString().toLong())}"),
            TestMimicQuery.Q3Neo4J("bolt://localhost:${limitToPort(size.toString().toLong())}")
        ).forEach { query ->
            println(query.javaClass.toString())
            runQuery(query, "neo4j", threads = 1, numMachines = 1, "mimic", size.toString(), mode = QueryMode.OPTIMIZED)
        }

        listOf(
            TestMimicQuery.Q1PGAge("mimic_$size"),
            TestMimicQuery.Q2PGAge("mimic_$size"),
            TestMimicQuery.Q3PGAge("mimic_$size"),
        ).forEach { query ->
            println(query.javaClass.toString())
            runQuery(query, "pgage", threads = 1, numMachines = -1, "mimic", size.toString(), mode = QueryMode.OPTIMIZED)
        }
    }
}