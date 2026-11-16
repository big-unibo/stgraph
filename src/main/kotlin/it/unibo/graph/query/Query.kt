package it.unibo.graph.query

import it.unibo.graph.interfaces.*
import it.unibo.graph.utils.*
import kotlinx.coroutines.asCoroutineDispatcher
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
    @Suppress("NAME_SHADOWING") val to = halfOpen(from, to) // an instant [t, t] as [t, t + 1)
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
    return aggregation(result, by, mapAliases, match)
}

/**
 * JOIN of the tuples of the first pattern ([pattern1]) with the second pattern (match[1]). For each tuple, the join
 * comparisons ([joinFilters]) become filters of the steps of the second pattern, one plan per version of the compared
 * properties of the tuple (with the validity of the version), and the second pattern is searched once per plan. No
 * recursion and no pool of its own: the tuples are joined one at a time by the calling thread, and the searches use the
 * shared pool (see searchPool), so a query never uses more than [threads] threads.
 * E.g., a.name = b.name with a.name = x in [0, 2) and y in [2, 4): search b with name = x in [0, 2) and name = y in [2, 4)
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
    val where2 = (where - joinFilters).filter { mapAliases[it.first]!!.first == 1 } // the comparisons within the second pattern
    return pattern1.flatMap { row ->
        // The plans of the second pattern for this tuple: (steps, from, to), one per combination of versions of the compared properties
        val plans = match[1].fold(listOf(Triple(listOf<Step?>(), max(from, row.from), min(to, row.to)))) { plans, step ->
            val c = joinFilters.filter { step != null && (step.alias == it.first || step.alias == it.second) }
            // Only one comparison per step would be applied: fail instead of ignoring the others
            if (c.size > 1) throw UnsupportedOperationException("At most one join comparison (where) per step: $c")
            plans.flatMap { (steps, f, t) ->
                if (c.isEmpty()) return@flatMap listOf(Triple(steps + step, f, t))
                val j = c.first()
                val other = if (step!!.alias != j.first) j.first else j.second // the alias in the first pattern
                row.result[mapAliases[other]!!.second].getProps(name = j.property, fromTimestamp = f, toTimestamp = t, timeaware = timeaware).map { p ->
                    val filter = Filter(j.property, j.operator, p.value, attrFirst = mapAliases[j.first]!!.first == 1)
                    Triple(steps + Step(step.label, step.properties + filter, step.alias), max(f, p.fromTimestamp), min(t, p.toTimestamp))
                }
            }
        }
        // PROJECTION before the join: with an aggregation pushed down in the second pattern, the grouping properties of
        // the first one, so that each search of the second pattern binds one version of them (temporal property join)
        val grouping = if (mode == QueryMode.OPTIMIZED && by.any { it.operator != null && mapAliases[it.n]?.first == 1 })
            by.filter { it.operator == null && it.property != null && mapAliases[it.n]?.first == 0 }.map { row.result[mapAliases[it.n]!!.second] to it.property!! }
            else emptyList()
        plans.flatMap { (steps, f, t) ->
            temporalPropertyJoin(grouping, f, t).flatMap { (slice, _) ->
                search(g, steps, where2, slice.first, slice.second, timeaware, by = by, threads, mode).map { Path(row.result + it.result, it.from, it.to) }
            }
        }
    }
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
                // Valid at the same time: the intersection of their validity
                if (slice != null && !timeOverlap(interval.first, interval.second, slice.first, slice.second)) return@mapNotNull null
                val i = if (slice == null) interval else max(interval.first, slice.first) to min(interval.second, slice.second)
                // The element replaced by a virtual one, with these versions of its properties (null if none)
                val props = aggregates.zip(v).map { (a, p) -> p ?: P(DUMMY_ID, sourceType = NODE, key = a.property!!, value = "null", type = PropType.NULL, fromTimestamp = i.first, toTimestamp = i.second, sourceId = DUMMY_ID, g = e.g) }
                val virtual = if (e is N) N.createVirtualN(e.label, props, i.first, i.second, e.g) else R.createVirtualR(e.label, props, i.first, i.second, e.g)
                elements.toMutableList().apply { this[index] = virtual } to i
            }
        }
    }
    return tuples.map { (elements, interval) -> Path(elements, interval.first, interval.second) }
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
 * AGGREGATION (γ) of the tuples [rows], projected on the properties of [by] (see project): grouped by the properties
 * without operator, with the property with an operator (one at most) aggregated by aggregateNumbers (which also combines
 * the partial aggregates computed by TS). Without operator, the groups; without [by], the tuples themselves. As Neo4J
 * returns them: [[a, 12.5], [b, 13.0], ...], or [a, b, ...] with a single property
 */
