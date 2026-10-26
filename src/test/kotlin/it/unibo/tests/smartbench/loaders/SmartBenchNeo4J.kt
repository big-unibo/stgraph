package it.unibo.tests.smartbench.loaders

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import it.unibo.graph.utils.getTime
import it.unibo.tests.systems.neo4jDriver
import org.neo4j.driver.Driver
import org.neo4j.driver.Session
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Loads SmartBench into Neo4j from its Cypher dumps (datasets/original/smartbench/neo4j/<size>), a port of
 * neo4j_ingestion.py (digital_twins_architectures):
 * - graph: the statements of graph.cypher, one at a time (cypher-shell in the Python code);
 * - time series: on "small", the statements of ts.cypher; otherwise the events of timeseries/<sensor>.json, sent in
 *   batches of [batchSize] with UNWIND, each event a Temperature node linked to its sensor.
 * The dumps are read before timing; as in the Python code, every timestamp of the dumps becomes a datetime.
 */
class SmartBenchNeo4J(
    size: String,
    uri: String = "bolt://localhost:7687",
    private val batchSize: Int = 10_000
) : AbstractSmartBenchLoader(size, threads = 1) {
    private val dumpPath: Path = projectRoot.resolve("datasets/original/smartbench/neo4j/$size")
    private val driver: Driver = neo4jDriver(uri)
    private val session: Session = driver.session()
    private val mapper = jacksonObjectMapper()

    init {
        // Clean-up (not timed): the Python code ran on a new container every time. The index too, so that it is built
        // after the load (see createIndexes), not maintained during it
        session.run("DROP INDEX $TIMESTAMP_INDEX IF EXISTS").consume()
        session.run("MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS").consume()
    }

    /**
     * The temporal index of the events, as the (tsid, _id) key of STGraph and Graphix and the time index of
     * TimescaleDB: a range index on the timestamps of the Temperature nodes, built after the load and awaited until
     * it is online (Neo4j populates it in the background). The planner decides whether to use it (no hints)
     */
    override fun createIndexes() {
        session.run("CREATE RANGE INDEX $TIMESTAMP_INDEX FOR (t:Temperature) ON (t.timestamp)").consume()
        session.run("CALL db.awaitIndex('$TIMESTAMP_INDEX', 3600)").consume()
    }

    override fun loadGraph() {
        val statements = cypherStatements(dumpPath.resolve("graph.cypher"))
        gsTime += getTime { statements.forEach { session.run(it).consume() } }
        gsCard = count("MATCH (n) RETURN count(n)")
    }

    override fun loadEvents() {
        if (size == "small") {
            val statements = cypherStatements(dumpPath.resolve("ts.cypher"))
            tsTime += getTime { statements.forEach { session.run(it).consume() } }
        } else {
            val files = Files.list(dumpPath.resolve("timeseries")).use { it.sorted().toList() }
            for (file in files) {
                val (sensorId, rows) = readTimeSeries(file)
                tsTime += getTime {
                    rows.chunked(batchSize).forEach { batch -> session.run(UNWIND_QUERY, mapOf("sensor_id" to sensorId, "rows" to batch)).consume() }
                }
                logger.info { "Processed ${file.name}" }
            }
        }
        tsCard = count("MATCH (n:Temperature) RETURN count(n)")
    }

    override fun close() {
        session.close()
        driver.close()
    }

    private fun count(query: String): Long = session.run(query).single()[0].asLong()

    /** The sensor and the rows of a time-series file (one JSON object per line) */
    private fun readTimeSeries(file: Path): Pair<String?, List<Map<String, Any>>> {
        var sensorId: String? = null
        val rows = Files.newBufferedReader(file).useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val obj: Map<String, Any> = mapper.readValue(line)
                if (sensorId == null) sensorId = (obj["sensor"] as Map<*, *>)["id"] as String
                val (x, y, z) = (obj["location"] as String).replace("POINT(", "").replace(")", "").trim().split(Regex("\\s+")).map { it.toDouble() }
                mapOf(
                    "id" to obj["id"]!!,
                    "timestamp" to obj["timestamp"]!!,
                    "temperature" to (obj["payload"] as Map<*, *>)["temperature"]!!,
                    "x" to x,
                    "y" to y,
                    "z" to z
                )
            }.toList()
        }
        return sensorId to rows
    }

    companion object {
        private const val TIMESTAMP_INDEX = "temperature_timestamp"

        private val UNWIND_QUERY = """
            MATCH (s:Sensor {id: ${'$'}sensor_id})
            UNWIND ${'$'}rows AS row
            CREATE (s)-[:hasTemperature]->(
                :Temperature {
                    id: row.id,
                    timestamp: datetime(row.timestamp),
                    temperature: row.temperature,
                    location: point({
                        x: row.x,
                        y: row.y,
                        z: row.z
                    })
                }
            )
        """.trimIndent()

        private val TS_REGEX = Regex("""timestamp\s*:\s*'([^']+)'""")

        // A relationship written without the colon, as the SmartBench generator writes it in ts.cypher
        // (CREATE (s)-[hasTemperature]->(...)): a variable without type, which CREATE rejects
        private val UNTYPED_RELATIONSHIP_REGEX = Regex("""-\[([A-Za-z_][A-Za-z0-9_]*)\]->""")

        /**
         * The statements of a Cypher file, as cypher-shell splits them (on the semicolons outside quotes), with the
         * timestamps as datetime and the relationships without type typed (-[hasTemperature]-> -> -[:hasTemperature]->)
         */
        fun cypherStatements(file: Path): List<String> {
            val text = TS_REGEX.replace(file.readText(), "timestamp: datetime('$1')")
                .replace(UNTYPED_RELATIONSHIP_REGEX, "-[:$1]->")
            val statements = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var i = 0
            while (i < text.length) {
                val c = text[i]
                current.append(c)
                when {
                    quote != null && c == '\\' && i + 1 < text.length -> current.append(text[++i])
                    quote != null && c == quote -> quote = null
                    quote == null && (c == '\'' || c == '"' || c == '`') -> quote = c
                    quote == null && c == ';' -> {
                        current.setLength(current.length - 1)
                        current.toString().trim().takeIf { it.isNotEmpty() }?.let { statements += it }
                        current.setLength(0)
                    }
                }
                i++
            }
            current.toString().trim().takeIf { it.isNotEmpty() }?.let { statements += it }
            return statements
        }
    }
}
