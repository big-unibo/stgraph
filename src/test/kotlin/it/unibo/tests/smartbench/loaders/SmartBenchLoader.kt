package it.unibo.tests.smartbench.loaders

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import it.unibo.graph.asterixdb.dateToTimestamp
import it.unibo.graph.utils.LOCATION
import it.unibo.graph.utils.Sensor
import it.unibo.graph.utils.TYPE
import it.unibo.graph.utils.VirtualSensor
import it.unibo.graph.utils.getTime
import it.unibo.stats.Loader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.logging.Logger

/** A time series of SmartBench: the events of [file], produced by the entity [sensorId] labelled [sensorLabel] */
data class SmartBenchTimeSeries(val sensorId: String, val sensorLabel: String, val eventLabel: String, val file: Path)

/** An event of a time series, with the fields used by the loaders and the [json] object as read from the file */
data class SmartBenchEvent(
    val id: String,
    val label: String,
    val timestamp: Long,
    val location: String,
    val value: Long,
    val json: Map<String, Any>
)

/**
 * Common skeleton of the SmartBench loaders: the graph is loaded first, then the time series, then the indexes.
 * Each phase accumulates its own time (graph-store, time-series and index time) and cardinality.
 */
abstract class AbstractSmartBenchLoader(val size: String, val threads: Int = 1) : Loader {
    protected val logger: Logger = Logger.getLogger(this.javaClass.name)
    protected val projectRoot: Path = Paths.get("").toAbsolutePath().normalize()
    protected var gsTime: Long = 0
    protected var tsTime: Long = 0
    protected var indexesTime: Long = 0
    protected var gsCard: Long = 0
    protected var tsCard: Long = 0

    /** Loads the entities and their relationships, accumulating [gsTime] and [gsCard] */
    protected abstract fun loadGraph()

    /** Loads the events of the time series, accumulating [tsTime] and [tsCard] */
    protected abstract fun loadEvents()

    /** Creates the indexes once the data are loaded (timed as index time) */
    protected open fun createIndexes() {}

    override fun loadData() {
        loadGraph()
        loadEvents()
        indexesTime += getTime { createIndexes() }
    }

    override fun getGSTime(): Long = gsTime
    override fun getTSTime(): Long = tsTime
    override fun getIndexTime(): Long = indexesTime
    override fun getGSCardinality(): Long = gsCard
    override fun getTSCardinality(): Long = tsCard
}

/**
 * A loader of the original SmartBench dataset (datasets/original/smartbench/<size>), as [it.unibo.tests.mimic.loaders.AbstractMimicIVLoader]
 * for MIMIC: it reads the data (not timed) and calls the hooks of the system (timed).
 * - Graph: the entities of [collections], in this order, through [addEntity]; right after a sensor (or virtual sensor)
 *   with a time-series file, [addTimeSeries]; at the end, [endEntities] (e.g., batches or edges left pending).
 * - Time series: split among [threads] workers; each worker reads the events of one series at a time and passes
 *   them to [addEvents]. The time-series time is the one of the slowest worker plus the one returned by [endEvents]
 *   (e.g., waiting until the events are stored).
 */
abstract class SmartBenchJsonLoader(size: String, threads: Int = 1) : AbstractSmartBenchLoader(size, threads) {
    protected val mapper: ObjectMapper = jacksonObjectMapper()
    protected val dataPath: Path = projectRoot.resolve("datasets/original/smartbench/$size")
    private val timeSeries = mutableListOf<SmartBenchTimeSeries>()

    /** Adds an [entity] (a JSON object of one of the [collections]), labelled with its capitalized type */
    abstract fun addEntity(label: String, entity: Map<String, Any>)

    /** Adds the time series [ts], right after its sensor */
    abstract fun addTimeSeries(ts: SmartBenchTimeSeries)

    /** Called once every entity has been added (timed as graph-store time) */
    open fun endEntities() {}

    /** Adds the [events] of [ts], from the worker [worker] (0 until [threads]) */
    abstract fun addEvents(worker: Int, ts: SmartBenchTimeSeries, events: List<SmartBenchEvent>)

