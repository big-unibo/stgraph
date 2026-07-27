package it.unibo.graph.query

import it.unibo.graph.interfaces.Direction

interface IStep {
    val label: String?
    val properties: List<Filter>
    val alias: String?
}

open class Step(
    override val label: String? = null,
    override val properties: List<Filter> = emptyList(),
    override val alias: String? = null
) : IStep

class EdgeStep(
    label: String? = null,
    properties: List<Filter> = emptyList(),
    alias: String? = null,
    val direction: Direction
) :
    Step(label, properties, alias)
