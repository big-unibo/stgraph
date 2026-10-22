package it.unibo.tests.smartbench

import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.query.QueryMode
import it.unibo.graph.utils.resetPort
import it.unibo.stats.Config
import it.unibo.stats.Querying
import it.unibo.stats.SetupConfig
import it.unibo.stats.TestConfig
import it.unibo.stats.loadDataset
import it.unibo.stats.runQuery
import it.unibo.tests.evaluation.EvaluationConfig
import it.unibo.tests.evaluation.deployed
import it.unibo.tests.evaluation.runCommand
import it.unibo.tests.graphix.GraphixClient
import it.unibo.tests.smartbench.TestSmartBenchCompetitorsQuery.QGraphix
import it.unibo.tests.smartbench.TestSmartBenchCompetitorsQuery.QNeo4J
import it.unibo.tests.smartbench.TestSmartBenchCompetitorsQuery.QPGAge
import it.unibo.tests.smartbench.loaders.SmartBenchDataLoader
import it.unibo.tests.smartbench.loaders.SmartBenchGraphix
import it.unibo.tests.smartbench.loaders.SmartBenchNeo4J
import it.unibo.tests.smartbench.loaders.SmartBenchPGAge
import it.unibo.tests.smartbench.loaders.SmartBenchSTGraph
import it.unibo.tests.smartbench.queries.*
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import java.io.File
import java.sql.DriverManager
import java.util.logging.Logger

/**
 * The SmartBench evaluation of one [size] (see it.unibo.tests.evaluation.main): for each system, ingestion and queries
 * as configured by [evaluation]. STGraph uses its reference loader (SmartBenchDataLoader) and queries
 * (TestSmartBenchQuery); "stgraph-std" only loads the data, through SmartBenchSTGraph. Results: results/ (model column).
 */
class SmartBenchEvaluation(private val config: Config, private val evaluation: EvaluationConfig, private val size: String) {
    private val logger = Logger.getLogger(this.javaClass.name)
    private val dataset = "smartbench"
    private val queryIds = evaluation.queryIds.ifEmpty { TestSmartBenchCompetitorsQuery.queryIds }
    private val setups: List<SetupConfig> = evaluation.setups.map { config.setups[it] ?: error("Unknown setup: $it") }

    fun run(system: String) {
        when (system) {
            "stgraph" -> stgraph(ingestion = true, queries = true)
            "stgraph-std" -> stgraph(ingestion = true, queries = false, model = "stgraph-std")
            "neo4j" -> neo4j()
            "pgage" -> pgage()
            "graphix" -> graphix()
            "aeong", "hygraph" -> selfContained(system)
            else -> error("Unknown system: $system")
        }
    }

    /** STGraph on each setup: on a local setup, the AsterixDB of the root docker-compose.yaml */
    private fun stgraph(ingestion: Boolean, queries: Boolean, model: String = "stgraph") {
        download("datasets/original/smartbench/$size", "smartbench")
        for (setup in setups) {
            val local = setup.host == "localhost"
            deployed(evaluation.deploy && local, "docker-compose.yaml", "asterixdb", ready = { asterixDBReady(setup.host, setup.port) }) {
                if (ingestion) {
                    for (threads in evaluation.threads) {
                        repeat(evaluation.ingestionIterations) {
                            resetPort()
                            val loader = if (model == "stgraph") {
                                SmartBenchDataLoader(size, threads = threads, host = setup.host, controllerIPs = setup.controllerIPs)
                            } else {
                                SmartBenchSTGraph(size, threads = threads, host = setup.host, controllerIPs = setup.controllerIPs)
                            }
                            loadDataset(loader, model, threads = threads, numMachines = setup.controllerIPs.toSet().size, dataset, size)
                        }
                    }
                }
                if (queries) stgraphQueries(setup, numMachines = if (local) -1 else setup.controllerIPs.toSet().size)
            }
        }
    }

    /** The queries of TestSmartBenchQuery, on the data loaded by SmartBenchDataLoader (as TestConfig.runTest) */
    private fun stgraphQueries(setup: SetupConfig, numMachines: Int) {
        val graph = MemoryGraphACID.readFromDisk("datasets/dump/$dataset/$size/")
        graph.tsm = AsterixDBTSM.createDefault(
            graph, host = setup.host, port = setup.port, controllerIps = setup.controllerIPs, dataverse = "${dataset}_$size", multiTs = false
        )
        try {
            queries("stgraph", numMachines, evaluation.threads, evaluation.modes) { queryId, selectivity, rangeIndex, mode ->
                // As TestSmartBenchQuery: NAIVE is skipped on the large sizes, except for the queries without time window
                val naiveOnLarge = size.contains("large") && mode == QueryMode.NAIVE
                when (queryId) {
                    "EnvironmentCoverage" -> EnvironmentCoverage(graph)
                    "EnvironmentAggregate" -> if (naiveOnLarge) null else EnvironmentAggregate(graph, size, selectivity, rangeIndex)
                    "MaintenanceOwners" -> if (naiveOnLarge) null else MaintenanceOwners(graph, size, selectivity, rangeIndex)
                    "EnvironmentOutlier" -> if (naiveOnLarge) null else EnvironmentOutlier(graph, size, selectivity, rangeIndex)
                    "AgentOutlier" -> if (naiveOnLarge) null else AgentOutlier(graph, size, selectivity, rangeIndex)
                    "AgentHistory" -> AgentHistory(graph)
                    else -> error("Unknown query: $queryId")
                }
            }
        } finally {
            graph.close()
        }
    }

