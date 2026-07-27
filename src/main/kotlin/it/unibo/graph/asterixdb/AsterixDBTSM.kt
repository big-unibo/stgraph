package it.unibo.graph.asterixdb

import it.unibo.graph.interfaces.Graph
import it.unibo.graph.interfaces.TS
import it.unibo.graph.interfaces.TSManager
import it.unibo.graph.interfaces.TsMode
import it.unibo.graph.utils.DATASET_PREFIX
import it.unibo.graph.utils.FIRSTFEEDPORT
import it.unibo.graph.utils.LASTFEEDPORT
import it.unibo.graph.utils.loadProps
import kotlin.random.Random

val props = loadProps()

class AsterixDBTSM private constructor(
    override val g: Graph,
    val host: String,
    val port: String,
    val dataverse: String,
    val nodeControllersIPs: List<String>,
    val maxConnections: Int? = 100,
    val multiTs: Boolean
) : TSManager {
    val isConcurrent = nodeControllersIPs.size > 1
    val dataset = "${DATASET_PREFIX}0"
    val connection = AsterixDBHTTPClient("http://$host:$port/query/service", getDataFeedIP(), dataverse, dataset, isConcurrent = isConcurrent)
    val connections = mutableListOf(connection)
    val r = Random(0)

    private fun getDataFeedIP(): String {
        val hash = (Math.random() * 100).toInt()
        val index = (hash and 0x7FFFFFFF) % nodeControllersIPs.size
        return nodeControllersIPs[index]
    }

    fun createAndOpenConnection(mode: TsMode): AsterixDBHTTPClient {
        // Determine which connection to use
        val connection = if (isConcurrent) {
            // If we've reached the max number of connections, reuse a random existing connection
            if (connections.size == maxConnections) {
                connections[r.nextInt(0, maxConnections)]
            } else {
                // Otherwise, create a new connection and store it
                val c = AsterixDBHTTPClient("http://$host:$port/query/service", getDataFeedIP(), dataverse, dataset, isConcurrent = isConcurrent)
                connections.add(c)
                c
            }
        } else {
            // In non-concurrent mode, reuse the single existing connection
            connection
        }
        // If the mode is WRITE, ensure the data feed connection is opened
        if (mode === TsMode.WRITE) {
            connection.openDataFeedConnection()
        }
        // Return the selected or newly created connection
        return connection
    }

    override fun addTS(id: Long): TS {
        return AsterixDBTS(g, id + 1,  dataverse, dataset, createAndOpenConnection(TsMode.WRITE))
    }

    override fun getTS(id: Long, mode: TsMode): TS {
        return AsterixDBTS(g, id, dataverse, dataset, createAndOpenConnection(mode))
    }

    override fun clear() {
        close()
        connection.createTSMEnvironment()
        connection.createDataset(multiTs)
    }

    companion object {
        fun createDefault(
            g: Graph,
            dataverse: String = props["default_dataverse"].toString(),
            host: String = System.getenv("ASTERIXDB_CC_HOST") ?: "localhost",
            port: String = props["default_cc_port"].toString(),
            controllerIps: List<String> = System.getenv("DEFAULT_NC_POOL")?.split(',') ?: listOf("localhost"),
            maxConnections: Int? = (LASTFEEDPORT - FIRSTFEEDPORT) / 2,
            multiTs: Boolean = false
        ): AsterixDBTSM {
            return AsterixDBTSM(g, host, port, dataverse, controllerIps, maxConnections, multiTs)
        }
    }

    override fun close() {
        connections.forEach { it.closeDataFeedConnection(true) }
    }
}
