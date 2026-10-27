package it.unibo.tests.smartbench

import it.unibo.graph.query.QueryMode
import it.unibo.stats.Config
import it.unibo.stats.SetupConfig
import it.unibo.tests.evaluation.DatasetEvaluation
import it.unibo.tests.evaluation.EvaluationConfig
import it.unibo.tests.evaluation.deployed
import it.unibo.tests.evaluation.progress
import it.unibo.tests.evaluation.runCommand
import it.unibo.tests.systems.neo4jDriver
import it.unibo.tests.systems.pgAgeConnection
import it.unibo.tests.smartbench.TestSmartBenchCompetitorsQuery.QGraphix
import it.unibo.tests.smartbench.TestSmartBenchCompetitorsQuery.QNeo4J
import it.unibo.tests.smartbench.TestSmartBenchCompetitorsQuery.QPGAge
import it.unibo.tests.smartbench.loaders.SmartBenchDataLoader
import it.unibo.tests.smartbench.loaders.SmartBenchGraphix
import it.unibo.tests.smartbench.loaders.SmartBenchNeo4J
import it.unibo.tests.smartbench.loaders.SmartBenchPGAge
import it.unibo.tests.smartbench.loaders.SmartBenchSTGraph
import it.unibo.tests.smartbench.queries.*
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

    private fun neo4j() {
        download("datasets/original/smartbench/neo4j/$size", "smartbench/neo4j")
        val ready = { neo4jDriver(NEO4J_URI).use { it.verifyConnectivity() } }
        deployed(evaluation.deploy, "evaluation/architectures/neo4j/docker-compose.yaml", env = evaluation.volumes, ready = ready) {
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
        // Three successful connections in a row: PostgreSQL restarts once at the end of its initialization
        val connects: () -> Unit = { DriverManager.getConnection(PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD).use { it.createStatement().use { st -> st.execute("SELECT 1") } } }
        deployed(evaluation.deploy, "evaluation/architectures/pgage/docker-compose.yaml", readyChecks = 3, env = evaluation.volumes, ready = connects) {
            repeat(evaluation.ingestionIterations) { ingest("pgage", it) { SmartBenchPGAge(size, PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD) } }
            pgAgeConnection(PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD).use { connection ->
                queries("pgage", numMachines = 1, queryIds) { queryId, selectivity, rangeIndex, _ -> QPGAge(queryId, size, connection, selectivity, rangeIndex) }
            }
        }
    }

    private fun graphix() {
        download("datasets/original/smartbench/$size", "smartbench")
        deployed(evaluation.deploy, "evaluation/architectures/graphix/docker-compose.yaml", env = evaluation.volumes, ready = { asterixDBReady("localhost", GRAPHIX_PORT) }) {
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
        const val PGAGE_URL = "jdbc:postgresql://localhost:5432/smartbench_eval"
        const val PGAGE_USER = "postgres"
        const val PGAGE_PASSWORD = "psw"
        const val GRAPHIX_PORT = 29002
    }
}
