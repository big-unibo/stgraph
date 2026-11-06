package it.unibo.tests.smartbench.loaders

import it.unibo.graph.utils.TYPE
import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.io.WKTWriter
import it.unibo.tests.systems.pgAgeConnection
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import java.io.StringReader
import java.sql.Connection
import java.util.*

/** Whether SmartBench is loaded into PGAge with COPY rather than batches of INSERTs: from medium on */
fun pgageCopy(size: String): Boolean = size !in listOf("xsmall", "small")

/** The primary key of the measurement table (<graph>.measurements, as PostgreSQL names it by default) */
private const val PRIMARY_KEY = "measurements_pkey"

/** The AGE graph of [size], whose schema holds the table measurements: smartbench_<size>, as mimic_<size> of MIMIC-IV */
fun pgageGraph(size: String) = "smartbench_$size"

/**
 * Loads SmartBench into PostgreSQL with Apache AGE (graph), TimescaleDB (events) and PostGIS (locations). A port of
 * smartbench_ingestion.py and age_middleware.py (digital_twins_architectures), with the same statements:
 * - graph: one vertex per entity, labelled with its type and holding all its fields (references as maps, locations
 *   as WKT); one edge has<Field> per reference, created only if both vertices already exist (an edge towards an
 *   entity loaded later is lost, as in the Python code); hasSensor is reversed into hasTemperature;
 * - time series: the first event of each series becomes a vertex (as an entity, so it is linked to its sensor through
 *   hasTemperature); the temperature events go to the hypertable [measurementTable], in batches of [batchSize] INSERTs
 *   or, with [copy] (above the small sizes, as MimicIVPGAge), with one COPY per series, its primary key dropped before
 *   the load and added back after it (index time): the same rows, the same graph.
 * As MimicIVPGAge: a graph and a measurement table per size (graph [graphName], table <graph>.measurements); one
 * statement per vertex, then one per edge, linking the vertices by their AGE ids (no search by property); the edges
 * follow the rules of the Python code (an edge only between vertices that exist when its source is loaded, at most one
 * per source, label and destination), decided in memory; a failing statement stops the ingestion, while the errors of
 * the clean-up and of the schema are printed.
 * Not ported: the multi-device entities (type "Device" with "hasDevice") and the GeoJSON locations, which SmartBench
 * does not have.
 */
