package it.unibo.graph.interfaces

import java.io.Serializable

/**
 * Validity as half-open intervals [from, to) of integer timestamps: an instant [t, t] is [t, t + 1), which contains the
 * instant t only. Applied where validities are given (elements, events, query windows), so that the queries only see
 * non-empty half-open intervals
 */
fun halfOpen(from: Long, to: Long): Long = if (from == to && to < Long.MAX_VALUE) to + 1 else to

fun timeOverlap(fromTimestamp: Long, toTimestamp: Long, from: Long, to: Long, timeaware: Boolean = true): Boolean {
    if (!timeaware) return true
    val empty1 = fromTimestamp == toTimestamp
    val empty2 = from == to

    return when {
        // Case 1: Both intervals are not empty.
        !empty1 && !empty2 -> maxOf(fromTimestamp, from) < minOf(toTimestamp, to)
        // Case 2: Both intervals are empty; they overlap if they represent the same point.
        empty1 && empty2 -> fromTimestamp == from
        // Case 3: Only the first interval is empty; it overlaps if its single point falls within [start2, end2).
        empty1 -> fromTimestamp in from until to
        // Case 4: Only the second interval is empty; it overlaps if its single point falls within [start1, end1).
        else -> from in fromTimestamp until toTimestamp
    }
}

interface Elem : Serializable {
    val id: Number
    val fromTimestamp: Long
    var toTimestamp: Long
    var g: Graph
    fun timeOverlap(timeaware: Boolean, from: Long, to: Long): Boolean =
        timeOverlap(fromTimestamp, toTimestamp, from, to, timeaware)
}
