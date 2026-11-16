package it.unibo.tests.pems.loaders

import it.unibo.graph.asterixdb.AsterixDBTS
import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.interfaces.PropType
import it.unibo.graph.utils.getTime
import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord

/**
 * Loads PeMS into STGraph, as MimicIVSTGraph. A station is one node: its attributes are properties valid in the
 * interval of the versions with the same value, its segment an inSegment edge valid in the same way; an incident is a
 * node valid in [time_from, time_to)
 */
class PemsSTGraph(
    size: String,
    threads: Int = 1,
    host: String = "localhost",
    controllerIPs: List<String> = listOf("localhost"),
    postgres: PostgresConfig = PostgresConfig()
) : AbstractPemsLoader(size, postgres) {
    val g = MemoryGraphACID(path = "datasets/dump/pems/$size")
    val t = AsterixDBTSM.createDefault(g, "pems_$size", host = host, controllerIps = controllerIPs, maxConnections = if (threads == 1) 1 else threads * 10, multiTs = true)
    private val tsMap = mutableMapOf<Long, AsterixDBTS>()

    init {
        g.tsm = t
        g.clear()
        t.clear()
    }

    private fun type(v: Any): PropType = when (v) {
        is Int -> PropType.INT
        is Long -> PropType.LONG
        is Double -> PropType.DOUBLE
        else -> PropType.STRING
    }

    override fun node(label: String, props: Map<String, Any?>, from: Long?, to: Long?): Long {
        val f = from ?: Long.MIN_VALUE
        val until = to ?: Long.MAX_VALUE
        val n = g.addNode(label, from = f, to = until)
        props.forEach { (k, v) -> if (v != null) g.addProperty(n.id, k, v, type(v), from = f, to = until) }
        return n.id
    }

    override fun edge(label: String, from: Long, to: Long, validFrom: Long, validTo: Long) {
        g.addEdge(label, fromNode = from, toNode = to, from = validFrom, to = validTo)
    }

    override fun addStation(station: PemsStation, versions: List<Pair<PemsStationVersion, Long?>>): Long {
        val id = node("Station", linkedMapOf("station_id" to station.stationId, "district_id" to station.districtId))
        // Calls [add] once per run of consecutive (adjacent) versions with the same non-null value
        fun runs(value: (Int) -> Any?, add: (Any, Long, Long) -> Unit) {
            var start = 0
            for (i in versions.indices) {
                if (i + 1 < versions.size && versions[i + 1].first.from == versions[i].first.to && value(i + 1) == value(i)) continue
                value(i)?.let { add(it, versions[start].first.from, versions[i].first.to) }
                start = i + 1
            }
        }
        versions.firstOrNull()?.first?.attributes()?.keys?.forEach { key ->
            runs({ versions[it].first.attributes()[key] }) { v, from, to -> g.addProperty(id, key, v, type(v), from = from, to = to) }
        }
        runs({ versions[it].second }) { segment, from, to -> edge("inSegment", id, segment as Long, from, to) }
        return id
    }

    override fun addTimeseries(station: Long, lane: Int): Long {
        val n = g.addNode("TimeSeries", isTs = true)
        tsMap[n.id] = t.addTS(n.id) as AsterixDBTS
        g.addProperty(n.id, "lane", lane, PropType.INT)
        g.addEdge("hasTimeSeries", fromNode = station, toNode = n.id)
        return n.id
    }

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        val ts = tsMap.getValue(tsId)
        ts.add(label = row.label, timestamp = row.timestamp, value = row.value as Long, flush = false)
        if (isLast) ts.connection.flush()
    }

    private var indexTime = 0L
    override fun getIndexTime(): Long = indexTime

    /** As MimicIVSTGraph: the close of the feeds timed as index time */
    override fun loadData() {
        addGs()
        addTs()
        indexTime = getTime { t.close() }
        close()
    }

    override fun awaitLast(tsId: Long, row: TSRecord) {
        while (runCatching { tsMap.getValue(tsId).get(row.timestamp) }.isFailure) Thread.sleep(1)
    }

    override fun close() {
        g.flushToDisk()
        g.close()
    }
}
