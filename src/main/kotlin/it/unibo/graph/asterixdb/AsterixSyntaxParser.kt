package it.unibo.graph.asterixdb

import it.unibo.graph.interfaces.*
import it.unibo.graph.query.Filter
import it.unibo.graph.query.Operators
import it.unibo.graph.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.io.WKTWriter
import org.locationtech.jts.io.geojson.GeoJsonReader
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter


sealed class AsterixDBResult {
    data class SelectResult(val entities: JSONArray) : AsterixDBResult()
    data class GroupByResult(val entities: JSONArray) : AsterixDBResult()
    object InsertResult : AsterixDBResult()
}

fun hashMapToGeometry(location: Map<String, Any>): Geometry? {
    val type = location["type"] as? String ?: return null
    val coordinates = location["coordinates"] as? List<*> ?: return null

    val wkt = when (type.uppercase()) {
        "POINT" -> {
            val x = coordinates[0]
            val y = coordinates[1]
            val z = if (coordinates.size > 2) coordinates[2] else 0
            "POINT($x $y $z)"
        }

        "POLYGON" -> {
            val points = (coordinates[0] as List<List<Double>>).joinToString(",") { (lon, lat) -> "$lon $lat" }
            "POLYGON(($points))"
        }

        else -> throw IllegalArgumentException("Unknown geometry type: $type")
    }
    val reader = WKTReader()
    return reader.read(wkt)
}

fun parseProp(it: HashMap<*, *>, fromTimestamp: Long, toTimestamp: Long, key: String, g: Graph): P {
    fun parsePropType(key: String, value: Any): Pair<PropType, Any> {
        return if (key == LOCATION) {
            Pair(PropType.GEOMETRY, hashMapToGeometry(value as HashMap<String, Any>)!!)
        } else {
            when (value) {
                is Int -> Pair(PropType.INT, value)
                is Long -> Pair(PropType.LONG, value)
                is Double -> Pair(PropType.DOUBLE, value)
                is HashMap<*, *> -> {
                    if (it.keys.contains(listOf("type","coordinates"))) {
                        Pair(PropType.GEOMETRY, hashMapToGeometry(value as HashMap<String, Any>)!!)
                    } else {
                        Pair(PropType.STRING, value.toString())
                    }
                }

                is String -> Pair(PropType.STRING, value)
                else -> throw IllegalArgumentException("Unrecognized prop type: $value")
            }
        }
    }

    val propType = parsePropType(key, it[key]!!)
    return P(
        DUMMY_ID,
        sourceId = DUMMY_ID,
        key = key,
        value = propType.second,
        type = propType.first,
        sourceType = NODE,
        g = g,
        fromTimestamp = fromTimestamp,
        toTimestamp = toTimestamp
    )
}

fun dateToTimestamp(date: String): Long {
    val formatterWithMillis = DateTimeFormatter.ofPattern("yyyy-MM-dd' 'HH:mm:ss")
    val formatterWithMillis2 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    var localDateTime: LocalDateTime
    try {
        localDateTime = LocalDateTime.parse(date, formatterWithMillis2)
    } catch (_: Exception) {
        localDateTime = LocalDateTime.parse(date, formatterWithMillis)
    }
    val instant = localDateTime.toInstant(ZoneOffset.UTC)
    return instant.toEpochMilli()
}

private fun jsonToValue(value: Any, type: PropType): Any {
    return when (type) {
        PropType.GEOMETRY -> GeoJsonReader().read(value as String)
        else -> value
    }
}

fun edgesToAsterixCitizen(edges: List<R>, isUpdate: Boolean): String {
    return when (edges.size) {
        0 -> ""
        else -> """"$EDGES": [${edges.joinToString(",", transform = { e-> edgeToJson(e, isUpdate)})}],"""
    }
}

fun propertiesToAsterixCitizen(props: List<P>, isUpdate: Boolean): String =
    props.joinToString(",", transform = { p -> propToJson(p, isUpdate) }).let { if (it.isNotEmpty()) "$it," else "" }

fun edgeToJson(edge: R, isUpdate: Boolean): String {
    return """{"$LABEL": ${edge.label.ordinal},${propertiesToAsterixCitizen(edge.properties, isUpdate)}"$FROM_N": ${edge.fromN},"$TO_N": ${edge.toN}}"""
}

fun propToJson(property: P, isUpdate: Boolean): String {
    return if (property.key == LOCATION) {
        "\"${property.key}\": ${valueToJson(property.value, isUpdate)}"
    } else {
        """"${property.key}": {"$VALUE": ${valueToJson(property.value, isUpdate)},"$TYPE": ${property.type.ordinal}}"""
    }
}

fun jsonToProp(json: JSONObject, sourceId: Long, sourceType: Boolean, key: String, fromTimestamp: Long, toTimestamp: Long, g: Graph): P {
    val value: Any
    val type: PropType
    if (key == LOCATION) {
        type = PropType.GEOMETRY
        value = jsonToValue(json.toString(), type)
    } else {
        type = PropType.entries[json.getInt(TYPE)]
        value = jsonToValue(json.get(VALUE), type)
    }

    return P(id = DUMMY_ID, sourceId = sourceId, sourceType = sourceType, key = key, value = value, type = type, fromTimestamp = fromTimestamp, toTimestamp = toTimestamp, g = g)
}

