package it.unibo.graph.interfaces

class Path(val result: List<ElemP>, val from: Long, val to: Long) {
    override fun toString(): String {
        return "{$result, from: $from, to: $to}"
    }
}