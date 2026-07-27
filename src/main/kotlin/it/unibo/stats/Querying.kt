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

fun runQuery(querying: Querying, model: String, threads: Int, numMachines: Int, dataset: String, size: String, mode: QueryMode) {
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
        "queryMode" to mode
    )

    val writeHeader = !statisticsFile.exists()
    statisticsFile.appendText(buildString {
        if (writeHeader) append(row.keys.joinToString(",") + "\n")
        append(row.values.joinToString(",") + "\n")
    })
}