package it.unibo.tests.smartbench.loaders

import it.unibo.graph.asterixdb.AsterixDBHTTPClient
import it.unibo.graph.interfaces.labelFromString
import it.unibo.graph.utils.EDGES
import it.unibo.graph.utils.EVENT
import it.unibo.graph.utils.FROM_N
import it.unibo.graph.utils.ID
import it.unibo.graph.utils.KEY
import it.unibo.graph.utils.LABEL
import it.unibo.graph.utils.LOCATION
import it.unibo.graph.utils.TO_N
import it.unibo.graph.utils.TSID
import it.unibo.graph.utils.TYPE
import it.unibo.graph.utils.VALUE
import it.unibo.graph.utils.getTime
import it.unibo.tests.systems.GraphixClient
import org.json.JSONObject
import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.io.WKTWriter

/**
 * Loads SmartBench into Graphix (AsterixDB + graph views), with the time series of [SmartBenchSTGraph]: same events,
 * same AsterixDB schema, same measurement.
 *
 * - One dataset per SmartBench collection (`Groups`, `Users`, `Sensors`, ...), holding the JSON objects as they are,
 *   inserted in batches (master data, timed as graph-store time).
 * - One dataset (`TimeSeries`) with one record per time series (its tsid, event label and sensor), as the
 *   time-series nodes of the graph store of STGraph (graph-store time).
 * - One dataset (`Observations`) for the events, with the type and the primary key (tsid, _id) of the events of
 *   STGraph, ingested through the same socket data feed: every event is the record STGraph sends (nodeToJson).
 * - One graph (`SmartBench`): one vertex label per collection and per event label, one edge label per reference
 *   field. As in [SmartBenchSTGraph], a top-level field `{"id": ...}` (or a list of them) is a reference and becomes
 *   an edge `has<Field>` towards the referenced entity; a sensor with a time series has an edge `has<EventLabel>`
 *   towards it. As in the graph store of STGraph, a time series is one vertex (a record of `TimeSeries`, labelled
 *   as its events), not one vertex per event: the queries reach its events through the primary key (tsid, _id) of
 *   `Observations` (see the queries in smartbench/queries/graphix).
 *
 * Timed as [SmartBenchSTGraph] and [SmartBenchDataLoader]: the events sent by each worker (not its flush), then its
 * wait until its last event is readable, without the last read (time-series time); the closes of the feeds and the
 * R-tree on the event locations (index time). One difference, forced by the AsterixDB version of Graphix: each worker
 * closes its feed before waiting for its last event, once AsterixDB has read its whole stream (see [awaitLast]).
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
    private val insertBatchSize: Int = 500
) : SmartBenchJsonLoader(size, threads) {
    private val dataverse = "smartbench_graphix_$size"
    private val graphix = GraphixClient(host, port, dataverse)
    private val eventsDataset = "Observations"
    private val timeSeriesDataset = "TimeSeries"
    private val graphName = "SmartBench"

    /** A reference field: entities labelled [source] point, through [field], to entities labelled [target] */
    private data class Reference(val source: String, val field: String, val isList: Boolean, val target: String)

    /** A reference towards the entity [targetId], not loaded yet when its source was added */
    private data class PendingReference(val source: String, val field: String, val isList: Boolean, val targetId: String)

    private val labelOf = mutableMapOf<String, String>()
    private val batches = mutableMapOf<String, MutableList<Map<String, Any>>>()
    private val timeSeries = mutableListOf<Map<String, Any>>()
    private val references = mutableSetOf<Reference>()
    private val pendingReferences = mutableSetOf<PendingReference>()
    // (sensor label, event label) of the time series
    private val eventEdges = mutableSetOf<Pair<String, String>>()
    private val feeds: List<AsterixDBHTTPClient> = (0 until threads).map { graphix.feed(eventsDataset, firstFeedPort + it) }
    // tsid of each time series: its number, as the id of the time-series node in STGraph
    private val tsIdOf = mutableMapOf<SmartBenchTimeSeries, Long>()
    // Time to close the feed of each worker (index time, see awaitLast)
    private val closeTimes = LongArray(threads)

    init {
        // Schema (not timed, as the graph/TS clean-up of the STGraph loader). The types of the events are the ones
        // of AsterixDBHTTPClient.createTSMEnvironment, with Edge between backticks (a keyword of Graphix)
        graphix.exec(
            """
            DROP DATAVERSE $dataverse IF EXISTS;
            CREATE DATAVERSE $dataverse;
            USE $dataverse;
            CREATE TYPE Property AS OPEN { `$KEY`: string, `$TYPE`: int };
            CREATE TYPE `Edge` AS CLOSED { $LABEL: int, $FROM_N: bigint, $TO_N: bigint };
            CREATE TYPE $EVENT AS OPEN { $TSID: bigint, $ID: bigint, $LABEL: int, $LOCATION: geometry?, $EDGES: [`Edge`]? };
            CREATE DATASET `$eventsDataset` ($EVENT) PRIMARY KEY $TSID, $ID;
            CREATE TYPE TimeSeriesType AS OPEN { id: bigint };
            CREATE DATASET `$timeSeriesDataset` (TimeSeriesType) PRIMARY KEY id;
            CREATE TYPE EntityType AS OPEN { id: string };
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
        if (batch.size >= insertBatchSize) insert(datasetOf(label), batch)
    }

    override fun addTimeSeries(ts: SmartBenchTimeSeries) {
        val tsId = tsIdOf.size + 1L
        tsIdOf[ts] = tsId
        timeSeries += mapOf("id" to tsId, LABEL to labelFromString(ts.eventLabel).ordinal, "sensor" to ts.sensorId, "sensorLabel" to ts.sensorLabel)
        eventEdges += ts.sensorLabel to ts.eventLabel
    }

    override fun endEntities() {
        batches.forEach { (label, batch) -> insert(datasetOf(label), batch) }
        insert(timeSeriesDataset, timeSeries)
        pendingReferences.forEach { ref -> labelOf[ref.targetId]?.let { references += Reference(ref.source, ref.field, ref.isList, it) } }
        val graphDefinition = createGraph()
        logger.info { graphDefinition }
        graphix.exec(graphDefinition)
    }

    override fun addEvents(worker: Int, ts: SmartBenchTimeSeries, events: List<SmartBenchEvent>) {
        val feed = feeds[worker]
        val tsId = tsIdOf.getValue(ts)
        val reader = WKTReader()
        val writer = WKTWriter()
        events.forEach { event ->
            // The record of nodeToJson (an event without edges, whose only property is its location): 2D WKT, as
            // STGraph writes it with JTS, which the feed parses into a geometry
            val location = writer.write(reader.read(event.location))
            val record = """{"$TSID": $tsId, "$ID": ${event.timestamp}, "$LABEL": ${labelFromString(event.label).ordinal}, "$LOCATION": "$location", "$VALUE": ${event.value}}"""
            feed.writeToFeed(record, flush = false)
        }
    }

    override fun endSeries(worker: Int, ts: SmartBenchTimeSeries) {
        feeds[worker].flush()
    }

    /**
     * Waits until the last event of the worker is readable, as [SmartBenchSTGraph], with the one difference forced by
     * the engine. The AsterixDB of Graphix (0.9.9-SNAPSHOT) stores the last, partial frame of a feed only when the
     * feed stops (the 0.9.9 of STGraph stores it while the feed is open), so the last events are never readable while
     * the feed is open. Hence, before the wait:
     * 1. wait until AsterixDB has read the whole stream of the worker (time-series time, as the rest of the wait):
     *    stopping the feed earlier loses the unread events;
     * 2. close the feed as STGraph closes its feeds (AsterixDBTSM.close), timed as index time, as in STGraph.
     */
    override fun awaitLast(worker: Int, ts: SmartBenchTimeSeries, last: SmartBenchEvent): Long {
        val read = getTime { feeds[worker].awaitDataFeedRead() }
        closeTimes[worker] = getTime { feeds[worker].closeDataFeedConnection(closeRemote = true) }
        return read + waitReadable { isStored(tsIdOf.getValue(ts), last.timestamp) }
    }

    // The closes of the feeds (see awaitLast), as STGraph closes its feeds at the beginning of its index time, then the
    // spatial index on the event locations, as the one STGraph creates (AsterixDBHTTPClient.createSpatialIndex)
    override fun createIndexes() {
        indexesTime += closeTimes.sum()
        graphix.exec("USE $dataverse; CREATE INDEX ${LOCATION}Idx ON `$eventsDataset`($LOCATION) TYPE RTREE;")
    }

    override fun close() {}

    /** Whether the event ([tsId], [timestamp]) is readable, with the read of AsterixDBTS.get */
    private fun isStored(tsId: Long, timestamp: Long): Boolean = try {
        JSONObject(graphix.exec("USE $dataverse; SELECT * FROM `$eventsDataset` WHERE $TSID = $tsId AND $ID = $timestamp;"))
            .getJSONArray("results").length() > 0
    } catch (_: Exception) {
        false
    }

    private fun addReference(source: String, field: String, isList: Boolean, targetId: String) {
        val target = labelOf[targetId]
        if (target != null) references += Reference(source, field, isList, target) else pendingReferences += PendingReference(source, field, isList, targetId)
    }

    /**
     * Inserts the pending records of [batch] into [dataset]. The batch is a request parameter, not part of the query
     * text: with the objects written in the query, a Graphix optimizer rule (ExtractFromRecordConstructorRule)
     * overflows the stack on nested records
     */
    private fun insert(dataset: String, batch: MutableList<Map<String, Any>>) {
        if (batch.isEmpty()) return
        graphix.exec("INSERT INTO `$dataset` (FROM \$rows AS r SELECT VALUE r);", mapOf("\$rows" to mapper.writeValueAsString(batch)))
        batch.clear()
    }

    /**
     * The CREATE GRAPH statement: vertices from the datasets and from the time series of `TimeSeries`, edges from the
     * reference fields and from the sensors to their time series
     */
    private fun createGraph(): String {
        val vertices = batches.keys.map { "VERTEX (:`$it`) PRIMARY KEY (id) AS `${datasetOf(it)}`" } +
            eventEdges.map { it.second }.toSet().map { label ->
                "VERTEX (:`$label`) PRIMARY KEY (id) AS (FROM `$timeSeriesDataset` T WHERE T.$LABEL = ${labelFromString(label).ordinal} SELECT VALUE T)"
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
        } + eventEdges.map { (sensorLabel, label) ->
            "EDGE (:`$sensorLabel`)-[:`${hasLabel(label)}`]->(:`$label`) SOURCE KEY (src) DESTINATION KEY (dst) " +
                "AS (FROM `$timeSeriesDataset` T " +
                "WHERE T.$LABEL = ${labelFromString(label).ordinal} AND T.sensorLabel = \"$sensorLabel\" SELECT T.sensor AS src, T.id AS dst)"
        }
        return "USE $dataverse; CREATE GRAPH $graphName AS\n  ${(vertices + edges).joinToString(",\n  ")};"
    }

    private fun hasLabel(field: String) = "has${label(field)}"
    private fun datasetOf(label: String) = "${label}s"
}
