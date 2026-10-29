package it.unibo.tests.smartbench.loaders

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import kotlin.io.path.name

/**
 * A bulk load of SmartBench into Neo4j, to query it afterwards (./gradlew fastLoadNeo4j): the nodes and relationships
 * that [SmartBenchNeo4J] creates from the Cypher dumps (datasets/original/smartbench/neo4j/<size>), written as CSV
 * files for neo4j-admin database import (offline, into an empty database), with the same rules:
 * - graph.cypher: one node per CREATE; a MATCH ... CREATE relationship only between the nodes that exist at that
 *   point of the file, for every pair of matching nodes (as the statement);
 * - events: on "small", the nodes of ts.cypher (location as a string); otherwise the events of timeseries/<sensor>.json
 *   (location as a cartesian 3D point), as the UNWIND of SmartBenchNeo4J; in both cases a hasTemperature relationship
 *   from every Sensor node with the id of the series, and no event for a series without one;
 * - the timestamps as datetime, the numbers as integers or floats as in the dumps (one file per property types).
 * The import itself (stop, neo4j-admin, start) and the range index of [SmartBenchNeo4J.createIndexes] are run by
 * SmartBenchEvaluation.fastLoad. Not an ingestion of the evaluation: its times are not comparable.
 */
class SmartBenchNeo4JFast(val size: String) {
    private val dumpPath: Path = Paths.get("").toAbsolutePath().normalize().resolve("datasets/original/smartbench/neo4j/$size")
    private val mapper = jacksonObjectMapper()

    /** A node of the graph: its import id, label and properties */
    private class Node(val importId: Long, val label: String, val properties: Map<String, Any?>)

    /** The value of a datetime(...) in the dumps */
    private data class DateTime(val text: String)

    private var nextId = 0L
    var graphNodes = 0L
        private set
    var events = 0L
        private set
    var relationships = 0L
        private set

    /**
     * Writes the CSV files of the import into [dir] (emptied first) and returns the arguments of neo4j-admin database
     * import for them, with [importDir] the folder where the import reads [dir]
     */
    fun writeImportFiles(dir: Path, importDir: String = "/import"): List<String> {
        dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val nodesById = mutableMapOf<String, MutableList<Node>>()
        val graphNodesByFile = mutableMapOf<String, MutableList<Node>>()
        val args = mutableListOf<String>()
        relationshipWriter(dir.resolve("relationships.csv.gz")).use { rels ->
            // Graph: the statements of graph.cypher, in order
            for (statement in SmartBenchNeo4J.cypherStatements(dumpPath.resolve("graph.cypher"))) {
                val create = CREATE_NODE.matchEntire(statement)
                val match = MATCH_RELATIONSHIP.matchEntire(statement)
                when {
                    create != null -> {
                        val node = Node(nextId++, create.groupValues[1], parseProperties(create.groupValues[2]))
                        nodesById.getOrPut(node.properties["id"].toString()) { mutableListOf() } += node
                        graphNodesByFile.getOrPut(fileKey(node)) { mutableListOf() } += node
                        graphNodes++
                    }
                    match != null -> {
                        val (sourceLabel, sourceId, destId, from, type, to) = match.destructured
                        val sources = nodesById[sourceId].orEmpty().filter { it.label == sourceLabel }
                        val dests = nodesById[destId].orEmpty()
                        for (u in sources) for (v in dests) {
                            val (start, end) = if (from == "u" && to == "v") u to v else v to u
                            rels.write("${start.importId},${end.importId},$type\n")
                            relationships++
                        }
                    }
                    else -> error("Statement of ${dumpPath.resolve("graph.cypher")} not supported by the fast load: ${statement.take(200)}")
                }
            }
            graphNodesByFile.values.forEachIndexed { index, nodes ->
                val file = dir.resolve("nodes_${nodes.first().label}_$index.csv")
                writeNodes(file, nodes)
                args += "--nodes=${nodes.first().label}=$importDir/${file.name}"
            }
            // Events: one file per type of the temperature (integer or float), as the dumps mix them
            val eventFiles = mutableMapOf<String, Pair<Path, BufferedWriter>>()
            fun eventWriter(header: String): BufferedWriter = eventFiles.getOrPut(header) {
                val file = dir.resolve("events_${eventFiles.size}.csv.gz")
                val writer = gzipWriter(file)
                writer.write(header + "\n")
                file to writer
            }.second
            try {
                if (size == "small") smallEvents(nodesById, rels, ::eventWriter) else jsonEvents(nodesById, rels, ::eventWriter)
            } finally {
                eventFiles.values.forEach { it.second.close() }
            }
            eventFiles.values.forEach { (file, _) -> args += "--nodes=Temperature=$importDir/${file.name}" }
        }
        args += "--relationships=$importDir/relationships.csv.gz"
        return args
    }

