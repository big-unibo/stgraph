package it.unibo.tests.smartbench.loaders

import it.unibo.graph.utils.TYPE
import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.io.WKTWriter
import it.unibo.tests.systems.pgAgeConnection
import java.sql.Connection
import java.util.*

/**
 * Loads SmartBench into PostgreSQL with Apache AGE (graph), TimescaleDB (events) and PostGIS (locations). A port of
 * smartbench_ingestion.py and age_middleware.py (digital_twins_architectures), with the same statements:
 * - graph: one vertex per entity, labelled with its type and holding all its fields (references as maps, locations
 *   as WKT); one edge has<Field> per reference, created only if both vertices already exist (an edge towards an
 *   entity loaded later is lost, as in the Python code); hasSensor is reversed into hasTemperature;
 * - time series: the first event of each series becomes a vertex (as an entity, so it is linked to its sensor through
 *   hasTemperature); the temperature events go to the hypertable [measurementTable], in batches of [batchSize].
 * Not ported: the multi-device entities (type "Device" with "hasDevice") and the GeoJSON locations, which SmartBench
 * does not have.
 */
open class SmartBenchPGAge(
    size: String,
    url: String = "jdbc:postgresql://localhost:5432/smartbench_eval",
    user: String = "postgres",
    password: String = "psw",
    protected val graphName: String = "smartbench_graph",
    private val batchSize: Int = 20_000
) : SmartBenchJsonLoader(size, threads = 1) {
    protected val measurementTable = "public.measurements"
    // With the session settings of the Python code (applied once); multi-row INSERTs from a batch, as execute_values
    protected val conn: Connection = pgAgeConnection(url, user, password, mapOf("reWriteBatchedInserts" to "true"))
    private var skippedEvents = 0L

    init {
        // Clean-up and schema (not timed). The first two fail when graph and table do not exist yet
        runCatching { execute("SELECT * FROM ag_catalog.drop_graph('$graphName', true);") }
        runCatching { execute("TRUNCATE TABLE $measurementTable;") }
        execute("CREATE EXTENSION IF NOT EXISTS age;")
        execute("CREATE EXTENSION IF NOT EXISTS postgis;")
        if (query("SELECT COUNT(*) FROM ag_catalog.ag_graph WHERE name = '$graphName';").single()[0] == 0L) {
            execute("SELECT * FROM ag_catalog.create_graph('$graphName');")
        }
        execute(
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
        execute("SELECT create_hypertable('$measurementTable', 'time', if_not_exists => TRUE);")
    }

    override fun addEntity(label: String, entity: Map<String, Any>) {
        // As the ingestion loop of the Python code, an entity that fails is logged and skipped
        try {
            insertVertex(entity[TYPE] as String, entity - "_id")
        } catch (e: Exception) {
            logger.severe { "Error processing entity: ${e.message}" }
        }
    }

    override fun addTimeSeries(ts: SmartBenchTimeSeries) {}

    override fun addEvents(worker: Int, ts: SmartBenchTimeSeries, events: List<SmartBenchEvent>) {
        if (events.isEmpty()) return
        val first = events.first().json
        insertVertex(first[TYPE] as String, first - "_id")
        // Only the temperatures have a mapping to the measurement table
        val rows = events.filter { it.json[TYPE] == "Temperature" }
        rows.chunked(batchSize).forEach { insertMeasurements(it) }
        skippedEvents += events.size - rows.size
    }

    override fun loadEvents() {
        super.loadEvents()
        tsCard -= skippedEvents
    }

    override fun createIndexes() {
        execute("ALTER TABLE $measurementTable ALTER COLUMN location TYPE geometry(PointZ, 4326) USING ST_Force3D(location);")
        execute("DROP INDEX IF EXISTS measurements_location_gist;")
        execute("CREATE INDEX measurements_location_gist ON $measurementTable USING GIST (location);")
    }

    override fun close() {
        conn.close()
    }

    private fun insertVertex(label: String, properties: Map<String, Any?>) {
        query("SELECT * FROM cypher('$graphName', $$ CREATE (n:$label ${formatProperties(properties)}) RETURN n $$) AS (n agtype);")
        for ((source, edgeLabel, dest) in extractEdges(properties)) {
            if (edgeLabel != "hasSensor") createEdge(source, edgeLabel, dest) else createEdge(dest, "hasTemperature", source)
        }
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

    private fun createEdge(source: String, label: String, dest: String) {
        if (!nodeExists(source) || !nodeExists(dest)) {
            logger.fine { "Failed to create edge from $source to $dest, some/all nodes do not exist" }
        } else if (!edgeExists(source, label, dest)) {
            try {
                query(
                    "SELECT * FROM cypher('$graphName', $$ MATCH (source {id: '$source'}), (dest {id: '$dest'}) " +
                        "CREATE (source)-[r:$label]->(dest) RETURN r $$) AS (r agtype);"
                )
            } catch (e: Exception) {
                logger.warning { "Failed to create edge from $source to $dest: ${e.message}" }
            }
        }
    }

    private fun nodeExists(id: String): Boolean =
        query("SELECT * FROM cypher('$graphName', $$ MATCH (n) WHERE n.id = '$id' RETURN n $$) AS (n agtype);").isNotEmpty()

    private fun edgeExists(source: String, label: String, dest: String): Boolean =
        query(
            "SELECT EXISTS (SELECT * FROM cypher('$graphName', $$ MATCH (source {id: '$source'})-[r:$label]->(dest {id: '$dest'}) " +
                "RETURN r $$) AS (r agtype));"
        ).single()[0] as Boolean

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
