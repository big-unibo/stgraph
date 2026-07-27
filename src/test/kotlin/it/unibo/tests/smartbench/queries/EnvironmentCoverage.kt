package it.unibo.tests.smartbench.queries

import it.unibo.graph.interfaces.Direction
import it.unibo.graph.interfaces.Graph
import it.unibo.graph.query.*
import it.unibo.graph.utils.*
import it.unibo.stats.QueryResultData
import it.unibo.stats.Querying
import kotlin.system.measureTimeMillis

class EnvironmentCoverage(val graph: Graph) : Querying {
    override val queryId = "EnvironmentCoverage"
    override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
        val infrastructureId = "3042"
        val edgesDirectionPattern = listOf(
            Step(Infrastructure, listOf(Filter("id", Operators.EQ, infrastructureId)), alias = "Environment"),
            EdgeStep(hasCoverage, direction = Direction.IN),
            Step(Sensor, alias = "device"),
            Step(hasTemperature),
            Step(Temperature)
        )
        var edgesDirectionResult: List<Any>
        val edgesDirectionTime: Long = measureTimeMillis {
            edgesDirectionResult = query(
                graph,
                edgesDirectionPattern,
                by = listOf(Aggregate("Environment", "id"), Aggregate("device", "id")),
                timeaware = false,
                threads = threads,
                mode = queryMode
            )
        }
        return QueryResultData(edgesDirectionTime, edgesDirectionResult.size)
    }
}