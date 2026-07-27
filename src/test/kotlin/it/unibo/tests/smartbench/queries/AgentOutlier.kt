package it.unibo.tests.smartbench.queries

import it.unibo.graph.interfaces.Direction
import it.unibo.graph.interfaces.Graph
import it.unibo.graph.query.*
import it.unibo.graph.utils.*
import it.unibo.stats.QueryResultData
import it.unibo.stats.Querying
import it.unibo.stats.TestConfig
import org.yaml.snakeyaml.Yaml
import kotlin.jvm.java
import kotlin.system.measureTimeMillis

class AgentOutlier(val graph: Graph, val size: String) : Querying {
    override val queryId = "AgentOutlier"
    val temporalConstraints = TestConfig.loadTemporalRanges("increased", queryId, size)[0]!!
    override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
        val tA = temporalConstraints.from
        val tB = temporalConstraints.to

        val edgesDirectionPattern = listOf(
            Step(Infrastructure, alias = "Environment"),
            EdgeStep(hasCoverage, direction = Direction.IN),
            Step(Sensor, alias = "Device"),
            null,
            null,
            Step(HasTS),
            Step(Temperature, alias = "Measurement")
        )

        val edgesDirectionResult: List<Any>
        val edgesDirectionTime = measureTimeMillis {
            edgesDirectionResult = query(
                graph,
                edgesDirectionPattern,
                by = listOf(
                    Aggregate("Device", "id"),
                    Aggregate("Environment", "id"),
                    Aggregate("Measurement", VALUE, AggOperator.MAX)
                ),
                from = tA,
                to = tB,
                timeaware = true,
                threads = threads,
                mode = queryMode
            )
        }
        return QueryResultData(edgesDirectionTime, edgesDirectionResult.size)
    }
}