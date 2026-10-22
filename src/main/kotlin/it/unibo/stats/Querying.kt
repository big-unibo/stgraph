package it.unibo.stats

import it.unibo.graph.query.QueryMode
import java.io.File
import java.util.*
import kotlin.system.measureTimeMillis

private const val resultPath = "results/dt_graph/query_time"
private val resultFolder = File(resultPath)
private val statisticsFile = File(resultFolder, "query_statistics.csv")

class QueryResultData(val timeMs: Long, val card: Int)
interface Querying {
    val queryId: String
    fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData
}

/**
 * Runs [querying] and appends its statistics to the query statistics. [iteration], [querySelectivity] and
 * [temporalRangeIndex] identify the run within an evaluation: the repetition, and the time window of
 * time_constraints.yaml (the queries without a window ignore it)
 */
fun runQuery(
    querying: Querying, model: String, threads: Int, numMachines: Int, dataset: String, size: String, mode: QueryMode,
    iteration: Int = 0, querySelectivity: String = "increased", temporalRangeIndex: Int = 0
) {
    if (!resultFolder.exists()) resultFolder.mkdirs()

    val data: QueryResultData
    val elapsedTime = measureTimeMillis {
        data = querying.runQuery(threads, mode)
    }

    val row = linkedMapOf(
        "test_id" to UUID.randomUUID().toString(),
        "queryid" to querying.queryId, // Q1, Q2, ...
        "model" to model, // STGraph, Neo4J, ...
        "dataset" to dataset, // SmartBench, Mimic-IV
        "datasetSize" to size, // small, medium, ...
        "threads" to threads,
        "elapsedTime" to elapsedTime,
        "numMachines" to numMachines,
        "cardinality" to data.card,
        "queryMode" to mode,
        "iteration" to iteration,
        "querySelectivity" to querySelectivity,
        "temporalRangeIndex" to temporalRangeIndex
    )

    // A file written before the last columns were added: extend its header (the old rows leave them empty)
    val header = row.keys.joinToString(",")
    if (statisticsFile.exists()) {
        val oldHeader = statisticsFile.bufferedReader().use { it.readLine() }
        if (oldHeader != null && oldHeader != header && header.startsWith("$oldHeader,")) {
            val rows = statisticsFile.readLines().drop(1)
            statisticsFile.writeText((listOf(header) + rows).joinToString("\n", postfix = "\n"))
        }
    }
    val writeHeader = !statisticsFile.exists()
    statisticsFile.appendText(buildString {
        if (writeHeader) append(header + "\n")
        append(row.values.joinToString(",") + "\n")
    })
}