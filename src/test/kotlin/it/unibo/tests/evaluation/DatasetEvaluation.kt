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
import it.unibo.stats.QUERY_WINDOWS
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
            val run = {
                // One thread: the threads of the configuration are those of the Kotlin queries only
                repeat(evaluation.ingestionIterations) { iteration ->
                    resetPort()
                    ingest(model, iteration, 1, numMachines = setup.controllerIPs.toSet().size) { loader(1, setup) }
                }
                // numMachines: the number of node controllers, as for the ingestion (1 on a local setup)
                if (evaluation.queryIterations > 0) queries?.invoke(setup, setup.controllerIPs.toSet().size)
            }
            // scaleOut: true, a cluster deployed on Docker Swarm (see Swarm.kt)
            if (evaluation.scaleOut && !local) swarmDeployed("stgraph", setup, block = run)
            else deployed(evaluation.deploy && local, "docker-compose.yaml", "asterixdb", env = environment, ready = { asterixDBReady(setup.host, setup.port) }, block = run)
        }
    }

    /**
     * Graphix on each setup of the run (as STGraph, for the scale-out): the local one (its compose file, port 29002) or a
     * cluster of AsterixDB with the Graphix extension deployed beforehand (its host and port, used as it is); [loader]
     * and [query] on its host and port, the data feeds of [loader] to its first node controller (a feed listens on a node
     * controller: all the events go through it); numMachines: its node controllers (1 on the local setup)
     */
    protected fun graphix(queryIds: List<String>, loader: (host: String, port: Int, feedHost: String) -> Loader, query: (queryId: String, host: String, port: Int) -> Querying) {
        for (setup in setups) {
            val local = setup.host == "localhost"
            val port = if (local) 29002 else setup.port
            val machines = if (local) 1 else setup.controllerIPs.toSet().size
            val run = {
                repeat(evaluation.ingestionIterations) { ingest("graphix", it, numMachines = machines) { loader(setup.host, port, setup.controllerIPs.first()) } }
                queries("graphix", machines, queryIds) { id, _ -> query(id, setup.host, port) }
            }
            // scaleOut: true, a cluster deployed on Docker Swarm (see Swarm.kt)
            if (evaluation.scaleOut && !local) swarmDeployed("graphix", setup, block = run)
            else deployed(evaluation.deploy && local, "evaluation/architectures/graphix/docker-compose.yaml", env = environment, ready = { asterixDBReady(setup.host, port) }, block = run)
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
     * Runs every query of [queryIds] [queryIterations][EvaluationConfig.queryIterations] times (each one with its time
     * window, see the queries), with each mode and number of threads, recorded as [model] (with the label of the run);
     * [build] returns null to skip a run
     */
    protected fun queries(
        model: String,
        numMachines: Int,
        queryIds: List<String>,
        threads: List<Int> = listOf(1),
        modes: List<QueryMode> = listOf(QueryMode.OPTIMIZED),
        build: (queryId: String, mode: QueryMode) -> Querying?
    ) {
        val recorded = evaluation.model(model)
        for (iteration in 0 until evaluation.queryIterations) {
            for (queryId in queryIds) {
                for (mode in modes) {
                    for (thread in threads) {
                        val query = build(queryId, mode) ?: continue
                        val run = "query | $recorded | $dataset $size | $queryId (iteration ${iteration + 1}/${evaluation.queryIterations}, $mode, $thread thread(s))"
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
                        runQuery(timed, recorded, thread, numMachines, dataset, size, mode, iteration, configuration = evaluation.configuration(model))
                        progress("$run: $elapsed ms, $cardinality results")
                    }
                }
            }
        }
    }

    /**
     * Fails if [path] (the dataset [name] of downloadDataset.sh) is missing and [needed] (by default, when the run loads
     * data): the evaluation never downloads, ./downloadDataset.sh does, on request
     */
    protected fun download(path: String, name: String, needed: Boolean = evaluation.ingestionIterations > 0) {
        // Never downloaded by the evaluation: only by ./downloadDataset.sh, on request
        check(!needed || File(path).exists()) { "Missing $path: download it with ./downloadDataset.sh $name $size" }
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
        // AeonG records the results of the run in a temporary folder, added to results/dt_graph (see AeonGResults)
        val aeongResults = if (system == "aeong") java.nio.file.Files.createTempDirectory("aeong-results").toFile() else null
        val env = mapOf(
            "DATASET" to dataset,
            "DATASET_SIZE" to containerSize,
            "INGESTION_ITERATIONS" to evaluation.ingestionIterations.toString(),
            "QUERY_ITERATIONS" to evaluation.queryIterations.toString(),
            "THREAD" to "1",
            "QUERY_SELECTIVITY" to QUERY_WINDOWS, // recorded as the querySelectivity of the runs
            "QUERIES" to queryIds.joinToString(","),
            "CONFIGURATION" to evaluation.configuration(system) // recorded by HyGraph, as the other systems
        ) + environment + extraEnv + listOfNotNull(aeongResults?.let { "AEONG_RESULTS" to it.absolutePath })
        val run = "$system | $dataset $size (size $containerSize in its container)"
        // The results of AeonG are exported as they come (every 10 s), then once more at the end
        val aeongExport = aeongResults?.let { AeonGResults(it, evaluation.model(system), dataset, size, evaluation.configuration(system)) }
        val exporter = aeongExport?.let {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor().apply {
                scheduleWithFixedDelay({ runCatching { it.export() } }, 10, 10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        pullImages(composeFile, env = env)
        progress("$run: inside its container, started")
        try {
            runCommand(listOf("docker", "compose", "-f", composeFile, "up", "--abort-on-container-exit", "--exit-code-from", system), env)
        } finally {
            exporter?.run { shutdown(); awaitTermination(1, java.util.concurrent.TimeUnit.MINUTES) }
            aeongExport?.export(last = true)
            runCommand(listOf("docker", "compose", "-f", composeFile, "down"), env)
            aeongResults?.deleteRecursively()
        }
        progress("$run: done, results in results/dt_graph")
    }

    /**
     * AeonG on the Cypher [dump] (its folder: AEONG_DATASETS of [extraEnv], with AEONG_QUERIES), in two runs of its
     * container: the ingestion, which records the transaction times of the dump in <dump>.aeong (see
     * evaluation/architectures/aeong/ingest_boundaries.py), then the queries, written by [writeQueries] with those times
     */
    protected fun aeong(
        containerSize: String,
        queryIds: List<String>,
        dump: File,
        extraEnv: Map<String, String>,
        writeQueries: (Map<String, Long>) -> Unit
    ) {
        if (evaluation.ingestionIterations > 0) {
            selfContained("aeong", containerSize, queryIds, extraEnv + ("QUERY_ITERATIONS" to "0"), singlePhase = true)
        }
        if (evaluation.queryIterations > 0) {
            val times = File(dump.path.removeSuffix(".cypher") + ".aeong")
            check(times.isFile) { "Missing $times: run AeonG ingestion to record the transaction times" }
            writeQueries(times.readLines().filter { "=" in it }.associate {
                it.substringBefore("=") to it.substringAfter("=").toLong()
            })
            selfContained("aeong", containerSize, queryIds, extraEnv + ("INGESTION_ITERATIONS" to "0"), singlePhase = true)
        }
    }

    protected fun asterixDBReady(host: String, port: Int) {
        GraphixClient(host, port, "Default").exec("SELECT VALUE 1;")
    }
}