    /** The events of ts.cypher: one statement per series, MATCH of its sensor and one CREATE per event */
    private fun smallEvents(nodesById: Map<String, List<Node>>, rels: BufferedWriter, writer: (String) -> BufferedWriter) {
        for (statement in SmartBenchNeo4J.cypherStatements(dumpPath.resolve("ts.cypher"))) {
            val sensorId = SERIES_SENSOR.find(statement)?.groupValues?.get(1) ?: error("No sensor in a statement of ts.cypher: ${statement.take(200)}")
            val sensors = nodesById[sensorId].orEmpty().filter { it.label == "Sensor" }
            for (event in SERIES_EVENT.findAll(statement)) {
                val properties = parseProperties(event.groupValues[1])
                for (sensor in sensors) writeEvent(properties, location = properties["location"], sensor, rels, writer)
            }
        }
    }

    /** The events of timeseries/<sensor>.json, as the UNWIND of SmartBenchNeo4J */
    private fun jsonEvents(nodesById: Map<String, List<Node>>, rels: BufferedWriter, writer: (String) -> BufferedWriter) {
        val files = Files.list(dumpPath.resolve("timeseries")).use { it.sorted().toList() }
        for (file in files) {
            Files.newBufferedReader(file).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val obj: Map<String, Any> = mapper.readValue(line)
                    val sensors = nodesById[(obj["sensor"] as Map<*, *>)["id"] as String].orEmpty().filter { it.label == "Sensor" }
                    val (x, y, z) = (obj["location"] as String).replace("POINT(", "").replace(")", "").trim().split(Regex("\\s+")).map { it.toDouble() }
                    val properties = mapOf(
                        "id" to obj["id"],
                        "timestamp" to DateTime(obj["timestamp"] as String),
                        "temperature" to (obj["payload"] as Map<*, *>)["temperature"]
                    )
                    for (sensor in sensors) writeEvent(properties, location = Point(x, y, z), sensor, rels, writer)
                }
            }
        }
    }

    private data class Point(val x: Double, val y: Double, val z: Double)

    /** One Temperature node (id, timestamp, temperature, location) and its hasTemperature relationship from [sensor] */
    private fun writeEvent(properties: Map<String, Any?>, location: Any?, sensor: Node, rels: BufferedWriter, writer: (String) -> BufferedWriter) {
        val temperature = properties["temperature"]
        val temperatureType = if (temperature is Double || temperature is Float) "double" else "long"
        val locationType = if (location is Point) "point{crs:cartesian-3d}" else "string"
        val out = writer(":ID,id:string,timestamp:datetime,temperature:$temperatureType,location:$locationType")
        val id = nextId++
        val locationValue = when (location) {
            is Point -> quote("{x:${location.x}, y:${location.y}, z:${location.z}}")
            null -> ""
            else -> quote(location.toString())
        }
        out.write("$id,${quote(properties["id"].toString())},${(properties["timestamp"] as DateTime).text},$temperature,$locationValue\n")
        rels.write("${sensor.importId},$id,hasTemperature\n")
        events++
        relationships++
    }

    /** Graph nodes of a label with the same property types go to the same file */
    private fun fileKey(node: Node): String =
        node.label + node.properties.entries.sortedBy { it.key }.joinToString(",", "(", ")") { (key, value) -> "$key:${typeOf(value)}" }

    private fun writeNodes(file: Path, nodes: List<Node>) {
        val keys = nodes.first().properties.keys.sorted()
        Files.newBufferedWriter(file).use { out ->
            out.write((listOf(":ID") + keys.map { "$it:${typeOf(nodes.first().properties[it])}" }).joinToString(",") + "\n")
            for (node in nodes) {
                out.write((listOf(node.importId.toString()) + keys.map { csvValue(node.properties[it]) }).joinToString(",") + "\n")
            }
        }
    }

    private fun typeOf(value: Any?): String = when (value) {
        is String -> "string"
        is Long, is Int -> "long"
        is Double -> "double"
        is Boolean -> "boolean"
        is DateTime -> "datetime"
        is List<*> -> when {
            value.all { it is Long || it is Int } -> "long[]"
            value.all { it is Number } -> "double[]"
            value.all { it is Boolean } -> "boolean[]"
            else -> "string[]"
        }
        null -> "string"
        else -> error("Property type not supported by the fast load: $value")
    }

    private fun csvValue(value: Any?): String = when (value) {
        null -> ""
        is String -> quote(value)
        is DateTime -> value.text
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

    /**
     * The properties of a map literal of the dumps (without its braces): 'strings' (not escaped), numbers, true and
     * false, null, datetime('...') (written by SmartBenchNeo4J.cypherStatements) and lists of JSON values
     */
    private fun parseProperties(text: String): Map<String, Any?> {
        val properties = linkedMapOf<String, Any?>()
        var i = 0
        fun skipSpaces() { while (i < text.length && (text[i].isWhitespace() || text[i] == ',')) i++ }
        fun parseValue(): Any? {
            while (text[i].isWhitespace()) i++
            return when {
                text[i] == '\'' -> { val end = text.indexOf('\'', i + 1); text.substring(i + 1, end).also { i = end + 1 } }
                text[i] == '"' -> {
                    val sb = StringBuilder(); i++
                    while (text[i] != '"') { if (text[i] == '\\') { i++; sb.append(if (text[i] == 'n') '\n' else text[i]) } else sb.append(text[i]); i++ }
                    i++; sb.toString()
                }
                text[i] == '[' -> {
                    i++; val list = mutableListOf<Any?>()
                    while (true) { while (text[i].isWhitespace() || text[i] == ',') i++; if (text[i] == ']') { i++; break }; list += parseValue() }
                    list
                }
                text.startsWith("datetime(", i) -> {
                    val start = text.indexOf('\'', i); val end = text.indexOf('\'', start + 1)
                    DateTime(text.substring(start + 1, end)).also { i = text.indexOf(')', end) + 1 }
                }
                else -> {
                    val start = i
                    while (i < text.length && text[i] != ',' && text[i] != ']' && !text[i].isWhitespace()) i++
                    when (val token = text.substring(start, i)) {
                        "true", "True" -> true
                        "false", "False" -> false
                        "null", "None" -> null
                        else -> token.toLongOrNull() ?: token.toDouble()
                    }
                }
            }
        }
        skipSpaces()
        while (i < text.length) {
            val colon = text.indexOf(':', i)
            val key = text.substring(i, colon).trim()
            i = colon + 1
            properties[key] = parseValue()
            skipSpaces()
        }
        return properties.filterValues { it != null }
    }

    companion object {
        // CREATE (n:Label { ... }) of graph.cypher
        private val CREATE_NODE = Regex("""CREATE \(n:(\w+) \{(.*)\}\)""", RegexOption.DOT_MATCHES_ALL)

        // MATCH (u : Label {id : 'x' }), (v {id : 'y' }) CREATE (u) - [r:type] -> (v) (or from v to u) of graph.cypher
        private val MATCH_RELATIONSHIP = Regex(
            """MATCH \(u : (\w+) \{id : '([^']*)' \}\), \(v \{id : '([^']*)' \}\) CREATE \((\w)\) - \[r:(\w+)\] -> \((\w)\)"""
        )

        // MATCH (s_N: Sensor {id: 'x'}) and CREATE (s_N)-[:hasTemperature]->(:Temperature {...}) of ts.cypher
        private val SERIES_SENSOR = Regex("""MATCH \(s_\d+: Sensor \{id: '([^']*)'\}\)""")
        private val SERIES_EVENT = Regex(""":Temperature \{(.*?)\}\s*\)""", RegexOption.DOT_MATCHES_ALL)
    }
}
