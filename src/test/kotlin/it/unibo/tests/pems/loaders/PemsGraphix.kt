package it.unibo.tests.pems.loaders

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import it.unibo.graph.utils.EVENT
import it.unibo.graph.utils.getTime
import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord
import it.unibo.tests.systems.GraphixClient
import org.json.JSONObject

/**
 * Loads PeMS into Graphix, as MimicIVGraphix: a dataset per node label and per edge (source label, label, target
 * label), {id, src, dst}, inserted in batches (graph-store time); the measurements through the data feed, with the
 * primary key of the events of STGraph (tsId, label, timestamp), and the graph view Pems over them
 */
class PemsGraphix(
    size: String,
    host: String = "localhost",
    port: Int = 29002,
    feedHost: String = host,
    feedPort: Int = 11001,
    private val insertBatchSize: Int = 500,
    postgres: PostgresConfig = PostgresConfig()
) : AbstractPemsLoader(size, postgres) {
    private val dataverse = "pems_graphix_$size"
    private val graphix = GraphixClient(host, port, dataverse, feedHost)
    private val mapper = jacksonObjectMapper()
    private val measurements = "Measurements"
    private val vertices = linkedMapOf<String, MutableList<Map<String, Any>>>()
    private val edges = linkedMapOf<Triple<String, String, String>, MutableList<Map<String, Any>>>()
    private val labels = HashMap<Long, String>()
    private var counter = 0L
    private val feed = graphix.feed(measurements, feedPort)
    private var last: Triple<Long, String, Long>? = null
    private var indexTime = 0L
    override fun getIndexTime(): Long = indexTime

    init {
        graphix.exec(
            """
            DROP DATAVERSE $dataverse IF EXISTS;
            CREATE DATAVERSE $dataverse;
            USE $dataverse;
            CREATE TYPE EntityType AS OPEN { id: bigint };
            CREATE TYPE $EVENT AS OPEN { tsId: bigint, label: string, `timestamp`: bigint };
            CREATE DATASET `$measurements` ($EVENT) PRIMARY KEY tsId, label, `timestamp`;
            """
        )
    }

    private fun dataset(edge: Triple<String, String, String>) = "${edge.first}_${edge.second}_${edge.third}"

    override fun node(label: String, props: Map<String, Any?>, from: Long?, to: Long?): Long {
        val id = ++counter
        val all = mapOf("id" to id) + props + (if (from != null) mapOf("valid_from" to from, "valid_to" to to) else emptyMap<String, Long?>())
        vertices.getOrPut(label) { mutableListOf() } += all.entries.filter { it.value != null }.associate { it.key to it.value!! }
        labels[id] = label
        return id
    }

    override fun edge(label: String, from: Long, to: Long, validFrom: Long, validTo: Long) {
        edges.getOrPut(Triple(labels.getValue(from), label, labels.getValue(to))) { mutableListOf() } += mapOf("id" to ++counter, "src" to from, "dst" to to)
    }

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        val measurement = mapOf("id" to ++counter, "tsId" to tsId, "label" to row.label, "timestamp" to row.timestamp, "value" to row.value)
        feed.writeToFeed(mapper.writeValueAsString(measurement), flush = false)
        last = Triple(tsId, row.label, row.timestamp)
        if (isLast) feed.flush()
    }

    private fun insert(dataset: String, rows: List<Map<String, Any>>) =
        rows.chunked(insertBatchSize).forEach { batch ->
            graphix.exec("INSERT INTO `$dataset` (FROM \$rows AS r SELECT VALUE r);", mapOf("\$rows" to mapper.writeValueAsString(batch)))
        }

    override fun loadData() {
        addGs() // collects the nodes and edges
        // Schema (not timed, as the clean-up of the other loaders)
        graphix.exec("USE $dataverse; " + (vertices.keys + edges.keys.map(::dataset)).joinToString(" ") { "CREATE DATASET `$it` (EntityType) PRIMARY KEY id;" })
        gsTime += getTime {
            vertices.forEach { (label, rows) -> insert(label, rows) }
            edges.forEach { (edge, rows) -> insert(dataset(edge), rows) }
            val definitions = vertices.keys.map { "VERTEX (:`$it`) PRIMARY KEY (id) AS `$it`" } +
                "VERTEX (:`Measurement`) PRIMARY KEY (id) AS `$measurements`" +
                edges.keys.map { (s, l, t) -> "EDGE (:`$s`)-[:`$l`]->(:`$t`) SOURCE KEY (src) DESTINATION KEY (dst) AS (FROM `${dataset(Triple(s, l, t))}` X SELECT X.src AS src, X.dst AS dst)" } +
                "EDGE (:`TimeSeries`)-[:`hasMeasurement`]->(:`Measurement`) SOURCE KEY (src) DESTINATION KEY (dst) AS (FROM `$measurements` X SELECT X.tsId AS src, X.id AS dst)"
            graphix.exec("USE $dataverse; CREATE GRAPH Pems AS ${definitions.joinToString(",\n")};")
        }
        addTs() // sends the measurements to the feed
        last?.let { (tsId, label, timestamp) ->
            tsTime += getTime { feed.awaitDataFeedRead() }
            indexTime = getTime { feed.closeDataFeedConnection(closeRemote = true) }
            val start = System.currentTimeMillis()
            while (true) {
                val startRead = System.currentTimeMillis()
                if (isStored(tsId, label, timestamp)) { tsTime += startRead - start; break }
                Thread.sleep(1)
            }
        }
        close()
    }

    private fun isStored(tsId: Long, label: String, timestamp: Long): Boolean = try {
        JSONObject(graphix.exec(
            "USE $dataverse; SELECT VALUE 1 FROM `$measurements` WHERE tsId = $tsId AND label = \$label AND `timestamp` = $timestamp;",
            mapOf("\$label" to mapper.writeValueAsString(label))
        )).getJSONArray("results").length() > 0
    } catch (_: Exception) {
        false
    }

    override fun close() {}
}
