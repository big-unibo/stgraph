package it.unibo.tests.smartbench.loaders

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import it.unibo.graph.asterixdb.AsterixDBTS
import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.asterixdb.dateToTimestamp
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.interfaces.TS
import it.unibo.graph.utils.*
import it.unibo.stats.Loader
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.logging.Logger

data class TSRecord(
    val label: String,
    val timestamp: Long,
    val location: String,
    val value: Any
)

class SmartBenchDataLoader(size: String, val threads: Int, host: String = "localhost", controllerIPs: List<String> = listOf("localhost")): Loader {
    val projectRoot = Paths.get("").toAbsolutePath().normalize()
    val path = "$projectRoot/datasets"
    val dataset = "smartbench"
    val dataPath: List<String> = listOf(
        "$path/original/$dataset/$size/group.json",
        "$path/original/$dataset/$size/user.json",
        "$path/original/$dataset/$size/platformType.json",
        "$path/original/$dataset/$size/sensorType.json",
        "$path/original/$dataset/$size/platform.json",
        "$path/original/$dataset/$size/infrastructureType.json",
        "$path/original/$dataset/$size/infrastructure.json",
        "$path/original/$dataset/$size/sensor.json",
        "$path/original/$dataset/$size/virtualSensorType.json",
        "$path/original/$dataset/$size/virtualSensor.json",
        "$path/original/$dataset/$size/semanticObservationType.json",
    )
    val graphPath = "datasets/dump/$dataset/$size/"

    val logger: Logger = Logger.getLogger(this.javaClass.name)
    val graph = MemoryGraphACID(path = graphPath)
    val tsm = AsterixDBTSM.createDefault(graph, dataverse = "${dataset}_$size", host = host, controllerIps = controllerIPs, maxConnections = if (threads == 1) 1 else { threads * 10 })

    init {
        val folder = Paths.get(graphPath)
        if (!Files.exists(folder)) {
            Files.createDirectories(folder)
        }
        graph.tsm = tsm
        graph.clear()
        tsm.clear()
    }

    private val mapper: ObjectMapper = jacksonObjectMapper()
    private var gsTime: Long = 0
    private var tsTime: Long = 0
    private var gsCard: Long = 0
    private var tsCard: Long = 0
    private var indexTime: Long = 0

    // JSON-id -> graph node-id
    private val graphIdList: MutableMap<String, Long> = mutableMapOf()
    // Edges failed to create because some entity did not exist
    private val leftoverEdgesList: MutableList<Triple<String, Long, String>> = mutableListOf()
    // Label caching
    private val edgeLabelCache: MutableMap<String, String> = mutableMapOf()

    private fun hasLabel(key: String): String = edgeLabelCache.getOrPut(key) {
        "has${key.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}"
    }

    override fun loadData() {
        val tsList: MutableMap<TS, String> = mutableMapOf()
        dataPath.forEach { file ->
            logger.info { "Loading $file..." }
            val path = Paths.get(file).toAbsolutePath().normalize()
            require(Files.exists(path)) { "File not found: $file" }
            for (obj in loadJsonObjects(path.toUri(), mapper)) {
                val typeVal = (obj[TYPE] as? String) ?: error("Missing \"${TYPE}\" in JSON object")
                val entityId = obj[ID] as? String ?: error("Missing \"${ID}\" in JSON object")
                val nodeLabel = typeVal.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                gsTime += getTime {
                    if (!graphIdList.contains(entityId)) {
                        // Add static node
                        val node = graph.addNode(nodeLabel)
                        val nodeId = node.id
                        graphIdList[entityId] = nodeId
                        gsCard++
                        // Parse its properties
                        for ((key, value) in obj) {
                            when (value) {
                                is Map<*, *> -> {
                                    val destId = value[ID] as? String
                                    if (destId != null) {
                                        val edgeLabel = hasLabel(key)
                                        val targetId = graphIdList[destId]
                                        if (targetId != null) {
                                            graph.addEdge(edgeLabel, nodeId, targetId)
                                        } else {
                                            leftoverEdgesList += Triple(edgeLabel, nodeId, destId)
                                        }
                                    }
                                }

                                is List<*> -> {
                                    for (subVal in value) {
                                        processProperty(subVal!!, key, nodeId)
                                    }
                                }

                                else -> processProperty(value, key, nodeId)
                            }
                        }

                        // If it's a sensor, check if it has a TS attached
                        if (typeVal == Sensor || typeVal == VirtualSensor) {
                            val sensorId = obj["id"] as String
                            val labelString = if (typeVal == Sensor) "Temperature" else "Presence"
                            val sensorTsFilePath = path.parent
                                ?.resolve("timeseries")
                                ?.resolve("$sensorId.json")
                                ?.toAbsolutePath()
                                ?.normalize()
                            if (sensorTsFilePath != null && Files.exists(sensorTsFilePath)) {
                                val newNode = graph.addNode(labelString, isTs = true)
                                val newTs: TS = tsm.addTS(newNode.id)
                                gsCard++

                                graph.addEdge(hasLabel(labelString), nodeId, newNode.id)
                                tsList[newTs] = sensorTsFilePath.toString()
                            }
                        }
                    }
                }
            }
        }
        gsTime += getTime {
            for ((label, source, destId) in leftoverEdgesList) {
                val target = graphIdList[destId] ?: continue
                graph.addEdge(label, source, target)
            }
        }
        val list = tsList.toList()
        val chunkSize = list.size / threads
        val results = LongArray(threads)
        val workers = mutableListOf<Thread>()
        for (i in 0 until threads) {
            val start = i * chunkSize
            val end = if (i == threads - 1) list.size else (i + 1) * chunkSize
            val subList = list.subList(start, end)
            val t = Thread {
                val time = subList
                    .mapIndexed { index, pair ->
                        loadTS(pair.first as AsterixDBTS, pair.second, index == subList.size - 1)
                    }.sum()
                results[i] = time
            }
            workers += t
            t.start()
        }
        workers.forEach { it.join() }
        tsTime = results.maxOrNull() ?: -1L
        indexTime = getTime {
            tsm.close()
            tsm.connection.createSpatialIndex()
        }
    }

