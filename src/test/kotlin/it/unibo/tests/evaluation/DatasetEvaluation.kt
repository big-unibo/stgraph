package it.unibo.tests.evaluation

import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.interfaces.Graph
import it.unibo.graph.query.QueryMode
import it.unibo.graph.utils.resetPort
import it.unibo.stats.Config
import it.unibo.stats.Loader
import it.unibo.stats.QueryResultData
import it.unibo.stats.Querying
import it.unibo.stats.SetupConfig
import it.unibo.stats.TestConfig
import it.unibo.stats.loadDataset
import it.unibo.stats.runQuery
import it.unibo.tests.systems.GraphixClient
import java.io.File

/**
 * The evaluation of one [size] of a [dataset] (see main): what every dataset shares, i.e., STGraph on the setups of
 * the configuration, the timed ingestion and queries with their progress lines, and the download of the data.
 * Results: results/ (model column).
 */
abstract class DatasetEvaluation(
    protected val config: Config,
    protected val evaluation: EvaluationConfig,
    protected val dataset: String,
    protected val size: String
) {
    protected val setups: List<SetupConfig> = evaluation.setups.map { config.setups[it] ?: error("Unknown setup: $it") }

    /** Whether the queries of the dataset have the time windows of time_constraints.yaml */
    protected open val timeWindows = true

    /** Ingestion and queries of [system] */
    abstract fun run(system: String)

    /**
     * STGraph on each setup (on a local setup, the AsterixDB of the root docker-compose.yaml), recorded as [model]:
     * the ingestion with the loader of [loader] (threads, setup) for every number of threads, then [queries] (setup,
     * numMachines), if any
     */
    protected fun stgraph(
        model: String,
        loader: (threads: Int, setup: SetupConfig) -> Loader,
        queries: ((setup: SetupConfig, numMachines: Int) -> Unit)? = null
    ) {
        for (setup in setups) {
            val local = setup.host == "localhost"
            deployed(evaluation.deploy && local, "docker-compose.yaml", "asterixdb", ready = { asterixDBReady(setup.host, setup.port) }) {
                for (threads in evaluation.threads) {
                    repeat(evaluation.ingestionIterations) { iteration ->
                        resetPort()
                        ingest(model, iteration, threads, numMachines = setup.controllerIPs.toSet().size) { loader(threads, setup) }
                    }
                }
                queries?.invoke(setup, if (local) -1 else setup.controllerIPs.toSet().size)
            }
        }
    }

    /** Runs [block] on the graph of STGraph dumped in [graphPath], with its time series in [dataverse] (as TestConfig.runTest) */
    protected fun withStgraph(setup: SetupConfig, graphPath: String, dataverse: String, block: (Graph) -> Unit) {
        val graph = MemoryGraphACID.readFromDisk(graphPath)
        graph.tsm = AsterixDBTSM.createDefault(
            graph, host = setup.host, port = setup.port, controllerIps = setup.controllerIPs, dataverse = dataverse, multiTs = false
        )
        try {
            block(graph)
        } finally {
            graph.close()
        }
    }

    /** Loads the data with the loader that [create] returns (loadDataset), recording it as [model], between two progress lines */
    protected fun ingest(model: String, iteration: Int, threads: Int = 1, numMachines: Int = 1, create: () -> Loader) {
        val run = "ingestion | $model | $dataset $size | iteration ${iteration + 1}/${evaluation.ingestionIterations}, $threads thread(s)"
        progress("$run: started")
        val start = System.currentTimeMillis()
        val loader = create()
        loadDataset(loader, model, threads = threads, numMachines = numMachines, dataset, size)
        progress(
            "$run: done in ${seconds(System.currentTimeMillis() - start)} (timed: graph ${seconds(loader.getGSTime())}, " +
                "time series ${seconds(loader.getTSTime())}, index ${seconds(loader.getIndexTime())}); " +
                "${loader.getGSCardinality()} graph elements, ${loader.getTSCardinality()} events"
        )
    }

    /**
     * Runs every query of [queryIds] [queryIterations][EvaluationConfig.queryIterations] times, on every time window of
     * each selectivity (as the Python evaluation), with each mode and number of threads; [build] returns null to skip a run
     */
    protected fun queries(
        model: String,
        numMachines: Int,
        queryIds: List<String>,
        threads: List<Int> = listOf(1),
        modes: List<QueryMode> = listOf(QueryMode.OPTIMIZED),
        build: (queryId: String, selectivity: String, rangeIndex: Int, mode: QueryMode) -> Querying?
    ) {
        for (iteration in 0 until evaluation.queryIterations) {
            for (queryId in queryIds) {
                for ((selectivity, rangeIndex) in windows(queryId)) {
                    for (mode in modes) {
                        for (thread in threads) {
                            val query = build(queryId, selectivity, rangeIndex, mode) ?: continue
                            val run = "query | $model | $dataset $size | $queryId ($selectivity, window $rangeIndex, " +
                                "iteration ${iteration + 1}/${evaluation.queryIterations}, $mode, $thread thread(s))"
                            progress("$run: started")
                            // The time and the cardinality recorded by runQuery, to print them
                            var elapsed = 0L
                            var cardinality = 0
                            val recorded = object : Querying {
                                override val queryId = query.queryId
                                override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
                                    val begin = System.currentTimeMillis()
                                    return query.runQuery(threads, queryMode).also {
                                        elapsed = System.currentTimeMillis() - begin
                                        cardinality = it.card
                                    }
                                }
                            }
                            resetPort()
                            runQuery(recorded, model, thread, numMachines, dataset, size, mode, iteration, selectivity, rangeIndex)
                            progress("$run: $elapsed ms, $cardinality results")
                        }
                    }
                }
            }
        }
    }

    /**
     * The time windows of [queryId] on [size]: (selectivity, range index). Without time windows, a single run, recorded
     * as the tests record it ("increased", 0)
     */
    private fun windows(queryId: String): List<Pair<String, Int>> {
        if (!timeWindows) return listOf("increased" to 0)
        return evaluation.selectivities.flatMap { selectivity ->
            try {
                TestConfig.loadTemporalRanges(selectivity, queryId, size).keys.sorted().map { selectivity to it }
            } catch (e: IllegalArgumentException) {
                progress("query | $queryId | $dataset $size: no time window for $selectivity, skipped")
                emptyList()
            }
        }
    }

    /** Downloads [path] (the dataset [name] of downloadDataset.sh) when it is missing and the configuration allows it */
    protected fun download(path: String, name: String) {
        if (File(path).exists()) return
        check(evaluation.download) { "Missing $path: run ./downloadDataset.sh $name $size, or set download: true" }
        progress("download | $name $size: started")
        downloadDataset(name, size)
        progress("download | $name $size: done")
    }

    protected fun asterixDBReady(host: String, port: Int) {
        GraphixClient(host, port, "Default").exec("SELECT VALUE 1;")
    }
}