open class SmartBenchPGAge(
    size: String,
    url: String = "jdbc:postgresql://localhost:5432/smartbench_eval",
    user: String = "postgres",
    password: String = "psw",
    protected val graphName: String = pgageGraph(size),
    private val batchSize: Int = 20_000,
    private val copy: Boolean = pgageCopy(size)
) : SmartBenchJsonLoader(size, threads = 1) {
    protected val measurementTable = "$graphName.measurements"
    // With the session settings of the Python code (applied once); multi-row INSERTs from a batch, as execute_values
    protected val conn: Connection = pgAgeConnection(url, user, password, mapOf("reWriteBatchedInserts" to "true"))
    private var skippedEvents = 0L

    /** The vertices created so far, by their JSON id: (label, AGE id), in the order of loading */
    private val vertices = mutableMapOf<String, MutableList<Pair<String, Long>>>()
    /** The edges already decided: (source, label, destination), by JSON id */
    private val decided = mutableSetOf<Triple<String, String, String>>()

    init {
        // Clean-up and schema (not timed), as MimicIVPGAge: an error is printed (e.g., a graph that does not exist yet).
        // The table is in the schema of the graph, dropped with it
        safeExec("SELECT * FROM ag_catalog.drop_graph('$graphName', true);")
        safeExec("CREATE EXTENSION IF NOT EXISTS age;")
        safeExec("CREATE EXTENSION IF NOT EXISTS postgis;")
        safeExec("SELECT * FROM ag_catalog.create_graph('$graphName');")
        safeExec(
            """
            CREATE TABLE IF NOT EXISTS $measurementTable (
                time timestamp NOT NULL,
                sensor_id text NOT NULL,
                type text,
                val double precision,
                location geometry(PointZ),
                PRIMARY KEY(time, sensor_id, type)
            );
            """.trimIndent()
        )
        // Chunks of 7 days, as the hypertable of MimicIVPGAge (604800000 ms there, on its epoch milliseconds)
        safeExec("SELECT create_hypertable('$measurementTable', 'time', chunk_time_interval => INTERVAL '7 days', if_not_exists => TRUE);")
        // The primary key: kept during the INSERTs; with COPY, dropped before the load and added back after it
        if (copy) safeExec("ALTER TABLE $measurementTable DROP CONSTRAINT IF EXISTS $PRIMARY_KEY;")
    }

    override fun addEntity(label: String, entity: Map<String, Any>) = addVertex(entity[TYPE] as String, entity - "_id")

    override fun addTimeSeries(ts: SmartBenchTimeSeries) {}

    override fun addEvents(worker: Int, ts: SmartBenchTimeSeries, events: List<SmartBenchEvent>) {
        if (events.isEmpty()) return
        val first = events.first().json
        addVertex(first[TYPE] as String, first - "_id")
        // Only the temperatures have a mapping to the measurement table
        val rows = events.filter { it.json[TYPE] == "Temperature" }
        if (copy) copyMeasurements(rows) else rows.chunked(batchSize).forEach { insertMeasurements(it) }
        skippedEvents += events.size - rows.size
    }

    override fun loadEvents() {
        super.loadEvents()
        tsCard -= skippedEvents
    }

    override fun createIndexes() {
        if (copy) addPrimaryKey()
        execute("ALTER TABLE $measurementTable ALTER COLUMN location TYPE geometry(PointZ, 4326) USING ST_Force3D(location);")
        execute("DROP INDEX IF EXISTS $graphName.measurements_location_gist;")
        execute("CREATE INDEX measurements_location_gist ON $measurementTable USING GIST (location);")
    }

    override fun close() {
        conn.close()
    }

    private fun addPrimaryKey() = execute("ALTER TABLE $measurementTable ADD CONSTRAINT $PRIMARY_KEY PRIMARY KEY (time, sensor_id, type);")

    /**
     * Creates the vertex (one statement, as MimicIVPGAge), then the edges of its references (hasSensor reversed into
     * hasTemperature), one statement each, towards the vertices with that JSON id that exist at this point
     */
    private fun addVertex(label: String, properties: Map<String, Any?>) {
        val id = conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM cypher('$graphName', $$ CREATE (n:$label ${formatProperties(properties)}) RETURN id(n) $$) AS (id agtype);").use { rs ->
                rs.next()
                rs.getString(1).replace("\"", "").toLong()
            }
        }
        vertices.getOrPut(properties["id"].toString()) { mutableListOf() } += label to id
        for ((source, edgeLabel, dest) in extractEdges(properties)) {
            val (s, edge, d) = if (edgeLabel != "hasSensor") Triple(source, edgeLabel, dest) else Triple(dest, "hasTemperature", source)
            val sources = vertices[s].orEmpty()
            val dests = vertices[d].orEmpty()
            if (sources.isEmpty() || dests.isEmpty() || !decided.add(Triple(s, edge, d))) continue
            for ((sourceLabel, sourceId) in sources) for ((destLabel, destId) in dests) {
                execute(
                    "SELECT * FROM cypher('$graphName', $$ MATCH (a:$sourceLabel), (b:$destLabel) WHERE id(a) = $sourceId AND id(b) = $destId " +
                        "CREATE (a)-[:$edge]->(b) $$) AS (r agtype);"
                )
            }
        }
    }

    /** As the safeExec of MimicIVPGAge: the error of [sql] is printed, not thrown */
    private fun safeExec(sql: String) {
        runCatching { execute(sql) }.onFailure { println("Warning executing SQL: $sql (${it.message})") }
    }

    /** The references of an entity: a field (not "id") holding {"id": ...}, or a list with some of them */
    protected fun extractEdges(entity: Map<String, Any?>): List<Triple<String, String, String>> {
        val source = entity["id"].toString()
        return entity.entries
            .filter { (key, value) ->
                (key != "id" && value is Map<*, *> && "id" in value) || (value is List<*> && value.any { it is Map<*, *> && "id" in it })
            }
            .flatMap { (key, dest) ->
                val edgeLabel = "has${capitalize(key)}"
                when (dest) {
                    is Map<*, *> -> listOf(Triple(source, edgeLabel, dest["id"].toString()))
                    is List<*> -> dest.filterIsInstance<Map<*, *>>().filter { "id" in it }.map { Triple(source, edgeLabel, it["id"].toString()) }
                    else -> emptyList()
                }
            }
    }

    private fun insertMeasurements(events: List<SmartBenchEvent>) {
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                "INSERT INTO $measurementTable (time, sensor_id, type, val, location) VALUES (?::timestamp, ?, ?, ?, ?::geometry)"
            ).use { st ->
                for (event in events) {
                    st.setString(1, event.json["timestamp"] as String)
                    st.setString(2, (event.json["sensor"] as Map<*, *>)["id"].toString())
                    st.setString(3, "Temperature")
                    st.setDouble(4, ((event.json["payload"] as Map<*, *>)["temperature"] as Number).toDouble())
                    st.setString(5, event.json["location"] as? String ?: "POINT EMPTY")
                    st.addBatch()
                }
                st.executeBatch()
            }
            conn.commit()
        } finally {
            conn.autoCommit = true
        }
    }

    /** The same rows as [insertMeasurements] (same values, cast by PostgreSQL in the same way), with one COPY */
    private fun copyMeasurements(events: List<SmartBenchEvent>) {
        if (events.isEmpty()) return
        val csv = StringBuilder()
        for (event in events) {
            listOf(
                event.json["timestamp"] as String,
                (event.json["sensor"] as Map<*, *>)["id"].toString(),
                "Temperature",
                ((event.json["payload"] as Map<*, *>)["temperature"] as Number).toDouble().toString(),
                event.json["location"] as? String ?: "POINT EMPTY"
            ).joinTo(csv, ",", postfix = "\n") { "\"" + it.replace("\"", "\"\"") + "\"" }
        }
        CopyManager(conn.unwrap(BaseConnection::class.java))
            .copyIn("COPY $measurementTable (time, sensor_id, type, val, location) FROM STDIN WITH (FORMAT csv)", StringReader(csv.toString()))
    }

    // Cypher literals as written by age_middleware.py (strings are not escaped, as there)
    protected fun formatProperties(properties: Map<*, *>): String =
        properties.entries.joinToString(", ", "{", "}") { (key, value) ->
            if (key == "location") "$key: '${toWkt(value)}'" else "$key: ${formatValue(value)}"
        }

    private fun formatValue(value: Any?): String = when (value) {
        is String -> if (value == "NULL") value else "'$value'"
        is List<*> -> value.joinToString(", ", "[", "]") { formatValue(it) }
        is Map<*, *> -> if ("type" in value && "coordinates" in value) "st_geomfromtext('${toWkt(value)}')" else formatProperties(value)
        // Python prints booleans and None as True, False and None
        is Boolean -> if (value) "True" else "False"
        null -> "None"
        else -> value.toString()
    }

    /**
     * A WKT string as normalized WKT (shapely in the Python code), keeping the Z coordinate. The Python code also
     * accepts GeoJSON objects, which SmartBench does not have
     */
    private fun toWkt(value: Any?): String {
        require(value is String) { "Unsupported geometry (only WKT strings): $value" }
        return WKTWriter(3).write(WKTReader().read(value))
    }

    /** Python's str.capitalize: first character upper case, the others lower case */
    private fun capitalize(s: String) = s.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }

    protected fun execute(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    protected fun query(sql: String): List<List<Any?>> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val columns = rs.metaData.columnCount
                buildList { while (rs.next()) add((1..columns).map { rs.getObject(it) }) }
            }
        }
}
