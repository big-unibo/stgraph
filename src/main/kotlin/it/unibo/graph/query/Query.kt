package it.unibo.graph.query

import it.unibo.graph.interfaces.*
import it.unibo.graph.utils.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import javax.management.Query
import kotlin.math.max
import kotlin.math.min

enum class QueryMode {
    NAIVE, OPTIMIZED
}

@JvmName("query")
fun query(g: Graph, match: List<Step?>, where: List<Compare> = emptyList(), by: List<Aggregate> = emptyList(), from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE, timeaware: Boolean = true, threads: Int = 1, mode: QueryMode = QueryMode.OPTIMIZED): List<Any> {
    return query(g, listOf(match), where, by, from, to, timeaware, threads, mode)
}

@JvmName("queryJoin")
fun query(g: Graph, match: List<List<Step?>>, where: List<Compare> = emptyList(), by: List<Aggregate> = emptyList(), from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE, timeaware: Boolean = true, threads: Int = 1, mode: QueryMode = QueryMode.OPTIMIZED): List<Any> {
    val mapAliases: Map<String, Pair<Int, Int>> =
        match // get the aliases for each pattern
            .mapIndexed { patternIndex, pattern ->
                pattern
                    .mapIndexed { a, b -> Pair(a, b) }
                    .filter { it.second?.alias != null } // Consider only the steps with aliases
                    .associate { it.second?.alias!! to Pair(patternIndex, it.first) } // Pair(patternIndex, stepIndex)
            }
            .reduce { acc, map -> acc + map }
    val joinFilters = where.filter { mapAliases[it.first]!!.first != mapAliases[it.second]!!.first } // Take the filters that refer to different patterns (i.e., filters for joining the patterns)
    val pattern1 = search(g, match[0], (where - joinFilters).filter { mapAliases[it.first]!!.first == 0 }, from, to, timeaware, by = by, threads, mode) // compute the first pattern by removing all join filters and consider only the filters on the first pattern (i.e., patternIndex = 0)
    var result =
        if (match.size == 1) {
            pattern1
        } else {
            join(g, pattern1, match, joinFilters, mapAliases, where, from, to, timeaware, by, threads, mode)
        }

    result = result.flatMap { row -> replaceTemporalProperties(row, match, by, mapAliases, from, to, timeaware) }
    val result2 = result.groupBy { row -> groupBy(by, mapAliases, row, match) }
    val result3 = result2.mapValues { rowGroup -> aggregate(by, rowGroup, mapAliases, match) }
    val result4 = result3.map { wrapResult(by, it) }
    val result5 = result4.flatten()
    return result5
}

/*
 * Join two patterns
 */
private fun join(
    g: Graph,
    pattern1: List<Path>,
    match: List<List<Step?>>,
    joinFilters: List<Compare>,
    mapAliases: Map<String, Pair<Int, Int>>,
    where: List<Compare>,
    from: Long,
    to: Long,
    timeaware: Boolean,
    by: List<Aggregate>,
    threads: Int,
    mode: QueryMode
): List<Path> {
    val result = ConcurrentLinkedQueue<Path>()
    // Funzione ricorsiva locale
    fun rec(curMatch: MutableList<Step?>, index: Int, fromLimit: Long, toLimit: Long, row: Path, acc: MutableList<Path>) {
        if (index >= match[1].size) {
            val paths =
                search(g, curMatch, (where - joinFilters).filter { mapAliases[it.first]!!.first == 1 }, fromLimit, toLimit, timeaware, by = by, threads, mode)
                    .map { Path(row.result + it.result, it.from, it.to) }
            acc.addAll(paths)
            return
        }

        val step = match[1][index]
        if (step == null) {
            curMatch.add(null)
            rec(curMatch, index + 1, fromLimit, toLimit, row, acc)
            curMatch.removeAt(curMatch.size - 1)
            return
        }

        val curJoinFilters = joinFilters.filter { step.alias == it.first || step.alias == it.second }
        if (curJoinFilters.isNotEmpty()) {
            val curJoinFilter = curJoinFilters.first()
            val cAlias = if (step.alias != curJoinFilter.first) curJoinFilter.first else curJoinFilter.second
            val props = row.result[mapAliases[cAlias]!!.second].getProps(name = curJoinFilter.property, fromTimestamp = fromLimit, toTimestamp = toLimit, timeaware = timeaware)
            props.forEach { p ->
                curMatch.add(
                    Step(
                        step.label,
                        step.properties + Filter(
                            curJoinFilter.property,
                            curJoinFilter.operator,
                            p.value,
                            attrFirst = mapAliases[curJoinFilter.first]!!.first == 1
                        ),
                        step.alias
                    )
                )
                rec(curMatch, index + 1, max(fromLimit, p.fromTimestamp), min(toLimit, p.toTimestamp), row, acc)
                curMatch.removeAt(curMatch.size - 1)
            }
        } else {
            curMatch.add(step)
            rec(curMatch, index + 1, fromLimit, toLimit, row, acc)
            curMatch.removeAt(curMatch.size - 1)
        }
    }

    val executor = Executors.newFixedThreadPool(threads).asCoroutineDispatcher()
     runBlocking {
        val jobs = pattern1.map { row ->
            launch(executor) {
                val acc = mutableListOf<Path>()
                rec(mutableListOf(), 0, max(from, row.from), min(to, row.to), row, acc)
                result += acc
            }
        }
        jobs.joinAll()
    }
    return result.toList()
}




