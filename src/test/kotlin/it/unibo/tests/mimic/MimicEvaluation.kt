package it.unibo.tests.mimic

import it.unibo.graph.query.QueryMode
import it.unibo.stats.Config
import it.unibo.stats.SetupConfig
import it.unibo.tests.evaluation.DatasetEvaluation
import it.unibo.tests.evaluation.EvaluationConfig
import it.unibo.tests.evaluation.deployed
import it.unibo.tests.evaluation.downloadDataset
import it.unibo.tests.mimic.loaders.MimicIVAeonG
import it.unibo.tests.mimic.loaders.MimicIVGraphix
import it.unibo.tests.mimic.loaders.MimicIVSTGraph
import it.unibo.tests.mimic.loaders.MimicIVNeo4J
import it.unibo.tests.mimic.loaders.MimicIVPGAge
import it.unibo.tests.mimic.loaders.pgageCsv
import it.unibo.tests.mimic.loaders.mimicLimit
import it.unibo.tests.mimic.loaders.neo4jCsv
import it.unibo.tests.evaluation.runCommand
import it.unibo.tests.evaluation.progress
import it.unibo.graph.utils.getTime
import it.unibo.tests.systems.neo4jDriver
import it.unibo.tests.systems.pgAgeConnection
import java.io.File
import java.sql.DriverManager

/**
 * The evaluation of MIMIC-IV (see it.unibo.tests.evaluation.main), with the loaders of TestMimicIngestion.main and the
 * queries Q1-Q3 of TestMimicQuery (no time windows; queries.ids selects them). The data is read from the PostgreSQL of
 * datasets.mimic: on localhost, the mimic-postgres of the root docker-compose.yaml, deployed for the ingestion as the
 * other systems, which restores the dump of datasets/original/mimic at its first start (see [source]).
 * - stgraph: deployed as for the other datasets (the AsterixDB of the root docker-compose.yaml);
 * - graphix: deployed as for the other datasets (evaluation/architectures/graphix);
 * - neo4j: deployed as for the other datasets (evaluation/architectures/neo4j), loaded by MimicIVNeo4J: one node at a
 *   time on the small sizes, otherwise CSV files imported with neo4j-admin (see [neo4jImport]);
 * - pgage: deployed as for the other datasets (evaluation/architectures/pgage), loaded by MimicIVPGAge: batches of
 *   INSERTs on the small sizes, otherwise a CSV file copied with COPY (see [pgageImport]);
 * - hygraph: ingestion and queries inside its container (evaluation/architectures/hygraph), on the CSV files of the
 *   import of Neo4j (datasets/dump/neo4j/mimic/<limit>), written first by MimicIVNeo4J if missing (not timed);
 * - aeong: only the ingestion of TestMimicIngestion (MimicIVAeonG), no queries.
 */
