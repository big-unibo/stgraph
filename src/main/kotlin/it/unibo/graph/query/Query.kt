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
import java.util.concurrent.atomic.AtomicReference
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

    result = result.flatMap { row -> project(row, match, by, mapAliases, from, to, timeaware) } // PROJECTION on the properties of the aggregation
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
            // PROJECTION before the join: with an aggregation pushed down in the second pattern, the grouping properties
            // of the first one, so that each search of the second pattern binds one version of them (temporal property join)
            val grouping = if (mode == QueryMode.OPTIMIZED && by.any { it.operator != null && mapAliases[it.n]?.first == 1 })
                by.filter { it.operator == null && it.property != null && mapAliases[it.n]?.first == 0 }.map { row.result[mapAliases[it.n]!!.second] to it.property!! }
                else emptyList()
            temporalPropertyJoin(grouping, fromLimit, toLimit).forEach { (slice, _) ->
                acc += search(g, curMatch, (where - joinFilters).filter { mapAliases[it.first]!!.first == 1 }, slice.first, slice.second, timeaware, by = by, threads, mode)
                    .map { Path(row.result + it.result, it.from, it.to) }
            }
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
            // Only one comparison per step would be applied: fail instead of ignoring the others
            if (curJoinFilters.size > 1) throw UnsupportedOperationException("At most one join comparison (where) per step: $curJoinFilters")
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

    // Closed at the end: its threads are not daemons, they would stay alive after the query
    Executors.newFixedThreadPool(threads).asCoroutineDispatcher().use { executor ->
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
    }
    return result.toList()
}




/**
 * PROJECTION (π) of a tuple ([row]) on the properties of [by]: each element with projected properties is replaced by a
 * (virtual) element holding one version of each of them, and the tuple is generated once per combination of versions
 * valid at the same time (temporal property join, Algorithm 1), with the validity of their intersection. An element
 * without any version in the tuple holds null. Without time ([timeaware] = false), all the combinations of versions.
 * E.g., (a, b) with a.name = x in [0, 2) and y in [2, 4), b.name = z: (x, z) in [0, 2), (y, z) in [2, 4)
 */
fun project(row: Path, match: List<List<Step?>>, by: List<Aggregate>, mapAliases: Map<String, Pair<Int, Int>>, from: Long, to: Long, timeaware: Boolean): List<Path> {
    val keys = by.filter { it.property != null }.groupBy { // the projected properties, by element (index in the tuple)
        val alias = mapAliases[it.n] ?: throw IllegalArgumentException("Alias `${it.n}` is not defined")
        alias.second + (if (alias.first == 1) match[0].size else 0)
    }
    val validity = max(from, row.from) to min(to, row.to)
    var tuples = listOf(row.result to validity) // the tuples generated so far, with their validity
    for ((index, aggregates) in keys) {
        val e = row.result[index]
        val versions: List<Pair<Pair<Long, Long>?, List<P?>>> = // (validity, a version of each property), null validity without time
            if (timeaware) temporalPropertyJoin(aggregates.map { e to it.property!! }, validity.first, validity.second).filter { (_, v) -> v.any { it != null } }
            else aggregates.fold(listOf(listOf<P?>())) { acc, a -> acc.flatMap { c -> e.getProps(name = a.property, timeaware = false).map { c + it } } }.map { null to it }
        tuples = tuples.flatMap { (elements, interval) ->
            versions.ifEmpty { listOf(null to aggregates.map { null }) }.mapNotNull { (slice, v) ->
                val i = if (slice == null) interval else intersection(interval, slice) ?: return@mapNotNull null // valid at the same time
                elements.toMutableList().apply { this[index] = virtual(e, aggregates.zip(v), i) } to i
            }
        }
    }
    return tuples.map { (elements, interval) -> Path(elements, interval.first, interval.second) }
}

/** The intersection of the intervals [a] and [b] (instants [t, t] as in timeOverlap), null if they do not overlap */
private fun intersection(a: Pair<Long, Long>, b: Pair<Long, Long>): Pair<Long, Long>? =
    if (timeOverlap(a.first, a.second, b.first, b.second)) max(a.first, b.first) to min(a.second, b.second) else null

