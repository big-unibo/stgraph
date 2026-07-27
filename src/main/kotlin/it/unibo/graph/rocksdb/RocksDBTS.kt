package it.unibo.graph.rocksdb

import it.unibo.graph.interfaces.Graph
import it.unibo.graph.interfaces.N
import it.unibo.graph.interfaces.TS
import it.unibo.graph.query.Aggregate
import it.unibo.graph.query.Filter
import org.rocksdb.RocksDB

class RocksDBTS(override val g: Graph, val id: Long, val db: RocksDB) : TS {
    override fun getTSId(): Long = id

    override fun add(n: N, isUpdate: Boolean, flush: Boolean): N {
        db.put("$id|${n.fromTimestamp}".toByteArray(), n.serialize())
        return n
    }

    override fun getValues(by: List<Aggregate>, filters: List<Filter>, isGroupBy: Boolean): List<N> {
        val acc: MutableList<N> = mutableListOf()
        val iterator = db.newIterator()
        iterator.seek("$id|".toByteArray())
        while (iterator.isValid) {
            val key = String(iterator.key())
            if (!key.startsWith("$id|")) break
            acc += N.fromByteArray(iterator.value(), g)
            iterator.next()
        }
        return acc
    }

    override fun get(id: Long) = N.fromByteArray(db.get("${this.id}|${id}".toByteArray()), g)
}