package it.unibo.tests.smartbench.loaders

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import it.unibo.graph.asterixdb.dateToTimestamp
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Writes SmartBench as the Cypher dump that AeonG loads (datasets/dump/aeong/smartbench/<size>/<size>.cypher, one
 * statement per line, each one a transaction of AeonG), with the graph of SmartBenchNeo4J (its nodes, properties and
 * relationships), so that the queries are the same; the timestamps of the events are epoch milliseconds (the windows
 * of time_constraints.yaml). The events are added as a time series database receives them: series by series, in
 * batches of [batchSize] (a batch per statement, written when full and at the end of each series). The indexes on the
 * ids of the nodes the statements match come first. Writing the dump is not timed: AeonG times its load (see
 * SmartBenchEvaluation).
 */
class SmartBenchAeonG(private val size: String, private val batchSize: Int = 10) {
    private val mapper = jacksonObjectMapper()
    private val jsonPath: Path = Paths.get("datasets/original/smartbench/$size").toAbsolutePath()

    fun writeDump() {
        val dump = dumpFile(size).apply { parentFile.mkdirs() }
        dump.printWriter().use { out ->
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
            // The events, series by series, in batches
            val series = Files.list(jsonPath.resolve("timeseries")).use { it.sorted().toList() }
            for (file in series) {
                var sensor: String? = null
                val rows = smartBenchReader(file).useLines { lines ->
                    lines.filter { it.isNotBlank() }.map { line ->
                        val obj: Map<String, Any> = mapper.readValue(line)
                        if (sensor == null) sensor = (obj["sensor"] as Map<*, *>)["id"] as String
                        linkedMapOf(
                            "id" to obj["id"]!!.toString(),
                            "timestamp" to dateToTimestamp(obj["timestamp"] as String),
                            "temperature" to ((obj["payload"] as Map<*, *>)["temperature"] as Number).toDouble(),
                            "location" to obj["location"]!!.toString()
                        )
                    }.toList()
                }
                val sensors = nodesById[sensor ?: continue].orEmpty().filter { it.first == "Sensor" }
                for (batch in rows.chunked(batchSize)) {
                    for ((_, sensorId) in sensors) {
                        out.println(
                            "MATCH (s:Sensor {id: $sensorId}) UNWIND ${literal(batch)} AS r CREATE (s)-[:hasTemperature]->" +
                                "(:Temperature {id: r.id, timestamp: r.timestamp, temperature: r.temperature, location: r.location});"
                        )
                    }
                }
            }
        }
        File(dump.path + ".done").writeText(DUMP_VERSION)
    }

    companion object {
        /** The version of the dumps: one written by an older SmartBenchAeonG (or downloaded, of the old evaluation) is written again */
        const val DUMP_VERSION = "neo4j-graph-batches"

        fun dumpFile(size: String) = File("datasets/dump/aeong/smartbench/$size/$size.cypher")

        /** Whether the dump of [size] is complete and of this version */
        fun dumpReady(size: String) = File(dumpFile(size).path + ".done").let { it.isFile && it.readText() == DUMP_VERSION }

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
