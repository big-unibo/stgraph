package it.unibo.tests.pems.loaders

import it.unibo.stats.PostgresConfig
import it.unibo.tests.pems.PemsWindows
import it.unibo.tests.smartbench.loaders.SmartBenchAeonG.Companion.literal
import it.unibo.tests.smartbench.loaders.TSRecord
import java.io.File
import java.util.PriorityQueue

/**
 * Writes PeMS as the Cypher dump that AeonG loads (datasets/dump/aeong/pems/<size>/<size>.cypher, one statement per
 * line, each one a transaction of AeonG), with the graph of the other loaders and the temporal model of AeonG
 * (transaction time), with the same information as the other systems (for the comparison of the storage): the station
 * versions are not nodes, the attributes of a version are set on the station when the version starts (and removed when
 * it ends), the segment of a station is its inSegment edge, created when the version starts and deleted when it ends
 * (the edges of the incidents as well); a node per measure of each time series (a series_id), created by its first
 * event and updated (SET of timestamp and value) by each next one. The events and the changes of the graph in the order
 * of their time, as AeonG would receive them, so that the transaction time follows the time of the events; the changes
 * after the last event at the end. Every node has a uid, indexed, to match it (as the id of the nodes of Neo4j).
 * <size>.tt: for each time of PemsWindows, the first statement of an event at or after it, and last=<n>, the statement
 * of the last event: the ingestion of AeonG (ingest_boundaries.py) records their transaction times in <size>.aeong.
 * Writing the dump is not timed: AeonG times its load.
 */
class PemsAeonG(size: String, postgres: PostgresConfig = PostgresConfig()) : AbstractPemsLoader(size, postgres) {
    private val dump = dumpFile(size).apply { parentFile.mkdirs() }
    private val writer = dump.printWriter()
    private var statements = 0L
    private var nodes = 0L
    private val labels = HashMap<Long, String>() // the label of each node (uid)
    private val indexed = HashSet<String>()
    /** The changes of the edges with a validity: (time, 0 delete / 1 create, statement) */
    private val changes = mutableListOf<Triple<Long, Int, String>>()

    private fun statement(s: String) {
        writer.println(s)
        statements++
    }

    private fun index(label: String, key: String) {
        if (indexed.add("$label.$key")) statement("CREATE INDEX ON :`$label`($key);")
    }

    private fun node(alias: String, id: Long) = "($alias:`${labels.getValue(id)}` {uid: $id})"

    /** A node; a validity (the incidents) as the properties valid_from and valid_to, as the other systems */
    override fun node(label: String, props: Map<String, Any?>, from: Long?, to: Long?): Long {
        val id = ++nodes
        labels[id] = label
        index(label, "uid")
        val validity = if (from != null) mapOf("valid_from" to from, "valid_to" to to) else emptyMap()
        statement("CREATE (:`$label` ${literal(mapOf("uid" to id) + props + validity)});")
        return id
    }

    /** An edge: created at [validFrom] and deleted at [validTo] (see close), or at once if always valid */
    override fun edge(label: String, from: Long, to: Long, validFrom: Long, validTo: Long) {
        val create = "MATCH ${node("a", from)}, ${node("b", to)} CREATE (a)-[:`$label`]->(b);"
        if (validFrom == Long.MIN_VALUE) statement(create) else changes += Triple(validFrom, 1, create)
        if (validTo != Long.MAX_VALUE) changes += Triple(validTo, 0, "MATCH ${node("a", from)}-[e:`$label`]->${node("b", to)} DELETE e;")
    }

    /**
     * The station, with the attributes of each version while it is valid (those of a version always valid since the
     * start at its creation) and in the segment of each version (an inSegment edge valid as the version)
     */
    override fun addStation(station: PemsStation, versions: List<Pair<PemsStationVersion, Long?>>): Long {
        val initial = versions.firstOrNull { it.first.from == Long.MIN_VALUE }?.first?.attributes().orEmpty()
        val id = node("Station", linkedMapOf<String, Any?>("station_id" to station.stationId, "district_id" to station.districtId) + initial)
        fun set(attributes: Map<String, Any?>) = "MATCH ${node("s", id)} SET " + attributes.entries.joinToString(", ") { (k, v) -> "s.$k = ${literal(v)}" } + ";"
        versions.forEach { (v, segment) ->
            if (v.from != Long.MIN_VALUE) changes += Triple(v.from, 1, set(v.attributes()))
            if (v.to != Long.MAX_VALUE) changes += Triple(v.to, 0, set(v.attributes().mapValues { null }))
            if (segment != null) edge("inSegment", id, segment, v.from, v.to)
        }
        return id
    }

