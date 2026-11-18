package it.unibo.tests.evaluation

import java.io.File
import java.util.UUID
import kotlin.math.roundToLong

/**
 * The results of AeonG in the files of the other systems. AeonG times its load and its queries inside its container,
 * and its post-processing appends them to results/aeong (ingestion_time/ingestion_statistics.csv,
 * query_evaluation/statistics.csv, columns of the old Python evaluation, each run with its test_id): the rows of a run
 * (the test ids not there before it, see [testIds]) are appended to results/dt_graph (ingestion_time/ingestion_statistics.csv,
 * query_time/query_statistics.csv) with the columns and the default values of loadDataset and runQuery: one test_id
 * per row, times in ms, the model, dataset and size of the evaluation, the configuration (conf or conf-free),
 * iterations from 0, the queries of MIMIC-IV as Q1-Q3 (q0, a warm-up, is not recorded), unknown cardinalities as -1
 */
internal object AeonGResults {
    private val ingestionIn = File("results/aeong/ingestion_time/ingestion_statistics.csv")
    private val queryIn = File("results/aeong/query_evaluation/statistics.csv")
    private val ingestionOut = File("results/dt_graph/ingestion_time/ingestion_statistics.csv")
    private val queryOut = File("results/dt_graph/query_time/query_statistics.csv")

    /** The columns of loadDataset and runQuery (it.unibo.stats), for a file not written yet */
    private val ingestionColumns = listOf(
        "test_id", "model", "startTimestamp", "endTimestamp", "dataset", "datasetSize", "threads", "graphElapsedTime",
        "tsElapsedTime", "indexTime", "elapsedTime", "numMachines", "storage", "tsStorage", "gsStorage", "tsCard", "gsCard", "configuration"
    )
    private val queryColumns = listOf(
        "test_id", "queryid", "model", "dataset", "datasetSize", "threads", "elapsedTime", "numMachines", "cardinality",
        "queryMode", "iteration", "querySelectivity", "temporalRangeIndex", "configuration"
    )

    /** The test ids of the results of AeonG so far */
    fun testIds(): Set<String> = (rows(ingestionIn) + rows(queryIn)).mapNotNull { it["test_id"] }.toSet()

    /** Appends the rows of AeonG not in [before] to results/dt_graph, recorded as [model] on [dataset] [size] */
    fun export(before: Set<String>, model: String, dataset: String, size: String, configuration: String) {
        val ingestion = rows(ingestionIn).filter { it["test_id"] !in before }.map { r ->
            // Seconds (the timestamps of AeonG) or milliseconds
            fun ms(key: String) = r[key]?.toDoubleOrNull()?.let { if (it < 1e11) it * 1000 else it }?.roundToLong()
            val start = ms("startTimestamp")
            val end = ms("endTimestamp")
            val elapsed = if (start != null && end != null) end - start else r["elapsedTime"]?.toDoubleOrNull()?.roundToLong() ?: -1
            mapOf(
                "test_id" to UUID.randomUUID().toString(), "model" to model, "startTimestamp" to start, "endTimestamp" to end,
                "dataset" to dataset, "datasetSize" to size, "threads" to 1, "graphElapsedTime" to elapsed, "tsElapsedTime" to 0,
                "indexTime" to 0, "elapsedTime" to elapsed, "numMachines" to 1, "storage" to (r["storage"] ?: -1),
                "tsStorage" to -1, "gsStorage" to -1, "tsCard" to -1, "gsCard" to -1, "configuration" to configuration
            )
        }
        val queries = rows(queryIn).filter { it["test_id"] !in before }.mapNotNull { r ->
            val name = r["queryName"].orEmpty()
            val queryId = if (dataset == "mimic") Regex("q([1-9])").matchEntire(name)?.let { "Q${it.groupValues[1]}" } ?: return@mapNotNull null else name
            mapOf(
                "test_id" to UUID.randomUUID().toString(), "queryid" to queryId, "model" to model, "dataset" to dataset,
                "datasetSize" to size, "threads" to (r["threads"] ?: 1), "elapsedTime" to r["elapsedTime"]?.toDoubleOrNull()?.roundToLong(),
                "numMachines" to 1, "cardinality" to -1, "queryMode" to "OPTIMIZED",
                "iteration" to ((r["iteration"]?.toIntOrNull() ?: 1) - 1), // from 1 in AeonG
                "querySelectivity" to (r["querySelectivity"]?.takeIf { it.isNotBlank() && it != "None" } ?: "increased"),
                "temporalRangeIndex" to (Regex("\\d+").find(r["temporalRangeIndex"].orEmpty())?.value ?: "0"),
                "configuration" to configuration
            )
        }
        append(ingestionOut, ingestionColumns, ingestion)
        append(queryOut, queryColumns, queries)
        progress("aeong | $dataset $size: ${ingestion.size} ingestion(s) and ${queries.size} query run(s) recorded in results/dt_graph")
    }

    /** The rows of a CSV file of AeonG (no quoted values), by column */
    private fun rows(file: File): List<Map<String, String>> {
        if (!file.isFile) return emptyList()
        val lines = file.readLines().filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.split(",") ?: return emptyList()
        return lines.drop(1).map { line -> header.zip(line.split(",")).toMap() }
    }

    /** Appends [rows] to [file] with its header (or [columns] if new); a column it does not have: empty */
    private fun append(file: File, columns: List<String>, rows: List<Map<String, Any?>>) {
        if (rows.isEmpty()) return
        file.parentFile.mkdirs()
        val header = file.takeIf { it.isFile }?.bufferedReader()?.use { it.readLine() }?.split(",") ?: columns
        file.appendText(buildString {
            if (!file.isFile || file.length() == 0L) append(header.joinToString(",") + "\n")
            else if (!file.readText().endsWith("\n")) append("\n")
            rows.forEach { row -> append(header.joinToString(",") { row[it]?.toString() ?: "" } + "\n") }
        })
    }
}
