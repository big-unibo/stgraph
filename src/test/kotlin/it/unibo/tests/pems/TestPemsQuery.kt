package it.unibo.tests.pems

import it.unibo.graph.interfaces.Direction
import it.unibo.graph.interfaces.Graph
import it.unibo.graph.query.AggOperator
import it.unibo.graph.query.Aggregate
import it.unibo.graph.query.EdgeStep
import it.unibo.graph.query.QueryMode
import it.unibo.graph.query.Step
import it.unibo.graph.query.query
import it.unibo.graph.utils.VALUE
import it.unibo.graph.utils.getTime
import it.unibo.stats.QueryResultData
import it.unibo.stats.Querying
import it.unibo.stats.TestConfig
import it.unibo.tests.systems.GraphixQuery
import it.unibo.tests.systems.Neo4JQuery
import it.unibo.tests.systems.PGAgeQuery
import it.unibo.tests.systems.queryTemplate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import org.neo4j.driver.Session
import java.sql.Connection

val pemsQueries = listOf("Q1", "Q2", "Q3", "Q4", "Q5")

/**
 * The time conditions of the PeMS queries (epoch ms, UTC), the same on every size: Q3 the narrow window (1 January 2025),
 * Q4 the broad one (January 2025), Q5 the instant T (1 January 2025, 00:00) at which the stations are active
 */
object PemsWindows {
    const val T = 1735689600000L // 2025-01-01
    val windows = mapOf("Q3" to (1735689600000L to 1735776000000L), "Q4" to (1735689600000L to 1738368000000L)) // [from, to)

    /** The values of the placeholders {FROM}, {TO} and {T} of the query files */
    fun params(queryId: String): Map<String, Any> =
        (windows[queryId] ?: (Long.MIN_VALUE to Long.MAX_VALUE)).let { (from, to) -> mapOf("FROM" to from, "TO" to to, "T" to T) }
}

/**
 * The PeMS queries, on the speed of the stations (lane0_avg_speed, the station total), each measurement with the segment
 * of its station at its timestamp (the validity of inSegment, see PemsSTGraph):
 * - Q1: average speed by station;
 * - Q2: average speed by segment;
 * - Q3, Q4: as Q2, in the narrow and in the broad window (PemsWindows);
 * - Q5: average speed (of all time) by station, of the stations in a segment at T (PemsWindows.T): the stations at T,
 *   then their measurements.
 * The competitors, without temporal graphs, match the station versions with explicit conditions on the half-open
 * [valid_from, valid_to) (resources/queries/pems/<system>/<Q>.txt)
 */
class PemsQuery(override val queryId: String, private val graph: Graph) : Querying {
    /** (Segment)<-[inSegment]-(Station)-[hasTimeSeries]->(TimeSeries)-->(m): the segment of the station at the time of m */
    private val match: List<Step?> = listOf(Step("Segment", alias = "g"), EdgeStep("inSegment", direction = Direction.IN), Step("Station", alias = "s"),
        Step("hasTimeSeries"), Step("TimeSeries"), null, Step("lane0_avg_speed", alias = "m"))

    private val avg = Aggregate("m", VALUE, AggOperator.AVG)

    override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
        val byStation = listOf(Aggregate("s", "station_id"), avg)
        val bySegment = listOf(Aggregate("g", "segment_id"), avg)
        var result: List<Any> = emptyList()
        val time = getTime {
            result = when (queryId) {
                "Q1" -> query(graph, match, by = byStation, threads = threads, mode = queryMode)
                "Q2" -> query(graph, match, by = bySegment, threads = threads, mode = queryMode)
                "Q3", "Q4" -> PemsWindows.windows.getValue(queryId).let { (from, to) ->
                    query(graph, match, by = bySegment, from = from, to = to, threads = threads, mode = queryMode)
                }
                "Q5" -> {
                    val active = query(graph, match.take(3), by = listOf(Aggregate("s", "station_id")), from = PemsWindows.T, to = PemsWindows.T,
                        threads = threads, mode = queryMode).toSet()
                    query(graph, match, by = byStation, threads = threads, mode = queryMode).filter { (it as List<*>)[0] in active }
                }
                else -> error("Unknown query: $queryId")
            }
        }
        return QueryResultData(time, result.size)
    }
}

class PemsNeo4JQuery(override val queryId: String, session: Session) : Neo4JQuery(session) {
    override fun statement() = queryTemplate("pems", "neo4j", queryId, PemsWindows.params(queryId))
}

class PemsPGAgeQuery(override val queryId: String, private val graph: String, connection: Connection) : PGAgeQuery(connection) {
    override fun statement() = queryTemplate("pems", "pgage", queryId, PemsWindows.params(queryId) + ("GRAPH" to graph))
}

class PemsGraphixQuery(override val queryId: String, size: String) : GraphixQuery("pems_graphix_$size") {
    override fun statement() = queryTemplate("pems", "graphix", queryId, PemsWindows.params(queryId))
}

class TestPemsQuery {
    /**
     * Expected cardinalities on xsmall, the dataset of the CI (datasets/original/pems/xsmall.dump): 1000 hourly events of
     * lane 0 of station 3088051 (segment 50_W_37, a single version, always valid), from 2025-01-01 (about 14 days): Q1
     * and Q5 one station, Q2, Q3 and Q4 one segment
     */
    private val expectedXSmall = mapOf("Q1" to 1, "Q2" to 1, "Q3" to 1, "Q4" to 1, "Q5" to 1)

    /** On xsmall, fails the test if [query] does not return the expected cardinality (any mode and number of threads) */
    private fun checked(size: String, query: Querying): Querying =
        if (size != "xsmall") query else object : Querying {
            override val queryId = query.queryId
            override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData =
                query.runQuery(threads, queryMode).also {
                    assertEquals(expectedXSmall.getValue(queryId), it.card, "Cardinality of $queryId on $size ($queryMode, $threads threads)")
                }
        }

    @Test
    fun `query PeMS`() = pemsQueries.forEach { id -> TestConfig.runTest(dataset = "pems") { graph, size, _ -> checked(size, PemsQuery(id, graph)) } }
}