fun jsonToEdge(json: JSONObject, fromTimestamp: Long, toTimestamp: Long, g: Graph): R {
    val id = DUMMY_ID
    val edge = R(id = id, label = Label.entries[json.getInt(LABEL)]!!, fromN = json.getLong(FROM_N), toN = json.getLong(TO_N), fromTimestamp = fromTimestamp, toTimestamp = toTimestamp, g = g)
    json.keys()
        .forEach { key ->
            if (!listOf(ID, LABEL, FROM_N, TO_N).contains(key)) {
                jsonToProp(json.getJSONObject(key), sourceId = id, sourceType = EDGE, key = key, fromTimestamp = fromTimestamp, toTimestamp = toTimestamp, g)
            }
        }
    return edge
}

fun nodeToJson(n: N, isUpdate: Boolean, tsId: Long): String {
    return """{"$TSID": $tsId, "$ID": ${n.fromTimestamp}, "$LABEL": ${n.label.ordinal}, ${edgesToAsterixCitizen(n.edges, isUpdate)} ${propertiesToAsterixCitizen(n.properties, isUpdate)} "$VALUE": ${n.value ?: 0}}"""
}

fun jsonToNode(tsId: Long, g: Graph, node: JSONObject): N {
    val timestamp: Long
    val id: Long
    if (node.has(ID)) {
        timestamp = node.getLong(ID)
        id = encodeBitwise(tsId, timestamp)
    } else {
        id = DUMMY_ID
        timestamp = DUMMY_ID
    }

    val fromTimestamp = if (node.has(FROM_TIMESTAMP)) node.getLong(FROM_TIMESTAMP) else timestamp
    val toTimestamp = if (node.has(TO_TIMESTAMP)) node.getLong(TO_TIMESTAMP) else timestamp

    val entity = N(
        id = id,
        label = Label.entries[node.getInt(LABEL)]!!,
        fromTimestamp = fromTimestamp,
        toTimestamp = toTimestamp,
        value = if (id != DUMMY_ID) node.getLong(VALUE) else null,
        g = g
    )
    node.keys()
        .forEach { key ->
            if ((id == DUMMY_ID && key == VALUE) || !listOf(ID, LABEL, VALUE, FROM_TIMESTAMP, TO_TIMESTAMP, TSID).contains(key)) {
                when (key) {
                    EDGES -> node.getJSONArray(EDGES)
                        ?.let { array -> List(array.length()) { array.getJSONObject(it) } }
                        ?.map { jsonToEdge(it, fromTimestamp = fromTimestamp, toTimestamp = toTimestamp, g) }
                        ?.let(entity.edges::addAll)
                    else -> {
                        jsonToProp(node.getJSONObject(key), sourceId = id, sourceType = NODE, key = key, fromTimestamp = fromTimestamp, toTimestamp = toTimestamp, g)
                            .let { entity.properties.add(it) }
                    }
                }
            }
        }
    return entity
}

fun valueToJson(value: Any, isUpdate: Boolean): String {
    return when (value) {
        is Int -> "$value"
        is Long -> "$value"
        is Double -> "$value"
        is Geometry -> if (isUpdate) "st_geom_from_text(\"${WKTWriter().write(value)}\")" else "\"${WKTWriter().write(value)}\""
        else -> "\"$value\""
        // else -> throw IllegalArgumentException("Unknown prop type ${value::class.qualifiedName}")
    }
}

fun applyFilters(filters: List<Filter>): String {
    return when {
        filters.isEmpty() -> ""
        else -> "WHERE ${filters.joinToString(separator = " AND ", transform = ::parseFilter)}"
    }
}

private fun parseFilter(filter: Filter): String {
    fun parseOperator(op: Operators): String = when (op) {
        Operators.EQ -> "="
        Operators.LT -> "<"
        Operators.GT -> ">"
        Operators.LTE -> "<="
        Operators.GTE -> ">="
        Operators.ST_CONTAINS -> "st_contains"
        Operators.ST_INTERSECTS -> "st_intersects"
    }

    fun normalizeWkt(wkt: String): String? {
        val clean = wkt.trim().removeSurrounding("\"").removeSurrounding("'")
        val wktPattern = Regex(
            """^(POINT|LINESTRING|POLYGON|MULTIPOINT|MULTILINESTRING|MULTIPOLYGON|GEOMETRYCOLLECTION)\s*\(.*\)$""",
            RegexOption.IGNORE_CASE
        )
        return if (wktPattern.matches(clean)) clean else null
    }

    val property = filter.property
    val parsedValue = valueToJson(filter.value, isUpdate = false)
    val left = if (filter.attrFirst) property else parsedValue
    val right = if (filter.attrFirst) parsedValue else property
    return when (filter.operator) {
        Operators.ST_CONTAINS, Operators.ST_INTERSECTS -> {
            val arg1Raw = remove3DfromWkt(if (filter.attrFirst) right else left)
            val arg2Raw = remove3DfromWkt(if (filter.attrFirst) left else right)
            val arg1 = normalizeWkt(arg1Raw)?.let { "st_geom_from_text('$it')" } ?: arg1Raw
            val arg2 = normalizeWkt(arg2Raw)?.let { "st_geom_from_text('$it')" } ?: arg2Raw
            "${parseOperator(filter.operator)}($arg1, $arg2)"
        }
        else -> "$left ${parseOperator(filter.operator)} $right"
    }
}