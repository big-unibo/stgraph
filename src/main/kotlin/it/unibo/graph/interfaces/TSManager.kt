package it.unibo.graph.interfaces

enum class TsMode { WRITE, READ }
interface TSManager {
    val g: Graph
    fun addTS(id: Long): TS
    fun getTS(id: Long, mode: TsMode = TsMode.WRITE): TS
    fun clear()
    fun close() {}
}