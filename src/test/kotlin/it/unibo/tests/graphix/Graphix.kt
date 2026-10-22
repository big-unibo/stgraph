package it.unibo.tests.graphix

import it.unibo.graph.asterixdb.AsterixDBHTTPClient
import it.unibo.graph.query.QueryMode
import it.unibo.graph.utils.getTime
import it.unibo.graph.utils.query
import it.unibo.stats.QueryResultData
import it.unibo.stats.Querying
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.system.measureTimeMillis

/** Statements and data-feed ingestion on a Graphix (AsterixDB + graph views) dataverse, shared by the Graphix loaders */
class GraphixClient(val host: String = "localhost", port: Int = 29002, val dataverse: String) {
    val endpoint = "http://$host:$port/query/service"

    /**
     * Runs [sql] on the query service, with the optional named [parameters] (e.g. `$rows`), and returns the response
     * body; fails if the statement does not succeed, including when the server returns no JSON at all
     */
    fun exec(sql: String, parameters: Map<String, String> = emptyMap()): String {
        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        val form = (mapOf("statement" to sql.trimIndent(), "dataverse" to dataverse) + parameters)
            .entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(form) }
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        val status = runCatching { JSONObject(body).optString("status") }.getOrNull()
        if (code !in 200..299 || status != "success") {
            throw IllegalStateException("Graphix statement failed (HTTP $code, status $status):\n${sql.take(500)}\n${body.take(2000)}")
        }
        return body
    }

    fun count(dataset: String): Long =
        JSONObject(exec("USE $dataverse; SELECT VALUE COUNT(*) FROM `$dataset`;")).getJSONArray("results").getLong(0)

    /** A socket data feed towards [dataset], whose type must be `Event` (the type used by the feeds of STGraph) */
    fun feed(dataset: String, port: Int) = AsterixDBHTTPClient(endpoint, host, dataverse, dataset, dataFeedPort = port, isConcurrent = false)

    /**
     * Ends the ingestion through [feeds], once every record has been sent: returns the time until all the [expected]
     * records of [dataset] are stored, and fails if they are not.
     * A feed stores the records one frame at a time, and keeps the last (partial) frame until it is stopped.
     * Stopping it while it is still reading from the socket loses the unread records, so:
     * 1. close the sockets and wait until the count stops growing (every full frame stored);
     * 2. stop the feeds, which stores the last partial frame;
     * 3. wait until every sent record is queryable.
     * The [stableWindowMs] is only needed to detect the end of the reads: it is not ingestion time.
     */
    fun completeFeeds(
        feeds: List<AsterixDBHTTPClient>,
        dataset: String,
        expected: Long,
        stableWindowMs: Long = 10_000,
        timeoutMs: Long = 60 * 60 * 1000
    ): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        var stored = 0L
        val completion = getTime {
            feeds.forEach { it.closeDataFeedConnection(closeRemote = false) }
            var last = -1L
            var stableSince = System.currentTimeMillis()
            while (System.currentTimeMillis() - stableSince < stableWindowMs) {
                val count = count(dataset)
                if (count != last) {
                    last = count
                    stableSince = System.currentTimeMillis()
                }
                check(System.currentTimeMillis() < deadline) { "Feeds still reading after ${timeoutMs / 1000} s ($count of $expected records stored)" }
                Thread.sleep(200)
            }
            feeds.forEach { it.stopFeed(); it.dropFeed() }
            while (true) {
                stored = count(dataset)
                if (stored >= expected) break
                check(System.currentTimeMillis() < deadline) { "Only $stored of $expected records stored after ${timeoutMs / 1000} s" }
                Thread.sleep(100)
            }
        }
        check(stored == expected) { "Stored $stored records, sent $expected" }
        return completion - stableWindowMs
    }
}

/** A gSQL++ query on a Graphix dataverse, timed from the request to the transfer of the whole result */
abstract class GraphixQuery(private val dataverse: String, host: String = "localhost", port: Int = 29002) : Querying {
    private val endpoint = "http://$host:$port/query/service"

    /** The gSQL++ query (without the USE clause) */
    abstract fun statement(): String

    /** Rows counted in the result, after the processing done by STGraph outside the timed query (if any) */
    open fun cardinality(rows: JSONArray): Int = rows.length()

    override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
        val sql = "USE $dataverse; ${statement()}"
        var body = ""
        var code = 0
        // Timed: query execution and transfer of the whole result
        val time = measureTimeMillis {
            val connection = query(sql, endpoint, dataverse)
            code = connection.responseCode
            body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        }
        val json = JSONObject(body)
        check(code in 200..299 && json.optString("status") == "success") { "$queryId failed (HTTP $code):\n$sql\n$body" }
        return QueryResultData(time, cardinality(json.getJSONArray("results")))
    }
}
