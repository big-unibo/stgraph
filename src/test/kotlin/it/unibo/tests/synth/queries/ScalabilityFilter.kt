package it.unibo.tests.synth.queries

import it.unibo.graph.interfaces.Graph
import it.unibo.graph.query.*
import it.unibo.graph.utils.*
import it.unibo.stats.QueryResultData
import it.unibo.stats.Querying
import kotlin.system.measureTimeMillis

class ScalabilityFilter(val graph: Graph, val thr: Int) : Querying {
    override val queryId = "SynthFilter_foo_$thr"
    override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
        val simplePattern =
            listOf(
                Step(Temperature, properties = listOf(Filter("cardinality", Operators.EQ, thr))),
                null,
                Step(properties = listOf(Filter(VALUE, Operators.LTE, 100L)))
            )
        val result: List<Any>
        val time = measureTimeMillis {
            result = query(graph, simplePattern, timeaware = true, threads = threads, mode = queryMode)
        }
        return QueryResultData(time, result.size)
    }
}