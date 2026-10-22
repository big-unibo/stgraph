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
import it.unibo.tests.systems.GraphixQuery
import it.unibo.tests.mimic.loaders.limitToPort
import it.unibo.tests.mimic.loaders.mimicLimit
import org.junit.jupiter.api.TestInstance
import it.unibo.tests.systems.Neo4JQuery
import it.unibo.tests.systems.PGAgeQuery
import it.unibo.tests.systems.neo4jDriver
import it.unibo.tests.systems.pgAgeConnection
import org.neo4j.driver.Session
import java.io.File
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

const val mimicFilter = 100L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestMimicQuery {
    /** Expected cardinalities per dataset size: x1692200 is the reduced dump used by the CI */
    private val expectedCardinalities = mapOf(
        "x1692200" to mapOf("Q1" to 7347, "Q2" to 7, "Q3" to 5)
    )

    /** For the sizes with known cardinalities, fails the test if [query] returns a different number of results */
    private fun checked(size: String, query: Querying?): Querying? {
        val expected = expectedCardinalities[size] ?: return query
        if (query == null) return null
        return object : Querying {
            override val queryId = query.queryId
            override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData =
                query.runQuery(threads, queryMode).also {
                    assertEquals(expected.getValue(queryId), it.card, "Cardinality of $queryId on $size ($queryMode, $threads threads)")
                }
        }
    }

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

    companion object {
        /** The Neo4j and the PostgreSQL (PGAge) of the MIMIC-IV evaluation */
        const val NEO4J_USER = "neo4j"
        const val NEO4J_PASSWORD = "password"
        const val PGAGE_URL = "jdbc:postgresql://192.168.30.108:5435/mimic-iv"
        const val PGAGE_USER = "postgres"
        const val PGAGE_PASSWORD = "password"
    }

    /** A query on the Neo4j loaded by MimicIVNeo4J, on an open [session] (runner of it.unibo.tests.systems) */
    abstract class QNeo4J(session: Session) : Neo4JQuery(session) {
        override val queryId = this::class.simpleName.toString()
        abstract fun query(): String
        override fun statement() = query()
    }

    /** A query on the PostgreSQL loaded by MimicIVPGAge, on an open [connection] (see pgAgeConnection) */
    abstract class QPgAge(val graph: String, connection: Connection) : PGAgeQuery(connection) {
        override val queryId = this::class.simpleName.toString()
        abstract fun query(): String
        override fun statement() = query()
    }

    /** A query on the graph created by MimicIVGraphix */
    abstract class QGraphix(size: String) : GraphixQuery("mimic_graphix_$size") {
        override val queryId = this::class.simpleName.toString()
        protected val path = "FROM GRAPH Mimic MATCH (p:`Person`)-[:`hasParameters`]->(t:`TimeSeries`)-[:`hasMeasurement`]->(m:`Measurement`)"
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

    class Q1Neo4J(session: Session) : QNeo4J(session) {
        override fun query(): String = "MATCH (p)-->(t)-->(m) WHERE toFloat(p.c) > 25000 AND toFloat(m.value) > $mimicFilter RETURN p, t, m"
    }

    class Q1PGAge(graph: String, connection: Connection) : QPgAge(graph, connection) {
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

    class Q1Graphix(size: String) : QGraphix(size) {
        override fun statement(): String = "$path WHERE p.c > 25000 AND m.`value` > $mimicFilter SELECT p, t, m;"
    }

    @Test
    fun `get measurements`() = TestConfig.runTest(dataset = "mimic") { graph, size, mode ->
        checked(size, if (mode == QueryMode.NAIVE && size == "full") null else Q1(graph))
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

    class Q2Neo4J(session: Session) : QNeo4J(session) {
        override fun query(): String = "MATCH (p)-->(t)-->(m) WHERE toFloat(p.c) > 25000 RETURN labels(m), avg(toFloat(m.value))"
    }

    class Q2PGAge(graph: String, connection: Connection) : QPgAge(graph, connection) {
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

    class Q2Graphix(size: String) : QGraphix(size) {
        override fun statement(): String = "$path WHERE p.c > 25000 GROUP BY m.`label` AS lbl SELECT lbl, AVG(m.`value`) AS avgValue;"
    }

    @Test
    fun `get average measurements`() = TestConfig.runTest(dataset = "mimic") { graph, size, mode ->
        checked(size, if (mode == QueryMode.NAIVE && size == "full") null else Q2(graph))
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

    class Q3Neo4J(session: Session) : QNeo4J(session) {
        override fun query(): String = "MATCH (p)-->(t)-->(m) WHERE toFloat(p.c) > 25000 AND toFloat(m.value) > $mimicFilter RETURN m.abbreviation, avg(toFloat(m.value))"
    }

    class Q3PGAge(graph: String, connection: Connection) : QPgAge(graph, connection) {
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

    class Q3Graphix(size: String) : QGraphix(size) {
        override fun statement(): String = "$path WHERE p.c > 25000 AND m.`value` > $mimicFilter GROUP BY m.`label` AS lbl SELECT lbl, AVG(m.`value`) AS avgValue;"
    }

    @Test
    fun `filter and group by measurements`() = TestConfig.runTest(dataset = "mimic") { graph, size, mode ->
        checked(size, if (mode == QueryMode.NAIVE && size == "full") null else Q3(graph))
    }

    // @Test
    // fun testMode() {
    //     val dataset = "mimic"
    //     val size = "1692200" // "full"
    //     val path = "datasets/dump/$dataset/$size/"
    //     val graph = MemoryGraphACID.readFromDisk(path)
    //     val tsm = AsterixDBTSM.createDefault(
    //         graph,
    //         host = "192.168.30.110",
    //         controllerIps = listOf("192.168.30.110"),
    //         dataverse = "${dataset}_$size",
    //         multiTs = false // I only need to read the data
    //     )
    //     graph.tsm = tsm
    //     File("src/main/resources/mimic-iv_subjectids_tsids.csv").useLines { lines ->
    //         lines
    //             .toList()
    //             .map { it.trim().split(",") }
    //             .map { Triple(it[0].toLong(), it[1].toLong(), it[2].toLong()) }
    //             .groupBy { it.first }
    //             .map { Pair(it.key, it.value.sumOf { it.third }) }
    //             .filterIndexed { index, _ -> index % 10 == 0 }
    //             .take(100)
    //             .forEach { (subjectId, c) ->
    //                 listOf(1, 2).forEach {
    //                     QueryMode.entries.forEach { mode ->
    //                         runQuery(Q1(graph, subjectId, c), "stgraph", threads = 1, numMachines = 1, dataset, size = size, mode = mode)
    //                         runQuery(Q2(graph, subjectId, c), "stgraph", threads = 1, numMachines = 1, dataset, size = size, mode = mode)
    //                     }
    //                 }
    //             }
    //     }
    //     graph.close()
    // }
}

fun main() {
    TestConfig.loadConfig().datasets["mimic"]!!.sizes.forEach { size ->
        // The sessions and the connections are opened once, not timed (runners of it.unibo.tests.systems)
        val neo4jUri = "bolt://localhost:${limitToPort(mimicLimit(size.toString()))}"
        neo4jDriver(neo4jUri, TestMimicQuery.NEO4J_USER, TestMimicQuery.NEO4J_PASSWORD).use { driver ->
            driver.session().use { session ->
                listOf(
                    TestMimicQuery.Q1Neo4J(session),
                    TestMimicQuery.Q2Neo4J(session),
                    TestMimicQuery.Q3Neo4J(session)
                ).forEach { query ->
                    println(query.javaClass.toString())
                    runQuery(query, "neo4j", threads = 1, numMachines = 1, "mimic", size.toString(), mode = QueryMode.OPTIMIZED)
                }
            }
        }

        pgAgeConnection(TestMimicQuery.PGAGE_URL, TestMimicQuery.PGAGE_USER, TestMimicQuery.PGAGE_PASSWORD).use { connection ->
            listOf(
                TestMimicQuery.Q1PGAge("mimic_$size", connection),
                TestMimicQuery.Q2PGAge("mimic_$size", connection),
                TestMimicQuery.Q3PGAge("mimic_$size", connection),
            ).forEach { query ->
                println(query.javaClass.toString())
                runQuery(query, "pgage", threads = 1, numMachines = -1, "mimic", size.toString(), mode = QueryMode.OPTIMIZED)
            }
        }

        listOf(
            TestMimicQuery.Q1Graphix(size.toString()),
            TestMimicQuery.Q2Graphix(size.toString()),
            TestMimicQuery.Q3Graphix(size.toString()),
        ).forEach { query ->
            println(query.javaClass.toString())
            runQuery(query, "graphix", threads = 1, numMachines = 1, "mimic", size.toString(), mode = QueryMode.OPTIMIZED)
        }
    }
}