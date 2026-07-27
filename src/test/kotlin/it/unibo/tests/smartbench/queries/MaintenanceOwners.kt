package it.unibo.tests.smartbench.queries

import it.unibo.graph.interfaces.Direction
import it.unibo.graph.interfaces.Graph
import it.unibo.graph.query.*
import it.unibo.graph.utils.*
import it.unibo.stats.QueryResultData
import it.unibo.stats.Querying
import it.unibo.stats.TestConfig
import kotlin.system.measureTimeMillis

class MaintenanceOwners(val graph: Graph, val size: String) : Querying {
    override val queryId = "MaintenanceOwners"
    val temporalConstraints = TestConfig.loadTemporalRanges("increased", queryId, size)[0]!!
    override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
        val tA = temporalConstraints.from
        val tB = temporalConstraints.to
        val minTemp = 65L
        val simplePattern = listOf(
            listOf(
                Step(Infrastructure, alias = "Environment"),
                EdgeStep(hasCoverage, direction = Direction.IN),
                Step(Sensor, alias = "Device"),
                null,
                null,
                Step(HasTS),
                Step(Temperature, properties = listOf(Filter(VALUE, Operators.GTE, minTemp)))
            ),
            listOf(
                Step(Sensor, alias = "Device2"),
                Step(hasOwner),
                Step(User, alias = "Owner")
            )
        )
        val edgesDirectionResult: List<Any>
        val edgesDirectionQueryTime = measureTimeMillis {
            edgesDirectionResult = query(
                graph, simplePattern,
                where = listOf(Compare("Device", "Device2", "id", Operators.EQ)),
                by = listOf(
                    Aggregate("Device", "id"),
                    Aggregate("Environment", "id"),
                    Aggregate("Owner", "id")
                ),
                from = tA,
                to = tB,
                timeaware = true,
                threads = threads,
                mode = queryMode
            )
        }

        return QueryResultData(edgesDirectionQueryTime, edgesDirectionResult.size)
    }
}