    private fun neo4j() {
        download("datasets/original/smartbench/neo4j/$size", "smartbench/neo4j")
        val ready = { GraphDatabase.driver(NEO4J_URI, AuthTokens.none()).use { it.verifyConnectivity() } }
        deployed(evaluation.deploy, "evaluation/architectures/neo4j/docker-compose.yaml", ready = ready) {
            repeat(evaluation.ingestionIterations) { loadDataset(SmartBenchNeo4J(size, NEO4J_URI), "neo4j", threads = 1, numMachines = 1, dataset, size) }
            GraphDatabase.driver(NEO4J_URI, AuthTokens.none()).use { driver ->
                driver.session().use { session ->
                    queries("neo4j", numMachines = 1) { queryId, selectivity, rangeIndex, _ -> QNeo4J(queryId, size, session, selectivity, rangeIndex) }
                }
            }
        }
    }

    private fun pgage() {
        download("datasets/original/smartbench/$size", "smartbench")
        // Three successful connections in a row: PostgreSQL restarts once at the end of its initialization
        val connects: () -> Unit = { DriverManager.getConnection(PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD).use { it.createStatement().use { st -> st.execute("SELECT 1") } } }
        deployed(evaluation.deploy, "evaluation/architectures/pgage/docker-compose.yaml", readyChecks = 3, ready = connects) {
            repeat(evaluation.ingestionIterations) {
                loadDataset(SmartBenchPGAge(size, PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD), "pgage", threads = 1, numMachines = 1, dataset, size)
            }
            QPGAge.pgAgeSession(DriverManager.getConnection(PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD)).use { connection ->
                queries("pgage", numMachines = 1) { queryId, selectivity, rangeIndex, _ -> QPGAge(queryId, size, connection, selectivity, rangeIndex) }
            }
        }
    }

    private fun graphix() {
        download("datasets/original/smartbench/$size", "smartbench")
        deployed(evaluation.deploy, "evaluation/architectures/graphix/docker-compose.yaml", ready = { asterixDBReady("localhost", GRAPHIX_PORT) }) {
            repeat(evaluation.ingestionIterations) { loadDataset(SmartBenchGraphix(size, port = GRAPHIX_PORT), "graphix", threads = 1, numMachines = 1, dataset, size) }
            queries("graphix", numMachines = 1) { queryId, selectivity, rangeIndex, _ -> QGraphix(queryId, size, selectivity, rangeIndex) }
        }
    }

    /**
     * AeonG and HyGraph load the data and run the queries inside their container, configured by environment variables,
     * with the sizes of the old evaluation (same time windows: small, medium -> large, large -> big)
     */
    private fun selfContained(system: String) {
        val oldSize = mapOf("small" to "small", "medium" to "large", "large" to "big")[size]
            ?: error("$system runs on small, medium and large only")
        val composeFile = "evaluation/architectures/$system/docker-compose.yaml"
        val env = mapOf(
            "DATASET" to dataset,
            "DATASET_SIZE" to oldSize,
            "INGESTION_ITERATIONS" to evaluation.ingestionIterations.toString(),
            "QUERY_ITERATIONS" to evaluation.queryIterations.toString(),
            "THREAD" to "1",
            "QUERY_SELECTIVITY" to evaluation.selectivities.joinToString(","),
            "QUERIES" to queryIds.joinToString(",")
        )
        try {
            runCommand(listOf("docker", "compose", "-f", composeFile, "up", "--abort-on-container-exit", "--exit-code-from", system), env)
        } finally {
            runCommand(listOf("docker", "compose", "-f", composeFile, "down"), env)
        }
    }

    /**
     * Runs every query [queryIterations][EvaluationConfig.queryIterations] times, on every time window of each
     * selectivity (as the Python evaluation), with each mode and number of threads; [build] returns null to skip a run
     */
    private fun queries(
        model: String,
        numMachines: Int,
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
                            resetPort()
                            runQuery(query, model, thread, numMachines, dataset, size, mode, iteration, selectivity, rangeIndex)
                        }
                    }
                }
            }
        }
    }

    /** The time windows of [queryId] on [size]: (selectivity, range index) */
    private fun windows(queryId: String): List<Pair<String, Int>> =
        evaluation.selectivities.flatMap { selectivity ->
            try {
                TestConfig.loadTemporalRanges(selectivity, queryId, size).keys.sorted().map { selectivity to it }
            } catch (e: IllegalArgumentException) {
                logger.warning("No time window for $queryId on $size ($selectivity): ${e.message}")
                emptyList()
            }
        }

    /** Downloads [path] with downloadDataset.sh (as [name]) when it is missing and the configuration allows it */
    private fun download(path: String, name: String) {
        if (File(path).exists()) return
        check(evaluation.download) { "Missing $path: run ./downloadDataset.sh $name $size, or set download: true" }
        runCommand(listOf("bash", "downloadDataset.sh", name, size))
    }

    private fun asterixDBReady(host: String, port: Int) {
        GraphixClient(host, port, "Default").exec("SELECT VALUE 1;")
    }

    companion object {
        const val NEO4J_URI = "bolt://localhost:7687"
        const val PGAGE_URL = "jdbc:postgresql://localhost:5432/smartbench_eval"
        const val PGAGE_USER = "postgres"
        const val PGAGE_PASSWORD = "psw"
        const val GRAPHIX_PORT = 29002
    }
}
