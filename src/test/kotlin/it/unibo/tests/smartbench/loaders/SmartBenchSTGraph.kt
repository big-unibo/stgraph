package it.unibo.tests.smartbench.loaders

import it.unibo.graph.asterixdb.AsterixDBTS
import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.utils.getTime
import it.unibo.graph.utils.propTypeFromValue
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

/**
 * STGraph on the [SmartBenchJsonLoader] skeleton: the same graph and time series as [SmartBenchDataLoader], which
 * stays the reference loader of STGraph (and of the CI). This one exists to measure the effect of the shared
 * measurement boundaries. Differences in what is timed:
 * - the check of the time-series file of a sensor is not timed (it is graph-store time in [SmartBenchDataLoader]);
 * - the flush of each series is time-series time (not timed in [SmartBenchDataLoader]);
 * - the wait until the last event of each worker is readable happens once all the workers are done, and includes
 *   the last (successful) read, while [SmartBenchDataLoader] waits inside each worker and excludes it.
 */
class SmartBenchSTGraph(
    size: String,
    threads: Int = 1,
    host: String = "localhost",
    controllerIPs: List<String> = listOf("localhost")
) : SmartBenchJsonLoader(size, threads) {
    private val graphPath = "datasets/dump/smartbench/$size/"
    private val graph = MemoryGraphACID(path = graphPath)
    private val tsm = AsterixDBTSM.createDefault(graph, dataverse = "smartbench_$size", host = host, controllerIps = controllerIPs, maxConnections = if (threads == 1) 1 else { threads * 10 })

    // JSON id -> graph node id
    private val graphIdList: MutableMap<String, Long> = mutableMapOf()
    // Edges towards entities not loaded yet: (label, source node, JSON id of the target)
    private val leftoverEdgesList: MutableList<Triple<String, Long, String>> = mutableListOf()
    private val edgeLabelCache: MutableMap<String, String> = mutableMapOf()
    private val tsOf: MutableMap<SmartBenchTimeSeries, AsterixDBTS> = mutableMapOf()
    // Last series and last timestamp loaded by each worker
    private val lastOf = arrayOfNulls<Pair<AsterixDBTS, Long>>(threads)

    init {
        Files.createDirectories(Paths.get(graphPath))
        graph.tsm = tsm
        graph.clear()
        tsm.clear()
    }

    private fun hasLabel(key: String): String = edgeLabelCache.getOrPut(key) {
        "has${key.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}"
    }

    override fun addEntity(label: String, entity: Map<String, Any>) {
        val entityId = entity["id"] as? String ?: error("Missing \"id\" in JSON object")
        if (graphIdList.contains(entityId)) return
        val nodeId = graph.addNode(label).id
        graphIdList[entityId] = nodeId
        for ((key, value) in entity) {
            when (value) {
                is Map<*, *> -> (value["id"] as? String)?.let { addEdge(hasLabel(key), nodeId, it) }
                is List<*> -> value.forEach { processProperty(it!!, key, nodeId) }
                else -> processProperty(value, key, nodeId)
            }
        }
    }

    override fun addTimeSeries(ts: SmartBenchTimeSeries) {
        val node = graph.addNode(ts.eventLabel, isTs = true)
        tsOf[ts] = tsm.addTS(node.id) as AsterixDBTS
        graph.addEdge(hasLabel(ts.eventLabel), graphIdList.getValue(ts.sensorId), node.id)
    }

    override fun endEntities() {
        for ((label, source, destId) in leftoverEdgesList) {
            val target = graphIdList[destId] ?: continue
            graph.addEdge(label, source, target)
        }
    }

    override fun addEvents(worker: Int, ts: SmartBenchTimeSeries, events: List<SmartBenchEvent>) {
        val series = tsOf.getValue(ts)
        events.forEach { event ->
            series.add(label = event.label, timestamp = event.timestamp, location = event.location, value = event.value, isUpdate = false, flush = false)
        }
        series.connection.flush()
        if (events.isNotEmpty()) lastOf[worker] = series to events.last().timestamp
    }

    /** Waits until the last event sent by each worker is readable */
    override fun endEvents(): Long = getTime {
        for ((series, timestamp) in lastOf.filterNotNull()) {
            while (true) {
                try {
                    series.get(timestamp)
                    break
                } catch (_: Exception) {
                    Thread.sleep(1)
                }
            }
        }
    }

    override fun createIndexes() {
        tsm.close()
        tsm.connection.createSpatialIndex()
    }

    override fun close() {
        graph.close()
    }

    private fun addEdge(label: String, source: Long, destId: String) {
        val target = graphIdList[destId]
        if (target != null) graph.addEdge(label, source, target) else leftoverEdgesList += Triple(label, source, destId)
    }

    private fun processProperty(property: Any, key: String, nodeId: Long) {
        if (property is Map<*, *> && property["id"] is String) {
            addEdge(hasLabel(key), nodeId, property["id"] as String)
        } else {
            graph.addProperty(nodeId, key, property, propTypeFromValue(property))
        }
    }
}
