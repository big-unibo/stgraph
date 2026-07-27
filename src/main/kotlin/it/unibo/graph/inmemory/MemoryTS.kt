package it.unibo.graph.inmemory

import it.unibo.graph.interfaces.*
import it.unibo.graph.query.*
import it.unibo.graph.utils.DUMMY_ID
import it.unibo.graph.utils.NODE
import it.unibo.graph.utils.VALUE
import it.unibo.graph.utils.decodeBitwise

class MemoryTS(override val g: Graph, val id: Long) : TS {
    private val values: MutableMap<Long, N> = mutableMapOf()

    override fun getTSId(): Long = id

    override fun add(n: N, isUpdate: Boolean, flush: Boolean): N {
        values[n.fromTimestamp] = n
        return n
    }

    override fun getValues(by: List<Aggregate>, filters: List<Filter>, isGroupBy: Boolean): List<N> {
        // Filtered list of nodes based on the provided filters
        val filteredValues = values.values.filter { node ->
            filters.all { filter -> // For each node, check that it satisfies *all* filters
                node.getProps(name = filter.property).any { property -> // Get all properties of the node with the name specified by the filter
                    // TODO: we assume the properties in a event to be only instantaneous
                    assert(property.fromTimestamp == property.toTimestamp) { property.toString() } // Ensure the property is instantaneous (start and end timestamps are equal)
                    if (filter.attrFirst) { // Depending on the direction of the comparison, apply the operator
                        Compare.apply(property.value, filter.value, filter.operator) // Compare property value to filter value
                    } else {
                        Compare.apply(filter.value, property.value, filter.operator) // Compare filter value to property value
                    }
                }
            }
        }
        if (by.isEmpty()) { // If no "by" clause is provided, simply return the filtered values
            return filteredValues
        } else {
            val aggregationOperators = by.filter { it.operator != null } // Extract aggregation operators (those with an operator defined)
            if (aggregationOperators.size > 1) throw IllegalArgumentException("More than one aggregation operator: $aggregationOperators")
            val aggregationOperator = aggregationOperators.first()
            val groupby = by.filter { it.operator == null } // Extract group-by attributes (those without an operator)
            if (groupby.size > 1) throw IllegalArgumentException("More than one attribute to group by: $groupby")
            val f = filteredValues
                .map { n -> Path(listOf(n), n.fromTimestamp, n.toTimestamp) }  // Convert each node into a Path object containing the node and its time range
                .flatMap { row -> // Expand temporal properties
                    replaceTemporalProperties(row, emptyList(), by, mapOf(by.first().n to Pair(0, 0)), from = row.from, to = row.to, timeaware = true)
                }
                .groupBy { row -> // Group the paths by the values of the group-by attribute
                    groupby.map {
                        val properties = row.result[0].getProps(name = it.property, fromTimestamp = row.from, toTimestamp = row.to)
                        if (properties.size > 1) throw IllegalArgumentException("More than one property: $properties")
                        properties.first().value // Use the property value as the group key
                    }
                }
                .map { // For each group, perform aggregation
                    val values = it.value.map { path ->
                        if (path.result.size > 1) { throw IllegalArgumentException("Too many nodes: $path") }
                        val n = path.result[0]
                        (n.getProps(name = aggregationOperator.property!!, fromTimestamp = path.from, toTimestamp = path.to).first().value as Number).toDouble() // Extract the value to aggregate
                    }
                    val value = aggregateNumbers(values, aggregationOperator.operator!!, lastAggregation = false) // Aggregate the values using the specified operator
                    val g = filteredValues.first().g
                    val fromTimestamp = filteredValues.minOfOrNull { it.fromTimestamp }!!
                    val toTimestamp = filteredValues.maxOfOrNull { it.toTimestamp }!!
                    N.createVirtualN( // Create a virtual node to represent the result of the aggregation
                        it.value.first().result.first().label,
                        value,
                        fromTimestamp = fromTimestamp,
                        toTimestamp = toTimestamp,
                        g,
                        groupby // Add properties for each group-by key and their corresponding value
                            .zip(it.key)
                            .map {
                                P(DUMMY_ID, sourceId = DUMMY_ID, key = it.first.property!!, value = it.second, type = PropType.STRING, sourceType = NODE, g = g, fromTimestamp = fromTimestamp, toTimestamp = toTimestamp)
                            } + (
                                if (aggregationOperator.property != VALUE) // Add a property for the aggregation value if it's not the default property "VALUE"
                                    listOf(P(DUMMY_ID, sourceId = DUMMY_ID, key = aggregationOperator.property!!, value = value, type = PropType.STRING, sourceType = NODE, g = g, fromTimestamp = fromTimestamp, toTimestamp = toTimestamp))
                                else
                                    emptyList()
                            )
                    )
                }
                return f
        }
    }

    override fun get(id: Long): N = values[decodeBitwise(id).second]!!
}