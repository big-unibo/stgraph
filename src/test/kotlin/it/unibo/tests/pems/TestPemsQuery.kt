package it.unibo.tests.pems

import it.unibo.graph.interfaces.Direction
import it.unibo.graph.interfaces.Graph
import it.unibo.graph.query.AggOperator
import it.unibo.graph.query.Aggregate
import it.unibo.graph.query.EdgeStep
import it.unibo.graph.query.Filter
import it.unibo.graph.query.Operators
import it.unibo.graph.query.QueryMode
import it.unibo.graph.query.Step
import it.unibo.graph.query.query
import it.unibo.graph.utils.LABEL
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
import org.neo4j.driver.Session
import java.sql.Connection

const val pemsFilter = 100L
val pemsQueries = listOf("Q1", "Q2", "Q3", "Q4")

/**
 * The PeMS queries, without time windows:
 * - Q1: average value by segment and measure;
 * - Q2: as Q1, on the values > [pemsFilter];
 * - Q3: average speed (station total) during incidents, by severity;
 * - Q4: average speed (station total) by station type.
 * A measurement belongs to the segment and has the type of its station at its timestamp. STGraph enforces it by the
 * validity of the elements of the path (the inSegment edges, the properties of the stations, the incidents); the
 * competitors with explicit conditions on the half-open [valid_from, valid_to) (resources/queries/pems/<system>/<Q>.txt);
 * the incidents of zero minutes are valid in [t, t + 1) in every system (see AbstractPemsLoader)
 */
class PemsQuery(override val queryId: String, private val graph: Graph) : Querying {
    /** (Segment)<-[inSegment]-(Station)-[hasTimeSeries]->(TimeSeries)-->(m): the segment of the station at the time of m */
    private fun measurements(segment: Step, station: Step, m: Step): List<Step?> =
        listOf(segment, EdgeStep("inSegment", direction = Direction.IN), station, Step("hasTimeSeries"), Step("TimeSeries"), null, m)

    private val avg = Aggregate("m", VALUE, AggOperator.AVG)

    override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
        val (match, by) = when (queryId) {
            "Q1" -> measurements(Step("Segment", alias = "g"), Step("Station"), Step(alias = "m")) to
                listOf(Aggregate("g", "segment_id"), Aggregate("m", LABEL), avg)
            "Q2" -> measurements(Step("Segment", alias = "g"), Step("Station"), Step(alias = "m", properties = listOf(Filter(VALUE, Operators.GT, pemsFilter)))) to
                listOf(Aggregate("g", "segment_id"), Aggregate("m", LABEL), avg)
            "Q3" -> listOf(Step("Incident", alias = "i"), Step("locatedIn")) + measurements(Step("Segment"), Step("Station"), Step("lane0_avg_speed", alias = "m")) to
                listOf(Aggregate("i", "severity"), avg)
            "Q4" -> measurements(Step("Segment"), Step("Station", alias = "s"), Step("lane0_avg_speed", alias = "m")) to
                listOf(Aggregate("s", "type"), avg)
            else -> error("Unknown query: $queryId")
        }
        var result: List<Any> = emptyList()
        val time = getTime { result = query(graph, match, by = by, threads = threads, mode = queryMode) }
        return QueryResultData(time, result.size)
    }
}

class PemsNeo4JQuery(override val queryId: String, session: Session) : Neo4JQuery(session) {
    override fun statement() = queryTemplate("pems", "neo4j", queryId, mapOf("FILTER" to pemsFilter))
}

class PemsPGAgeQuery(override val queryId: String, private val graph: String, connection: Connection) : PGAgeQuery(connection) {
    override fun statement() = queryTemplate("pems", "pgage", queryId, mapOf("FILTER" to pemsFilter, "GRAPH" to graph))
}

class PemsGraphixQuery(override val queryId: String, size: String) : GraphixQuery("pems_graphix_$size") {
    override fun statement() = queryTemplate("pems", "graphix", queryId, mapOf("FILTER" to pemsFilter))
}

class TestPemsQuery {
    @Test
    fun `query PeMS`() = pemsQueries.forEach { id -> TestConfig.runTest(dataset = "pems") { graph, _, _ -> PemsQuery(id, graph) } }
}
