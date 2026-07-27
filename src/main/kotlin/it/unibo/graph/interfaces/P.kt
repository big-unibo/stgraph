package it.unibo.graph.interfaces

import it.unibo.graph.utils.DUMMY_ID
import it.unibo.graph.utils.GRAPH_SOURCE
import it.unibo.graph.utils.NODE
import it.unibo.graph.utils.decodeBitwiseSource
import org.locationtech.jts.io.WKTReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.*

enum class PropType { INT, LONG, DOUBLE, STRING, GEOMETRY, NULL }

const val MAX_LENGTH_KEY = 24
const val MAX_LENGTH_VALUE = 8
const val PROPERTY_SIZE: Int = 45 + MAX_LENGTH_KEY + MAX_LENGTH_VALUE

open class P(
    final override val id: Long,
    val sourceId: Long,
    val sourceType: Boolean,
    val key: String,
    val value: Any,
    val type: PropType,
    var next: Long? = null,
    final override var fromTimestamp: Long = Long.MIN_VALUE,
    final override var toTimestamp: Long = Long.MAX_VALUE,
    @Transient final override var g: Graph,
    @Transient val fromDisk: Boolean = false
) : Elem {

    companion object {
        fun fromByteArray(bytes: ByteArray, g: Graph): P {
            val buffer = ByteBuffer.wrap(bytes)
            val id = buffer.long
            val fromTimestamp = buffer.long
            val toTimestamp = buffer.long
            val sourceId = buffer.long
            val sourceType = buffer.get().toInt() == 1
            // Read fixed-length strings
            fun readString(buffer: ByteBuffer, length: Int): String {
                val bytes = ByteArray(length)
                buffer.get(bytes)
                return bytes.toString(StandardCharsets.UTF_8).trimEnd('\u0000') // Trim null padding
            }
            val key = readString(buffer, MAX_LENGTH_KEY)
            val typeOrdinal = buffer.int
            val type = PropType.entries[typeOrdinal]
            val value: Any = when (type) {
                PropType.LONG -> buffer.long     // Serialize Long as 8 bytes
                PropType.DOUBLE -> buffer.double // Serialize Double as 8 bytes
                PropType.INT -> {                // Serialize Int as 4 bytes + 4 bytes as padding
                    buffer.int
                    buffer.int
                }
                PropType.STRING -> {             // Serialize String (using your serializeString method)
                    val v = readString(buffer, MAX_LENGTH_VALUE)
                    v.ifEmpty {
                        String(g.dynamicDb!!.get("$id".toByteArray()), Charsets.UTF_8)
                    }
                }
                PropType.GEOMETRY -> {
                    WKTReader().read(String(g.dynamicDb!!.get("$id".toByteArray()), Charsets.UTF_8))
                }
                else -> throw IllegalArgumentException("Unsupported type: $type")
            }
            val next = buffer.long.let { if (it == Long.MIN_VALUE) null else it }
            return P(id, sourceId, sourceType, key, value, type, next, fromTimestamp, toTimestamp, g = g, fromDisk = true)
        }
    }

    fun serialize(): ByteArray {
        fun serializeString(s: String, size: Int): ByteArray {
            val sBuffer = ByteBuffer.allocate(size)
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            val truncated = bytes.copyOfRange(0, minOf(bytes.size, size)) // Trim if too long
            sBuffer.put(truncated)
            return sBuffer.array()
        }
        val buffer = ByteBuffer.allocate(PROPERTY_SIZE)
        buffer.putLong(id)                                        // 8 bytes
        buffer.putLong(fromTimestamp)                             // 8 bytes
        buffer.putLong(toTimestamp)                               // 8 bytes
        buffer.putLong(sourceId)                                  // 8 bytes
        buffer.put(if (sourceType) 1 else 0)                         // 1 Byte
        buffer.put(serializeString(key, MAX_LENGTH_KEY)) // MAX_LENGTH_KEY Bytes
        buffer.putInt(type.ordinal)                               // 4 bytes
        when (type) {
            PropType.LONG -> buffer.putLong(value as Long)       // Serialize Long as 8 bytes
            PropType.DOUBLE -> buffer.putDouble(value as Double) // Serialize Double as 8 bytes
            PropType.INT -> {                                           // Serialize Int as 4 bytes + 4 bytes as padding
                buffer.putInt(0)
                buffer.putInt(value as Int)
            }
            PropType.STRING -> {                                        // Serialize String
                val value = value as String
                if (value.length > MAX_LENGTH_VALUE) { // in place if the string is longer than 8 bytes
                    g.dynamicDb!!.put("$id".toByteArray(), value.toByteArray(StandardCharsets.UTF_8))
                    repeat(8) { buffer.put(0.toByte()) } // Add 8 empty bytes (0x00)
                } else { // in place if the string is shorter than or equal to 8 bytes
                    buffer.put(serializeString(value, MAX_LENGTH_VALUE))
                }
            }
            PropType.GEOMETRY -> {
                val value = value.toString()
                g.dynamicDb!!.put("$id".toByteArray(), value.toByteArray(StandardCharsets.UTF_8))
            }
            else -> throw IllegalArgumentException("Unsupported type: $type")
        }
        buffer.putLong(next?: Long.MIN_VALUE)    // 8 bytes
        return buffer.array()                           // 45 + MAX_LENGTH_KEY + MAX_LENGTH_VALUE
    }

    init {
        if (!fromDisk && id != DUMMY_ID && decodeBitwiseSource(sourceId) == GRAPH_SOURCE) { // If the source is the graph and the property is not created at runtime
            val elem: ElemP = if (sourceType == NODE) g.getNode(sourceId) else g.getEdge(sourceId) // get the source of the property (either an edge or a node)
            assert(elem.fromTimestamp <= fromTimestamp && toTimestamp <= elem.toTimestamp) { "${elem}\n${toString()}" }
            if (elem.nextProp == null) elem.nextProp = id // if the element already has no properties, do nothing
            else {
                 // TODO we only update the `toTimestamp` of properties of elements of the graph (and not of the TS)
                 // TODO here we iterate all the properties of the element, but maybe we could exploit some sorting over time
                 val oldP: P? = elem.getProps(name = key).filter { it.toTimestamp > fromTimestamp && it.fromTimestamp < fromTimestamp}.maxByOrNull { it.toTimestamp } // check if the element contains a property with the same name
                 if (oldP != null) { // if so, and if the `toTimestamp` is \ge than `fromTimestamp`
                     oldP.toTimestamp = fromTimestamp // update the previous `toTimestamp`
                     g.addProperty(oldP) // update the property
                 }
                 next = elem.nextProp  // update the next pointer of the node
                 elem.nextProp = id
            }

            if (sourceType == NODE) g.addNode(elem as N) else g.addEdge(elem as R) // store the element again
        }
    }

    override fun toString(): String {
        return "{id: $id, node: $sourceId, key: $key, value: $value, type: $type, from: $fromTimestamp, to: $toTimestamp}"
    }

    override fun equals(other: Any?): Boolean {
        if (other !is P) return false
        return id == other.id && key == other.key && value == other.value && sourceId == other.sourceId && type == other.type && fromTimestamp == other.fromTimestamp && toTimestamp == other.toTimestamp
    }

    override fun hashCode(): Int {
        return Objects.hash(id, key, value, sourceId, type, fromTimestamp, toTimestamp)
    }
}