fun intervalOuterJoin(listA: List<Elem>, listB: List<Elem>, timeaware: Boolean): Set<Pair<Elem?, Elem?>> {
    if (!timeaware) {
        return cartesianProduct(listA, listB)
    }

    val boundaries =
        (
            listA.flatMap { listOf(it.fromTimestamp, it.toTimestamp) } +
            listB.flatMap { listOf(it.fromTimestamp, it.toTimestamp) }
        )
        .toSortedSet()
        .toList()

    val slices = mutableSetOf<Pair<Elem?, Elem?>>()

    // Slice the timeline
    for (i in 0 until boundaries.size - 1) {
        val start = boundaries[i]
        val end = boundaries[i + 1]
        val activeA = listA.find { it.timeOverlap(timeaware, start, end) }
        val activeB = listB.find { it.timeOverlap(timeaware, start, end) }
        slices.add(Pair(activeA, activeB))
    }

    // Handle exact point intervals (from == to)
    val pointEvents = boundaries.filter { b ->
        listA.any { it.fromTimestamp == b && it.toTimestamp == b } ||
        listB.any { it.fromTimestamp == b && it.toTimestamp == b }
    }

    for (point in pointEvents) {
        val a = listA.find { it.timeOverlap(timeaware, point, point) }
        val b = listB.find { it.timeOverlap(timeaware, point, point) }
        // Avoid duplicate (e.g., already part of a longer interval slice)
        if (a != null || b != null) {
            slices.add(Pair(a, b))
        }
    }
    return slices
}

/**
 * Replace properties if necessary
 */
