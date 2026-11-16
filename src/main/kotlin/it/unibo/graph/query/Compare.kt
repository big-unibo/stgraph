package it.unibo.graph.query

import it.unibo.graph.interfaces.ElemP
import it.unibo.graph.interfaces.timeOverlap
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
     * property valid at the same time (any pair without time). It only checks the step (matchesStep): the selection then
     * restricts the tuple to the time in which it holds (see select in Query.kt)
     */
    fun isOk(a: ElemP, b: ElemP, timeaware: Boolean, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): Boolean =
        a.getProps(name = property, fromTimestamp = from, toTimestamp = to, timeaware = timeaware).any { p ->
            b.getProps(name = property, fromTimestamp = from, toTimestamp = to, timeaware = timeaware)
                .any { q -> (!timeaware || timeOverlap(p.fromTimestamp, p.toTimestamp, q.fromTimestamp, q.toTimestamp)) && holds(p.value, q.value) }
        }

    /** Whether the values satisfy the operator; values that cannot be compared (e.g., of different types) do not */
    fun holds(a: Any, b: Any): Boolean = runCatching { compareIfSameType(a, b, operator) }.getOrDefault(false)
}