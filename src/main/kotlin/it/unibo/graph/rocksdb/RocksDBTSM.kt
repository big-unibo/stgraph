package it.unibo.graph.rocksdb

import it.unibo.graph.interfaces.Graph
import it.unibo.graph.interfaces.TS
import it.unibo.graph.interfaces.TSManager
import it.unibo.graph.interfaces.TsMode
import org.rocksdb.Options
import org.rocksdb.RocksDB

class RocksDBTSM(override val g: Graph): TSManager {
    val db: RocksDB

    init {
        val options = Options()
        options.setCreateIfMissing(true)
        options.setCreateMissingColumnFamilies(true)
        db = RocksDB.open(options, "${g.path}/ts_rocksdb")
    }

    override fun getTS(id: Long, mode: TsMode): TS {
        return RocksDBTS(g, id, db)
    }

    override fun addTS(id: Long): TS {
        return RocksDBTS(g, id + 1, db)
    }

    override fun clear() {
        val iterator = db.newIterator()
        iterator.seekToFirst()
        while (iterator.isValid) {
            db.delete(iterator.key())
            iterator.next()
        }
    }
}