    override fun close() {
        graph.close()
    }

    private fun processProperty(property: Any, key: String, nodeId: Long) {
        if (property is Map<*, *> && property["id"] is String) {
            val destId = property["id"] as String
            val edgeLabel = hasLabel(key)
            val targetId = graphIdList[destId]
            if (targetId != null) {
                graph.addEdge(edgeLabel, nodeId, targetId)
            } else {
                leftoverEdgesList += Triple(edgeLabel, nodeId, destId)
            }
        } else {
            graph.addProperty(nodeId, key, property, propTypeFromValue(property))
        }
    }

    fun loadTS(ts: AsterixDBTS, path: String, isLast: Boolean): Long {
        val projectRoot = Paths.get("").toAbsolutePath().normalize()
        val filePath = projectRoot.resolve(path).normalize()
        if (!Files.exists(filePath)) {
            throw UnsupportedOperationException("File not found: $filePath")
        }
        val records = mutableListOf<TSRecord>()
        Files.newBufferedReader(filePath).useLines { lines ->
            lines.forEach { line ->
                val json: JsonNode = mapper.readTree(line)
                val type = json.get(TYPE).textValue()
                val label = if (type == "Temperature") "temperature" else "presence"
                records += TSRecord(
                    type,
                    dateToTimestamp(json.get("timestamp").textValue()),
                    json.get(LOCATION).textValue(),
                    json.get("payload").get(label).longValue()
                )
            }
        }

        var last = -1L
        val time = getTime {
            records.forEach { event ->
                last = event.timestamp
                ts.add(label = event.label, timestamp = last, location = event.location, value = event.value as Long, isUpdate = false, flush = false)
            }
        }
        tsCard += records.size
        ts.connection.flush()
        if (isLast) {
            val start = System.currentTimeMillis()
            var fail = 0
            while (true) {
                try {
                    val startQuery = System.currentTimeMillis()
                    ts.get(last)
                    val end = System.currentTimeMillis()
                    val deltaQuery = end - startQuery
                    // logger.info { "Load time: $time. Waited for ${end - start - deltaQuery} ms after $fail failures" }
                    return time + end - start - deltaQuery // do not count the time of the last query
                } catch (_: Exception) {
                    fail++
                    Thread.sleep(1)
                }
            }
        } else {
            return time
        }
    }

    private fun loadJsonObjects(file: URI, mapper: ObjectMapper): Sequence<Map<String, Any>> {
        val factory = JsonFactory()
        val parser = factory.createParser(File(file))
        parser.codec = mapper

        if (parser.nextToken() != JsonToken.START_ARRAY) {
            throw IllegalStateException("Expected JSON array")
        }

        return sequence {
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                @Suppress("UNCHECKED_CAST")
                val obj: Map<String, Any> = parser.readValueAs(Map::class.java) as Map<String, Any>
                yield(obj)
            }
        }
    }

    override fun getGSTime(): Long = gsTime
    override fun getTSTime(): Long = tsTime
    override fun getIndexTime(): Long = indexTime
    override fun getGSCardinality(): Long = gsCard
    override fun getTSCardinality(): Long = tsCard
}