fun replaceTemporalProperties(row: Path, match: List<List<Step?>>, by: List<Aggregate>, mapAliases: Map<String, Pair<Int, Int>>, from: Long, to: Long, timeaware: Boolean): List<Path> {
    // Restrict the temporal interval
    val from = max(from, row.from)
    val to = min(to, row.to)
    val row = row.result
    // Find the nodes that should be replaced by their properties
    // MATCH (n)-->(m) RETURN n.name, m => only n
    // MATCH (n)-->(m) RETURN n.name, avg(m.value) => n and m
    val toReplace: Map<Int, List<Aggregate>> = by.filter { it.property != null }.groupBy {
        val alias = mapAliases[it.n] ?: throw IllegalArgumentException("Alias `${it.n}` is not defined")
        alias.second + (if (alias.first == 1) match[0].size else 0)
    } // find its index in the path
    val acc: MutableList<Path> = mutableListOf() // accumulator of rows
    fun createVirtual(replace: List<Pair<Aggregate, P?>>, from: Long, to: Long, elem: ElemP): ElemP {
        val virtualP: List<P> = replace.map { pair->
            val p = pair.second
            p?: P(DUMMY_ID, sourceType = NODE, key = pair.first.property!!, value = "null", type = PropType.NULL, fromTimestamp = from, toTimestamp = to, sourceId = DUMMY_ID, g = elem.g)
        }
        val maxFrom = max(from, virtualP.maxOf { it.fromTimestamp })
        val minTo = min(to, virtualP.minOf { it.toTimestamp })
        val virtual =
            if (elem is N) {
                N.createVirtualN(elem.label, virtualP, maxFrom, minTo, elem.g)
            } else {
                R.createVirtualR(elem.label, virtualP, maxFrom, minTo, elem.g)
            }
        return virtual
    }

    return if (toReplace.isNotEmpty()) { // if I need to replace at least one element
        fun rec(curRow: List<ElemP>, index: Int, from: Long, to: Long) { // recursive function
            if (index < row.size) { // iterate until all elements have been checked
                val replace: List<Aggregate>? = toReplace[index] // find the next item to replace
                val elem: ElemP = row[index]
                if (replace == null) {  // if I don't need to replace this item... continue the recursion but compute the reduced from/to timestamps
                    if (timeOverlap(from, to, elem.fromTimestamp, elem.toTimestamp, timeaware)) { // this node/edge is still valid (i.e., it overlaps with the time span of the property)
                        val newFrom = max(from, elem.fromTimestamp)
                        val newTo = min(to, elem.toTimestamp)
                        rec(curRow + elem, index + 1, newFrom, newTo) // continue the recursion
                    }
                } else { // else, I need to replace these items
                    val props: List<Pair<Aggregate, List<P>>> = replace.map { it to elem.getProps(name = it.property, fromTimestamp = from, toTimestamp = to, timeaware = timeaware) } // For each aggregate, I can have multiple historical properties
                    if (props.size > 2) throw IllegalArgumentException("Too many props: $props")
                    val replacements: List<ElemP> =
                        if (props.size == 2) {
                            intervalOuterJoin(props[0].second, props[1].second, timeaware)
                                .map { listOf(Pair(props[0].first, it.first as P?), Pair(props[1].first, it.second as P?)) }
                                .map { createVirtual(it, from, to, elem) }
                        } else {
                            props
                                .flatMap { group -> group.second.map { Pair(group.first, it) } }
                                .map { replacement -> createVirtual(listOf(replacement), from, to, elem) }
                        }
                    if (replacements.isEmpty()) { // if they are empty, add the null value as a return
                        rec(curRow + createVirtual(props.map { Pair(it.first, null) }, from, to, elem), index + 1, from, to) // ... and continue the recursion
                    } else {
                        /* I cannot simply iterate over the properties to start the recursion, for instance let's consider
                         * (a)---[0, 0)--->(b1)
                         * (a)---[1, 1)--->(b2)
                         * (a)---[2, 2)--->(b3)
                         *
                         * where (a) has two properties
                         * a.name = "foo" in (0, 0)
                         * a.name = "bar" in (1, 1)
                         *
                         * The query MATCH (a)-->(b) RETURN a.name, b
                         * must return
                         * -------------
                         * | foo  | b1 |
                         * | bar  | b2 |
                         * | null | b3 |
                         * -------------
                         * So I need to check the time span of the next element to decide whether I should push a "null" value down the recursion
                         */
                        if (index + 1 < row.size) { // if this element is not the last
                            val nextElem = row[index + 1] // look ahead the next element
                            val overlappingReplacements = replacements.filter { p -> !(p.fromTimestamp > nextElem.toTimestamp || p.toTimestamp < nextElem.fromTimestamp) } // find the overlapping properties
                            if (overlappingReplacements.isEmpty()) { // if there is no overlapping, push null down to the recursion
                                rec(curRow + createVirtual(props.map { Pair(it.first, null) }, from, to, elem), index + 1, from, to)
                            } else {
                                overlappingReplacements.forEach { p ->  // if an overlapping element exists
                                    rec(curRow + p, index + 1, max(from, p.fromTimestamp), min(to, p.toTimestamp)) // continue the recursion by further reducing the interval with the element values
                                }
                            }
                        } else { // If this is the last element, simply iterate over the existing properties
                            replacements.forEach { rec(curRow + it, index + 1, max(from, it.fromTimestamp), min(to, it.toTimestamp)) } // continue the recursion by further reducing the interval with the element values
                        }
                    }
                }
            } else {
                acc.add(Path(curRow, from, to))
            }
        }
        rec(emptyList(), 0, from, to)
        acc
    } else {
        listOf(Path(row, from, to))
    }
}

/**
 * Make the result compatible with Neo4J
 */
