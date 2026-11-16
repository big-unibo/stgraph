package it.unibo.graph.query

import it.unibo.graph.interfaces.ElemP
import it.unibo.graph.interfaces.timeOverlap
import kotlin.math.max
import kotlin.math.min
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.io.WKTReader

enum class Operators { EQ, LT, GT, LTE, GTE, ST_CONTAINS, ST_INTERSECTS }

class Compare(val first: Any, val second: Any, val property: String, val operator: Operators) {

    companion object {
        fun apply(a: Any, b: Any, operator: Operators): Boolean {
            return Compare(a, b, "", operator).compareIfSameType(a, b, operator)
        }
    }
    private fun compareIfSameType(a: Any, b: Any, operator: Operators): Boolean {
        if (operator == Operators.EQ) return a == b

        if (a is Comparable<*> && b is Comparable<*>) {
            @Suppress("UNCHECKED_CAST")
            val compA = a as Comparable<Any>
            val compB = b as Any

            return when (operator) {
                Operators.LT -> compA < compB
                Operators.GT -> compA > compB
                Operators.LTE -> compA <= compB
                Operators.GTE -> compA >= compB
                Operators.ST_CONTAINS -> geometryCheck(a, b, operator)
                Operators.ST_INTERSECTS -> geometryCheck(a,b, operator)
                else -> throw UnsupportedOperationException("$operator is not supported.")
            }
        }
        throw UnsupportedOperationException("Cannot compare $first $operator $second")
    }

    private fun geometryCheck(a: Any, b: Any, operator: Operators): Boolean {
        val parser = WKTReader()
        if (a == "" || b == "") {
            return false
        }
        val geomA = when (a) {
            is Geometry -> a
            is String -> parser.read(a)
            else -> throw IllegalArgumentException("Invalid type for 'a': ${a::class.simpleName}")
        }
        val geomB = when (b) {
            is Geometry -> b
            is String -> parser.read(b)
            else -> throw IllegalArgumentException("Invalid type for 'b': ${b::class.simpleName}")
        }
        return when(operator){
            Operators.ST_CONTAINS -> geomA.contains(geomB)
            Operators.ST_INTERSECTS -> geomA.intersects(geomB)
            else -> throw IllegalArgumentException("Unsupported operator: $operator")
        }
    }

    /**
     * Whether the comparison holds between [a] and [b] at some time of [[from], [to]): for some pair of versions of the
     * property valid at the same time (see [intervals]). Without time ([timeaware] = false), for some pair of versions.
     * Before, it compared only the first version returned by getProps (the last one added), whatever its validity
     */
    fun isOk(a: ElemP, b: ElemP, timeaware: Boolean, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): Boolean =
        if (!timeaware) a.getProps(name = property).any { p -> b.getProps(name = property).any { q -> holds(p.value, q.value) } }
        else intervals(a, b, from, to).isNotEmpty()

    /** Whether the values satisfy the operator; values that cannot be compared (e.g., of different types) do not */
    private fun holds(a: Any, b: Any): Boolean = runCatching { compareIfSameType(a, b, operator) }.getOrDefault(false)

    /**
     * The maximal intervals of [[from], [to]) in which the comparison holds between [a] and [b] (continuous semantics):
     * the versions of the property of a and b are paired when valid at the same time (as in the temporal property join,
     * Algorithm 1), and each pair whose values satisfy the operator holds in the intersection of their validity (and of
     * [from, to)). Overlapping or consecutive intervals are merged (e.g., [2, 4) and [4, 6) -> [2, 6)), an instant [t, t]
     * (as in timeOverlap) only when it falls inside another interval. E.g., a.v = 1 in [0, 4), 5 in [4, MAX), b.v = 3,
     * a.v < b.v: [0, 4)
     */
    fun intervals(a: ElemP, b: ElemP, from: Long, to: Long): List<Pair<Long, Long>> {
        val holding = a.getProps(name = property, fromTimestamp = from, toTimestamp = to).flatMap { p ->
            b.getProps(name = property, fromTimestamp = from, toTimestamp = to)
                .filter { q -> timeOverlap(p.fromTimestamp, p.toTimestamp, q.fromTimestamp, q.toTimestamp) && holds(p.value, q.value) } // valid at the same time, and satisfying the operator
                .map { q -> max(from, max(p.fromTimestamp, q.fromTimestamp)) to min(to, min(p.toTimestamp, q.toTimestamp)) } // ... in the intersection of their validity
        }.filter { (f, t) -> timeOverlap(f, t, from, to) } // within [from, to)
        val merged = mutableListOf<Pair<Long, Long>>()
        holding.sortedWith(compareBy<Pair<Long, Long>> { it.first }.thenByDescending { it.second }).forEach { (f, t) -> // by start, the longest first
            val last = merged.lastOrNull()
            when {
                last != null && (if (f == t) timeOverlap(last.first, last.second, f, t) else f >= last.first && t <= last.second && last.first != last.second) -> {} // already covered
                last != null && last.first != last.second && f != t && f <= last.second -> merged[merged.size - 1] = last.first to max(last.second, t) // overlapping or consecutive
                else -> merged += f to t
            }
        }
        return merged
    }
}