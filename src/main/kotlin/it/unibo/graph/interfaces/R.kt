package it.unibo.graph.interfaces

import it.unibo.graph.utils.DUMMY_ID
import java.nio.ByteBuffer
import java.util.*

enum class Direction { IN, OUT }

const val EDGE_SIZE: Int = 68

open class R(
    final override val id: Long, // id of the edge
    final override val label: Label, // label
    val fromN: Long, // from node
    val toN: Long, // to node
    var fromNextRel: Long? = null, // pointer to the next edge of the `from node`
    var toNextRel: Long? = null, // pointer to the next edge of the `to node`
    final override val fromTimestamp: Long = Long.MIN_VALUE,
    final override var toTimestamp: Long = Long.MAX_VALUE,
    final override var nextProp: Long? = null,
    @Transient final override val properties: MutableList<P> = mutableListOf(),
    @Transient final override var g: Graph,
    @Transient val fromDisk: Boolean = false
): ElemP {

    constructor(id: Long, labelName: String, fromN: Long, toN: Long, fromNextRel: Long? = null, toNextRel: Long? = null, fromTimestamp: Long = Long.MIN_VALUE, toTimestamp: Long = Long.MAX_VALUE, nextProp: Long? = null, properties: MutableList<P> = mutableListOf(), g: Graph, fromDisk: Boolean = false)
            : this(id, labelFromString(labelName), fromN, toN, fromNextRel, toNextRel, fromTimestamp, toTimestamp, nextProp, properties, g, fromDisk)

    companion object {
        fun fromByteArray(bytes: ByteArray, g: Graph): R {
            val buffer = ByteBuffer.wrap(bytes)
            val id = buffer.long // Read ID (stored as Long, convert to Int)
            val fromTimestamp = buffer.long
            val toTimestamp = buffer.long
            val labelOrdinal = buffer.int
            val label = Label.entries[labelOrdinal]!! // Convert ordinal to enum
            val nextProp = buffer.long.let { if (it == Long.MIN_VALUE) null else it }
            val fromN = buffer.long
            val toN = buffer.long
            val fromNextRel = buffer.long.let { if (it == Long.MIN_VALUE) null else it }
            val toNextRel = buffer.long.let { if (it == Long.MIN_VALUE) null else it }
            return R(id, label, fromN, toN, fromNextRel, toNextRel, fromTimestamp, toTimestamp, nextProp, g = g, fromDisk = true)
        }

        fun createVirtualR(label: Label, properties: List<P>, fromTimestamp: Long, toTimestamp: Long, g: Graph) = R(DUMMY_ID, label, DUMMY_ID, DUMMY_ID, null, null, fromTimestamp, toTimestamp, null, properties.toMutableList(), g)
    }

    fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(EDGE_SIZE)
        buffer.putLong(id)                           // 8 bytes
        buffer.putLong(fromTimestamp)                // 8 bytes
        buffer.putLong(toTimestamp)                  // 8 bytes
        buffer.putInt(label.ordinal)                 // 4 bytes
        buffer.putLong(nextProp?: Long.MIN_VALUE)           // 8 bytes
        buffer.putLong(fromN)                        // 8 bytes
        buffer.putLong(toN)                          // 8 bytes
        buffer.putLong(fromNextRel?: Long.MIN_VALUE)        // 8 bytes
        buffer.putLong(toNextRel?: Long.MIN_VALUE)          // 8 bytes
        return buffer.array()                               // Total: 68 bytes
    }

    init {
        if (!fromDisk && id != DUMMY_ID) {
            val from = g.getNode(fromN)
            val to = g.getNode(toN)
            if (from.nextEdge == null) { // this is the first edge
                from.nextEdge = id
            } else {
                // No, when we add a new edge among the two same nodes, this is a not a new version of a previous edge
                // for (it in from.getRels(direction = Direction.OUT, label = label)) {
                //     if (it.toN == toN && toTimestamp == Long.MAX_VALUE) {
                //         it.toTimestamp = fromTimestamp
                //         break
                //     }
                // }
                fromNextRel = from.nextEdge
                from.nextEdge = id
            }
            if (to.nextEdge == null) { // this is the first edge
                to.nextEdge = id
            } else {
                toNextRel = to.nextEdge
                to.nextEdge = id
            }
            g.addNode(from)
            g.addNode(to)
        }
    }

    override fun toString(): String {
        return "($fromN)-[id: $id, type: $label, from: $fromTimestamp, to: $toTimestamp]->($toN)"
    }

    override fun equals(other: Any?): Boolean {
        if (other !is R) return false
        return id == other.id && label == other.label && fromN == other.fromN && toN == other.toN && fromTimestamp == other.fromTimestamp && toTimestamp == other.toTimestamp
    }

    override fun hashCode(): Int {
        return Objects.hash(id, label, fromN, toN, fromTimestamp, toTimestamp)
    }
}