    /**
     * Called once every worker has added its events: returns the time to add to the time-series time (e.g., waiting
     * until the events are stored), measured by the loader since part of the wait may not be ingestion time
     */
    open fun endEvents(): Long = 0

    override fun loadGraph() {
        for (collection in collections) {
            val file = dataPath.resolve("$collection.json")
            require(Files.exists(file)) { "File not found: $file" }
            logger.info { "Loading $file..." }
            for (entity in readEntities(file)) {
                val label = label(entity[TYPE] as? String ?: error("Missing \"$TYPE\" in $file"))
                val ts = if (label == Sensor || label == VirtualSensor) findTimeSeries(entity, label) else null
                gsTime += getTime {
                    addEntity(label, entity)
                    if (ts != null) addTimeSeries(ts)
                }
                gsCard++
                if (ts != null) {
                    timeSeries += ts
                    gsCard++
                }
            }
        }
        gsTime += getTime { endEntities() }
    }

    override fun loadEvents() {
        // Contiguous chunks of series, the last worker takes the remainder
        val chunkSize = timeSeries.size / threads
        val times = LongArray(threads)
        val counts = LongArray(threads)
        val workers = (0 until threads).map { i ->
            val start = i * chunkSize
            val end = if (i == threads - 1) timeSeries.size else (i + 1) * chunkSize
            Thread {
                for (ts in timeSeries.subList(start, end)) {
                    val events = readEvents(ts)
                    times[i] += getTime { addEvents(i, ts, events) }
                    counts[i] += events.size.toLong()
                }
            }.also { it.start() }
        }
        workers.forEach { it.join() }
        tsTime += (times.maxOrNull() ?: 0) + endEvents()
        tsCard += counts.sum()
    }

    /** The time series of a sensor, if it has a file: its event label is the type of the first event */
    private fun findTimeSeries(entity: Map<String, Any>, sensorLabel: String): SmartBenchTimeSeries? {
        val sensorId = entity["id"] as? String ?: error("Missing \"id\" in $entity")
        val file = dataPath.resolve("timeseries/$sensorId.json")
        if (!Files.exists(file)) return null
        val firstType = Files.newBufferedReader(file).use { it.readLine() }
            ?.let { mapper.readTree(it).get(TYPE)?.textValue() }
        val eventLabel = firstType ?: if (sensorLabel == Sensor) "Temperature" else "Presence"
        return SmartBenchTimeSeries(sensorId, sensorLabel, eventLabel, file)
    }

    /** The entities of a collection file (a JSON array), parsed one at a time */
    private fun readEntities(file: Path): Sequence<Map<String, Any>> = sequence {
        JsonFactory().createParser(file.toFile()).use { parser ->
            parser.codec = mapper
            check(parser.nextToken() == JsonToken.START_ARRAY) { "Expected a JSON array in $file" }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                @Suppress("UNCHECKED_CAST")
                yield(parser.readValueAs(Map::class.java) as Map<String, Any>)
            }
        }
    }

    /** The events of a time-series file (one JSON object per line) */
    private fun readEvents(ts: SmartBenchTimeSeries): List<SmartBenchEvent> =
        Files.newBufferedReader(ts.file).useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val json: Map<String, Any> = mapper.readValue(line)
                val type = json[TYPE] as String
                val payload = json["payload"] as Map<*, *>
                SmartBenchEvent(
                    id = json["id"] as String,
                    label = type,
                    timestamp = dateToTimestamp(json["timestamp"] as String),
                    location = json[LOCATION] as String,
                    value = (payload[if (type == "Temperature") "temperature" else "presence"] as Number).toLong(),
                    json = json
                )
            }.toList()
        }

    companion object {
        /** The SmartBench collections, in loading order */
        val collections = listOf(
            "group", "user", "platformType", "sensorType", "platform", "infrastructureType",
            "infrastructure", "sensor", "virtualSensorType", "virtualSensor", "semanticObservationType"
        )

        fun label(type: String) = type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}