class MimicEvaluation(config: Config, evaluation: EvaluationConfig, size: String) :
    DatasetEvaluation(config, evaluation, "mimic", size) {
    override val timeWindows = false

    private val limit = mimicLimit(size)
    /** The size in the dump of STGraph and in the dataverses: "full" for all the events (as MimicIVSTGraph) */
    private val name = if (limit == Long.MAX_VALUE) "full" else size
    private val postgres = config.datasets.getValue(dataset).postgres
    private val queryIds = evaluation.queryIds.ifEmpty { listOf("Q1", "Q2", "Q3") }

    override fun run(system: String) = source { runSystem(system) }

    private fun runSystem(system: String) {
        when (system) {
            "stgraph" -> stgraph("stgraph", { threads, setup ->
                MimicIVSTGraph(size, threads = threads, host = setup.host, controllerIPs = setup.controllerIPs, postgres = postgres)
            }) { setup, numMachines -> stgraphQueries(setup, numMachines) }
            "neo4j" -> neo4j()
            "pgage" -> pgage()
            "graphix" -> graphix()
            "hygraph" -> hygraph()
            "aeong" -> repeat(evaluation.ingestionIterations) { ingest("aeong", it) { MimicIVAeonG(limit, postgres = postgres) } }
            else -> error("$system is not evaluated on $dataset")
        }
    }

    /** As TestMimicQuery: NAIVE is skipped on the full dataset */
    private fun stgraphQueries(setup: SetupConfig, numMachines: Int) =
        withStgraph(setup, "datasets/dump/$dataset/$name/", "${dataset}_$name") { graph ->
            queries("stgraph", numMachines, queryIds, evaluation.threads, evaluation.modes) { queryId, _, _, mode ->
                if (mode == QueryMode.NAIVE && name == "full") null
                else when (queryId) {
                    "Q1" -> TestMimicQuery.Q1(graph)
                    "Q2" -> TestMimicQuery.Q2(graph)
                    "Q3" -> TestMimicQuery.Q3(graph)
                    else -> error("Unknown query: $queryId")
                }
            }
        }

    private fun neo4j() {
        deployed(evaluation.deploy, NEO4J_COMPOSE, env = environment, ready = ::neo4jReady) {
            val csv = neo4jCsv(limit)
            if (csv && evaluation.ingestionIterations > 0) check(evaluation.deploy) { "The CSV import of Neo4j ($size) stops and starts Neo4j itself: it needs deploy: true" }
            repeat(evaluation.ingestionIterations) {
                ingest("neo4j", it) { MimicIVNeo4J(limit, NEO4J_URI, user = null, password = null, csv = csv, postgres = postgres, csvImport = ::neo4jImport) }
            }
            neo4jDriver(NEO4J_URI).use { driver ->
                driver.session().use { session ->
                    queries("neo4j", numMachines = 1, queryIds) { queryId, _, _, _ ->
                        when (queryId) {
                            "Q1" -> TestMimicQuery.Q1Neo4J(session)
                            "Q2" -> TestMimicQuery.Q2Neo4J(session)
                            "Q3" -> TestMimicQuery.Q3Neo4J(session)
                            else -> error("Unknown query: $queryId")
                        }
                    }
                }
            }
        }
    }

    private fun pgage() {
        deployed(evaluation.deploy, PGAGE_COMPOSE, readyChecks = 3, env = environment, ready = ::pgageReady) {
            repeat(evaluation.ingestionIterations) {
                ingest("pgage", it) {
                    MimicIVPGAge(limit, PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD, postgres = postgres, csv = pgageCsv(limit), csvImport = ::pgageImport)
                }
            }
            val graph = MimicIVPGAge.graphName(limit)
            pgAgeConnection(PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD).use { connection ->
                queries("pgage", numMachines = 1, queryIds) { queryId, _, _, _ ->
                    when (queryId) {
                        "Q1" -> TestMimicQuery.Q1PGAge(graph, connection)
                        "Q2" -> TestMimicQuery.Q2PGAge(graph, connection)
                        "Q3" -> TestMimicQuery.Q3PGAge(graph, connection)
                        else -> error("Unknown query: $queryId")
                    }
                }
            }
        }
    }

    /**
     * Runs [block] with the PostgreSQL the data is read from, when the run loads data: on localhost, the mimic-postgres
     * of the root docker-compose.yaml, deployed as the other systems (with deploy: true), with the dump it restores at its
     * first start (downloaded if missing, as run.sh: <size>.dump, e.g., x1692200.dump)
     */
    private fun source(block: () -> Unit) {
        val local = postgres.host == "localhost" || postgres.host == "127.0.0.1"
        if (evaluation.ingestionIterations == 0 || !local) return block()
        if (evaluation.deploy && File("datasets/original/mimic").listFiles { f -> f.name.endsWith(".dump") }.isNullOrEmpty()) {
            check(evaluation.download) { "No dump in datasets/original/mimic: run ./downloadDataset.sh mimic $size.dump, or set download: true" }
            downloadDataset("mimic", "$size.dump")
        }
        // Three successful connections in a row, as PGAge: PostgreSQL restarts at the end of its initialization (the
        // restore of the dump, up to hours on the large dumps)
        deployed(evaluation.deploy, "docker-compose.yaml", "mimic-postgres", readyChecks = 3, readyTimeoutMs = 6 * 60 * 60 * 1000L, env = environment, ready = ::sourceReady) { block() }
    }

    private fun sourceReady() {
        DriverManager.getConnection("jdbc:postgresql://${postgres.host}:${postgres.port}/${postgres.database}", postgres.user, postgres.password)
            .use { it.createStatement().use { st -> st.execute("SELECT 1 FROM d_items LIMIT 1") } }
    }

    /**
     * HyGraph loads the CSV files of the import of Neo4j (mounted by its compose file): written by MimicIVNeo4J (CSV
     * mode, no import, not recorded) when missing, e.g. on the small sizes, which Neo4j loads without them
     */
    private fun hygraph() {
        val measurements = File("datasets/dump/neo4j/mimic/$limit/measurement.csv")
        if (evaluation.ingestionIterations > 0 && (!measurements.exists() || measurements.useLines { it.take(2).count() } < 2)) {
            progress("hygraph | $dataset $size: writing the CSV files of Neo4j")
            MimicIVNeo4J(limit, csv = true, postgres = postgres).loadData()
        }
        // Recorded in results/dt_graph as the other systems: the model (with the label of the run) and the size
        selfContained("hygraph", limit.toString(), queryIds, mapOf("MODEL" to evaluation.model("hygraph"), "RESULT_SIZE" to size))
    }

    private fun neo4jReady() = neo4jDriver(NEO4J_URI).use { it.verifyConnectivity() }

    /**
     * Imports the CSV files of MimicIVNeo4J in [csvDir] into the database of the Neo4j of the evaluation (replaced), as
     * loaders/start.sh: Neo4j stopped, neo4j-admin database import full, Neo4j started again. Returns the time of the
     * import alone (not of the stop and start)
     */
    private fun neo4jImport(csvDir: File): Long {
        val compose = listOf("docker", "compose", "-f", NEO4J_COMPOSE)
        runCommand(compose + "stop", environment)
        val time = getTime {
            runCommand(
                compose + listOf("run", "--rm", "--no-deps", "-v", "${csvDir.absolutePath}:/csv:ro", "neo4j",
                    "neo4j-admin", "database", "import", "full", "neo4j", "--overwrite-destination", "--id-type=integer",
                    "--nodes=/csv/person.csv", "--nodes=/csv/ts.csv", "--nodes=/csv/measurement.csv",
                    "--relationships=/csv/has_parameters.csv", "--relationships=/csv/has_measurement.csv"),
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

    /**
     * Copies the CSV file [csv] of MimicIVPGAge into <graph>.measurements of the PGAge of the evaluation: the file is
     * copied into the container (not timed), then COPY ... FROM ... CSV runs there with psql. Returns the time of the COPY
     */
    private fun pgageImport(csv: File): Long {
        val compose = listOf("docker", "compose", "-f", PGAGE_COMPOSE)
        val inContainer = "/tmp/mimic_measurements.csv"
        runCommand(compose + listOf("cp", csv.absolutePath, "pgage:$inContainer"), environment)
        try {
            return getTime {
                runCommand(
                    compose + listOf("exec", "-T", "pgage", "psql", "-v", "ON_ERROR_STOP=1", "-U", PGAGE_USER, "-d", PGAGE_DB, "-c",
                        "COPY ${MimicIVPGAge.graphName(limit)}.measurements (${MimicIVPGAge.COLUMNS}) FROM '$inContainer' DELIMITER ',' CSV;"),
                    environment
                )
            }
        } finally {
            runCommand(compose + listOf("exec", "-T", "pgage", "rm", "-f", inContainer), environment)
        }
    }

    // Three successful connections in a row: PostgreSQL restarts once at the end of its initialization
    private fun pgageReady() {
        DriverManager.getConnection(PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD).use { it.createStatement().use { st -> st.execute("SELECT 1") } }
    }

    private fun graphix() {
        deployed(evaluation.deploy, "evaluation/architectures/graphix/docker-compose.yaml", env = environment, ready = { asterixDBReady("localhost", 29002) }) {
            repeat(evaluation.ingestionIterations) { ingest("graphix", it) { MimicIVGraphix(size, postgres = postgres) } }
            queries("graphix", numMachines = 1, queryIds) { queryId, _, _, _ ->
                when (queryId) {
                    "Q1" -> TestMimicQuery.Q1Graphix(name)
                    "Q2" -> TestMimicQuery.Q2Graphix(name)
                    "Q3" -> TestMimicQuery.Q3Graphix(name)
                    else -> error("Unknown query: $queryId")
                }
            }
        }
    }

    companion object {
        // The Neo4j and the PGAge of the evaluation (evaluation/architectures/neo4j and pgage)
        const val NEO4J_URI = "bolt://localhost:7687"
        const val NEO4J_COMPOSE = "evaluation/architectures/neo4j/docker-compose.yaml"
        const val PGAGE_COMPOSE = "evaluation/architectures/pgage/docker-compose.yaml"
        const val PGAGE_URL = "jdbc:postgresql://localhost:5432/smartbench_eval"
        const val PGAGE_USER = "postgres"
        const val PGAGE_DB = "smartbench_eval"
        const val PGAGE_PASSWORD = "psw"
    }
}
