package it.unibo.tests.mimic.loaders

import it.unibo.graph.asterixdb.AsterixDBTS
import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.interfaces.PropType
import it.unibo.graph.utils.*
import it.unibo.tests.smartbench.loaders.TSRecord
import kotlin.collections.set

class MimicIVSTGraph(
    size: String,
    threads: Int = 1,
    host: String = "localhost",
    controllerIPs: List<String> = listOf("localhost")
) : AbstractMimicIVLoader(if (size == "full") Long.MAX_VALUE else size.toLong(), threads = threads) {
    val s = if (size == "full" || size.toLong() == Long.MAX_VALUE) "full" else size
    val g = MemoryGraphACID(path = "datasets/dump/mimic/$s")
    val t = AsterixDBTSM.createDefault(g, "mimic_$s", host = host, controllerIps = controllerIPs, maxConnections = if (threads == 1) 1 else { threads * 10 }, multiTs = true)
    var i = 0
    val tsMap = mutableMapOf<Long, AsterixDBTS>()
    val set = mutableSetOf<TSRecord>()
    var pId = 0

    init {
        g.tsm = t
        g.clear()
        t.clear()
    }

    override fun addPerson(subjectId: Int, c: Int): Long {
        val person = g.addNode(Person)
        g.addProperty(person.id, key = "subject_id", value = subjectId, PropType.INT)
        g.addProperty(person.id, key = "rnd", value = subjectId % 10, PropType.INT)
        g.addProperty(person.id, key = "pId", value = pId++, PropType.INT)
        g.addProperty(person.id, key = "c", value = c, PropType.INT)
        return person.id
    }

    override fun addTimeseries(abbreviation: String, unitname: String?, category: String, label: String, itemid: Int, person: Long): Long {
        if (!tsMap.contains(person)) {
            val n = g.addNode("TimeSeries", isTs = true)
            val ts = (g.getTSM() as AsterixDBTSM).addTS(n.id) as AsterixDBTS
            tsMap[person] = ts
            g.addEdge("hasParameters", fromNode = person, toNode = n.id)
        }
        return person
    }

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        val ts = tsMap[tsId]!!
        val tmp = tsCache[row.label.toInt()]!!
        val properties = mutableListOf(
            Triple("category", tmp.category, PropType.STRING),
            Triple("abbreviation", tmp.abbreviation, PropType.STRING),
            Triple("itemid", tmp.itemid, PropType.INT)
        )
        if (tmp.unitname != null) properties += Triple("unitname", tmp.unitname, PropType.STRING)
        ts.add(
            label = tmp.abbreviation,
            timestamp = row.timestamp,
            value = row.value as Long,
            flush = false,
            properties = properties
        )
        if (isLast) {
            ts.connection.flush()
        }
    }

    override fun close() {
        g.flushToDisk()
        g.close()
    }

//    init {
//        g.tsm = t
//        g.clear()
//        t.clear()
//    }
//
//    override fun addPerson(subjectId: Int): Long {
//        val person = g.addNode(Person)
//        g.addProperty(person.id, key = "subject_id", value = subjectId, PropType.INT)
//        return person.id
//    }
//
//    override fun addTimeseries(abbreviation: String, unitname: String?, category: String, label: String, itemid: Int, person: Long): Long {
//        val n = g.addNode(abbreviation, isTs = true)
//        if (unitname != null) g.addProperty(n.id, key = "unitname", value = unitname, PropType.STRING)
//        g.addProperty(n.id, key = "category", value = category, PropType.STRING)
//        g.addProperty(n.id, key = "label", value = label, PropType.STRING)
//        g.addProperty(n.id, key = "itemid", value = itemid, PropType.INT)
//        val ts = (g.getTSM() as AsterixDBTSM).addTS(n.id) as AsterixDBTS
//        tsMap[n.id] = ts
//        g.addEdge("hasParameters", fromNode = person, toNode = n.id)
//        return n.id
//    }
//
//    override fun addMeasurement(tsId: Long, row: TSRecord, isLast: Boolean) {
//        val ts = tsMap[tsId]!!
//        ts.add(
//            label = Measurement,
//            timestamp = row.timestamp,
//            value = row.value as Long,
//            flush = false
//        )
//        if (isLast) {
//            ts.connection.flush()
//        }
//    }
//
//    override fun close() {
//        g.flushToDisk()
//        g.close()
//    }
}