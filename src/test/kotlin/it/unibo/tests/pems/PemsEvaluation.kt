package it.unibo.tests.pems

import it.unibo.graph.utils.getTime
import it.unibo.stats.Config
import it.unibo.tests.evaluation.DatasetEvaluation
import it.unibo.tests.evaluation.EvaluationConfig
import it.unibo.tests.evaluation.deployed
import it.unibo.tests.evaluation.progress
import it.unibo.tests.evaluation.runCommand
import it.unibo.tests.mimic.MimicEvaluation.Companion.NEO4J_COMPOSE
import it.unibo.tests.mimic.MimicEvaluation.Companion.NEO4J_URI
import it.unibo.tests.mimic.MimicEvaluation.Companion.PGAGE_COMPOSE
import it.unibo.tests.mimic.MimicEvaluation.Companion.PGAGE_PASSWORD
import it.unibo.tests.mimic.MimicEvaluation.Companion.PGAGE_URL
import it.unibo.tests.mimic.MimicEvaluation.Companion.PGAGE_USER
import it.unibo.tests.pems.loaders.PemsAeonG
import it.unibo.tests.pems.loaders.PemsGraphix
import it.unibo.tests.pems.loaders.PemsNeo4J
import it.unibo.tests.pems.loaders.PemsPGAge
import it.unibo.tests.pems.loaders.PemsSTGraph
import it.unibo.tests.systems.pgAgeConnection
import it.unibo.tests.systems.queryTemplate
import it.unibo.tests.systems.neo4jDriver
import java.io.File
import java.sql.DriverManager

/**
 * The evaluation of PeMS (see it.unibo.tests.evaluation.main), with the systems deployed as for MIMIC-IV and
 * SmartBench, and the queries Q1-Q5 of TestPemsQuery (queries.ids selects them). The loaders read the local copy of
 * the data, or the PostgreSQL of datasets.pems if missing (see AbstractPemsLoader). AeonG loads its Cypher dump
 * (PemsAeonG, written when missing, not timed) and HyGraph the local copy (src/pems of its container), in their containers
 */
class PemsEvaluation(config: Config, evaluation: EvaluationConfig, size: String) : DatasetEvaluation(config, evaluation, "pems", size) {
    private val postgres = config.datasets.getValue(dataset).postgres
    private val queryIds = evaluation.queryIds.ifEmpty { pemsQueries }

    override fun run(system: String) {
        when (system) {
            "stgraph" -> stgraph("stgraph", { threads, setup -> PemsSTGraph(size, threads, setup.host, setup.controllerIPs, postgres) }) { setup, numMachines ->
                withStgraph(setup, "datasets/dump/$dataset/$size/", "${dataset}_$size") { graph ->
                    queries("stgraph", numMachines, queryIds, evaluation.threads, evaluation.modes) { id, _ -> PemsQuery(id, graph) }
                }
            }
            "neo4j" -> deployed(evaluation.deploy, NEO4J_COMPOSE, env = environment, ready = ::neo4jReady) {
                repeat(evaluation.ingestionIterations) { ingest("neo4j", it) { PemsNeo4J(size, NEO4J_URI, null, null, postgres = postgres, csvImport = ::neo4jImport) } }
                neo4jDriver(NEO4J_URI).use { driver -> driver.session().use { session -> queries("neo4j", 1, queryIds) { id, _ -> PemsNeo4JQuery(id, session) } } }
            }
            "pgage" -> deployed(evaluation.deploy, PGAGE_COMPOSE, readyChecks = 3, env = environment, ready = ::pgageReady) {
                repeat(evaluation.ingestionIterations) { ingest("pgage", it) { PemsPGAge(size, PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD, postgres) } }
                pgAgeConnection(PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD).use { c -> queries("pgage", 1, queryIds) { id, _ -> PemsPGAgeQuery(id, PemsPGAge.graphName(size), c) } }
            }
            "graphix" -> deployed(evaluation.deploy, "evaluation/architectures/graphix/docker-compose.yaml", env = environment, ready = { asterixDBReady("localhost", 29002) }) {
                repeat(evaluation.ingestionIterations) { ingest("graphix", it) { PemsGraphix(size, postgres = postgres) } }
                queries("graphix", 1, queryIds) { id, _ -> PemsGraphixQuery(id, size) }
            }
            "aeong" -> aeong()
            "hygraph" -> {
                PemsCacheLoader(size, postgres).prepareCache() // the local copy, mounted in its container
                selfContained("hygraph", size, queryIds, mapOf("MODEL" to evaluation.model("hygraph"), "RESULT_SIZE" to size))
            }
            else -> error("$system is not evaluated on $dataset")
        }
    }

    /**
     * AeonG loads its Cypher dump (written by PemsAeonG when missing, not timed); its queries (queries/pems/aeong) with
     * the transaction times of the times of PemsWindows and of the last statement, recorded by the ingestion
     */
    private fun aeong() {
        val dump = PemsAeonG.dumpFile(size)
        if (evaluation.ingestionIterations > 0 && !PemsAeonG.dumpReady(size)) {
            progress("aeong | $dataset $size: writing the Cypher dump")
            PemsAeonG(size, postgres).loadData()
        }
        val folder = dump.absoluteFile.parentFile.apply { mkdirs() }
        val queries = File(folder, "queries").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        aeong(size, queryIds, dump, mapOf("AEONG_QUERIES" to queries.path, "AEONG_DATASETS" to folder.path)) { tt ->
            queryIds.forEach { q ->
                val (from, to) = PemsWindows.windows[q] ?: (0L to 0L)
                val text = queryTemplate("pems", "aeong", q, mapOf("TTFROM" to (tt[from.toString()] ?: 0), "TTTO" to (tt[to.toString()] ?: 0),
                    "TTT" to tt.getValue(PemsWindows.T.toString()), "TTLAST" to tt.getValue("last")))
                File(queries, "$q.txt").writeText(text.lines().map { it.replace(Regex("//.*$"), "").trim() }.filter { it.isNotEmpty() }.joinToString(" ") + "\n")
            }
        }
    }

    private fun neo4jReady() = neo4jDriver(NEO4J_URI).use { it.verifyConnectivity() }

    private fun pgageReady() = DriverManager.getConnection(PGAGE_URL, PGAGE_USER, PGAGE_PASSWORD).use { it.createStatement().use { st -> st.execute("SELECT 1") }; Unit }

    /** As SmartBenchEvaluation.neo4jImport, on the CSV files of PemsNeo4J: returns the time of the import alone */
    private fun neo4jImport(dir: File): Long {
        val compose = listOf("docker", "compose", "-f", NEO4J_COMPOSE)
        val files = dir.list()!!.sorted()
        val args = files.filter { it.startsWith("nodes_") }.map { "--nodes=/import/$it" } + files.filter { it.startsWith("rels") }.map { "--relationships=/import/$it" }
        runCommand(compose + "stop", environment)
        val time = getTime {
            runCommand(compose + listOf("run", "--rm", "--no-deps", "-v", "${dir.absolutePath}:/import:ro", "neo4j",
                "neo4j-admin", "database", "import", "full", "neo4j", "--overwrite-destination", "--id-type=integer") + args, environment)
        }
        runCommand(compose + listOf("start", "neo4j"), environment)
        val deadline = System.currentTimeMillis() + 15 * 60 * 1000
        while (runCatching { neo4jReady() }.isFailure) {
            check(System.currentTimeMillis() < deadline) { "Neo4j not ready after the import" }
            Thread.sleep(2000)
        }
        return time
    }
}