private fun aggregation(rows: List<Path>, by: List<Aggregate>, mapAliases: Map<String, Pair<Int, Int>>, match: List<List<Step?>>): List<Any> {
    if (by.isEmpty()) return rows
    val operators = by.filter { it.operator != null }
    if (operators.size > 1) throw IllegalArgumentException("More than one aggregation operator")
    fun value(row: Path, a: Aggregate): Any { // the value of the property of [a] in the projected tuple (a single version)
        val alias = mapAliases[a.n]!!
        val properties = row.result[alias.second + (if (alias.first == 1) match[0].size else 0)].getProps(name = a.property)
        if (properties.size != 1) throw IllegalArgumentException("Properties should be 1: $properties")
        return properties.first().value
    }
    return rows.groupBy { row -> by.filter { it.operator == null }.map { value(row, it) } }.map { (key, group) ->
        val values = key + operators.map { a -> aggregateNumbers(group.map { value(it, a) }, a.operator!!, lastAggregation = true) }
        if (by.size == 1) values else listOf(values)
    }.flatten()
}

/**
 * TEMPORAL PROPERTY JOIN (Algorithm 1) of the properties [keys] of the elements of a tuple valid in [[from], [to]), on any
 * number of properties: the slices of time in which every property keeps the same value, each with its validity and its
 * versions (null where a property has none); consecutive slices with the same values are merged (e.g., ML in [2, 4) and
 * ML in [4, 6): [2, 6)). Extended with them, the tuple has the validity of the slice (the intersection of the validity of
 * its elements). Used by the projection (see project), also before the operators that read time-varying properties: the
 * selection (see select), the expansion towards TS with pushdown (see queryTSOpt), and the join.
 * E.g., a.type = ML in [0, 2), HV in [2, 4), [from, to) = [MIN, MAX): [MIN, 0) null, [0, 2) ML, [2, 4) HV, [4, MAX) null.
 * Without properties, the whole [from, to)
 */
fun temporalPropertyJoin(keys: List<Pair<ElemP, String>>, from: Long, to: Long): List<Pair<Pair<Long, Long>, List<P?>>> {
    val versions = keys.map { (e, key) -> e.getProps(name = key, fromTimestamp = from, toTimestamp = to) } // the versions of each property in the tuple
    // The bounds of the versions (within the tuple) and of the tuple: no version starts or ends inside a slice
    val bounds = (versions.flatten().flatMap { listOf(it.fromTimestamp, it.toTimestamp) }.filter { it in from..to } + from + to).toSortedSet().toList()
    val merged = mutableListOf<Pair<Pair<Long, Long>, List<P?>>>()
    bounds.zipWithNext().forEach { (f, t) ->
        val slice = versions.map { vs -> vs.firstOrNull { it.fromTimestamp <= f && t <= it.toTimestamp } } // the version of each property in [f, t)
        val last = merged.lastOrNull()
        if (last != null && last.first.second == f && last.second.map { it?.value } == slice.map { it?.value }) {
            merged[merged.size - 1] = (last.first.first to t) to last.second // consecutive slices with the same values: a single slice
        } else merged += (f to t) to slice
    }
    return merged
}

/**
 * SELECTION (σ), the only evaluation of the predicates, on the properties of the last element of the tuple [cur] (the
 * filters of its step, and the comparison of the where clause with an element already in the tuple), after their
 * temporal property join (π): the slices of the validity of the tuple in which they hold. The tuple holds only while its properties
 * satisfy the predicate (continuous semantics), so it continues once per slice, or not at all. Without time
 * ([timeaware] = false), the whole validity if some version of the properties satisfies it. The whole validity without
 * predicates. The filters on the properties aggregated by the time series ([skip]: OPTIMIZED, already applied by TS
 * before aggregating, see queryTSOpt) are not evaluated on the partial aggregates
 */
