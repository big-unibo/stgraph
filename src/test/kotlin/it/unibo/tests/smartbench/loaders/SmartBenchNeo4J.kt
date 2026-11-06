package it.unibo.tests.smartbench.loaders

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import it.unibo.graph.utils.TYPE
import it.unibo.graph.utils.getTime
import it.unibo.tests.systems.neo4jDriver
import org.neo4j.driver.Driver
import org.neo4j.driver.Session
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import kotlin.io.path.name

/** Whether SmartBench is loaded into Neo4j from CSV files (neo4j-admin import) rather than through the driver: from medium on */
fun neo4jCsv(size: String): Boolean = size !in listOf("xsmall", "small")

/**
 * Loads SmartBench into Neo4j from the JSON dataset of the other systems (datasets/original/smartbench/<size>), as
 * MimicIVNeo4J loads MIMIC-IV, in two ways that produce the same graph (see [jsonNode]): a node per entity of the
 * collections of SmartBenchJsonLoader (label: its capitalized type; properties: its fields, but the objects with an id)
 * and a relationship has<Key> towards every node with an id it refers to (alone or in a list), once every entity
 * exists; a Temperature node per event of timeseries/<sensor>.json (id, timestamp as a datetime, temperature, location
 * as WKT), linked by hasTemperature from every Sensor with the id of the series (no event for a series without one).
 * The property types are the ones of the headers of the CSV files, the same in both ways (see [schema]): per label, a
 * property is long, double, boolean, string[] (a list) or string, the widest type of its values; the temperature is a
 * double.
 * - Without [csv], through [uri], as MimicIVNeo4J: one statement per entity, then one per relationship (graph time);
 *   the events in batches of [batchSize], a batch sent when full and at the end of each series, in one transaction, its
 *   sensors matched by element id (time-series time).
 * - With [csv], the same nodes and relationships written as CSV files for neo4j-admin database import
 *   (datasets/import/neo4j/<size>, generated here and removed at the end; one file of nodes per label, gzipped
 *   relationships and events, which neo4j-admin reads as they are): their writing is graph and time-series time; then [csvImport] (e.g., neo4j-admin
 *   database import into an empty database, see SmartBenchEvaluation.neo4jImport) imports them and returns the time
 *   of the import, added to the time-series time.
 * The files are read before timing. The range index of the events is index time (see [createIndexes]).
 */
