package it.unibo.graph.asterixdb

import it.unibo.graph.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.Writer
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock


class AsterixDBHTTPClient(
    private val clusterControllerHost: String,
    val dataFeedIp: String,
    private val dataverse: String,
    private val dataset: String,
    private var dataFeedPort: Int = incPort(),
    private val isConcurrent: Boolean
) {
    companion object {
        val id: AtomicInteger = AtomicInteger(0)
    }
    private val id = Companion.id.incrementAndGet()
    private val dataType: String = EVENT
    private val feedName: String = "$DATAFEED_PREFIX${id}"
    private val logger = LoggerFactory.getLogger(AsterixDBHTTPClient::class.java)
    private lateinit var socket: Socket
    private lateinit var outputStream: OutputStream
    private lateinit var writer: Writer
    private var isFeedConnectionOpen: Boolean = false
    private var isFeedInitialized: Boolean = false
    private var isFeedDrained: Boolean = false
    private val mutex = ReentrantLock()

    fun flush() {
        if (isConcurrent) {
            mutex.lock()
            writer.flush()
            mutex.unlock()
        } else {
            writer.flush()
        }
    }

    private fun write(data: String, flush: Boolean) {
        openDataFeedConnection()
        // The record and its line break, without building a new string for each event
        writer.write(data)
        writer.write('\n'.code)
    }

    fun writeToFeed(data: String, flush: Boolean) {
        if (isConcurrent) {
            mutex.lock()
            write(data, flush)
            mutex.unlock()
        } else {
            write(data, flush)
        }
        if (flush) flush()
    }

    fun openDataFeedConnection() {
        if (!isFeedConnectionOpen) {
            try {
                if (!isFeedInitialized) {
                    initializeFeed()
                    isFeedInitialized = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // the datafeed could be already opened and started, if so do nothing
            }
            socket = Socket(dataFeedIp, dataFeedPort)
            socket.sendBufferSize = 1_000_000
            outputStream = socket.getOutputStream()
            // writer = PrintWriter(outputStream, true)
            writer = BufferedWriter(OutputStreamWriter(outputStream), 1024 * 1024)
            isFeedConnectionOpen = true
            isFeedDrained = false
        }
    }

    /**
     * Closes the data feed connection. With [closeRemote], it also stops and drops the feed, after waiting until
     * AsterixDB has read the whole stream ([awaitDataFeedRead]): stopping the feed while AsterixDB is still reading
     * from the socket loses the unread records. Without [closeRemote], the feed keeps running and reads the stream up
     * to its end.
     */
    fun closeDataFeedConnection(closeRemote: Boolean = false) {
        if (!this::writer.isInitialized) return
        try {
            if (closeRemote) awaitDataFeedRead()
        } finally {
            try {
                writer.close()
                outputStream.close()
                socket.close()
            } finally {
                if (closeRemote) {
                    stopFeed()
                    dropFeed()
                    isFeedInitialized = false
                }
                isFeedConnectionOpen = false
            }
        }
    }

    /**
     * Half-closes the data feed connection and waits until AsterixDB has read the whole stream (it closes its side
     * once it reaches the end of the stream): the feed can then be stopped without losing records, while stopping it
     * when it is still reading from the socket loses the unread ones. Called by [closeDataFeedConnection] before
     * stopping the feed; it can be called before it to time the read on its own (e.g., the AsterixDB of Graphix, which
     * keeps the last frame of a feed until the feed stops). Idempotent: a drained connection is not drained again.
     * Fails if AsterixDB has not read the whole stream within [timeoutMs].
     */
    fun awaitDataFeedRead(timeoutMs: Int = 30 * 60 * 1000) {
        if (!isFeedConnectionOpen || isFeedDrained) return
        flush()
        socket.shutdownOutput()
        socket.soTimeout = timeoutMs
        try {
            socket.getInputStream().read() // -1 once AsterixDB has read the whole stream and closed its side
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException("Feed $feedName ($dataFeedIp:$dataFeedPort): AsterixDB did not read the whole stream within $timeoutMs ms", e)
        }
        isFeedDrained = true
    }

    fun stopFeed() = queryAsterixDB("USE $dataverse; STOP FEED $feedName;", blockOnError = false) // The feed could not exist yet

    fun createDataset(multiTs: Boolean): Boolean {
        var pk = "$TSID, $ID"
        if (multiTs) pk = "$TSID, $LABEL, $ID"
        return queryAsterixDB("USE $dataverse; CREATE DATASET $dataset($dataType) IF NOT EXISTS primary key $pk;")
    }

    fun createSpatialIndex(): Boolean {
        return queryAsterixDB("USE $dataverse; CREATE INDEX ${LOCATION}_$id on $dataset($LOCATION) type rtree;")
    }

    fun initializeFeed() : Boolean {
        var failures = 0
        do { // Until I have successfully created a DataFeed and I can actually connect to it
            var datafeedSetup = false
            var socketConnect = false
            try {
                stopFeed()
                datafeedSetup = setupDataFeed()
                if (!datafeedSetup) {
                    val msg = "Cannot setup feed: $dataFeedPort"
                    logger.warn(msg)
                    failures += 1
                    throw IllegalArgumentException(msg)
                }
                socketConnect = tryDataFeedConnection(dataFeedIp, dataFeedPort) // Try to connect to it
                if (!socketConnect) {
                    val msg = "Cannot connect to feed: $dataFeedPort"
                    logger.warn(msg)
                    failures += 1
                    throw IllegalArgumentException(msg)
                }
                if (failures > 0) logger.warn("Failures: $failures")
                return true
            } catch (e: Exception) {
                e.printStackTrace()
                dataFeedPort = incPort()
            }
        } while (!datafeedSetup && !socketConnect)
        return false
    }

    fun dropFeed(): Boolean {
        return queryAsterixDB("USE $dataverse; DROP FEED $feedName IF EXISTS;")
    }

    fun setupDataFeed(): Boolean {
        val dataFeedSetupQuery = """
            USE $dataverse;
            DROP FEED $feedName IF EXISTS;
            CREATE FEED $feedName WITH {
                "adapter-name": "socket_adapter",
                "sockets": "$dataFeedIp:$dataFeedPort",
                "address-type": "IP",
                "type-name": "$dataType",
                "format": "adm",
                "policy": "spill"
            };
            CONNECT FEED $feedName TO DATASET $dataset;
            START FEED $feedName;
        """.trimIndent()
        return queryAsterixDB(dataFeedSetupQuery)
    }

    private fun tryDataFeedConnection(dataFeedIp: String, dataFeedPort: Int): Boolean {
        try {
            Socket(dataFeedIp, dataFeedPort).close()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun queryAsterixDB(sql: String, checkResults: Boolean = false, blockOnError: Boolean = true): Boolean {
        // println(sql)
        val connection = query(sql, clusterControllerHost, dataverse)
        val responseCode = connection.responseCode
        val responseText = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            val errMsg = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            if (blockOnError) throw UnsupportedOperationException(errMsg)
            errMsg
        }

        if (responseCode in 200..299) {
            if (!checkResults) { // If I just want the request to return successfully
                return true
            } else {
                // Check if it returned something
                val isEmpty = Regex("\"results\"\\s*:\\s*\\[\\s*]").containsMatchIn(responseText)
                return !isEmpty
            }
        } else {
            return false
        }
    }

    fun updateTs(upsertStatement: String) {
        queryAsterixDB(upsertStatement)
    }

    fun selectFromAsterixDB(sql: String, isGroupBy: Boolean = false): AsterixDBResult {
        val connection = query(sql, clusterControllerHost, dataverse)
        val responseCode = connection.responseCode
        val responseText = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            val errMsg = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            if (!errMsg.contains("ASX1077")) { // The dataset could not exist: e.g. when a GS node does not have an assigned ts in TS yet
                throw UnsupportedOperationException("SQL:\n ${sql}\nError:\n$errMsg")
            } else {
                errMsg
            }
        }

        return when (responseCode) {
            in 200..299 -> {
                val cleanedQuery = sql.substringAfter(";").trimStart()
                if (cleanedQuery.startsWith("SELECT", ignoreCase = true)) {
                    try {
                        val jsonResponse = JSONObject(responseText)
                        val resultArray = jsonResponse.getJSONArray("results")
                        if (!isGroupBy) {
                            AsterixDBResult.SelectResult(resultArray)
                        } else {
                            AsterixDBResult.GroupByResult(resultArray)
                        }
                    } catch (e: Exception) {
                        throw Exception("Error parsing JSON response: ${e.message}")
                    }
                } else {
                    AsterixDBResult.InsertResult
                }
            }

            400 ->
                if (!isGroupBy) {
                    AsterixDBResult.SelectResult(JSONArray())
                } else {
                    AsterixDBResult.GroupByResult(JSONArray())
                }

            else -> throw UnsupportedOperationException("Query failed with status code $responseCode: $responseText")
        }
    }

    fun createTSMEnvironment(): Boolean {
        val sql =
            """
                DROP dataverse $dataverse IF EXISTS;
                CREATE DATAVERSE $dataverse;
                USE $dataverse;

                CREATE TYPE Property AS OPEN {
                    `$KEY`: string,
                    `$TYPE`: int
                };

                CREATE TYPE Edge AS CLOSED {
                    $LABEL: int,
                    $FROM_N: bigint,
                    $TO_N: bigint
                };

                CREATE TYPE $EVENT AS OPEN {
                    $TSID: bigint,
                    $ID: bigint,
                    $LABEL: int,
                    $LOCATION: geometry?,
                    $EDGES: [Edge]?
                };
            """.trimIndent()
        return queryAsterixDB(sql)
    }
}
