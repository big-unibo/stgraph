package it.unibo.graph.query

class Filter(val property: String, val operator: Operators, val value: Any, val attrFirst: Boolean = true)