class SmartBenchNeo4J(
    size: String,
    private val uri: String = "bolt://localhost:7687",
    private val batchSize: Int = 20_000,
    private val csv: Boolean = neo4jCsv(size),
    private val csvImport: ((File, List<String>) -> Long)? = null
) : AbstractSmartBenchLoader(size, threads = 1) {
    private val jsonPath: Path = projectRoot.resolve("datasets/original/smartbench/$size")
    private val driver: Driver = neo4jDriver(uri)
    private val session: Session = driver.session()
    private val mapper = jacksonObjectMapper()
    // Without csv: the element ids of the nodes, by JSON id, with their label
    private val nodeIds = mutableMapOf<String, MutableList<Pair<String, String>>>()

    init {
        // Clean-up (not timed): the Python code ran on a new container every time. The index too, so that it is built
        // after the load (see createIndexes), not maintained during it. With csv, the import replaces the database
        if (!csv) {
            session.run("DROP INDEX $TIMESTAMP_INDEX IF EXISTS").consume()
            session.run("MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS").consume()
        }
    }

    /**
     * The temporal index of the events, as the (tsid, _id) key of STGraph and Graphix and the time index of
     * TimescaleDB: a range index on the timestamps of the Temperature nodes, built after the load and awaited until
     * it is online (Neo4j populates it in the background). The planner decides whether to use it (no hints)
     */
    override fun createIndexes() = createIndexes(session)

    /** Without [csv], as the skeleton; with [csv], the CSV files, their import, then the index on a new connection */
    override fun loadData() {
        if (!csv) return super.loadData()
        val dir = projectRoot.resolve("datasets/import/neo4j/$size").toFile()
        try {
            val start = System.currentTimeMillis()
            val args = writeImportFiles(dir.toPath())
            tsTime += System.currentTimeMillis() - start - gsTime + (csvImport?.invoke(dir, args) ?: 0L)
        } finally {
            dir.deleteRecursively()
        }
        // Neo4j restarted by the import: a new driver
        if (csvImport != null) indexesTime += getTime { neo4jDriver(uri).use { d -> d.session().use { createIndexes(it) } } }
    }

    override fun loadGraph() {
        val references = mutableListOf<Triple<String, String, String>>()
        val nodes = jsonEntities().map { (file, entity) -> jsonNode(entity, file) }
        val types = schema(nodes)
        for ((label, properties, referenced) in nodes) {
            val props = properties.mapValues { (key, value) -> typed(value, types.getValue(label).getValue(key)) }
            gsTime += getTime {
                val node = session.run("CREATE (n:`$label`) SET n = \$props RETURN elementId(n)", mapOf("props" to props)).single()[0].asString()
                nodeIds.getOrPut(properties["id"].toString()) { mutableListOf() } += label to node
                referenced.forEach { (type, id) -> references += Triple(node, type, id) }
            }
        }
        gsTime += getTime {
            for ((source, type, id) in references) {
                for ((_, dest) in nodeIds[id].orEmpty()) {
                    session.run(
                        "MATCH (a), (b) WHERE elementId(a) = \$a AND elementId(b) = \$b CREATE (a)-[:`$type`]->(b)",
                        mapOf("a" to source, "b" to dest)
                    ).consume()
                }
            }
        }
        gsCard = count("MATCH (n) RETURN count(n)")
    }

    override fun loadEvents() {
        for (file in timeSeriesFiles()) {
            val (sensorId, rows) = readTimeSeries(file)
            val sensors = nodeIds[sensorId].orEmpty().filter { it.first == "Sensor" }.map { it.second }
            tsTime += getTime {
                // As MimicIVNeo4J: a batch when full and at the end of the series, in one transaction
                rows.chunked(batchSize).forEach { batch ->
                    session.beginTransaction().use { tx ->
                        sensors.forEach { sensor -> tx.run(UNWIND_QUERY, mapOf("sensor" to sensor, "rows" to batch)).consume() }
                        tx.commit()
                    }
                }
            }
            logger.info { "Processed ${file.name}" }
        }
        tsCard = count("MATCH (n:Temperature) RETURN count(n)")
    }

    override fun close() {
        session.close()
        driver.close()
    }

    private fun count(query: String): Long = session.run(query).single()[0].asLong()

    /** The entities of the collections of SmartBenchJsonLoader, in order, with their file */
    private fun jsonEntities(): List<Pair<Path, Map<String, Any?>>> =
        SmartBenchJsonLoader.collections.flatMap { collection ->
            val file = jsonPath.resolve("$collection.json")
            require(Files.exists(file)) { "File not found: $file" }
            mapper.readValue<List<Map<String, Any?>>>(file.toFile()).map { file to it }
        }

    private fun timeSeriesFiles(): List<Path> = Files.list(jsonPath.resolve("timeseries")).use { it.sorted().toList() }

    /** The sensor and the rows of a time-series file (one JSON object per line) */
    private fun readTimeSeries(file: Path): Pair<String?, List<Map<String, Any>>> {
        var sensorId: String? = null
        val rows = Files.newBufferedReader(file).useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val obj: Map<String, Any> = mapper.readValue(line)
                if (sensorId == null) sensorId = (obj["sensor"] as Map<*, *>)["id"] as String
                mapOf(
                    "id" to obj["id"]!!,
                    "timestamp" to obj["timestamp"]!!,
                    "temperature" to ((obj["payload"] as Map<*, *>)["temperature"] as Number).toDouble(),
                    // The WKT of the file (e.g., POINT(76 17 0)), as the locations of the entities
                    "location" to obj["location"]!!
                )
            }.toList()
        }
        return sensorId to rows
    }

    // --- With csv: the CSV files of neo4j-admin database import ---

    /** A node of the CSV files: its import id, label and properties */
    private class Node(val importId: Long, val label: String, val properties: Map<String, Any?>)

    private var nextId = 0L

    /**
     * Writes the CSV files of the import into [dir] (emptied first) and returns the arguments of neo4j-admin database
     * import for them, as read in /import; the time to write the nodes of the entities and the relationships is graph
     * time ([gsTime]), the cardinalities those of the files
     */
    private fun writeImportFiles(dir: Path): List<String> {
        val start = System.currentTimeMillis()
        dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val nodesById = mutableMapOf<String, MutableList<Node>>()
        val nodesByLabel = linkedMapOf<String, MutableList<Node>>()
        val args = mutableListOf<String>()
        relationshipWriter(dir.resolve("relationships.csv.gz")).use { rels ->
            // Graph: the nodes of the entities, then their relationships, once every entity exists
            val references = mutableListOf<Triple<Node, String, String>>()
            val nodes = jsonEntities().map { (file, entity) -> jsonNode(entity, file) }
            val types = schema(nodes)
            for ((label, properties, referenced) in nodes) {
                val node = Node(nextId++, label, properties)
                nodesById.getOrPut(properties["id"].toString()) { mutableListOf() } += node
                nodesByLabel.getOrPut(label) { mutableListOf() } += node
                gsCard++
                referenced.forEach { (type, id) -> references += Triple(node, type, id) }
            }
            for ((source, type, id) in references) {
                for (dest in nodesById[id].orEmpty()) rels.write("${source.importId},${dest.importId},$type\n")
            }
            for ((label, labelNodes) in nodesByLabel) {
                val file = dir.resolve("nodes_$label.csv")
                writeNodes(file, labelNodes, types.getValue(label))
                args += "--nodes=$label=/import/${file.name}"
            }
            gsTime += System.currentTimeMillis() - start
            // Events: one file, the header with the types of the UNWIND
            gzipWriter(dir.resolve("events.csv.gz")).use { events ->
                events.write(":ID,id:string,timestamp:datetime,temperature:double,location:string\n")
                for (file in timeSeriesFiles()) {
                    Files.newBufferedReader(file).useLines { lines ->
                        for (line in lines) {
                            if (line.isBlank()) continue
                            val obj: Map<String, Any> = mapper.readValue(line)
                            val sensors = nodesById[(obj["sensor"] as Map<*, *>)["id"] as String].orEmpty().filter { it.label == "Sensor" }
                            for (sensor in sensors) writeEvent(obj, sensor, rels, events)
                        }
                    }
                }
            }
            args += "--nodes=Temperature=/import/events.csv.gz"
        }
        args += "--relationships=/import/relationships.csv.gz"
        return args
    }

    /** One Temperature node (id, timestamp, temperature, location), as the UNWIND, and its hasTemperature relationship from [sensor] */
    private fun writeEvent(obj: Map<String, Any>, sensor: Node, rels: BufferedWriter, out: BufferedWriter) {
        val temperature = ((obj["payload"] as Map<*, *>)["temperature"] as Number).toDouble()
        val id = nextId++
        val location = obj["location"]?.let { quote(it.toString()) } ?: ""
        out.write("$id,${quote(obj["id"].toString())},${obj["timestamp"]},$temperature,$location\n")
        rels.write("${sensor.importId},$id,hasTemperature\n")
        tsCard++
    }

    /** The nodes of a label, the header with the types of [types] (a missing property: an empty field, no property) */
    private fun writeNodes(file: Path, nodes: List<Node>, types: Map<String, String>) {
        Files.newBufferedWriter(file).use { out ->
            out.write((listOf(":ID") + types.map { (key, type) -> "$key:$type" }).joinToString(",") + "\n")
            for (node in nodes) {
                out.write((listOf(node.importId.toString()) + types.map { (key, type) -> csvValue(typed(node.properties[key], type)) }).joinToString(",") + "\n")
            }
        }
    }

    private fun csvValue(value: Any?): String = when (value) {
        null -> ""
        is String -> quote(value)
        is List<*> -> quote(value.joinToString(";"))
        else -> value.toString()
    }

    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    private fun gzipWriter(file: Path): BufferedWriter {
        val gzip = object : GZIPOutputStream(Files.newOutputStream(file), 1 shl 16) {
            init { def.setLevel(Deflater.BEST_SPEED) }
        }
        return BufferedWriter(OutputStreamWriter(gzip), 1 shl 20)
    }

    private fun relationshipWriter(file: Path): BufferedWriter = gzipWriter(file).apply { write(":START_ID,:END_ID,:TYPE\n") }

    companion object {
        private const val TIMESTAMP_INDEX = "temperature_timestamp"

        /** The range index on the timestamps of the Temperature nodes (see the KDoc of the override), awaited */
        fun createIndexes(session: Session) {
            session.run("CREATE RANGE INDEX $TIMESTAMP_INDEX FOR (t:Temperature) ON (t.timestamp)").consume()
            session.run("CALL db.awaitIndex('$TIMESTAMP_INDEX', 3600)").consume()
        }

        private val UNWIND_QUERY = """
            MATCH (s) WHERE elementId(s) = ${'$'}sensor
            UNWIND ${'$'}rows AS row
            CREATE (s)-[:hasTemperature]->(
                :Temperature {
                    id: row.id,
                    timestamp: datetime(row.timestamp),
                    temperature: row.temperature,
                    location: row.location
                }
            )
        """.trimIndent()

        /**
         * The property types of each label, as the headers of the CSV files and the values of the statements: per key,
         * the widest type of its values (long and double: double; any other mix: string), in the order of the keys
         */
        private fun schema(nodes: List<Triple<String, Map<String, Any?>, List<Pair<String, String>>>>): Map<String, Map<String, String>> {
            val types = linkedMapOf<String, LinkedHashMap<String, String>>()
            for ((label, properties, _) in nodes) {
                val labelTypes = types.getOrPut(label) { linkedMapOf() }
                for ((key, value) in properties) {
                    val type = when (value) {
                        is Boolean -> "boolean"
                        is Int, is Long -> "long"
                        is Number -> "double"
                        is List<*> -> "string[]"
                        else -> "string"
                    }
                    labelTypes[key] = when (val previous = labelTypes[key]) {
                        null, type -> type
                        else -> if (setOf(previous, type) == setOf("long", "double")) "double" else "string"
                    }
                }
            }
            return types
        }

        /** [value] as a property of type [type] (see [schema]) */
        private fun typed(value: Any?, type: String): Any? = when {
            value == null -> null
            type == "long" -> (value as Number).toLong()
            type == "double" -> (value as Number).toDouble()
            type == "boolean" -> value as Boolean
            type == "string[]" -> (value as List<*>).map { it.toString() }
            else -> value.toString()
        }

        /**
         * An entity of the JSON dataset as a node, the same with and without csv: its label (its capitalized type), its
         * properties (its fields, but the type, the nulls and the objects with an id) and its relationships has<Key>
         * towards the ids of the objects it refers to (alone or in a list; an empty list: nothing, as in STGraph)
         */
        private fun jsonNode(entity: Map<String, Any?>, file: Path): Triple<String, Map<String, Any?>, List<Pair<String, String>>> {
            val properties = linkedMapOf<String, Any?>()
            val references = mutableListOf<Pair<String, String>>()
            for ((key, value) in entity) {
                val type = "has" + key.replaceFirstChar { it.uppercase() }
                when {
                    key == TYPE || value == null -> {}
                    value is Map<*, *> && "id" in value -> references += type to value["id"].toString()
                    value is List<*> && value.all { it is Map<*, *> && "id" in it } -> value.forEach { references += type to (it as Map<*, *>)["id"].toString() }
                    value is Map<*, *> -> error("Field $key of $file not supported: an object without id")
                    else -> properties[key] = value
                }
            }
            val label = SmartBenchJsonLoader.label(entity[TYPE] as? String ?: error("Missing \"$TYPE\" in $file"))
            return Triple(label, properties, references)
        }
    }
}
