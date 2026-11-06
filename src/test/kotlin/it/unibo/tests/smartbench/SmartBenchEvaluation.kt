package it.unibo.tests.smartbench

import it.unibo.graph.query.QueryMode
import it.unibo.stats.Config
import it.unibo.stats.SetupConfig
import it.unibo.tests.evaluation.DatasetEvaluation
import it.unibo.tests.evaluation.EvaluationConfig
import it.unibo.tests.evaluation.deployed
import it.unibo.tests.evaluation.downloadDataset
import it.unibo.tests.evaluation.progress
import it.unibo.tests.evaluation.runCommand
import it.unibo.graph.utils.getTime
import it.unibo.tests.systems.neo4jDriver
import it.unibo.tests.systems.pgAgeConnection
import it.unibo.tests.smartbench.TestSmartBenchCompetitorsQuery.QGraphix
import it.unibo.tests.smartbench.TestSmartBenchCompetitorsQuery.QNeo4J
import it.unibo.tests.smartbench.TestSmartBenchCompetitorsQuery.QPGAge
import it.unibo.tests.smartbench.loaders.SmartBenchDataLoader
import it.unibo.tests.smartbench.loaders.SmartBenchGraphix
import it.unibo.tests.smartbench.loaders.SmartBenchNeo4J
import it.unibo.tests.smartbench.loaders.neo4jCsv
import it.unibo.tests.smartbench.loaders.SmartBenchPGAge
import it.unibo.tests.smartbench.queries.*
import java.io.File
import java.sql.DriverManager

/**
 * The SmartBench evaluation of one [size] (see it.unibo.tests.evaluation.main), for every system. STGraph uses its
 * reference loader (SmartBenchDataLoader) and queries (TestSmartBenchQuery).
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

    private fun neo4jReady() = neo4jDriver(NEO4J_URI).use { it.verifyConnectivity() }

    /**
     * Imports the CSV files of SmartBenchNeo4J in [dir] ([args]: the files, read in /import) into the database of the
     * Neo4j of the evaluation (replaced), as MimicEvaluation.neo4jImport: Neo4j stopped, neo4j-admin database import
     * full, Neo4j started again. Returns the time of the import alone (not of the stop and start)
     */
    private fun neo4jImport(dir: File, args: List<String>): Long {
        val compose = listOf("docker", "compose", "-f", NEO4J_COMPOSE)
        runCommand(compose + "stop", environment)
        // The database (neo4j) before the options: after them, --relationships (several files) would take it as a file
        val time = getTime {
            runCommand(
                compose + listOf("run", "--rm", "--no-deps", "-v", "${dir.absolutePath}:/import:ro", "neo4j",
                    "neo4j-admin", "database", "import", "full", "neo4j", "--overwrite-destination", "--id-type=integer") + args,
                environment
            )
        }
        runCommand(compose + listOf("start", "neo4j"), environment)
        val deadline = System.currentTimeMillis() + 15 * 60 * 1000
        while (runCatching { neo4jReady() }.isFailure) {
            check(System.currentTimeMillis() < deadline) { "Neo4j not ready after the import" }
            Thread.sleep(2000)
        }
        return time
    }

    // Three successful connections in a row: PostgreSQL restarts once at the end of its initialization
    private fun pgageReady() {
        DriverManager.getConnection(PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD).use { it.createStatement().use { st -> st.execute("SELECT 1") } }
    }

    private fun neo4j() {
        download("datasets/original/smartbench/$size", "smartbench")
        deployed(evaluation.deploy, NEO4J_COMPOSE, env = environment, ready = ::neo4jReady) {
            // As MimicEvaluation: from medium on, CSV files imported with neo4j-admin, which stops and starts Neo4j
            if (neo4jCsv(size) && evaluation.ingestionIterations > 0) check(evaluation.deploy) { "The CSV import of Neo4j ($size) stops and starts Neo4j itself: it needs deploy: true" }
            repeat(evaluation.ingestionIterations) { ingest("neo4j", it) { SmartBenchNeo4J(size, NEO4J_URI, csvImport = ::neo4jImport) } }
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
     * AeonG and HyGraph load the data and run the queries inside their container, configured by environment variables.
     * AeonG up to medium only, with the sizes of the old evaluation (same time windows: medium -> large); HyGraph
     * with the sizes, the dataset and the time windows (time_constraints.yaml) of the other systems, mounted by its
     * compose file
     */
    private fun selfContained(system: String) {
        // HyGraph records in results/dt_graph as the other systems: the model (with the label of the run) and the size
        val recorded = mapOf("MODEL" to evaluation.model(system), "RESULT_SIZE" to size)
        if (system == "hygraph") {
            download("datasets/original/smartbench/$size", "smartbench")
            selfContained(system, size, queryIds, recorded)
            return
        }
        val oldSize = mapOf("xsmall" to "xsmall", "small" to "small", "medium" to "large")[size]
        if (oldSize == null) {
            progress("$system | $dataset $size: skipped, it runs up to medium only")
            return
        }
        selfContained(system, oldSize, queryIds, recorded + ("AEONG_DATASETS" to aeongDataset(oldSize)), singlePhase = true)
    }

    /** The folder of the Cypher dump of AeonG, downloaded on the host: its image cannot reach the server (TLS 1.3 only) */
    private fun aeongDataset(oldSize: String): String {
        val dump = File("datasets/original/smartbench/aeong/$oldSize.cypher")
        if (!dump.exists() && evaluation.ingestionIterations > 0) {
            check(evaluation.download) { "Missing $dump: run ./downloadDataset.sh smartbench/aeong $oldSize.cypher, or set download: true" }
            progress("download | smartbench/aeong $oldSize: started")
            downloadDataset("smartbench/aeong", dump.name)
            progress("download | smartbench/aeong $oldSize: done")
        }
        return dump.absoluteFile.parentFile.apply { mkdirs() }.path
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