fun select(cur: ExploredPath, match: List<Step?>, mapAlias: Map<String, Int>, mapWhere: Map<String, Compare>, timeaware: Boolean, skip: List<String?>): List<Pair<Long, Long>> {
    val path = cur.path
    val step = match[cur.index]
    val filters = step?.properties.orEmpty().filter { it.property !in skip }
    val c = step?.alias?.let { mapWhere[it] } // the comparison of the where clause, with an element already in the path
    if (filters.isEmpty() && c == null) return listOf(cur.from to cur.to) // no predicate
    val compared = if (c == null) emptyList() else listOf(path[mapAlias[c.first.toString()]!!] to c.property, path[mapAlias[c.second.toString()]!!] to c.property)
    if (!timeaware) { // without time: some version of each property satisfies the predicate
        val holds = filters.all { f -> cur.e.getProps(name = f.property, timeaware = false).any { f.holds(it.value) } } &&
            (c == null || c.isOk(compared[0].first, compared[1].first, timeaware = false))
        return if (holds) listOf(cur.from to cur.to) else emptyList()
    }
    val n = filters.size
    return temporalPropertyJoin(filters.map { cur.e to it.property } + compared, cur.from, cur.to)
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
 * event satisfying the condition of the expansion (label and validity, see extend) extends the tuple (NAIVE)
 */
fun queryTS(ts: TS, index: Int, path: List<ElemP>, from: Long, to: Long, match: List<Step?>, timeaware: Boolean): List<ExploredPath> =
    getTSEvents(ts, emptyList(), emptyList(), false).mapNotNull { extend(it, index + 1, path, from, to, match, timeaware) }

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
fun queryTSOpt(ts: TS, index: Int, path: List<ElemP>, from: Long, to: Long, match: List<Step?>, by: List<Aggregate>, mapAlias: Map<String, Int>, mapWhere: Map<String, Compare>, timeaware: Boolean): List<ExploredPath> {
    val aggregate = getAggregate(index, by, match)
    val grouping = if (aggregate.isEmpty()) emptyList()
        else by.filter { it.operator == null && it.property != null }.mapNotNull { a -> mapAlias[a.n]?.takeIf { it < path.size }?.let { path[it] to a.property!! } }
    val nextAlias = match.getOrNull(index + 1)?.alias
    val c = nextAlias?.let { mapWhere[it] }
    val compared = if (c == null) null else mapAlias[(if (c.first == nextAlias) c.second else c.first).toString()]?.takeIf { it < path.size }?.let { path[it] to c.property }
    val slices = temporalPropertyJoin(grouping + listOfNotNull(compared), from, to).filter { compared == null || it.second.last() != null }.map { it.first }
    return if (slices.size > 1 && compared == null) {
        getTSEvents(ts, getPredicates(index, path, from, to, match, mapAlias, mapWhere), aggregate, by.isNotEmpty(), slices)
            .mapNotNull { n -> slices.first { timeOverlap(it.first, it.second, n.fromTimestamp, n.fromTimestamp) }.let { (f, t) -> extend(n, index + 1, path, f, t, match, timeaware) } }
    } else {
        slices.flatMap { (f, t) ->
            getTSEvents(ts, getPredicates(index, path, f, t, match, mapAlias, mapWhere), aggregate, by.isNotEmpty()).mapNotNull { extend(it, index + 1, path, f, t, match, timeaware) }
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
    if (to != Long.MAX_VALUE) filters += Filter(FROM_TIMESTAMP, Operators.LT, to) // add the to filter (the events starting in [from, to))
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

/**
 * A tuple ([path]) valid in [[from], [to]] (the intersection of the validity of its elements), whose last element [e]
 * matches the step [index]: queued only once valid (see extend)
 */
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

/** The tuples of the first step: the nodes of the graph matching it (see extend), valid in [[from], [to]] */
fun initializeQueue(g: Graph, from: Long, to: Long, match: List<Step?>, timeaware: Boolean): Queue<ExploredPath> {
    val acc = ConcurrentLinkedQueue<ExploredPath>()
    acc.addAll(g.getNodes().mapNotNull { extend(it, 0, emptyList(), from, to, match, timeaware) })
    return acc
}

/**
 * The condition of EXPAND (θ and the temporal condition), also for the first step: the tuple [path] (valid in [[from],
 * [to]]) extended by the element [e] at the step [index] if [e] has the label of the step (the label of the edge or of
 * the node of the pattern) and its validity overlaps that of the tuple, valid in their intersection; null otherwise
 */
fun extend(e: ElemP, index: Int, path: List<ElemP>, from: Long, to: Long, match: List<Step?>, timeaware: Boolean): ExploredPath? {
    val label = match.getOrNull(index)?.label
    if (label != null && label != e.label.name || !e.timeOverlap(timeaware, from, to)) return null
    return ExploredPath(e, index, path + e, max(from, e.fromTimestamp), min(to, e.toTimestamp))
}

fun isCompletePath(path: List<Elem>, match: List<Step?>): Boolean =
    path.size == match.size

/**
 * EXPAND from the node of the tuple [cur] (restricted to [[from], [to]] by the selection): its edges in the direction of
 * the next step, extending the tuple if they satisfy the condition of the expansion (see extend)
 */
fun expand(cur: ExploredPath, from: Long, to: Long, match: List<Step?>, timeaware: Boolean): List<ExploredPath> {
    val nextStep = match.getOrNull(cur.index + 1)
    return (cur.e as N)
        .getEdges(direction = if (nextStep is EdgeStep) nextStep.direction else Direction.OUT, includeHasTs = true)
        .mapNotNull { extend(it, cur.index + 1, cur.path, from, to, match, timeaware) }
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
 * the pool is never closed, and it does not keep the JVM alive. Also the joins use it, through search: join itself
 * runs on the calling thread (its tasks would wait for search, and could take all the threads of a shared pool)
 */
fun searchPool(threads: Int): kotlinx.coroutines.ExecutorCoroutineDispatcher = searchPools.computeIfAbsent(threads) { n ->
    val count = AtomicInteger() // the threads are named stgraph-search-<threads>-<i> (e.g., to find them in a thread dump)
    Executors.newFixedThreadPool(n) { r -> Thread(r, "stgraph-search-$n-${count.incrementAndGet()}").apply { isDaemon = true } }.asCoroutineDispatcher()
}

fun search(g: Graph, match: List<Step?>, where: List<Compare> = emptyList(), from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE, timeaware: Boolean = false, by: List<Aggregate> = emptyList(), threads: Int = 1, mode: QueryMode = QueryMode.OPTIMIZED): List<Path> {
    @Suppress("NAME_SHADOWING") val to = halfOpen(from, to) // an instant [t, t] as [t, t + 1)
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
    val queue = initializeQueue(g, from, to, match, timeaware)

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
                val curElem = queue.poll() // a valid tuple (see extend)
                // SELECTION after the PROJECTION of the properties it reads (σ ∘ π, see select): one tuple per slice of time
                // in which the filters of the step and the comparison of the where clause hold, none if they never hold
                val skip = if (mode == QueryMode.OPTIMIZED) by.filter { it.operator != null }.map { it.property } else emptyList()
                for ((tFrom, tTo) in select(curElem, match, mapAlias, mapWhere, timeaware, skip)) {
                    if (isCompletePath(curElem.path, match)) { // the tuple has completed the query plan
                        acc.add(Path(curElem.path, tFrom, tTo))
                    } else if (curElem.index % 2 == 0) { // EXPAND from a node: its edges
                        queue.addAll(expand(curElem, tFrom, tTo, match, timeaware))
                    } else if (curElem.e.label.name == HasTS) { // EXPAND towards TS: QueryTS (NAIVE) or QueryTSOpt (OPTIMIZED)
                        val r = curElem.e as R
                        launched++
                        launch(executor) {
                            try {
                                val ts = g.getTSM().getTS(r.toN, mode = TsMode.READ)
                                queue.addAll(
                                    if (mode == QueryMode.NAIVE) queryTS(ts, curElem.index, curElem.path, tFrom, tTo, match, timeaware)
                                    else queryTSOpt(ts, curElem.index, curElem.path, tFrom, tTo, match, by, mapAlias, mapWhere, timeaware)
                                )
                            } catch (e: Exception) {
                                failure.compareAndSet(null, e)
                            } finally {
                                completed.incrementAndGet()
                                wait.release()
                            }
                        }
                    } else { // EXPAND from an edge: its node, if it satisfies the condition of the expansion
                        val r = curElem.e as R
                        val step = match[curElem.index]
                        val n = g.getNode(if (step is EdgeStep && step.direction === Direction.IN) r.fromN else r.toN)
                        extend(n, curElem.index + 1, curElem.path, tFrom, tTo, match, timeaware)?.let { queue.add(it) }
                    }
                }
            }
        }
    }

    failure.get()?.let { throw it }
    return acc
}