private fun wrapResult(by: List<Aggregate>, it: Map.Entry<List<Any>, List<Any>>) =
    if (by.isNotEmpty()) {
        if (by.any { it.operator != null }) {
            if (by.size == 1) {
                it.key + it.value // MATCH (n)-->(m) RETURN n.name, avg(m.value) => [[a, 12.5], [b, 13.0], ...]
            } else {
                listOf(it.key + it.value)
            }
        } else {
            if (by.size == 1) {
                it.key // MATCH (n) RETURN n.name => [a, b, ...]
            } else {
                listOf(it.key)  // MATCH (n)-->(m) RETURN n.name, m.name => [[a, b], [a, c], ...]
            }
        }
    } else {
        it.value
    }

fun aggregateNumbers(numbers: List<Any>, aggregationOperator: AggOperator, lastAggregation: Boolean): Any {
    val numbers = numbers.filter { it != null && it != "null" } // TODO: this is necessary to handle data grouped by in TS
    if (numbers.first() is Number) {
        val cNumbers = numbers.map { (it as Number).toDouble() }
        return when (aggregationOperator) {
            AggOperator.SUM -> cNumbers.sum()
            AggOperator.COUNT -> cNumbers.count()
            AggOperator.MAX -> cNumbers.max()
            AggOperator.MIN -> cNumbers.min()
            AggOperator.AVG -> {
                val v =
                    cNumbers.fold(0.0 to 0) { (sum, count), num ->
                        (sum + num) to (count + 1)
                    }
                if (lastAggregation) v.first / v.second else v
            }
            else -> throw IllegalArgumentException("Unsupported aggregation operator: $aggregationOperator")
        }
    } else if (numbers.first() is Pair<*, *>) {
        val cNumbers = numbers.map { it as Pair<Double, Int> }
        return when (aggregationOperator) {
            AggOperator.AVG -> {
                val v = cNumbers.fold(0.0 to 0) { (sum, count), num ->
                    (sum + num.first) to (count + num.second)
                }
                if (lastAggregation) v.first / v.second else v
            }

            AggOperator.SUM -> cNumbers.sumOf { it.first }
            AggOperator.MAX -> cNumbers.maxOf { it.first }
            AggOperator.COUNT -> cNumbers.sumOf { it.second }
            else -> throw IllegalArgumentException("Unsupported aggregation operator: $aggregationOperator")
        }
    } else {
        throw IllegalArgumentException("Unsupported type of: ${numbers.first()}")
    }
}

/**
 * Aggregate the result of group by
 */
private fun aggregate(by: List<Aggregate>, group: Map.Entry<List<Any>, List<Path>>, mapAliases: Map<String, Pair<Int, Int>>, match: List<List<Step?>>): List<Any> {
    val aggregationOperators = by.filter { it.operator != null }
    if (aggregationOperators.isEmpty()) {
        // no aggregation operator has been specified
        // E.g., MATCH (n) RETURN n.name => [a, b, ...]
        return group.value
    } else {
        // some aggregation operator has been specified
        if (aggregationOperators.filter { it.operator != null }.size > 1) throw IllegalArgumentException("More than one aggregation operator")
        val aggregationOperator = aggregationOperators.first()
        // E.g., MATCH (n)-->(m) RETURN n.name, avg(m.value) => [[a, 12.5], [b, 13.0], ...]
        val values: List<Any> = group.value
            .map { row ->
                val alias = mapAliases[by.first { it.operator != null }.n]!!
                val properties = row.result[alias.second + (if (alias.first == 1) match[0].size else 0)].getProps(name = aggregationOperator.property!!)
                if (properties.size != 1) {
                    throw IllegalArgumentException("Properties should be 1: $properties")
                }
                properties.first().value
            }
        val value = aggregateNumbers(values, aggregationOperator.operator!!, lastAggregation = true)
        return listOf(value) // E.g., [12.5]
    }
}

/**
 * Group several rows by key
 */
private fun groupBy(by: List<Aggregate>, mapAliases: Map<String, Pair<Int, Int>>, row: Path, match: List<List<Step?>>): List<Any> =
    // group by all elements that are not aggregation operator
    // MATCH (n)-->(m) RETURN n.name, m.name => group by n and m
    // MATCH (n)-->(m) RETURN n.name, avg(m.value) => group by only on n
    by.filter { it.operator == null }.map {
        val alias = mapAliases[it.n]!!
        // row[alias.second + (if (alias.first == 1) match[0].size else 0)]
        val properties = row.result[alias.second + (if (alias.first == 1) match[0].size else 0)].getProps(name = it.property)
        if (properties.size != 1) {
            throw IllegalArgumentException("Properties should be 1: $properties")
        }
        properties.first().value
    }

