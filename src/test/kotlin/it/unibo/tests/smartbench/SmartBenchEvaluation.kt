package it.unibo.tests.smartbench

import it.unibo.graph.query.QueryMode
import it.unibo.stats.Config
import it.unibo.stats.SetupConfig
import it.unibo.tests.evaluation.AeonGTransactionTimes
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
import it.unibo.tests.smartbench.loaders.SmartBenchAeonG
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
            queries("stgraph", numMachines, queryIds, evaluation.threads, evaluation.modes) { queryId, mode ->
                // As TestSmartBenchQuery: NAIVE is skipped on the large sizes, except for the queries without time window
                val naiveOnLarge = size.contains("large") && mode == QueryMode.NAIVE
                when (queryId) {
                    "EnvironmentCoverage" -> EnvironmentCoverage(graph)
                    "EnvironmentAggregate" -> if (naiveOnLarge) null else EnvironmentAggregate(graph, size)
                    "MaintenanceOwners" -> if (naiveOnLarge) null else MaintenanceOwners(graph, size)
                    "EnvironmentOutlier" -> if (naiveOnLarge) null else EnvironmentOutlier(graph, size)
                    "AgentOutlier" -> if (naiveOnLarge) null else AgentOutlier(graph, size)
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
                    queries("neo4j", numMachines = 1, queryIds) { queryId, _ -> QNeo4J(queryId, size, session) }
                }
            }
        }
    }

    private fun pgage() {
        download("datasets/original/smartbench/$size", "smartbench")
        deployed(evaluation.deploy, PGAGE_COMPOSE, readyChecks = 3, env = environment, ready = ::pgageReady) {
            repeat(evaluation.ingestionIterations) { ingest("pgage", it) { SmartBenchPGAge(size, PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD) } }
            pgAgeConnection(PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD).use { connection ->
                queries("pgage", numMachines = 1, queryIds) { queryId, _ -> QPGAge(queryId, size, connection) }
            }
        }
    }

    private fun graphix() {
        download("datasets/original/smartbench/$size", "smartbench")
        deployed(evaluation.deploy, "evaluation/architectures/graphix/docker-compose.yaml", env = environment, ready = { asterixDBReady("localhost", GRAPHIX_PORT) }) {
            repeat(evaluation.ingestionIterations) { ingest("graphix", it) { SmartBenchGraphix(size, port = GRAPHIX_PORT) } }
            queries("graphix", numMachines = 1, queryIds) { queryId, _ -> QGraphix(queryId, size) }
        }
    }

    /**
     * AeonG and HyGraph load the data and run the queries inside their container, configured by environment variables,
     * with the sizes, the dataset and the time windows (TimeWindows) of the other systems. AeonG loads its
     * Cypher dump, written by SmartBenchAeonG from the dataset of the other systems (not timed) when missing, and runs
     * its queries (queries/smartbench) with the time window of the query as transaction time, measured on the loaded
     * database (see [aeongTransactionTimes] and [aeongQueries])
     */
    private fun selfContained(system: String) {
        // HyGraph records in results/dt_graph as the other systems: the model (with the label of the run) and the size
        val recorded = mapOf("MODEL" to evaluation.model(system), "RESULT_SIZE" to size)
        download("datasets/original/smartbench/$size", "smartbench")
        if (system == "hygraph") {
            selfContained(system, size, queryIds, recorded)
            return
        }
        if (evaluation.ingestionIterations > 0 && !SmartBenchAeonG.dumpReady(size)) {
            progress("aeong | $dataset $size: writing the Cypher dump")
            SmartBenchAeonG(size).writeDump()
        }
        val dump = SmartBenchAeonG.dumpFile(size)
        val queries = File(dump.absoluteFile.parentFile, "queries").apply { mkdirs() }
        val env = mapOf("AEONG_DATASETS" to dump.absoluteFile.parentFile.path, "AEONG_QUERIES" to queries.path)
        aeong(size, queryIds, dump, recorded + env, ::aeongTransactionTimes) { aeongQueries(queries, it) }
    }

    /**
     * The transaction times of the loaded database of AeonG (see AeonGTransactionTimes): for each bound of the time
     * windows on the size, the time of the first event at or after it (the first time as of which a Temperature node has
     * a timestamp at or after the bound); last, the time of the last statement (the first time as of which every
     * Temperature node with the last timestamp has it)
     */
    private fun aeongTransactionTimes(search: AeonGTransactionTimes.Search): Map<String, Long> {
        val start = 2 * SmartBenchAeonG.transactionTimes(size).getValue("last") // a transaction starts and commits
        val lastTimestamp = search.value("MATCH (n:Temperature) RETURN max(n.timestamp)")
        val atLast = search.value("MATCH (n:Temperature) WHERE n.timestamp = $lastTimestamp RETURN count(n)")
        val last = search.first(start) { t -> search.value("MATCH (n:Temperature) WHERE n.timestamp = $lastTimestamp TT AS $t RETURN count(n)") >= atLast }
        return SmartBenchAeonG.windowBounds(size).associate { bound ->
            bound.toString() to search.first(last + 1) { t -> t > last || search.value("MATCH (n:Temperature) TT AS $t RETURN max(n.timestamp)") >= bound }
        } + ("last" to last)
    }

    /**
     * Writes the queries of AeonG for this size into [folder] (datasets/dump/aeong/smartbench/<size>/queries), as its
     * scripts read them (one statement per line, without comments; the names of its files, e.g., q2 for
     * EnvironmentAggregate), with the time window of the query on the size (TimeWindows) as transaction time (TT FROM
     * ... TO ...), from the transaction times [tt] of the loaded database (see [aeongTransactionTimes]): from the first
     * event at or after its start to the last one before its end; the whole history for the queries without a window
     * (EnvironmentCoverage, AgentHistory)
     */
    private fun aeongQueries(folder: File, tt: Map<String, Long>) {
        val files = mapOf("EnvironmentAggregate" to "q2", "MaintenanceOwners" to "q3", "EnvironmentOutlier" to "q4", "AgentOutlier" to "q5")
        for (queryId in TestSmartBenchCompetitorsQuery.queryIds) {
            val windowed = TimeWindows.windowed(queryId)
            val from = if (windowed) tt.getValue(TimeWindows.of(queryId, size).from.toString()) else 0L
            val to = if (windowed) maxOf(tt.getValue(TimeWindows.of(queryId, size).to.toString()) - 1, 0L) else tt.getValue("last")
            val query = File("evaluation/architectures/aeong/queries/smartbench/$queryId.txt").readLines()
                .map { it.replace(Regex("//.*$"), "").trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                .replace("{TTFROM_PARAM}", from.toString()).replace("{TTTO_PARAM}", to.toString())
            File(folder, "${files[queryId] ?: queryId}.txt").writeText(query + "\n")
        }
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