/** The (virtual) element replacing [e] in a projected tuple valid in [interval]: the given versions of its properties (null if none) */
private fun virtual(e: ElemP, versions: List<Pair<Aggregate, P?>>, interval: Pair<Long, Long>): ElemP {
    val props = versions.map { (a, p) -> p ?: P(DUMMY_ID, sourceType = NODE, key = a.property!!, value = "null", type = PropType.NULL, fromTimestamp = interval.first, toTimestamp = interval.second, sourceId = DUMMY_ID, g = e.g) }
    return if (e is N) N.createVirtualN(e.label, props, interval.first, interval.second, e.g) else R.createVirtualR(e.label, props, interval.first, interval.second, e.g)
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
            // A partial count (lastAggregation = false, e.g., MemoryTS) as AsterixDBTS returns it, (count, count): the final
            // aggregation sums the partial counts (Pair branch), instead of counting the partial aggregates
            AggOperator.COUNT -> if (lastAggregation) cNumbers.count() else cNumbers.count().toDouble() to cNumbers.count()
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
            AggOperator.MIN -> cNumbers.minOf { it.first } // the partial minimums (e.g., from AsterixDBTS), as MAX
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

/**
 * TEMPORAL PROPERTY JOIN (Algorithm 1) of the properties [keys] of the elements of a tuple valid in [[from], [to]), on any
 * number of properties: the slices of time in which every property keeps the same value, each with its validity and its
 * versions (null where a property has none); consecutive slices with the same values are merged (e.g., ML in [2, 4) and
 * ML in [4, 6): [2, 6)). Extended with them, the tuple has the validity of the slice (the intersection of the validity of
 * its elements). Used by the projection (see project), also before the operators that read time-varying properties: the
 * selection (see select), the expansion towards TS with pushdown (see queryTSOpt), and the join.
 * E.g., a.type = ML in [0, 2), HV in [2, 4), [from, to) = [MIN, MAX): [MIN, 0) null, [0, 2) ML, [2, 4) HV, [4, MAX) null.
 * Instants as in timeOverlap: a version valid in [t, t] gives the slice [t, t] (with integer timestamps, it is separated
 * from the next instants as [t, t + 1)). Without properties, or in an instant ([from] = [to]), the whole [from, to]
 */
fun temporalPropertyJoin(keys: List<Pair<ElemP, String>>, from: Long, to: Long): List<Pair<Pair<Long, Long>, List<P?>>> {
    val versions = keys.map { (e, key) -> e.getProps(name = key, fromTimestamp = from, toTimestamp = to) } // the versions of each property in the tuple
    if (from >= to) return listOf((from to to) to versions.map { it.firstOrNull() }) // an instant: at most one version each
    fun end(p: P) = if (p.fromTimestamp == p.toTimestamp && p.toTimestamp < Long.MAX_VALUE) p.toTimestamp + 1 else p.toTimestamp
    // The bounds of the versions (within the tuple) and of the tuple: no version starts or ends inside a slice
    val bounds = (versions.flatten().flatMap { listOf(it.fromTimestamp, end(it)) }.filter { it in from..to } + from + to).toSortedSet().toList()
    val slices = bounds.zipWithNext().map { (f, t) ->
        val slice = versions.map { vs -> vs.firstOrNull { it.fromTimestamp <= f && t <= end(it) } } // the version of each property in [f, t)
        (if (t == f + 1 && slice.any { it != null && it.fromTimestamp == it.toTimestamp }) f to f else f to t) to slice
    }
    val merged = mutableListOf<Pair<Pair<Long, Long>, List<P?>>>()
    slices.forEach { (i, v) -> // consecutive slices (not instants) with the same values: a single slice
        val last = merged.lastOrNull()
        if (last != null && last.first.second == i.first && last.first.first != last.first.second && i.first != i.second && last.second.map { it?.value } == v.map { it?.value }) {
            merged[merged.size - 1] = (last.first.first to i.second) to last.second
        } else merged += i to v
    }
    return merged
}

/**
 * SELECTION (σ) on the properties of the element matched at the step of [cur] (the filters of the step, and the
 * comparison of the where clause with an element already in [path]), after their temporal property join (π): the slices
 * of [[from], [to]) in which they hold. The tuple holds only while its properties satisfy the predicate (continuous
 * semantics), so it continues once per slice, or not at all. [[from], [to]] itself without such properties or without
 * time. The filters on the properties aggregated by the time series ([skip], as matchesStep) are not evaluated
 */
fun select(cur: ExploredPath, path: List<ElemP>, from: Long, to: Long, match: List<Step?>, mapAlias: Map<String, Int>, mapWhere: Map<String, Compare>, timeaware: Boolean, skip: List<String?>): List<Pair<Long, Long>> {
    val step = match[cur.index]
    val filters = step?.properties.orEmpty().filter { it.property !in skip }
    val c = step?.alias?.let { mapWhere[it] } // the comparison of the where clause, with an element already in the path
    if (!timeaware || (filters.isEmpty() && c == null)) return listOf(from to to)
    val compared = if (c == null) emptyList() else listOf(path[mapAlias[c.first.toString()]!!] to c.property, path[mapAlias[c.second.toString()]!!] to c.property)
    val n = filters.size
    return temporalPropertyJoin(filters.map { cur.e to it.property } + compared, from, to)
        .filter { (_, v) ->
            filters.indices.all { i -> v[i]?.let { p -> filters[i].holds(p.value) } ?: false } && // every filter (no version: false)
                (c == null || (v[n] != null && v[n + 1] != null && c.holds(v[n]!!.value, v[n + 1]!!.value))) // ... and the comparison
        }
        .map { it.first }
}

/** Whether the value [v] satisfies the filter; values that cannot be compared (e.g., of different types) do not */
private fun Filter.holds(v: Any): Boolean = runCatching { if (attrFirst) Compare.apply(v, value, operator) else Compare.apply(value, v, operator) }.getOrDefault(false)

/**
 * QUERYTS: the expansion of the tuple [path] (valid in [[from], [to]]) towards the events of the time series [ts]: every
 * event extends the tuple, and the next steps check them one by one (NAIVE)
 */
fun queryTS(ts: TS, index: Int, path: List<ElemP>, from: Long, to: Long): List<ExploredPath> =
    getTSEvents(ts, emptyList(), emptyList(), false).map { ExploredPath(it, index + 1, path, from, to) }

/**
 * QUERYTSOPT: the expansion of the tuple [path] (valid in [[from], [to]]) towards the time series [ts], pushing down the
 * operators of the next steps (OPTIMIZED): the aggregation (GetAggregate) and the predicates (GetPredicates, with the
 * validity of the tuple). Before the expansion, the tuple is projected on the time-varying properties the pushdown binds
 * (temporal property join): the grouping properties of the path, if the aggregation is pushed down, and the property
 * compared with the events (where clause on the next step), so that every partial aggregate and every bound predicate
 * refers to one version (slices without the compared property are discarded: the comparison never holds). The slices go
 * to TS in one request, unless the comparison binds a different value in each of them. GetProjectedProperties is not
 * needed: the time series returns whole events
 */
fun queryTSOpt(ts: TS, index: Int, path: List<ElemP>, from: Long, to: Long, match: List<Step?>, by: List<Aggregate>, mapAlias: Map<String, Int>, mapWhere: Map<String, Compare>): List<ExploredPath> {
    val aggregate = getAggregate(index, by, match)
    val grouping = if (aggregate.isEmpty()) emptyList()
        else by.filter { it.operator == null && it.property != null }.mapNotNull { a -> mapAlias[a.n]?.takeIf { it < path.size }?.let { path[it] to a.property!! } }
    val nextAlias = match.getOrNull(index + 1)?.alias
    val c = nextAlias?.let { mapWhere[it] }
    val compared = if (c == null) null else mapAlias[(if (c.first == nextAlias) c.second else c.first).toString()]?.takeIf { it < path.size }?.let { path[it] to c.property }
    val slices = temporalPropertyJoin(grouping + listOfNotNull(compared), from, to).filter { compared == null || it.second.last() != null }.map { it.first }
    return if (slices.size > 1 && compared == null) {
        getTSEvents(ts, getPredicates(index, path, from, to, match, mapAlias, mapWhere), aggregate, by.isNotEmpty(), slices)
            .map { n -> slices.first { timeOverlap(it.first, it.second, n.fromTimestamp, n.fromTimestamp) }.let { (f, t) -> ExploredPath(n, index + 1, path, f, t) } }
    } else {
        slices.flatMap { (f, t) ->
            getTSEvents(ts, getPredicates(index, path, f, t, match, mapAlias, mapWhere), aggregate, by.isNotEmpty()).map { ExploredPath(it, index + 1, path, f, t) }
        }
    }
}

/**
 * GETTSEVENTS: the events of the time series [ts] satisfying the [predicates] (Θ), aggregated by [aggregate] (Γ) if any;
 * on several consecutive [intervals], in one request (each result in one of them)
 */
fun getTSEvents(ts: TS, predicates: List<Filter>, aggregate: List<Aggregate>, isGroupBy: Boolean, intervals: List<Pair<Long, Long>>? = null): List<N> =
    if (intervals == null) ts.getValues(aggregate, predicates, isGroupBy) else ts.getValues(aggregate, predicates, isGroupBy, intervals)

/** GETAGGREGATE: the aggregation (Γ) on the events of the next step, which TS can compute */
fun getAggregate(index: Int, by: List<Aggregate>, match: List<Step?>): List<Aggregate> {
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

/** GETPREDICATES: the predicates (Θ) on the events of the next step, with the validity of the tuple and the bound values of the where clause */
fun getPredicates(index: Int, curPath: List<ElemP>, from: Long, to: Long, match: List<Step?>, mapAlias: Map<String, Int>, mapWhere: Map<String, Compare>): List<Filter> {
    val filters = mutableListOf<Filter>()
    if (from != Long.MIN_VALUE) filters += Filter(FROM_TIMESTAMP, Operators.GTE, from) // add the from filter
    if (to != Long.MAX_VALUE) filters += Filter(TO_TIMESTAMP, if (from == to) Operators.LTE else Operators.LT, to) // add the to filter ([t, t] is the instant t, as in timeOverlap)
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

fun whereClause(e: ElemP, path: List<ElemP>, alias: String, mapWhere: Map<String, Compare>, c: Compare, timeaware: Boolean, mapAlias: Map<String, Int>, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): Boolean {
    return when (alias) {
        mapWhere[alias]?.first -> c.isOk(e, path[mapAlias[c.second]!!], timeaware, from, to)
        mapWhere[alias]?.second -> c.isOk(path[mapAlias[c.first]!!], e, timeaware, from, to)
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
    // All the comparisons are bound to the same alias, and only the last one would be kept: fail instead of ignoring them
    if (where.size > 1) throw UnsupportedOperationException("At most one comparison (where) between the elements of the same pattern: $where") else where
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
                        && (compare == null || whereClause(cur.e, cur.path, alias, mapWhere, compare, timeaware, mapAlias, cur.from, cur.to))

}

fun updatePath(cur: ExploredPath): Triple<Long, Long, List<ElemP>> {
    val newFrom = max(cur.e.fromTimestamp, cur.from)
    val newTo = min(cur.e.toTimestamp, cur.to)
    val newPath = cur.path + cur.e
    return Triple(newFrom, newTo, newPath)
}

fun isCompletePath(path: List<Elem>, match: List<Step?>): Boolean =
    path.size == match.size

/** EXPAND from the node of [cur]: its edges (with the direction of the next step) */
fun expand(cur: ExploredPath, path: List<ElemP>, from: Long, to: Long, match: List<Step?>): List<ExploredPath> {
    val nextStep = match.getOrNull(cur.index + 1)
    return (cur.e as N)
        .getEdges(
            direction = if (nextStep is EdgeStep) nextStep.direction else Direction.OUT,
            includeHasTs = true
        )
        .map { ExploredPath(it, cur.index + 1, path, from, to) }
}

/**
 * The thread pools of [search], one per number of threads, shared by all the queries: daemon threads, created once.
 * Before, each search created its pool (and its threads) and closed it at the end, which costs more than a short query.
 * In the paper: the fixed-size thread pool of the "Query Service" (multi-threaded query execution).
 */
private val searchPools = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.ExecutorCoroutineDispatcher>()

/**
 * The pool of [search] with [threads] threads, created at the first query with that number of threads. Bounded: at most
 * [threads] threads, whatever the number of queries (also concurrent ones, which share it). The threads are daemons:
 * the pool is never closed, and it does not keep the JVM alive. Only search uses it: join keeps its own pool, since its
 * tasks run search and wait for it (with a shared fixed pool, they could take all its threads and wait forever)
 */
fun searchPool(threads: Int): kotlinx.coroutines.ExecutorCoroutineDispatcher = searchPools.computeIfAbsent(threads) { n ->
    val count = AtomicInteger() // the threads are named stgraph-search-<threads>-<i> (e.g., to find them in a thread dump)
    Executors.newFixedThreadPool(n) { r -> Thread(r, "stgraph-search-$n-${count.incrementAndGet()}").apply { isDaemon = true } }.asCoroutineDispatcher()
}

fun search(g: Graph, match: List<Step?>, where: List<Compare> = emptyList(), from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE, timeaware: Boolean = false, by: List<Aggregate> = emptyList(), threads: Int = 1, mode: QueryMode = QueryMode.OPTIMIZED): List<Path> {
    val executor = searchPool(threads)
    val wait = Semaphore(0) // Mutex(locked = true)
    val completed = AtomicInteger(0)
    var launched = 0
    // The first failure while reading a time series: the query fails with it once the search ends (failing inside the
    // coroutine would cancel the tasks not started yet, which would never release the semaphore)
    val failure = AtomicReference<Exception?>(null)

    val acc = mutableListOf<Path>()
    val mapAlias = buildAliasMap(match)
    val mapWhere = buildWhereMap(where, mapAlias)
    val queue = initializeQueue(g, from, to)

    // The executor is shared by the queries with the same number of threads (see searchPool): not closed
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
                // SELECTION after the PROJECTION of the properties it reads (σ ∘ π, see select): one tuple per slice of time
                // in which the filters of the step and the comparison of the where clause hold, none if they never hold
                val skip = if (mode == QueryMode.OPTIMIZED) by.filter { it.operator != null }.map { it.property } else emptyList()
                for ((tFrom, tTo) in select(curElem, curPath, newFrom, newTo, match, mapAlias, mapWhere, timeaware, skip)) {
                    if (isCompletePath(curPath, match)) { // the tuple has completed the query plan
                        acc.add(Path(curPath, tFrom, tTo))
                    } else if (curElem.index % 2 == 0) { // EXPAND from a node: its edges
                        queue.addAll(expand(curElem, curPath, tFrom, tTo, match))
                    } else if (curElem.e.label.name == HasTS) { // EXPAND towards TS: QueryTS (NAIVE) or QueryTSOpt (OPTIMIZED)
                        val r = curElem.e as R
                        launched++
                        launch(executor) {
                            try {
                                val ts = g.getTSM().getTS(r.toN, mode = TsMode.READ)
                                queue.addAll(
                                    if (mode == QueryMode.NAIVE) queryTS(ts, curElem.index, curPath, tFrom, tTo)
                                    else queryTSOpt(ts, curElem.index, curPath, tFrom, tTo, match, by, mapAlias, mapWhere)
                                )
                            } catch (e: Exception) {
                                failure.compareAndSet(null, e)
                            } finally {
                                completed.incrementAndGet()
                                wait.release()
                            }
                        }
                    } else { // EXPAND from an edge: its node
                        val r = curElem.e as R
                        val step = match[curElem.index]
                        val n = g.getNode(if (step is EdgeStep && step.direction === Direction.IN) r.fromN else r.toN)
                        queue.add(ExploredPath(n, curElem.index + 1, curPath, tFrom, tTo))
                    }
                }
            }
        }
    }

    failure.get()?.let { throw it }
    return acc
}