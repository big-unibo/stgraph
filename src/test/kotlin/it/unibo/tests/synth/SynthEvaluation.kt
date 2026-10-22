package it.unibo.tests.synth

import it.unibo.graph.interfaces.Graph
import it.unibo.stats.Config
import it.unibo.stats.Querying
import it.unibo.stats.SetupConfig
import it.unibo.tests.evaluation.DatasetEvaluation
import it.unibo.tests.evaluation.EvaluationConfig
import it.unibo.tests.evaluation.progress
import it.unibo.tests.synth.loaders.SynthDataLoader
import it.unibo.tests.synth.queries.ScalabilityBy
import it.unibo.tests.synth.queries.ScalabilityFilter
import it.unibo.tests.synth.queries.ScalabilityFilterIdx
import it.unibo.tests.synth.queries.ScalabilityFilterProp

/**
 * The evaluation of the synthetic dataset (see it.unibo.tests.evaluation.main), for STGraph only: the ingestion of
 * TestSynthIngestion (SynthDataLoader generates the data) and the queries of TestSynthQuery, one per threshold of
 * SynthDataLoader.ids. The queries have no time window; queries.ids selects their families (e.g., "SynthFilter").
 */
class SynthEvaluation(config: Config, evaluation: EvaluationConfig, size: String) :
    DatasetEvaluation(config, evaluation, "synth", size) {
    override val timeWindows = false

    /** The query families of TestSynthQuery: their ids are "<family>_foo_<threshold>" */
    private val families: Map<String, (Graph, Int) -> Querying> = linkedMapOf(
        "SynthFilter" to ::ScalabilityFilter,
        "SynthFilterIdx" to ::ScalabilityFilterIdx,
        "SynthFilterProp" to ::ScalabilityFilterProp,
        "SynthBy" to ::ScalabilityBy
    )

    override fun run(system: String) {
        require(system == "stgraph") { "$system is not evaluated on $dataset: only stgraph" }
        stgraph("stgraph", { threads, setup ->
            SynthDataLoader(size, threads = threads, host = setup.host, controllerIPs = setup.controllerIPs)
        }) { setup, numMachines -> stgraphQueries(setup, numMachines) }
    }

    /** As TestSynthQuery: the graph of SynthDataLoader is always dumped in datasets/dump/synth/full/ */
    private fun stgraphQueries(setup: SetupConfig, numMachines: Int) =
        withStgraph(setup, "datasets/dump/$dataset/full/", "${dataset}_$size") { graph ->
            val selected = families.filterKeys { evaluation.queryIds.isEmpty() || it in evaluation.queryIds }
            if (selected.isEmpty()) progress("query | stgraph | $dataset $size: no query among ${evaluation.queryIds} (families: ${families.keys})")
            val byId = SynthDataLoader.ids.flatMap { threshold ->
                selected.map { (family, build) -> "${family}_foo_$threshold" to { build(graph, threshold) } }
            }.toMap()
            queries("stgraph", numMachines, byId.keys.toList(), evaluation.threads, evaluation.modes) { queryId, _, _, _ ->
                byId.getValue(queryId)()
            }
        }
}
