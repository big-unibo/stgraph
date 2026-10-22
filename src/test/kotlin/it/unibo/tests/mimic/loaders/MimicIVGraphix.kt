package it.unibo.tests.mimic.loaders

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import it.unibo.graph.utils.EVENT
import it.unibo.graph.utils.getTime
import it.unibo.tests.graphix.GraphixClient
import it.unibo.tests.smartbench.loaders.TSRecord

/**
 * Loads MIMIC-IV into Graphix (AsterixDB + graph views), with the graph of the other loaders:
 * (:Person)-[:hasParameters]->(:TimeSeries)-[:hasMeasurement]->(:Measurement).
 * - `Persons` and `TimeSeries` hold the master data, inserted in batches (graph-store time);
 * - `Measurements` holds the events, ingested through the same socket data feed used by STGraph (time-series time).
 */
class MimicIVGraphix(
    size: String,
    host: String = "localhost",
    port: Int = 29002,
    feedPort: Int = 11001,
    // Rows per INSERT statement: one INSERT per row would mostly measure the fixed cost of an AsterixDB job
    private val insertBatchSize: Int = 500,
    // End of the event ingestion: the count must not change for this long before the feed is stopped
    private val stableWindowMs: Long = 10_000,
    private val completionTimeoutMs: Long = 60 * 60 * 1000
) : AbstractMimicIVLoader(mimicLimit(size), threads = 1) {
    private val dataverse = "mimic_graphix_${if (mimicLimit(size) == Long.MAX_VALUE) "full" else size}"
    private val graphix = GraphixClient(host, port, dataverse)
    private val mapper = jacksonObjectMapper()
    private val measurements = "Measurements"
    private val persons = mutableListOf<Map<String, Any>>()
    private val timeSeries = mutableListOf<Map<String, Any>>()
    private val tsMap = mutableMapOf<Long, Long>()
    private var personCounter: Long = 0
    private var tsCounter: Long = 0
    private var measurementCounter: Long = 0
    private var pId = 0
    private val feed = graphix.feed(measurements, feedPort)

    init {
        // Schema (not timed, as the graph/TS clean-up of the STGraph loader)
        graphix.exec(
            """
            DROP DATAVERSE $dataverse IF EXISTS;
            CREATE DATAVERSE $dataverse;
            USE $dataverse;
            CREATE TYPE EntityType AS OPEN { id: bigint };
            CREATE TYPE $EVENT AS OPEN { id: bigint };
            CREATE DATASET `Persons` (EntityType) PRIMARY KEY id;
            CREATE DATASET `TimeSeries` (EntityType) PRIMARY KEY id;
            CREATE DATASET `$measurements` ($EVENT) PRIMARY KEY id;
            """
        )
    }

    override fun addPerson(subjectId: Int, c: Int): Long {
        val personId = ++personCounter
        persons += mapOf("id" to personId, "subject_id" to subjectId, "rnd" to subjectId % 10, "pId" to pId++, "c" to c)
        return personId
    }

    override fun addTimeseries(abbreviation: String, unitname: String?, category: String, label: String, itemid: Int, person: Long): Long {
        if (!tsMap.contains(person)) {
            val tsId = ++tsCounter
            timeSeries += mapOf("id" to tsId, "person" to person)
            tsMap[person] = tsId
        }
        return person
    }

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        val tmp = tsCache[row.label.toInt()]!!
        val measurement = linkedMapOf<String, Any>(
            "id" to ++measurementCounter,
            "tsId" to tsMap[tsId]!!,
            "label" to tmp.abbreviation,
            "timestamp" to row.timestamp,
            "value" to row.value,
            "category" to tmp.category,
            "abbreviation" to tmp.abbreviation,
            "itemid" to tmp.itemid
        )
        if (tmp.unitname != null) measurement["unitname"] = tmp.unitname
        feed.writeToFeed(mapper.writeValueAsString(measurement), flush = false)
        if (isLast) feed.flush()
    }

    private fun insert(dataset: String, rows: List<Map<String, Any>>) =
        // The rows are a request parameter, not part of the query text (see SmartBenchGraphix)
        rows.chunked(insertBatchSize).forEach { batch ->
            graphix.exec("INSERT INTO `$dataset` (FROM \$rows AS r SELECT VALUE r);", mapOf("\$rows" to mapper.writeValueAsString(batch)))
        }

    private fun edge(source: String, label: String, target: String, sourceKey: String, dataset: String) =
        "EDGE (:`$source`)-[:`$label`]->(:`$target`) SOURCE KEY (src) DESTINATION KEY (dst) AS (FROM `$dataset` X SELECT X.`$sourceKey` AS src, X.id AS dst)"

    override fun loadData() {
        addGs() // collects persons and time series
        gsTime += getTime {
            insert("Persons", persons)
            insert("TimeSeries", timeSeries)
            graphix.exec(
                """
                USE $dataverse; CREATE GRAPH Mimic AS
                  VERTEX (:`Person`) PRIMARY KEY (id) AS `Persons`,
                  VERTEX (:`TimeSeries`) PRIMARY KEY (id) AS `TimeSeries`,
                  VERTEX (:`Measurement`) PRIMARY KEY (id) AS `$measurements`,
                  ${edge("Person", "hasParameters", "TimeSeries", "person", "TimeSeries")},
                  ${edge("TimeSeries", "hasMeasurement", "Measurement", "tsId", measurements)};
                """
            )
        }
        addTs() // sends the measurements to the feed
        if (tsCard > 0) tsTime += graphix.completeFeeds(listOf(feed), measurements, tsCard, stableWindowMs, completionTimeoutMs)
        close()
    }

    override fun close() {}
}
