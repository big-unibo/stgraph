package it.unibo.tests.smartbench.loaders

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import it.unibo.graph.asterixdb.dateToTimestamp
import it.unibo.tests.smartbench.queries.TimeWindows
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Writes SmartBench as the Cypher dump that AeonG loads (datasets/dump/aeong/smartbench/<size>/<size>.cypher, one
 * statement per line, each one a transaction of AeonG), with the graph of SmartBenchNeo4J (its nodes, properties and
 * relationships) and the temporal model of AeonG: a Temperature node per sensor (with its sensor id), created by its
 * first event and updated (SET of id, timestamp, temperature, location) by each next one, one statement (one
 * transaction, one version) per event, the events of all the series in the order of their timestamps (epoch ms), as
 * AeonG would receive them, so that the transaction time follows the time of the events. The indexes on the ids of the
 * nodes the statements match come first. <size>.tt: for each bound of the windows of the queries on the size
 * (TimeWindows), the first statement of an event at or after it, and last=<n>, the number of statements: the ingestion
 * of AeonG (evaluation/architectures/aeong/ingest_boundaries.py) records the transaction time of each one in
 * <size>.aeong. Writing the dump is not timed: AeonG times its load.
 */
class SmartBenchAeonG(private val size: String) {
    private val mapper = jacksonObjectMapper()
    private val jsonPath: Path = Paths.get("datasets/original/smartbench/$size").toAbsolutePath()

    fun writeDump() {
        val dump = dumpFile(size).apply { parentFile.mkdirs() }
        var statements = 0L
        val bounds = sortedMapOf<Long, Long>() // a bound of the windows -> the first statement of an event at or after it
        windowBounds(size).forEach { bounds[it] = -1 }
        dump.printWriter().use { file ->
            val out = object { fun println(s: String) { file.println(s); statements++ } }
            val nodes = SmartBenchJsonLoader.collections.flatMap { collection ->
                val file = jsonPath.resolve("$collection.json")
                require(smartBenchExists(file)) { "File not found: $file" }
                smartBenchReader(file).use { mapper.readValue<List<Map<String, Any?>>>(it) }.map { SmartBenchNeo4J.jsonNode(it, file) }
            }
            val types = SmartBenchNeo4J.schema(nodes)
            types.keys.forEach { label -> out.println("CREATE INDEX ON :`$label`(id);") }
            // The nodes of the entities, then their relationships, once every entity exists (as SmartBenchNeo4J)
            val nodesById = mutableMapOf<String, MutableList<Pair<String, String>>>() // JSON id -> (label, id literal)
            for ((label, properties, _) in nodes) {
                val props = properties.mapValues { (key, value) -> SmartBenchNeo4J.typed(value, types.getValue(label).getValue(key)) }
                out.println("CREATE (:`$label` ${literal(props)});")
                nodesById.getOrPut(properties["id"].toString()) { mutableListOf() } += label to literal(props["id"])
            }
            for ((label, properties, referenced) in nodes) {
                val source = literal(SmartBenchNeo4J.typed(properties["id"], types.getValue(label).getValue("id")))
                for ((type, id) in referenced) {
                    for ((destLabel, destId) in nodesById[id].orEmpty()) {
                        out.println("MATCH (a:`$label` {id: $source}), (b:`$destLabel` {id: $destId}) CREATE (a)-[:`$type`]->(b);")
                    }
                }
            }
            val series = Files.list(jsonPath.resolve("timeseries")).use { it.sorted().toList() }
            run {
                out.println("CREATE INDEX ON :Temperature(sensor);")
                val created = HashSet<String>()
                mergedEvents(series) { sensor, event ->
                    val timestamp = event["timestamp"] as Long
                    bounds.headMap(timestamp + 1).filterValues { it < 0 }.keys.forEach { bounds[it] = statements + 1 }
                    for ((_, sensorId) in nodesById[sensor].orEmpty().filter { it.first == "Sensor" }) {
                        if (created.add(sensorId)) {
                            out.println("MATCH (s:Sensor {id: $sensorId}) CREATE (s)-[:hasTemperature]->(:Temperature ${literal(mapOf("sensor" to sensor) + event)});")
                        } else {
                            out.println("MATCH (t:Temperature {sensor: ${literal(sensor)}}) SET " + event.entries.joinToString(", ") { (k, v) -> "t.$k = ${literal(v)}" } + ";")
                        }
                    }
                }
            }
        }
        File(dump.path.removeSuffix(".cypher") + ".tt").writeText(
            bounds.entries.joinToString("") { (bound, statement) -> "$bound=${if (statement < 0) statements + 1 else statement}\n" } + "last=$statements\n"
        )
        File(dump.path + ".done").writeText(DUMP_VERSION)
    }

    /** An event of a series: its properties on the Temperature node (timestamp in epoch ms) */
    private fun event(obj: Map<String, Any>): Map<String, Any> = linkedMapOf(
        "id" to obj["id"]!!.toString(),
        "timestamp" to dateToTimestamp(obj["timestamp"] as String),
        "temperature" to ((obj["payload"] as Map<*, *>)["temperature"] as Number).toDouble(),
        "location" to obj["location"]!!.toString()
    )

    /** The events of all the [series] (each one sorted by time) in the order of their timestamps: [block] (sensor, event) */
    private fun mergedEvents(series: List<Path>, block: (String, Map<String, Any>) -> Unit) {
        class Cursor(val lines: Iterator<String>, val reader: java.io.BufferedReader) {
            var sensor = ""
            var current: Map<String, Any>? = null
            fun next() {
                current = null
                while (lines.hasNext()) {
                    val line = lines.next()
                    if (line.isBlank()) continue
                    val obj: Map<String, Any> = mapper.readValue(line)
                    sensor = (obj["sensor"] as Map<*, *>)["id"] as String
                    current = event(obj)
                    return
                }
                reader.close()
            }
        }
        val queue = java.util.PriorityQueue<Cursor>(compareBy { it.current!!["timestamp"] as Long })
        for (file in series) {
            val reader = smartBenchReader(file)
            Cursor(reader.lineSequence().iterator(), reader).apply { next() }.takeIf { it.current != null }?.let { queue += it }
        }
        while (queue.isNotEmpty()) {
            val cursor = queue.poll()
            block(cursor.sensor, cursor.current!!)
            cursor.next()
            if (cursor.current != null) queue += cursor
        }
    }

    companion object {
        /** The version of the dumps: one written by an older SmartBenchAeonG (another model) is written again */
        const val DUMP_VERSION = "updates"

        fun dumpFile(size: String) = File("datasets/dump/aeong/smartbench/$size/$size.cypher")

        /** Whether the dump of [size] is complete and of this version */
        fun dumpReady(size: String) = File(dumpFile(size).path + ".done").let { it.isFile && it.readText() == DUMP_VERSION }

        /** The bounds (from and to) of the time windows of the queries on [size] (TimeWindows) */
        fun windowBounds(size: String): Set<Long> = TimeWindows.of(size).flatMap { listOf(it.from, it.to) }.toSet()

        /** [value] as a Cypher literal (strings in single quotes, lists, maps) */
        fun literal(value: Any?): String = when (value) {
            null -> "null"
            is String -> "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'"
            is Boolean, is Number -> value.toString()
            is List<*> -> value.joinToString(", ", "[", "]") { literal(it) }
            is Map<*, *> -> value.entries.filter { it.value != null }.joinToString(", ", "{", "}") { (k, v) -> "$k: ${literal(v)}" }
            else -> literal(value.toString())
        }
    }
}
