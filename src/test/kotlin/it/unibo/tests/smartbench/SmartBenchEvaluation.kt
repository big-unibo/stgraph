package it.unibo.tests.smartbench

import it.unibo.graph.query.QueryMode
import it.unibo.stats.Config
import it.unibo.stats.SetupConfig
import it.unibo.tests.evaluation.DatasetEvaluation
import it.unibo.tests.evaluation.EvaluationConfig
import it.unibo.tests.evaluation.deployed
import it.unibo.tests.evaluation.progress
import it.unibo.tests.evaluation.runCommand
import it.unibo.tests.evaluation.seconds
import it.unibo.tests.systems.neo4jDriver
import it.unibo.tests.systems.pgAgeConnection
import it.unibo.tests.smartbench.TestSmartBenchCompetitorsQuery.QGraphix
import it.unibo.tests.smartbench.TestSmartBenchCompetitorsQuery.QNeo4J
import it.unibo.tests.smartbench.TestSmartBenchCompetitorsQuery.QPGAge
import it.unibo.tests.smartbench.loaders.SmartBenchDataLoader
import it.unibo.tests.smartbench.loaders.SmartBenchGraphix
import it.unibo.tests.smartbench.loaders.SmartBenchNeo4J
import it.unibo.tests.smartbench.loaders.SmartBenchNeo4JFast
import it.unibo.tests.smartbench.loaders.SmartBenchPGAge
import it.unibo.tests.smartbench.loaders.SmartBenchPGAgeFast
import it.unibo.tests.smartbench.loaders.SmartBenchSTGraph
import it.unibo.tests.smartbench.queries.*
import java.io.File
import java.sql.DriverManager

/**
 * The SmartBench evaluation of one [size] (see it.unibo.tests.evaluation.main), for every system. STGraph uses its
 * reference loader (SmartBenchDataLoader) and queries (TestSmartBenchQuery); "stgraph-std" only loads the data, through
 * SmartBenchSTGraph.
 */
