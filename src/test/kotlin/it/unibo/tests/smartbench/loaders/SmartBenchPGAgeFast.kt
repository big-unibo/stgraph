package it.unibo.tests.smartbench.loaders

import it.unibo.graph.utils.TYPE
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import java.io.StringReader

/**
 * A bulk load of SmartBench into PGAge, to query it afterwards (./gradlew fastLoadPgAge): the graph and the hypertable
 * of [SmartBenchPGAge], with the same content, written in bulk. Not an ingestion of the evaluation: its times are not
 * comparable with the ones of the other systems.
 * - Graph: the same vertices and edges, by the same rules: an entity whose statement fails is skipped (e.g., a quote
 *   or a null in its fields), an edge links vertices that exist when its source is loaded (an edge towards an entity
 *   loaded later is lost), at most one per source, label and destination. The vertices are created [vertexBatch] per
 *   statement (one statement per vertex when the batch fails, to skip only the failing ones); the edges are decided in
 *   memory and created [edgeBatch] per statement (UNWIND), instead of one statement each after looking for both
 *   vertices among all the vertices.
 * - Events: COPY into the hypertable, created with the final type of the location (geometry(PointZ, 4326)); its
 *   primary key is added after the load, with the spatial index.
 */
class SmartBenchPGAgeFast(
    size: String,
    url: String = "jdbc:postgresql://localhost:5432/smartbench_eval",
    user: String = "postgres",
    password: String = "psw",
    private val vertexBatch: Int = 200,
    private val edgeBatch: Int = 500
) : SmartBenchPGAge(size, url, user, password) {
    /** A vertex, numbered in the order of loading, as the statements of SmartBenchPGAge */
    private data class Vertex(val seq: Int, val label: String, val properties: Map<String, Any?>) {
        val id: String = properties["id"].toString()
    }

    private var loaded = 0
    private val pending = mutableListOf<Vertex>()
    private val created = mutableListOf<Vertex>()
    private val copy = CopyManager(conn.unwrap(BaseConnection::class.java))
    private var skipped = 0L

    init {
        // The table of SmartBenchPGAge, without its primary key (added after the load) and with the final location type
        execute("DROP TABLE IF EXISTS $measurementTable;")
        execute(
            """
            CREATE TABLE $measurementTable (
                time timestamp NOT NULL,
                sensor_id text NOT NULL,
                type text,
                val double precision,
                location geometry(PointZ, 4326)
            );
            """.trimIndent()
        )
        execute("SELECT create_hypertable('$measurementTable', 'time');")
    }

    override fun addEntity(label: String, entity: Map<String, Any>) = addVertex(entity[TYPE] as String, entity - "_id")

    override fun addEvents(worker: Int, ts: SmartBenchTimeSeries, events: List<SmartBenchEvent>) {
        if (events.isEmpty()) return
        val first = events.first().json
        addVertex(first[TYPE] as String, first - "_id")
        // Only the temperatures have a mapping to the measurement table, as in SmartBenchPGAge
        val rows = events.filter { it.json[TYPE] == "Temperature" }
        val csv = StringBuilder()
        for (event in rows) {
            val fields = listOf(
                event.json["timestamp"] as String,
                (event.json["sensor"] as Map<*, *>)["id"].toString(),
                "Temperature",
                ((event.json["payload"] as Map<*, *>)["temperature"] as Number).toDouble().toString(),
                "SRID=4326;" + (event.json["location"] as? String ?: "POINT EMPTY")
            )
            fields.joinTo(csv, ",", postfix = "\n") { "\"" + it.replace("\"", "\"\"") + "\"" }
        }
        copy.copyIn("COPY $measurementTable (time, sensor_id, type, val, location) FROM STDIN WITH (FORMAT csv)", StringReader(csv.toString()))
        skipped += events.size - rows.size
    }

    override fun endEntities() = flushVertices()

    override fun loadEvents() {
        super.loadEvents()
        tsCard -= skipped
        flushVertices()
        createEdges()
    }

    override fun createIndexes() {
        execute("ALTER TABLE $measurementTable ADD PRIMARY KEY (time, sensor_id, type);")
        execute("DROP INDEX IF EXISTS measurements_location_gist;")
        execute("CREATE INDEX measurements_location_gist ON $measurementTable USING GIST (location);")
    }

    private fun addVertex(label: String, properties: Map<String, Any?>) {
        pending += Vertex(loaded++, label, properties)
        if (pending.size >= vertexBatch) flushVertices()
    }

    /** Creates the pending vertices, one statement per label; if it fails, one statement per vertex */
    private fun flushVertices() {
        for ((label, group) in pending.groupBy { it.label }) {
            if (runCatching { createVertices(label, group) }.isSuccess) {
                created += group
            } else {
                for (vertex in group) {
                    runCatching { createVertices(label, listOf(vertex)) }
                        .onSuccess { created += vertex }
                        .onFailure { logger.severe { "Error processing entity: ${it.message}" } }
                }
            }
        }
        pending.clear()
    }

    private fun createVertices(label: String, vertices: List<Vertex>) {
        val patterns = vertices.joinToString(", ") { "(:$label ${formatProperties(it.properties)})" }
        query("SELECT * FROM cypher('$graphName', $$ CREATE $patterns $$) AS (n agtype);")
    }

    /**
     * The edges of the created vertices, as SmartBenchPGAge.insertVertex creates them: for each vertex, in the order of
     * loading, its references (hasSensor reversed into hasTemperature), when both ends exist at that point and the
     * edge does not exist yet; created per source label, edge label and destination label
     */
    private fun createEdges() {
        val inOrder = created.sortedBy { it.seq }
        val byId = inOrder.groupBy { it.id }
        val decided = mutableSetOf<Triple<String, String, String>>()
        val edges = mutableMapOf<Triple<String, String, String>, MutableList<Pair<String, String>>>()
        for (vertex in inOrder) {
            for ((source, edgeLabel, dest) in extractEdges(vertex.properties)) {
                val (s, label, d) = if (edgeLabel != "hasSensor") Triple(source, edgeLabel, dest) else Triple(dest, "hasTemperature", source)
                val sources = byId[s].orEmpty().filter { it.seq <= vertex.seq }.map { it.label }.distinct()
                val dests = byId[d].orEmpty().filter { it.seq <= vertex.seq }.map { it.label }.distinct()
                if (sources.isEmpty() || dests.isEmpty() || !decided.add(Triple(s, label, d))) continue
                for (sourceLabel in sources) for (destLabel in dests) {
                    edges.getOrPut(Triple(sourceLabel, label, destLabel)) { mutableListOf() } += s to d
                }
            }
        }
        for ((labels, pairs) in edges) {
            val (sourceLabel, label, destLabel) = labels
            for (batch in pairs.chunked(edgeBatch)) {
                val rows = batch.joinToString(", ", "[", "]") { (s, d) -> "{s: '$s', d: '$d'}" }
                query(
                    "SELECT * FROM cypher('$graphName', $$ UNWIND $rows AS e MATCH (a:$sourceLabel {id: e.s}), (b:$destLabel {id: e.d}) " +
                        "CREATE (a)-[:$label]->(b) $$) AS (r agtype);"
                )
            }
        }
    }
}