fun pushDownBy(index: Int, by: List<Aggregate>, match: List<Step?>): List<Aggregate> {
    val cby = mutableListOf<Aggregate>()
    if (index + 1 < match.size) { // If there is another step
        val nextMatch = match[index + 1] ?: return cby // select it
        val nextAlias = nextMatch.alias // and its alias (if any)
        if (nextAlias != null) { // If the alias is available
            cby += by.filter { it.n == nextAlias }
        }
    }
    return cby
}

fun pushDownFilters(index: Int, curPath: List<ElemP>, from: Long, to: Long, match: List<Step?>, mapAlias: Map<String, Int>, mapWhere: Map<String, Compare>): List<Filter> {
    val filters = mutableListOf<Filter>()
    if (from != Long.MIN_VALUE) filters += Filter(FROM_TIMESTAMP, Operators.GTE, from) // add the from filter
    if (to != Long.MAX_VALUE) filters += Filter(TO_TIMESTAMP, Operators.LT, to) // add the to filter
    if (index + 1 < match.size) { // If there is another step
        val nextMatch = match[index + 1] ?: return filters // select it
        val nextAlias = nextMatch.alias // and its alias (if any)
        filters += nextMatch.properties
        if (nextMatch.label != null) filters += Filter(LABEL, Operators.EQ, labelFromString(nextMatch.label!!))
        if (nextAlias != null) { // If the alias is available
            val c = mapWhere[nextAlias] // Check whether this node is also part of a comparison
            if (c != null) { // if so...
                val tmp = if (c.first == nextAlias) c.second else c.first // take the other element
                // ... and push down a new filter based on the property values
                val values = curPath[mapAlias[tmp]!!].getProps(name = c.property, fromTimestamp = from, toTimestamp = to)
                if (values.size > 1) throw IllegalArgumentException("Too many property values in the same temporal range")
                filters += Filter(c.property, c.operator, values.first().value, nextAlias == c.first) // TODO should run for each property
            }
        }
    }
    return filters // push down the filters from the next step
}

fun whereClause(e: ElemP, path: List<ElemP>, alias: String, mapWhere: Map<String, Compare>, c: Compare, timeaware: Boolean, mapAlias: Map<String, Int>): Boolean {
    return when (alias) {
        mapWhere[alias]?.first -> c.isOk(e, path[mapAlias[c.second]!!], timeaware)
        mapWhere[alias]?.second -> c.isOk(path[mapAlias[c.first]!!], e, timeaware)
        else -> false
    }
}

class ExploredPath(val e: ElemP, val index: Int, val path: List<ElemP>, val from: Long, val to: Long)

fun buildAliasMap(match: List<Step?>): Map<String, Int> =
    match
        .mapIndexed { a, b -> Pair(a, b) }
        .filter { it.second?.alias !== null }
        .associate { it.second?.alias!! to it.first }

fun buildWhereMap(where: List<Compare>, mapAlias: Map<String, Int>): Map<String, Compare> =
    where
        .associateBy {
            mapAlias
                .filterKeys { it in where.flatMap { listOf(it.first, it.second) }.toSet() }
                .maxByOrNull { it.value }?.key!!
        }

fun initializeQueue(g: Graph, from: Long, to: Long): Queue<ExploredPath> {
    val acc = ConcurrentLinkedQueue<ExploredPath>()
    // val acc = ArrayDeque<ExploredPath>()
    acc.addAll(g.getNodes().map { ExploredPath(it, 0, emptyList(), from, to) })
    return acc
}

fun matchesStep(cur: ExploredPath, match: List<Step?>, by: List<Aggregate>, timeaware: Boolean, mapAlias: Map<String, Int>, mapWhere: Map<String, Compare>, mode: QueryMode): Boolean {
    val step = match[cur.index]
    val alias = step?.alias
    val compare = alias?.let { mapWhere[it] }

    return (step == null || ( // no filter
                            (step.label == null || step.label == cur.e.label.name)  // filter on label
                                && step
                                    .properties
                                    .filter { f ->  !(mode == QueryMode.OPTIMIZED && by.filter { it.operator != null }.map { it.property }.contains(f.property)) } // do not apply filter on the result of pushed-down group by (otherwise it would be like SELECT * FROM (SELECT SUM(value) as value ...) WHERE value > 10)
                                    .all { f -> // for all filters...
                                        cur.e
                                            .getProps(name = f.property, fromTimestamp = cur.from, toTimestamp = cur.to, timeaware = timeaware) // ... a certain time-aware property must match
                                            .any { p -> if (f.attrFirst) { Compare.apply(p.value, f.value, f.operator) } else { Compare.apply(f.value, p.value, f.operator) } }
                                        })) // filter on properties
                        && cur.e.timeOverlap(timeaware, cur.from, cur.to) // check time overlap
                        && (compare == null || whereClause(cur.e, cur.path, alias, mapWhere, compare, timeaware, mapAlias))

}

