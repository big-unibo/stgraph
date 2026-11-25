package it.unibo.tests.synth

import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.stats.stgraphDataverse
import it.unibo.stats.stgraphDump
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.query.QueryMode
import it.unibo.graph.utils.resetPort
import it.unibo.stats.runQuery
import it.unibo.tests.synth.loaders.SynthDataLoader
import it.unibo.tests.synth.queries.ScalabilityBy
import it.unibo.tests.synth.queries.ScalabilityFilter
import it.unibo.tests.synth.queries.ScalabilityFilterIdx
import it.unibo.tests.synth.queries.ScalabilityFilterProp
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestSynthQuery {
    @Test
    fun testMode() {
        val dataset = "synth"
        val size = "full"
        val path = stgraphDump(dataset, size)
        // Only after its ingestion (synth in stgraphRuns)
        org.junit.jupiter.api.Assumptions.assumeTrue(java.io.File(path, "nodes.dat").isFile, "No synth graph in $path")
        val graph = MemoryGraphACID.readFromDisk(path)
        val tsm = AsterixDBTSM.createDefault(
            graph,
            host = "localhost",
            controllerIps = listOf("localhost"),
            dataverse = stgraphDataverse(dataset, size)
        )
        graph.tsm = tsm
        listOf(1..3).forEach { _ ->
            SynthDataLoader.ids.forEach { thr ->
                //listOf(QueryMode.OPTIMIZED).forEach { mode ->
                QueryMode.entries.forEach { mode ->
                    resetPort()
                    runQuery(ScalabilityFilter(graph, thr), "stgraph", threads = 1, numMachines = 1, dataset, size = size, mode = mode)
                    runQuery(ScalabilityFilterIdx(graph, thr), "stgraph", threads = 1, numMachines = 1, dataset, size = size, mode = mode)
                    runQuery(ScalabilityFilterProp(graph, thr), "stgraph", threads = 1, numMachines = 1, dataset, size = size, mode = mode)
                    runQuery(ScalabilityBy(graph, thr), "stgraph", threads = 1, numMachines = 1, dataset, size = size, mode = mode)
                }
            }
        }
        graph.close()
    }
}