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

    override fun getValues(by: List<Aggregate>, filters: List<Filter>, isGroupBy: Boolean): List<N> {
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
            selectQuery +=  "SELECT ${(groupby + aggregators).joinToString(",")} FROM $dataset ${applyFilters(filters.map { it.second })} GROUP BY ${groupby.joinToString(","){ removeAlias(it).replace(".$VALUE", "") }}"
        } else {
            selectQuery += "SELECT * FROM $dataset ${applyFilters(filters.map { it.second })}"
            if (isGroupBy) {
                selectQuery += " LIMIT 1"
            }
        }
        selectQuery = selectQuery.replace(VALUE, "`$VALUE`")
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

    private fun updateTs(n: N) = connection.updateTs("USE $dataverse; UPSERT INTO $dataset ([${nodeToJson(n, isUpdate = true, tsId = id)}])")
}