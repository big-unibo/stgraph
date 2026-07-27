package it.unibo.graph.query

enum class AggOperator { SUM, COUNT, AVG, MIN, MAX }

class Aggregate(val n: String, val property: String? = null, val operator: AggOperator? = null) {
    override fun toString(): String {
        return if (operator != null) {
            "$operator($n.$property)"
        } else {
            "$n.$property"
        }
    }
}