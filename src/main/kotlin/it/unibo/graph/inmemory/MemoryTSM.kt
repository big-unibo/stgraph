package it.unibo.graph.inmemory

import it.unibo.graph.interfaces.Graph
import it.unibo.graph.interfaces.TS
import it.unibo.graph.interfaces.TSManager
import it.unibo.graph.interfaces.TsMode

class MemoryTSM(override val g: Graph): TSManager {
    private val tss: MutableMap<Long, TS> = mutableMapOf()

    override fun getTS(id: Long, mode: TsMode): TS {
        return tss[id]!!
    }

    override fun addTS(id: Long): TS {
        val cId = id + 1
        val ts = MemoryTS(g, cId)
        tss[cId] = ts
        return ts
    }

    override fun clear() {
        tss.clear()
    }
}
