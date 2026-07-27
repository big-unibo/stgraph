package it.unibo.graph.rocksdb

import it.unibo.graph.interfaces.*
import it.unibo.graph.utils.PATH
import org.apache.commons.lang3.NotImplementedException
import org.rocksdb.*
import java.io.File

class RocksDBGraph(override val path: String = PATH, override var dynamicDb: RocksDB? = null) : Graph {

    var nodes: ColumnFamilyHandle? = null
    var edges: ColumnFamilyHandle? = null
    var properties: ColumnFamilyHandle? = null
    val options = DBOptions()
    val cfHandles: List<ColumnFamilyHandle> = ArrayList()

    init {
        options.setCreateIfMissing(true)
        options.setCreateMissingColumnFamilies(true)
        val cfDescriptors = listOf(
            ColumnFamilyDescriptor("default".toByteArray(), ColumnFamilyOptions()),
            ColumnFamilyDescriptor("nodes".toByteArray(), ColumnFamilyOptions()),
            ColumnFamilyDescriptor("edges".toByteArray(), ColumnFamilyOptions()),
            ColumnFamilyDescriptor("properties".toByteArray(), ColumnFamilyOptions())
        )

        dynamicDb = RocksDB.open(options, "${path}/graph_rocksdb", cfDescriptors, cfHandles)
        nodes = cfHandles[1]
        edges = cfHandles[2]
        properties = cfHandles[3]
    }

    override var tsm: TSManager? = null
    private var nodeId = 0L
    private var edgeId = 0L
    private var propId = 0L

    override fun clear() {
        listOf(nodes, edges, properties).forEach {
            val iterator = dynamicDb!!.newIterator(it)
            iterator.seekToFirst()
            while (iterator.isValid) {
                dynamicDb!!.delete(it, iterator.key())
                iterator.next()
            }
        }
        nodeId = 0
        edgeId = 0
        propId = 0
    }

    override fun close() {
        cfHandles.forEach { it.close() }
        dynamicDb?.closeE()
        options.close()
        File("$path/properties/LOCK").delete()
        tsm?.close()
    }

    override fun nextNodeId(): Long = nodeId++

    override fun nextPropertyId(): Long = propId++

    override fun nextEdgeId(): Long = edgeId++

    override fun addNode(n: N): N {
        dynamicDb!!.put(nodes, "${n.id}".toByteArray(), n.serialize())
        return n
    }

    override fun addPropertyLocal(key: Long, p: P): P {
        dynamicDb!!.put(properties, "${p.id}".toByteArray(), p.serialize())
        return p
    }

    override fun addEdgeLocal(key: Long, r: R): R {
        dynamicDb!!.put(edges, "${r.id}".toByteArray(), r.serialize())
        return r
    }

    override fun getProps(): MutableList<P> {
        throw NotImplementedException()
    }

    override fun getNodes(): MutableList<N> {
        val acc: MutableList<N> = mutableListOf()
        val iterator = dynamicDb!!.newIterator(nodes)
        iterator.seekToFirst()
        while (iterator.isValid) {
            acc += N.fromByteArray(iterator.value(), this)
            iterator.next()
        }
        return acc
    }

    override fun getEdges(): MutableList<R> {
        throw NotImplementedException()
    }

    override fun getProp(id: Long): P {
        val b = dynamicDb!!.get(properties, "$id".toByteArray())
        return P.fromByteArray(b, this)
    }

    override fun getNode(id: Long): N {
        val b = dynamicDb!!.get(nodes, "$id".toByteArray())
        return N.fromByteArray(b, this)
    }

    override fun getEdge(id: Long): R {
        val b = dynamicDb!!.get(edges, "$id".toByteArray())
        return R.fromByteArray(b, this)
    }
}