fun updatePath(cur: ExploredPath): Triple<Long, Long, List<ElemP>> {
    val newFrom = max(cur.e.fromTimestamp, cur.from)
    val newTo = min(cur.e.toTimestamp, cur.to)
    val newPath = cur.path + cur.e
    return Triple(newFrom, newTo, newPath)
}

fun isCompletePath(path: List<Elem>, match: List<Step?>): Boolean =
    path.size == match.size

fun expandNode(cur: ExploredPath, path: List<ElemP>, from: Long, to: Long, match: List<Step?>): List<ExploredPath> {
    val nextStep = match.getOrNull(cur.index + 1)
    return (cur.e as N)
        .getEdges(
            direction = if (nextStep is EdgeStep) nextStep.direction else Direction.OUT,
            includeHasTs = true
        )
        .map { ExploredPath(it, cur.index + 1, path, from, to) }
}

fun search(g: Graph, match: List<Step?>, where: List<Compare> = emptyList(), from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE, timeaware: Boolean = false, by: List<Aggregate> = emptyList(), threads: Int = 1, mode: QueryMode = QueryMode.OPTIMIZED): List<Path> {
    val executor = Executors.newFixedThreadPool(threads).asCoroutineDispatcher()
    val wait = Semaphore(0) // Mutex(locked = true)
    val completed = AtomicInteger(0)
    var launched = 0

    val acc = mutableListOf<Path>()
    val mapAlias = buildAliasMap(match)
    val mapWhere = buildWhereMap(where, mapAlias)
    val queue = initializeQueue(g, from, to)

    runBlocking {
        while (true) {
            if (queue.isEmpty()) {
                if (completed.get() < launched) {
                    wait.acquire()
                } else {
                    break
                }
            } else {
                val curElem = queue.poll()// .removeAt(0)
                if (!matchesStep(curElem, match, by, timeaware, mapAlias, mapWhere, mode)) continue
                val (newFrom, newTo, curPath) = updatePath(curElem)
                if (isCompletePath(curPath, match)) {
                    acc.add(Path(curPath, newFrom, newTo))
                } else {
                    if (curElem.index % 2 == 0) { // is node
                        val toAdd = expandNode(curElem, curPath, newFrom, newTo, match)
                        queue.addAll(toAdd)
                    } else { // is edge...
                        val r = (curElem.e as R)
                        if (curElem.e.label.name == HasTS) { // ... to time series
                            launched++
                            launch(executor) {
                                val pushDownBy: List<Aggregate>
                                val pushDownFilter: List<Filter>
                                val isGroupBy: Boolean
                                if (mode == QueryMode.NAIVE) {
                                    pushDownBy = emptyList()
                                    pushDownFilter = emptyList()
                                    isGroupBy = false
                                } else {
                                    pushDownBy = pushDownBy(curElem.index, by, match)
                                    pushDownFilter = pushDownFilters(curElem.index, curPath, newFrom, newTo, match, mapAlias, mapWhere)
                                    isGroupBy = by.isNotEmpty()
                                }
                                try {
                                    val res = g.getTSM()
                                        .getTS(r.toN, mode = TsMode.READ)
                                        .getValues(pushDownBy, pushDownFilter, isGroupBy) // push down the filters from the next step
                                        .map { ExploredPath(it, curElem.index + 1, curPath, newFrom, newTo) }
                                    queue.addAll(res)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    completed.incrementAndGet()
                                    wait.release()
                                }
                            }
                        } else { // ... or to graph node
                            val step = match[curElem.index]
                            val n = g.getNode(if (step is EdgeStep && step.direction === Direction.IN) { r.fromN } else { r.toN })
                            queue.add(ExploredPath(n, curElem.index + 1, curPath, newFrom, newTo))
                        }
                    }
                }
            }
        }
    }

    return acc
}