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

    /** The data folders of the systems for this size and their resources, as the variables of their compose files */
    protected val environment: Map<String, String> = evaluation.environment(dataset, size)

    /** Whether the queries of the dataset have the time windows of time_constraints.yaml */
    protected open val timeWindows = true

    /** Ingestion and queries of [system] */
    abstract fun run(system: String)

    /**
     * STGraph on each setup (on a local setup, the AsterixDB of the root docker-compose.yaml), recorded as [model]:
     * the ingestion with the loader of [loader] (threads, setup), single-threaded, then [queries] (setup, numMachines),
     * if any, with the threads of the configuration (see EvaluationConfig.threads)
     */
    protected fun stgraph(
        model: String,
        loader: (threads: Int, setup: SetupConfig) -> Loader,
        queries: ((setup: SetupConfig, numMachines: Int) -> Unit)? = null
    ) {
        for (setup in setups) {
            val local = setup.host == "localhost"
            deployed(evaluation.deploy && local, "docker-compose.yaml", "asterixdb", env = environment, ready = { asterixDBReady(setup.host, setup.port) }) {
                // One thread: the threads of the configuration are those of the Kotlin queries only
                repeat(evaluation.ingestionIterations) { iteration ->
                    resetPort()
                    ingest(model, iteration, 1, numMachines = setup.controllerIPs.toSet().size) { loader(1, setup) }
                }
                // numMachines: the number of node controllers, as for the ingestion (1 on a local setup)
                if (evaluation.queryIterations > 0) queries?.invoke(setup, setup.controllerIPs.toSet().size)
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

    /**
     * Loads the data with the loader that [create] returns (loadDataset), recording it as [model] (with the label of
     * the run, see EvaluationConfig.model), between two progress lines
     */
    protected fun ingest(model: String, iteration: Int, threads: Int = 1, numMachines: Int = 1, create: () -> Loader) {
        val recorded = evaluation.model(model)
        val run = "ingestion | $recorded | $dataset $size | iteration ${iteration + 1}/${evaluation.ingestionIterations}, $threads thread(s)"
        progress("$run: started")
        val start = System.currentTimeMillis()
        val loader = create()
        loadDataset(loader, recorded, threads = threads, numMachines = numMachines, dataset, size, configuration = evaluation.configuration(model))
        progress(
            "$run: done in ${seconds(System.currentTimeMillis() - start)} (timed: graph ${seconds(loader.getGSTime())}, " +
                "time series ${seconds(loader.getTSTime())}, index ${seconds(loader.getIndexTime())}); " +
                "${loader.getGSCardinality()} graph elements, ${loader.getTSCardinality()} events"
        )
    }

    /**
     * Runs every query of [queryIds] [queryIterations][EvaluationConfig.queryIterations] times, on every time window of
     * each selectivity (as the Python evaluation), with each mode and number of threads, recorded as [model] (with the
     * label of the run); [build] returns null to skip a run
     */
    protected fun queries(
        model: String,
        numMachines: Int,
        queryIds: List<String>,
        threads: List<Int> = listOf(1),
        modes: List<QueryMode> = listOf(QueryMode.OPTIMIZED),
        build: (queryId: String, selectivity: String, rangeIndex: Int, mode: QueryMode) -> Querying?
    ) {
        val recorded = evaluation.model(model)
        for (iteration in 0 until evaluation.queryIterations) {
            for (queryId in queryIds) {
                for ((selectivity, rangeIndex) in windows(queryId)) {
                    for (mode in modes) {
                        for (thread in threads) {
                            val query = build(queryId, selectivity, rangeIndex, mode) ?: continue
                            val run = "query | $recorded | $dataset $size | $queryId ($selectivity, window $rangeIndex, " +
                                "iteration ${iteration + 1}/${evaluation.queryIterations}, $mode, $thread thread(s))"
                            progress("$run: started")
                            // The time and the cardinality recorded by runQuery, to print them
                            var elapsed = 0L
                            var cardinality = 0
                            val timed = object : Querying {
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
                            runQuery(timed, recorded, thread, numMachines, dataset, size, mode, iteration, selectivity, rangeIndex, configuration = evaluation.configuration(model))
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

    /**
     * Downloads [path] (the dataset [name] of downloadDataset.sh) when it is missing and the configuration allows it;
     * not when the run only queries the data already loaded, unless [needed]
     */
    protected fun download(path: String, name: String, needed: Boolean = evaluation.ingestionIterations > 0) {
        if (!needed || File(path).exists()) return
        check(evaluation.download) { "Missing $path: run ./downloadDataset.sh $name $size, or set download: true" }
        progress("download | $name $size: started")
        downloadDataset(name, size)
        progress("download | $name $size: done")
    }

    /**
     * [system] loads the data and runs the queries inside its container, configured by environment variables, on
     * [containerSize]; [extraEnv]: further variables of its compose file; [singlePhase]: its container keeps the data
     * between two runs, so it can run only the ingestion or only the queries
     */
    protected fun selfContained(
        system: String,
        containerSize: String,
        queryIds: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        singlePhase: Boolean = false
    ) {
        if (!singlePhase && (evaluation.ingestionIterations == 0 || evaluation.queryIterations == 0)) {
            progress("$system | $dataset $size: skipped, its container runs the ingestion and the queries together (no single phase)")
            return
        }
        val composeFile = "evaluation/architectures/$system/docker-compose.yaml"
        val env = mapOf(
            "DATASET" to dataset,
            "DATASET_SIZE" to containerSize,
            "INGESTION_ITERATIONS" to evaluation.ingestionIterations.toString(),
            "QUERY_ITERATIONS" to evaluation.queryIterations.toString(),
            "THREAD" to "1",
            "QUERY_SELECTIVITY" to evaluation.selectivities.joinToString(","),
            "QUERIES" to queryIds.joinToString(",")
        ) + environment + extraEnv
        val run = "$system | $dataset $size (size $containerSize in its container)"
        progress("$run: inside its container, started")
        try {
            runCommand(listOf("docker", "compose", "-f", composeFile, "up", "--abort-on-container-exit", "--exit-code-from", system), env)
        } finally {
            runCommand(listOf("docker", "compose", "-f", composeFile, "down"), env)
        }
        progress("$run: done, results in results/$system")
    }

    protected fun asterixDBReady(host: String, port: Int) {
        GraphixClient(host, port, "Default").exec("SELECT VALUE 1;")
    }
}
