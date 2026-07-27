package it.unibo.tests.synth.loaders

import it.unibo.graph.asterixdb.AsterixDBTS
import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.interfaces.PropType
import it.unibo.graph.interfaces.TS
import it.unibo.graph.utils.Measurement
import it.unibo.graph.utils.Temperature
import it.unibo.stats.Loader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.logging.Logger
import kotlin.math.exp
import kotlin.math.ln

open class SynthDataLoader(
    size: String,
    val threads: Int,
    host: String = "localhost",
    controllerIPs: List<String> = listOf("localhost")
) : Loader {
    companion object {
        fun logRange(start: Int, end: Int, factor: Double): List<Int> {
            require(start > 0)
            require(factor > 1)
            val result = mutableListOf<Int>()
            var value = start.toDouble()
            while (value <= end) {
                result.add(value.toInt())
                value *= factor
            }
            return result
        }

        fun logSpace(start: Int, end: Int, points: Int): List<Int> {
            val logStart = ln(start.toDouble())
            val logEnd = ln(end.toDouble())

            return (0 until points).map { i ->
                val t = i.toDouble() / (points - 1)
                exp(logStart + t * (logEnd - logStart)).toInt()
            }.distinct()
        }

        val ids = logRange(100, 51_200, 2.0)
    }

    open val dataset = "synth"
    val graphPath = "datasets/dump/$dataset/full/"
    val logger: Logger = Logger.getLogger(this.javaClass.name)
    val graph = MemoryGraphACID(path = graphPath)
    val tsm = AsterixDBTSM.createDefault(
        graph,
        dataverse = "${dataset}_$size",
        host = host,
        controllerIps = controllerIPs,
        maxConnections = if (threads == 1) 1 else {
            threads * 10
        }
    )

    init {
        val folder = Paths.get(graphPath)
        if (!Files.exists(folder)) {
            Files.createDirectories(folder)
        }
        graph.tsm = tsm
        graph.clear()
        tsm.clear()
    }

    fun load(ids: List<Int>) {
        var i = 0
        ids.forEach { thr ->
            logger.info { "Loading $thr" }
            val newNode = graph.addNode(Temperature, isTs = true)
            graph.addProperty(newNode.id, "cardinality", thr, PropType.INT)
            val ts: AsterixDBTS = tsm.addTS(newNode.id) as AsterixDBTS
            for (timestamp in 1..thr.toLong() step 1) {
                ts.add(
                    label = Measurement,
                    timestamp = timestamp,
                    value = timestamp,
                    isUpdate = false
                )
                i++
            }
            ts.connection.flush()
            Thread.sleep(100)
        }
        logger.info { "Cardinality is $i" }
    }

    override fun loadData() {
        load(ids)
    }

    override fun close() {
        graph.close()
    }

    override fun getGSTime(): Long = -1
    override fun getTSTime(): Long = -1
    override fun getIndexTime(): Long = -1
    override fun getGSCardinality(): Long = -1
    override fun getTSCardinality(): Long = -1
}