    // The events, kept until close (one primitive array per field), in runs of the same time series sorted by time
    private var count = 0
    private var tsIds = LongArray(1024); private var labelIds = IntArray(1024); private var times = LongArray(1024); private var values = LongArray(1024)
    private val measureLabels = mutableListOf<String>()

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        if (count == times.size) {
            val c = count * 2
            tsIds = tsIds.copyOf(c); labelIds = labelIds.copyOf(c); times = times.copyOf(c); values = values.copyOf(c)
        }
        if (row.label !in measureLabels) measureLabels += row.label
        tsIds[count] = tsId; labelIds[count] = measureLabels.indexOf(row.label)
        times[count] = row.timestamp; values[count] = (row.value as Number).toLong()
        count++
    }

    /** The events of all the time series and the changes of the edges in the order of their time, then the manifest */
    override fun close() {
        val bounds = sortedMapOf<Long, Long>() // a time of PemsWindows -> the first statement of an event at or after it
        (PemsWindows.windows.values.flatMap { listOf(it.first, it.second) } + PemsWindows.T).forEach { bounds[it] = -1 }
        val pending = changes.sortedWith(compareBy({ it.first }, { it.second })).iterator() // deletions first at the same time
        var change = if (pending.hasNext()) pending.next() else null
        // The runs of the time series (consecutive events of the same one), merged by time
        val runs = PriorityQueue<IntArray>(compareBy<IntArray> { times[it[0]] }) // [next event, end of the run]
        var start = 0
        for (i in 1..count) if (i == count || tsIds[i] != tsIds[i - 1]) { if (i > start) runs += intArrayOf(start, i); start = i }
        val created = HashSet<String>()
        var last = 0L // the statement of the last event
        while (runs.isNotEmpty()) {
            val run = runs.poll()
            val i = run[0]
            while (change != null && change.first <= times[i]) { statement(change.third); change = if (pending.hasNext()) pending.next() else null }
            bounds.headMap(times[i] + 1).filterValues { it < 0 }.keys.forEach { bounds[it] = statements + 1 }
            val label = "`${measureLabels[labelIds[i]]}`"
            val series = "'${tsIds[i]}_${measureLabels[labelIds[i]]}'"
            if (created.add(series)) {
                index(measureLabels[labelIds[i]], "series_id")
                statement("MATCH ${node("t", tsIds[i])} CREATE (t)-[:hasMeasurement]->(:$label {series_id: $series, timestamp: ${times[i]}, value: ${values[i]}});")
            } else {
                statement("MATCH (m:$label {series_id: $series}) SET m.timestamp = ${times[i]}, m.value = ${values[i]};")
            }
            last = statements
            if (i + 1 < run[1]) runs += intArrayOf(i + 1, run[1])
        }
        while (change != null) { statement(change.third); change = if (pending.hasNext()) pending.next() else null } // after the last event
        writer.close()
        File(dump.path.removeSuffix(".cypher") + ".tt").writeText(
            bounds.entries.joinToString("") { (bound, statement) -> "$bound=${if (statement < 0) last + 1 else statement}\n" } + "last=$last\n"
        )
        File(dump.path + ".done").writeText(DUMP_VERSION)
    }

    companion object {
        /** The version of the dumps: one written by an older PemsAeonG (another model) is written again */
        const val DUMP_VERSION = "edges+attributes"

        fun dumpFile(size: String) = File("datasets/dump/aeong/pems/$size/$size.cypher")

        /** Whether the dump of [size] is complete and of this version */
        fun dumpReady(size: String) = File(dumpFile(size).path + ".done").let { it.isFile && it.readText() == DUMP_VERSION }
    }
}
