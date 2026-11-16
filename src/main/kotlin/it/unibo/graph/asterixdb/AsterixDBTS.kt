package it.unibo.graph.asterixdb

import it.unibo.graph.interfaces.Graph
import it.unibo.graph.interfaces.Label
import it.unibo.graph.interfaces.N
import it.unibo.graph.interfaces.PropType
import it.unibo.graph.interfaces.TS
import it.unibo.graph.query.AggOperator
import it.unibo.graph.query.Aggregate
import it.unibo.graph.query.Filter
import it.unibo.graph.query.Operators
import it.unibo.graph.utils.*
import kotlinx.coroutines.DelicateCoroutinesApi
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.PrintWriter

@OptIn(DelicateCoroutinesApi::class)
/**
 * The interval of the partial aggregates of a request on several intervals (see AsterixDBTS.getValues): the alias of the
 * CASE expression in the GROUP BY, i.e., the index of the interval of the events. Removed from the results (it is not a
 * property)
 */
private const val BUCKET = "stgraphBucket"

class AsterixDBTS(
    override val g: Graph,
    val id: Long,
    private val dataverse: String,
    private val dataset: String,
    val connection: AsterixDBHTTPClient
) : TS {

    override fun getTSId(): Long = id

    override fun add(n: N, isUpdate: Boolean, flush: Boolean): N {
        if (isUpdate) {
            updateTs(n)
        } else {
            val event = nodeToJson(n, isUpdate = false, tsId = id)
            connection.writeToFeed(event, flush)
        }
        return n
    }

    val firstCitizens = listOf(ID, LABEL, VALUE, EDGES, LOCATION, FROM_TIMESTAMP, TO_TIMESTAMP, TSID)
    fun removeAlias(s: String): String {
        val index = s.indexOf(" as")
        return if (index == -1) s else s.substring(0, index)
    }

    fun replacePropertyName(property: String, alias: Boolean = true): String {
        return if (!firstCitizens.contains(property)) {
            "$property.$VALUE" + (if (alias) " as $property" else "")
        } else {
            property
        }
    }

    override fun getValues(by: List<Aggregate>, filters: List<Filter>, isGroupBy: Boolean): List<N> = select(by, filters, isGroupBy, emptyList())

    /**
     * An aggregation on several [intervals] in one request: the events are also grouped by interval (CASE on the timestamp).
     * E.g., [by] = sum(value), [intervals] = [MIN, 2), [2, 4), [4, MAX):
     *   SELECT ..., SUM(value), MIN(id) AS from, MAX(id) AS to FROM ... WHERE tsId = ...
     *   GROUP BY tsId, CASE WHEN id < 2 THEN 0 WHEN id < 4 THEN 1 ELSE 2 END AS stgraphBucket
     * returns one partial aggregate per non-empty interval (as one request per interval, see TS.getValues), with a single
     * scan of the events instead of one request (and job) per interval
     */
    override fun getValues(by: List<Aggregate>, filters: List<Filter>, isGroupBy: Boolean, intervals: List<Pair<Long, Long>>): List<N> =
        // Without aggregation (the events themselves, each in its interval) or with a single interval: the usual requests
        if (by.isEmpty() || intervals.size <= 1) super.getValues(by, filters, isGroupBy, intervals)
        else select(by, filters + listOfNotNull( // the events of all the intervals: from the start of the first one...
            intervals.first().first.takeIf { it != Long.MIN_VALUE }?.let { Filter(FROM_TIMESTAMP, Operators.GTE, it) },
            intervals.last().let { (f, t) -> t.takeIf { it != Long.MAX_VALUE }?.let { Filter(TO_TIMESTAMP, if (f == t) Operators.LTE else Operators.LT, it) } } // ... to the end of the last one
        ), isGroupBy, intervals.dropLast(1).map { (f, t) -> if (f == t) t + 1 else t }) // ... split at the end of each interval but the last (after t for the instant [t, t])

    /** [buckets]: the upper bounds of the intervals but the last, whose events are aggregated separately */
    private fun select(by: List<Aggregate>, filters: List<Filter>, isGroupBy: Boolean, buckets: List<Long>): List<N> {
        var selectQuery: String

        val filters: List<Pair<String, Filter>> = (filters + Filter(TSID, Operators.EQ, id))
                .map { if (it.property == FROM_TIMESTAMP || it.property == TO_TIMESTAMP) Filter(ID, it.operator, it.value, it.attrFirst) else it } // rename id into TO/FROM TIMESTAMP
                .map { if (it.property == LABEL) Filter(LABEL, it.operator, (it.value as Label).ordinal, it.attrFirst) else it } // rename id into TO/FROM TIMESTAMP
                .map { f ->
                        if (!firstCitizens.contains(f.property)) {
                            Pair(f.property,  Filter(f.property + ".$VALUE", f.operator, f.value, f.attrFirst))
                        } else {
                            Pair(f.property, f)
                        }
                    }

        val groupby: MutableSet<String> =
            by
                .filter { it.operator == null }
                .map { f -> f.property!! }
                .toMutableSet()
        groupby += filters
            .filter { f -> !by.map { it.property }.contains(f.second.property) } // cannot select an attribute that I should also aggregate (e.g., SELECT value, SUM(value) ... GROUP BY value)
            .filter { it.first != ID }.map { (orig, _) -> orig }
        // groupby += listOf(LABEL)

        val aggregators: MutableSet<String> =
            by
                .filter { it.operator != null }
                .map { f -> "${(if (f.operator == AggOperator.AVG) AggOperator.SUM else f.operator)?.name}(${ replacePropertyName(f.property!!, alias = false) }) as ${f.property}" }.toMutableSet()
        if (!groupby.any { it == LABEL })
            aggregators += listOf("MIN($LABEL) as $LABEL")
        aggregators += listOf("COUNT(*) as $COUNT", "MIN($ID) as $FROM_TIMESTAMP", "MAX($ID) as $TO_TIMESTAMP")

        selectQuery =  "USE $dataverse;"
        if (by.isNotEmpty()) {
            // With buckets, the events are also grouped by interval: the i-th interval (i.e., the first whose end is after
            // the timestamp of the event, since the intervals are sorted and consecutive), the last one otherwise. Empty
            // intervals return no group. The interval is selected (and removed from the results, see BUCKET)
            val bucket = if (buckets.isEmpty()) "" else ", CASE ${buckets.mapIndexed { i, b -> "WHEN $ID < $b THEN $i" }.joinToString(" ")} ELSE ${buckets.size} END AS $BUCKET"
            selectQuery +=  "SELECT ${(groupby + aggregators + listOfNotNull(BUCKET.takeIf { buckets.isNotEmpty() })).joinToString(",")} FROM $dataset ${applyFilters(filters.map { it.second })} GROUP BY ${groupby.joinToString(","){ removeAlias(it).replace(".$VALUE", "") }}$bucket"
        } else {
            selectQuery += "SELECT * FROM $dataset ${applyFilters(filters.map { it.second })}"
            if (isGroupBy) {
                selectQuery += " LIMIT 1"
            }
        }
        selectQuery = quoteValue(selectQuery)
        return when (val result = connection.selectFromAsterixDB(selectQuery, isGroupBy = by.isNotEmpty())) {
            is AsterixDBResult.SelectResult -> {
                if (result.entities.isEmpty) return emptyList()
                result.entities.map { jsonToNode(tsId = id, g = g, node = (it as JSONObject).getJSONObject(dataset)) }
            }
            is AsterixDBResult.GroupByResult -> {
                if (result.entities.isEmpty) return emptyList()
                result.entities
                    .map { it as JSONObject }
                    .map { json ->
                        val aggOperator = by.first { it.operator != null }.property!!
                        json.put(aggOperator, JSONObject().let {
                            it.put(VALUE, Pair((json[aggOperator] as? Number)?.toDouble(), (json[COUNT] as? Number)?.toDouble()))
                            it.put(TYPE, PropType.NULL.ordinal)
                        })
                        json.remove(COUNT)
                        json.remove(BUCKET)
                        jsonToNode(tsId = id, g = g, node = json)
                    }
            }
            else -> throw UnsupportedOperationException(selectQuery)
        }
    }

    override fun get(eventId: Long): N {
        val selectQuery = "USE $dataverse; SELECT * FROM $dataset WHERE $TSID = $id AND $ID = $eventId"
        when (val result = connection.selectFromAsterixDB(selectQuery)) {
            is AsterixDBResult.SelectResult -> {
                if (result.entities.length() > 0) {
                    return jsonToNode(tsId = id, g = g, node = (result.entities[0] as JSONObject).get(dataset) as JSONObject)
                } else {
                    throw IllegalArgumentException("Result is empty")
                }
            }

            else -> throw IllegalArgumentException("Error occurred while performing query \n $selectQuery")
        }
    }

    /**
     * [sql] with the identifier $VALUE (a reserved word of SQL++) between backticks: only as a whole word, outside of the
     * string literals and of the longer identifiers (e.g., not in "values" or in a string value)
     */
    private fun quoteValue(sql: String): String {
        val identifier = Regex("""(?<![\w`])$VALUE(?![\w`])""")
        val literal = Regex(""""(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'""")
        val result = StringBuilder()
        var last = 0
        for (m in literal.findAll(sql)) {
            result.append(sql.substring(last, m.range.first).replace(identifier, "`$VALUE`")).append(m.value)
            last = m.range.last + 1
        }
        return result.append(sql.substring(last).replace(identifier, "`$VALUE`")).toString()
    }

    private fun updateTs(n: N) = connection.updateTs("USE $dataverse; UPSERT INTO $dataset ([${nodeToJson(n, isUpdate = true, tsId = id)}])")
}