class SmartBenchEvaluation(config: Config, evaluation: EvaluationConfig, size: String) :
    DatasetEvaluation(config, evaluation, "smartbench", size) {
    private val queryIds = evaluation.queryIds.ifEmpty { TestSmartBenchCompetitorsQuery.queryIds }

    override fun run(system: String) {
        when (system) {
            "stgraph" -> {
                download("datasets/original/smartbench/$size", "smartbench")
                stgraph("stgraph", { threads, setup ->
                    SmartBenchDataLoader(size, threads = threads, host = setup.host, controllerIPs = setup.controllerIPs)
                }) { setup, numMachines -> stgraphQueries(setup, numMachines) }
            }
            "stgraph-std" -> {
                download("datasets/original/smartbench/$size", "smartbench")
                stgraph("stgraph-std", { threads, setup ->
                    SmartBenchSTGraph(size, threads = threads, host = setup.host, controllerIPs = setup.controllerIPs)
                })
            }
            "neo4j" -> neo4j()
            "pgage" -> pgage()
            "graphix" -> graphix()
            "aeong", "hygraph" -> selfContained(system)
            else -> error("Unknown system: $system")
        }
    }

    /** The queries of TestSmartBenchQuery, on the data loaded by SmartBenchDataLoader */
    private fun stgraphQueries(setup: SetupConfig, numMachines: Int) =
        withStgraph(setup, "datasets/dump/$dataset/$size/", "${dataset}_$size") { graph ->
            queries("stgraph", numMachines, queryIds, evaluation.threads, evaluation.modes) { queryId, selectivity, rangeIndex, mode ->
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
        }

    /**
     * Loads SmartBench into [system] (neo4j or pgage) in bulk, to query it afterwards (./gradlew fastLoadNeo4j,
     * fastLoadPgAge, then e.g. evaluateQueries): the system is left with the data of its ingestion (same nodes, edges,
     * rows and indexes), in its data folder (volumes section), but the load is not timed and nothing goes to results/.
     * - pgage: SmartBenchPGAgeFast on the system started as for the evaluation (deploy);
     * - neo4j: the CSV files of SmartBenchNeo4JFast (datasets/import/neo4j/<size>, removed at the end), imported with
     *   neo4j-admin into an empty database while Neo4j is stopped, then the range index of SmartBenchNeo4J; it starts
     *   and stops Neo4j itself, so it needs deploy: true.
     */
    fun fastLoad(system: String) {
        val run = "fast load | $system | $dataset $size"
        val start = System.currentTimeMillis()
        when (system) {
            "pgage" -> {
                download("datasets/original/smartbench/$size", "smartbench", needed = true)
                deployed(evaluation.deploy, PGAGE_COMPOSE, readyChecks = 3, env = environment, ready = ::pgageReady) {
                    progress("$run: started")
                    val loader = SmartBenchPGAgeFast(size, PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD)
                    try {
                        loader.loadData()
                    } finally {
                        loader.close()
                    }
                    progress("$run: done in ${seconds(System.currentTimeMillis() - start)}; ${loader.getGSCardinality()} graph elements, ${loader.getTSCardinality()} events")
                }
            }
            "neo4j" -> {
                check(evaluation.deploy) { "The fast load of Neo4j stops and starts Neo4j itself: it needs deploy: true" }
                download("datasets/original/smartbench/neo4j/$size", "smartbench/neo4j", needed = true)
                val importDir = File("datasets/import/neo4j/$size").absoluteFile
                val loader = SmartBenchNeo4JFast(size)
                progress("$run: writing the files of the import ($importDir)")
                val importArgs = loader.writeImportFiles(importDir.toPath())
                progress("$run: ${loader.graphNodes} graph nodes, ${loader.events} events, ${loader.relationships} relationships; importing")
                try {
                    runCommand(listOf("docker", "compose", "-f", NEO4J_COMPOSE, "stop"), environment)
                    // The database (neo4j) before the options: after them, --relationships (several files) would take it as a file
                    runCommand(
                        listOf("docker", "compose", "-f", NEO4J_COMPOSE, "run", "--rm", "--no-deps", "-v", "$importDir:/import:ro", "neo4j",
                            "neo4j-admin", "database", "import", "full", "neo4j", "--overwrite-destination", "--id-type=integer") + importArgs,
                        environment
                    )
                } finally {
                    importDir.deleteRecursively()
                }
                deployed(true, NEO4J_COMPOSE, env = environment, ready = ::neo4jReady) {
                    neo4jDriver(NEO4J_URI).use { driver -> driver.session().use { SmartBenchNeo4J.createIndexes(it) } }
                }
                progress("$run: done in ${seconds(System.currentTimeMillis() - start)}")
            }
            else -> error("No fast load for $system: only neo4j and pgage")
        }
    }

    private fun neo4jReady() = neo4jDriver(NEO4J_URI).use { it.verifyConnectivity() }

    // Three successful connections in a row: PostgreSQL restarts once at the end of its initialization
    private fun pgageReady() {
        DriverManager.getConnection(PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD).use { it.createStatement().use { st -> st.execute("SELECT 1") } }
    }

    private fun neo4j() {
        download("datasets/original/smartbench/neo4j/$size", "smartbench/neo4j")
        deployed(evaluation.deploy, NEO4J_COMPOSE, env = environment, ready = ::neo4jReady) {
            repeat(evaluation.ingestionIterations) { ingest("neo4j", it) { SmartBenchNeo4J(size, NEO4J_URI) } }
            neo4jDriver(NEO4J_URI).use { driver ->
                driver.session().use { session ->
                    queries("neo4j", numMachines = 1, queryIds) { queryId, selectivity, rangeIndex, _ -> QNeo4J(queryId, size, session, selectivity, rangeIndex) }
                }
            }
        }
    }

    private fun pgage() {
        download("datasets/original/smartbench/$size", "smartbench")
        deployed(evaluation.deploy, PGAGE_COMPOSE, readyChecks = 3, env = environment, ready = ::pgageReady) {
            repeat(evaluation.ingestionIterations) { ingest("pgage", it) { SmartBenchPGAge(size, PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD) } }
            pgAgeConnection(PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD).use { connection ->
                queries("pgage", numMachines = 1, queryIds) { queryId, selectivity, rangeIndex, _ -> QPGAge(queryId, size, connection, selectivity, rangeIndex) }
            }
        }
    }

    private fun graphix() {
        download("datasets/original/smartbench/$size", "smartbench")
        deployed(evaluation.deploy, "evaluation/architectures/graphix/docker-compose.yaml", env = environment, ready = { asterixDBReady("localhost", GRAPHIX_PORT) }) {
            repeat(evaluation.ingestionIterations) { ingest("graphix", it) { SmartBenchGraphix(size, port = GRAPHIX_PORT) } }
            queries("graphix", numMachines = 1, queryIds) { queryId, selectivity, rangeIndex, _ -> QGraphix(queryId, size, selectivity, rangeIndex) }
        }
    }

    /**
     * AeonG and HyGraph load the data and run the queries inside their container, configured by environment variables,
     * with the sizes of the old evaluation (same time windows: small, medium -> large, large -> big)
     */
    private fun selfContained(system: String) {
        val oldSize = mapOf("small" to "small", "medium" to "large", "large" to "big")[size]
            ?: error("$system runs on small, medium and large only")
        if (evaluation.ingestionIterations == 0 || evaluation.queryIterations == 0) {
            progress("$system | $dataset $size: skipped, its container runs the ingestion and the queries together (no single phase)")
            return
        }
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
        val run = "$system | $dataset $size (size $oldSize of the old evaluation)"
        progress("$run: ingestion and queries inside its container, started")
        try {
            runCommand(listOf("docker", "compose", "-f", composeFile, "up", "--abort-on-container-exit", "--exit-code-from", system), env)
        } finally {
            runCommand(listOf("docker", "compose", "-f", composeFile, "down"), env)
        }
        progress("$run: done, results in results/$system")
    }

    companion object {
        const val NEO4J_URI = "bolt://localhost:7687"
        const val NEO4J_COMPOSE = "evaluation/architectures/neo4j/docker-compose.yaml"
        const val PGAGE_COMPOSE = "evaluation/architectures/pgage/docker-compose.yaml"
        const val PGAGE_URL = "jdbc:postgresql://localhost:5432/smartbench_eval"
        const val PGAGE_USER = "postgres"
        const val PGAGE_PASSWORD = "psw"
        const val GRAPHIX_PORT = 29002
    }
}
