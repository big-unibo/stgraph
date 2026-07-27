package it.unibo.graph.utils

import it.unibo.graph.interfaces.Elem
import it.unibo.graph.interfaces.Graph
import it.unibo.graph.interfaces.PropType
import java.io.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max
import kotlin.system.measureTimeMillis

// Serialize an object to byte array
fun serialize(obj: Serializable): ByteArray {
    ByteArrayOutputStream().use { bos ->
        ObjectOutputStream(bos).use { out -> out.writeObject(obj) }
        return bos.toByteArray()
    }
}

// Deserialize a byte array to an object
inline fun <reified T : Elem> deserialize(bytes: ByteArray?, g: Graph): T {
    val r: T = ByteArrayInputStream(bytes).use { bis ->
        ObjectInputStream(bis).use { `in` -> `in`.readObject() as T }
    }
    (r as Elem).g = g
    return r
}

fun encodeBitwise(x: Long, y: Long, offset: Int = 44, mask: Long = 0xFFFFFFFFFFF): Long {
    return (x shl offset) or (y and mask)
}

fun decodeBitwise(z: Long, offset: Int = 44, mask: Long = 0xFFFFFFFFFFF): Pair<Long, Long> {
    val x = (z shr offset)
    val y = (z and mask)
    return Pair(x, y)
}

fun decodeBitwiseSource(z: Long, offset: Int = 44): Long {
    return z shr offset
}

fun <A, B> cartesianProduct(list1: List<A>, list2: List<B>): Set<Pair<A, B>> {
    return list1.flatMap { a -> list2.map { b -> a to b } }.toSet()
}

fun propTypeFromValue(value: Any): PropType {
    return when(value){
        is Int -> PropType.INT
        is Long -> PropType.LONG
        is Double -> PropType.DOUBLE
        is HashMap<*, *> -> {
            if (value.keys.contains(listOf("type", "coordinates"))) {
                PropType.GEOMETRY
            } else {
                PropType.STRING
            }
        }

        else -> PropType.STRING
    }
}

fun remove3DfromWkt(wkt: String): String {
    // 1. Rimuove eventuali marker Z, M, ZM
    var result = wkt.replace(Regex("""\bZ[M]?\b"""), "").trim()

    // 2. Cerca solo tuple con 3 o 4 numeri -> le riduce a 2
    result = result.replace(
        Regex("""(-?\d+(?:\.\d+)?)[ ]+(-?\d+(?:\.\d+)?)(?:[ ]+-?\d+(?:\.\d+)?){1,2}""")
    ) { match ->
        val x = match.groupValues[1]
        val y = match.groupValues[2]
        "$x $y"
    }

    return result
}

fun loadProps(): Properties {
    return Properties().apply {
        load(ClassLoader.getSystemResourceAsStream("config.properties"))
    }
}

typealias TemporalRanges = Map<Int, TimeRange>

data class TimeRange(
    val from: Long,
    val to: Long
)

@OptIn(ExperimentalAtomicApi::class)
val atomicPort = AtomicInt(FIRSTFEEDPORT)

@OptIn(ExperimentalAtomicApi::class)
fun incPort(): Int {
    val port = atomicPort.fetchAndAdd(1)
    if (port > LASTFEEDPORT) {
        throw UnsupportedOperationException("Ports are exhausted")
    }
    return port
}

@OptIn(ExperimentalAtomicApi::class)
fun resetPort() {
    atomicPort.store(FIRSTFEEDPORT)
}

fun query(sql: String, host: String, dataverse: String): HttpURLConnection {
    val connection = URI(host).toURL().openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    connection.doOutput = true
    val charset = Charsets.UTF_8
    connection.outputStream.bufferedWriter(charset).use { writer ->
        fun appendParam(key: String, value: String, first: Boolean = false) {
            if (!first) writer.append('&')
            writer.append(URLEncoder.encode(key, charset.name()))
            writer.append('=')
            writer.append(URLEncoder.encode(value, charset.name()))
        }
        appendParam("statement", sql, first = true)
        appendParam("pretty", "true")
        appendParam("mode", "immediate")
        appendParam("dataverse", dataverse)
    }
    return connection
}

fun getTime(f: ()->Unit): Long {
    return max(1, measureTimeMillis { f() })
}