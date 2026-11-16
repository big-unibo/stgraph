package it.unibo.graph.interfaces

import it.unibo.graph.query.Aggregate
import it.unibo.graph.query.Filter
import it.unibo.graph.query.Operators
import it.unibo.graph.utils.FROM_TIMESTAMP
import it.unibo.graph.utils.TO_TIMESTAMP
import it.unibo.graph.utils.DUMMY_ID
import it.unibo.graph.utils.LOCATION
import it.unibo.graph.utils.NODE
import it.unibo.graph.utils.encodeBitwise
import org.locationtech.jts.io.WKTReader
import java.io.Serializable

/**
 * The WKT reader of the locations of the events, one per thread (a WKTReader is not thread-safe): created once, not at
 * every event of the ingestion
 */
private val wktReader: ThreadLocal<WKTReader> = ThreadLocal.withInitial { WKTReader() }

interface TS : Serializable {
    val g: Graph

    fun getTSId(): Long

    fun createNode(label: String, timestamp: Long, value: Long, properties: List<Triple<String, Any, PropType>> = emptyList()): N {
        val id = encodeBitwise(getTSId(), timestamp)
        return N(
            id,
            label,
            value = value,
            fromTimestamp = timestamp,
            toTimestamp = timestamp,
            g = g,
            properties = properties.map { (key, value, type) -> P(DUMMY_ID, id, NODE, key, value, type, fromTimestamp = timestamp, toTimestamp = timestamp, g = g) }.toMutableList()
        )
    }

    fun add(label: String, timestamp: Long, value: Long, location: String, isUpdate: Boolean = false, flush: Boolean = true, properties: List<Triple<String, Any, PropType>> = emptyList()): N {
        val n = createNode(label, timestamp, value, properties + Triple(LOCATION, wktReader.get().read(location), PropType.GEOMETRY))
        return add(n, isUpdate, flush)
    }

    fun add(label: String, timestamp: Long, value: Long, isUpdate: Boolean = false, flush: Boolean = true, properties: List<Triple<String, Any, PropType>> = emptyList()) = add(createNode(label, timestamp, value, properties), isUpdate, flush)

    fun add(n: N, isUpdate: Boolean, flush: Boolean = true): N
    fun getValues(by: List<Aggregate>, filters: List<Filter>, isGroupBy: Boolean = false): List<N>

    /**
     * As [getValues], separately on each of the consecutive and sorted [intervals] of time: each result lies in one
     * interval. By default, one request per interval
     */
    fun getValues(by: List<Aggregate>, filters: List<Filter>, isGroupBy: Boolean, intervals: List<Pair<Long, Long>>): List<N> =
        intervals.flatMap { (from, to) ->
            val time = listOfNotNull(
                if (from != Long.MIN_VALUE) Filter(FROM_TIMESTAMP, Operators.GTE, from) else null,
                if (to != Long.MAX_VALUE) Filter(TO_TIMESTAMP, Operators.LT, to) else null
            )
            getValues(by, filters + time, isGroupBy)
        }
    fun get(id: Long): N
}