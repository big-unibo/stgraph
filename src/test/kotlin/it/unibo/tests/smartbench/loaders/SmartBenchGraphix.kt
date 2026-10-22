package it.unibo.tests.smartbench.loaders

import it.unibo.graph.utils.EVENT
import it.unibo.graph.utils.LOCATION
import it.unibo.graph.utils.TYPE
import it.unibo.graph.utils.VALUE
import it.unibo.tests.graphix.GraphixClient
import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.io.WKTWriter

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
class SmartBenchGraphix(
    size: String,
    threads: Int = 1,
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
) : SmartBenchJsonLoader(size, threads) {
    private val dataverse = "smartbench_graphix_$size"
    private val graphix = GraphixClient(host, port, dataverse)
    private val eventsDataset = "Observations"
    private val graphName = "SmartBench"

    /** A reference field: entities labelled [source] point, through [field], to entities labelled [target] */
    private data class Reference(val source: String, val field: String, val isList: Boolean, val target: String)

    /** A reference towards the entity [targetId], not loaded yet when its source was added */
    private data class PendingReference(val source: String, val field: String, val isList: Boolean, val targetId: String)

    private val labelOf = mutableMapOf<String, String>()
    private val batches = mutableMapOf<String, MutableList<Map<String, Any>>>()
    private val references = mutableSetOf<Reference>()
    private val pendingReferences = mutableSetOf<PendingReference>()
    // (sensor label, event label) of the time series
    private val eventEdges = mutableSetOf<Pair<String, String>>()
    private val feeds = (0 until threads).map { graphix.feed(eventsDataset, firstFeedPort + it) }
    private val sent = LongArray(threads)

    init {
        // Schema (not timed, as the graph/TS clean-up of the STGraph loader)
        graphix.exec(
            """
            DROP DATAVERSE $dataverse IF EXISTS;
            CREATE DATAVERSE $dataverse;
            USE $dataverse;
            CREATE TYPE EntityType AS OPEN { id: string };
            CREATE TYPE $EVENT AS OPEN { id: string, $LOCATION: geometry? };
            CREATE DATASET `$eventsDataset` ($EVENT) PRIMARY KEY id;
            """ + collections.joinToString("\n") { "CREATE DATASET `${datasetOf(label(it))}` (EntityType) PRIMARY KEY id;" }
        )
    }

    override fun addEntity(label: String, entity: Map<String, Any>) {
        labelOf[entity["id"] as String] = label
        for ((field, value) in entity) {
            when (value) {
                is Map<*, *> -> (value["id"] as? String)?.let { addReference(label, field, false, it) }
                is List<*> -> value.forEach { item -> ((item as? Map<*, *>)?.get("id") as? String)?.let { addReference(label, field, true, it) } }
            }
        }
        val batch = batches.getOrPut(label) { mutableListOf() }
        batch += entity
        if (batch.size >= insertBatchSize) insert(label)
    }

    override fun addTimeSeries(ts: SmartBenchTimeSeries) {
        eventEdges += ts.sensorLabel to ts.eventLabel
    }

    override fun endEntities() {
        batches.keys.forEach { insert(it) }
        pendingReferences.forEach { ref -> labelOf[ref.targetId]?.let { references += Reference(ref.source, ref.field, ref.isList, it) } }
        val graphDefinition = createGraph()
        logger.info { graphDefinition }
        graphix.exec(graphDefinition)
    }

    override fun addEvents(worker: Int, ts: SmartBenchTimeSeries, events: List<SmartBenchEvent>) {
        val feed = feeds[worker]
        val reader = WKTReader()
        val writer = WKTWriter()
        events.forEach { event ->
            val record = linkedMapOf<String, Any>(
                "id" to event.id,
                "sensorId" to ts.sensorId,
                "sensorLabel" to ts.sensorLabel,
                TYPE to event.label,
                "timestamp" to event.timestamp,
                VALUE to event.value,
                // 2D WKT, as STGraph sends it (JTS writes 2D by default): the feed parses it into a geometry,
                // and drops the records whose WKT has a Z coordinate
                LOCATION to writer.write(reader.read(event.location))
            )
            feed.writeToFeed(mapper.writeValueAsString(record), flush = false)
        }
        feed.flush()
        sent[worker] += events.size.toLong()
    }

    /** Waits until every sent event is stored (see [GraphixClient.completeFeeds]) */
    override fun endEvents(): Long {
        val expected = sent.sum()
        if (expected == 0L) return 0
        return graphix.completeFeeds(feeds.filterIndexed { i, _ -> sent[i] > 0 }, eventsDataset, expected, stableWindowMs, completionTimeoutMs)
    }

    // Spatial index on the event locations, as the one STGraph creates on its time-series store
    override fun createIndexes() {
        graphix.exec("CREATE INDEX ${LOCATION}Idx ON `$eventsDataset`($LOCATION) TYPE RTREE;")
    }

    override fun close() {}

    private fun addReference(source: String, field: String, isList: Boolean, targetId: String) {
        val target = labelOf[targetId]
        if (target != null) references += Reference(source, field, isList, target) else pendingReferences += PendingReference(source, field, isList, targetId)
    }

    /**
     * Inserts the pending entities labelled [label]. The batch is a request parameter, not part of the query text:
     * with the objects written in the query, a Graphix optimizer rule (ExtractFromRecordConstructorRule) overflows
     * the stack on nested records
     */
    private fun insert(label: String) {
        val batch = batches.getValue(label)
        if (batch.isEmpty()) return
        graphix.exec("INSERT INTO `${datasetOf(label)}` (FROM \$rows AS r SELECT VALUE r);", mapOf("\$rows" to mapper.writeValueAsString(batch)))
        batch.clear()
    }

    /** The CREATE GRAPH statement: vertices from the datasets, edges from the reference fields and the events */
    private fun createGraph(): String {
        val vertices = batches.keys.map { "VERTEX (:`$it`) PRIMARY KEY (id) AS `${datasetOf(it)}`" } +
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

    private fun hasLabel(field: String) = "has${label(field)}"
    private fun datasetOf(label: String) = "${label}s"
}
