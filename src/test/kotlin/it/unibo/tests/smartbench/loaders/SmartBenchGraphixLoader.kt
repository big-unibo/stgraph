package it.unibo.tests.smartbench.loaders

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import it.unibo.graph.asterixdb.dateToTimestamp
import it.unibo.graph.utils.*
import it.unibo.stats.Loader
import it.unibo.tests.graphix.GraphixClient
import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.io.WKTWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.logging.Logger

/**
 * Loads SmartBench into Graphix (AsterixDB + graph views).
 *
 * - One dataset per SmartBench collection (`Groups`, `Users`, `Sensors`, ...), holding the JSON objects as they are,
 *   inserted in batches (master data, timed as graph-store time).
 * - One dataset (`Observations`) for the time-series events, ingested through the same socket data feed used by STGraph.
 * - One graph (`SmartBench`): one vertex label per collection and per event type, one edge label per reference field.
 *   As in [SmartBenchDataLoader], a top-level field `{"id": ...}` (or a list of them) is a reference and becomes
 *   an edge `has<Field>` towards the referenced entity; a sensor with a time series has an edge `has<EventType>`
 *   towards each of its events.
 */
class SmartBenchGraphixLoader(
    size: String,
    val threads: Int = 1,
    host: String = "localhost",
    port: Int = 29002,
    private val firstFeedPort: Int = 11001,
    // Entities per INSERT statement. Entities (master data) are loaded in batches and timed separately from the
    // events, which are streamed one at a time through the data feed. One INSERT per entity would mostly measure
    // the fixed cost of compiling and running an AsterixDB job (~170 ms) for each statement.
    private val insertBatchSize: Int = 500,
    // End of the event ingestion: the count must not change for this long before the feeds are stopped
    private val stableWindowMs: Long = 10_000,
    private val completionTimeoutMs: Long = 60 * 60 * 1000
) : Loader {
    private val projectRoot = Paths.get("").toAbsolutePath().normalize()
    private val dataset = "smartbench"
    private val dataPath = "$projectRoot/datasets/original/$dataset/$size"
    private val collections = listOf(
        "group", "user", "platformType", "sensorType", "platform", "infrastructureType",
        "infrastructure", "sensor", "virtualSensorType", "virtualSensor", "semanticObservationType"
    )
    private val dataverse = "${dataset}_graphix_$size"
    private val graphix = GraphixClient(host, port, dataverse)
    private val eventsDataset = "Observations"
    private val graphName = "SmartBench"

    private val logger: Logger = Logger.getLogger(this.javaClass.name)
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private var gsTime: Long = 0
    private var tsTime: Long = 0
    private var gsCard: Long = 0
    private var tsCard: Long = 0
    private var indexTime: Long = 0

    /** A reference field: entities labelled [source] point, through [field], to entities labelled [target] */
    private data class Reference(val source: String, val field: String, val isList: Boolean, val target: String)

    /** A time series: the events of [file] belong to the entity [sensorId] labelled [sensorLabel] */
    private data class TimeSeries(val sensorId: String, val sensorLabel: String, val file: String)

    init {
        // Schema (not timed, as the graph/TS clean-up of the STGraph loader)
        exec(
            """
            DROP DATAVERSE $dataverse IF EXISTS;
            CREATE DATAVERSE $dataverse;
            USE $dataverse;
            CREATE TYPE EntityType AS OPEN { id: string };
            CREATE TYPE $EVENT AS OPEN { id: string, $LOCATION: geometry? };
            CREATE DATASET `$eventsDataset` ($EVENT) PRIMARY KEY id;
            """
        )
    }

    override fun loadData() {
        // Read the entities (not timed, as the file parsing of the STGraph loader)
        val entities: Map<String, List<Map<String, Any>>> = collections.associateWith { name ->
            val file = File("$dataPath/$name.json")
            require(file.exists()) { "File not found: $file" }
            mapper.readValue<List<Map<String, Any>>>(file)
        }
        val labels = entities.mapValues { (name, objs) -> label(objs.firstOrNull()?.get(TYPE) as? String ?: name) }
        val labelOf: Map<String, String> = entities.flatMap { (name, objs) -> objs.map { it["id"] as String to labels[name]!! } }.toMap()
        val references = findReferences(entities, labels, labelOf)
        val timeSeries = findTimeSeries(entities, labels)
        val graphDefinition = createGraph(labels.values.toSet(), references, timeSeries)
        logger.info { graphDefinition }
        entities.keys.forEach { name -> exec("CREATE DATASET `${datasetOf(labels[name]!!)}` (EntityType) PRIMARY KEY id;") }

        // Entities and graph definition
        gsTime += getTime {
            for ((name, objs) in entities) {
                // The batch is a request parameter, not part of the query text: with the objects written in the query,
                // a Graphix optimizer rule (ExtractFromRecordConstructorRule) overflows the stack on nested records
                objs.chunked(insertBatchSize).forEach { batch ->
                    exec(
                        "INSERT INTO `${datasetOf(labels[name]!!)}` (FROM \$rows AS r SELECT VALUE r);",
                        mapOf("\$rows" to mapper.writeValueAsString(batch))
                    )
                }
                gsCard += objs.size
            }
            exec(graphDefinition)
        }

        // Events, through one socket data feed per thread
        tsTime += loadEvents(timeSeries)

        // Spatial index on the event locations, as the one STGraph creates on its time-series store
        indexTime += getTime { exec("CREATE INDEX ${LOCATION}Idx ON `$eventsDataset`($LOCATION) TYPE RTREE;") }
    }

    /** Reference fields found in the data, with the label of the referenced entities */
    private fun findReferences(
        entities: Map<String, List<Map<String, Any>>>,
        labels: Map<String, String>,
        labelOf: Map<String, String>
    ): Set<Reference> {
        val references = mutableSetOf<Reference>()
        for ((name, objs) in entities) {
            for (obj in objs) {
                for ((field, value) in obj) {
                    when (value) {
                        is Map<*, *> -> (value["id"] as? String)?.let { labelOf[it] }?.let { references += Reference(labels[name]!!, field, false, it) }
                        is List<*> -> value.forEach { item ->
                            ((item as? Map<*, *>)?.get("id") as? String)?.let { labelOf[it] }?.let { references += Reference(labels[name]!!, field, true, it) }
                        }
                    }
                }
            }
        }
        return references
    }

    /** Sensors and virtual sensors with a time-series file, as in the STGraph loader */
    private fun findTimeSeries(entities: Map<String, List<Map<String, Any>>>, labels: Map<String, String>): List<TimeSeries> =
        entities
            .filterKeys { labels[it] == Sensor || labels[it] == VirtualSensor }
            .flatMap { (name, objs) ->
                objs.mapNotNull { obj ->
                    val sensorId = obj["id"] as String
                    val file = File("$dataPath/timeseries/$sensorId.json")
                    if (file.exists()) TimeSeries(sensorId, labels[name]!!, file.path) else null
                }
            }

    /** The CREATE GRAPH statement: vertices from the datasets, edges from the reference fields and the events */
    private fun createGraph(labels: Set<String>, references: Set<Reference>, timeSeries: List<TimeSeries>): String {
        val eventEdges = timeSeries.flatMap { ts -> eventTypes(ts.file).map { ts.sensorLabel to it } }.toSet()
        val vertices = labels.map { "VERTEX (:`$it`) PRIMARY KEY (id) AS `${datasetOf(it)}`" } +
            eventEdges.map { it.second }.toSet().map { type ->
                "VERTEX (:`$type`) PRIMARY KEY (id) AS (FROM `$eventsDataset` O WHERE O.`type` = \"$type\" SELECT VALUE O)"
            }
        val edges = references.map { ref ->
            val from = if (ref.isList) "FROM `${datasetOf(ref.source)}` S, S.`${ref.field}` R" else "FROM `${datasetOf(ref.source)}` S LET R = S.`${ref.field}`"
            val conditions = listOfNotNull(
                // Entities without the field (or with a non-reference value) have no edge
                "R.id IS NOT UNKNOWN",
                // A field may point to entities of several labels: then each edge keeps only its own targets
                if (references.count { it.source == ref.source && it.field == ref.field } > 1) "R.id IN (FROM `${datasetOf(ref.target)}` T SELECT VALUE T.id)" else null
            )
            "EDGE (:`${ref.source}`)-[:`${hasLabel(ref.field)}`]->(:`${ref.target}`) SOURCE KEY (src) DESTINATION KEY (dst) " +
                "AS ($from WHERE ${conditions.joinToString(" AND ")} SELECT S.id AS src, R.id AS dst)"
        } + eventEdges.map { (sensorLabel, type) ->
            "EDGE (:`$sensorLabel`)-[:`${hasLabel(type)}`]->(:`$type`) SOURCE KEY (src) DESTINATION KEY (dst) " +
                "AS (FROM `$eventsDataset` O WHERE O.`type` = \"$type\" AND O.sensorLabel = \"$sensorLabel\" SELECT O.sensorId AS src, O.id AS dst)"
        }
        return "USE $dataverse; CREATE GRAPH $graphName AS\n  ${(vertices + edges).joinToString(",\n  ")};"
    }

    /** Event types of a time series: each SmartBench sensor produces events of a single type, read from the first one */
    private fun eventTypes(file: String): Set<String> =
        File(file).useLines { lines -> lines.firstOrNull()?.let { setOf(mapper.readTree(it).get(TYPE).textValue()) } ?: emptySet() }

    /** Sends the events to Graphix and returns the time until all of them are stored */
    private fun loadEvents(timeSeries: List<TimeSeries>): Long {
        if (timeSeries.isEmpty()) return 0
        val nThreads = threads.coerceIn(1, timeSeries.size)
        val clients = (0 until nThreads).map { i ->
            graphix.feed(eventsDataset, firstFeedPort + i)
        }
        val chunks = timeSeries.chunked((timeSeries.size + nThreads - 1) / nThreads)
        val sent = LongArray(nThreads)
        val times = LongArray(nThreads)
        val workers = chunks.mapIndexed { i, chunk ->
            Thread {
                for (ts in chunk) {
                    val events = readEvents(ts) // read before timing, as in the STGraph loader
                    times[i] += getTime {
                        events.forEach { clients[i].writeToFeed(it, flush = false) }
                        clients[i].flush()
                    }
                    sent[i] += events.size.toLong()
                }
            }.also { it.start() }
        }
        workers.forEach { it.join() }
        val expected = sent.sum()
        val completion = graphix.completeFeeds(clients, eventsDataset, expected, stableWindowMs, completionTimeoutMs)
        tsCard = expected
        return (times.maxOrNull() ?: 0) + completion
    }

    private fun readEvents(ts: TimeSeries): List<String> =
        Files.newBufferedReader(Paths.get(ts.file)).useLines { lines ->
            lines.map { line ->
                val json: JsonNode = mapper.readTree(line)
                val type = json.get(TYPE).textValue()
                val payloadField = if (type == "Temperature") "temperature" else "presence"
                mapper.writeValueAsString(
                    linkedMapOf(
                        "id" to json.get("id").textValue(),
                        "sensorId" to ts.sensorId,
                        "sensorLabel" to ts.sensorLabel,
                        TYPE to type,
                        "timestamp" to dateToTimestamp(json.get("timestamp").textValue()),
                        VALUE to json.get("payload").get(payloadField).longValue(),
                        // 2D WKT, as STGraph sends it (JTS writes 2D by default): the feed parses it into a geometry,
                        // and drops the records whose WKT has a Z coordinate
                        LOCATION to WKTWriter().write(WKTReader().read(json.get(LOCATION).textValue()))
                    )
                )
            }.toList()
        }

    private fun exec(sql: String, parameters: Map<String, String> = emptyMap()): String = graphix.exec(sql, parameters)

    private fun label(type: String) = type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    private fun hasLabel(field: String) = "has${label(field)}"
    private fun datasetOf(label: String) = "${label}s"

    override fun close() {}
    override fun getGSTime(): Long = gsTime
    override fun getTSTime(): Long = tsTime
    override fun getIndexTime(): Long = indexTime
    override fun getGSCardinality(): Long = gsCard
    override fun getTSCardinality(): Long = tsCard
}
