package it.unibo.tests.evaluation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.UUID
import kotlin.math.roundToLong

/**
 * The results of AeonG in the files of the other systems. AeonG times its load and its queries inside its container,
 * which writes a statistics file per load and per query in [folder] (AEONG_RESULTS of its compose file, a temporary
 * folder), under query_results/<dataset>: ingestion_stats/ingestion_<size>_<iteration>.json (times in microseconds),
 * query_stats/<query>_sz<size>_wrk<threads>_it<iteration>_sel<selectivity>_tr<range>.json (the duration in seconds),
 * and query_cards/<the same name>.txt, the number of rows of the query (see run_queries.py). [export] appends
 * the files not exported yet to results/dt_graph (ingestion_time/ingestion_statistics.csv,
 * query_time/query_statistics.csv), with the columns and the default values of loadDataset and runQuery: one test_id
 * per row, times in ms, the model, dataset and size of the evaluation, the configuration (conf or conf-free),
 * iterations from 0, unknown cardinalities as -1.
 * It runs while the container runs too (see DatasetEvaluation.selfContained), so the results are kept as they come.
 */
internal class AeonGResults(
    private val folder: File,
    private val model: String,
    private val dataset: String,
    private val size: String,
    private val configuration: String
) {
    private val mapper = jacksonObjectMapper()
    private val exported = HashSet<String>()
    private val ingestionIn = File(folder, "query_results/$dataset/ingestion_stats")
    private val queryIn = File(folder, "query_results/$dataset/query_stats")
    private val cardsIn = File(folder, "query_results/$dataset/query_cards")

    /**
     * Appends the statistics not exported yet: one still being written, or a query without its cardinality yet, is
     * exported by the next call; the [last] one (the container stopped) exports a query without cardinality as -1
     */
    @Synchronized
    fun export(last: Boolean = false) {
        val ingestion = newFiles(ingestionIn).mapNotNull { file ->
            val r = read(file) ?: return@mapNotNull null
            exported += file.name
            val start = (r["start_time"] as? Number)?.toLong()?.div(1000) // microseconds
            val end = (r["end_time"] as? Number)?.toLong()?.div(1000)
            val elapsed = if (start != null && end != null) end - start else -1
            mapOf(
                "test_id" to UUID.randomUUID().toString(), "model" to model, "startTimestamp" to start, "endTimestamp" to end,
                "dataset" to dataset, "datasetSize" to size, "threads" to 1, "graphElapsedTime" to elapsed, "tsElapsedTime" to 0,
                "indexTime" to 0, "elapsedTime" to elapsed, "numMachines" to 1, "storage" to (r["storage_consumption"] ?: -1),
                "tsStorage" to -1, "gsStorage" to -1, "tsCard" to -1, "gsCard" to -1, "configuration" to configuration
            )
        }
        val queries = newFiles(queryIn).mapNotNull { file ->
            val card = File(cardsIn, file.nameWithoutExtension + ".txt")
            if (!last && !card.isFile) return@mapNotNull null
            val duration = (read(file)?.get("duration") as? Number)?.toDouble() ?: return@mapNotNull null // seconds
            exported += file.name
            // <query>_sz<size>_wrk<threads>_it<iteration>_sel<selectivity>_tr<range>
            val parts = file.nameWithoutExtension.split("_")
            val queryId = parts[0]
            fun part(prefix: String) = parts.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
            mapOf(
                "test_id" to UUID.randomUUID().toString(), "queryid" to queryId, "model" to model, "dataset" to dataset,
                "datasetSize" to size, "threads" to (part("wrk") ?: 1),
                "elapsedTime" to maxOf((duration * 1000).roundToLong(), 1L),
                "numMachines" to 1,
                "cardinality" to (card.takeIf { it.isFile }?.readText()?.trim() ?: -1),
                "queryMode" to "OPTIMIZED",
                "iteration" to ((part("it")?.toIntOrNull() ?: 1) - 1), // from 1 in AeonG
                "querySelectivity" to (part("sel") ?: ""), "temporalRangeIndex" to (part("tr") ?: 0),
                "configuration" to configuration
            )
        }
        append(ingestionOut, ingestionColumns, ingestion)
        append(queryOut, queryColumns, queries)
        if (ingestion.isNotEmpty() || queries.isNotEmpty()) {
            progress("aeong | $dataset $size: ${ingestion.size} ingestion(s) and ${queries.size} query run(s) recorded in results/dt_graph")
        }
    }

    /** The JSON files of [dir] not exported yet, in the order they were written */
    private fun newFiles(dir: File): List<File> =
        dir.listFiles { f -> f.name.endsWith(".json") && f.name !in exported }.orEmpty().sortedBy { it.lastModified() }

    /** The first JSON object of [file] (of the client, one per line, or of create_database.sh), null if not complete yet */
    private fun read(file: File): Map<String, Any?>? = try {
        mapper.readerFor(Map::class.java).readValues<Map<String, Any?>>(file.readText()).next()
    } catch (e: Exception) {
        null
    }

    companion object {
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

        /**
         * Appends [rows] to [file] with its header (or [columns] if new); a file written before the last columns were
         * added (e.g., configuration) gets them in its header, the old rows leave them empty (as loadDataset)
         */
        private fun append(file: File, columns: List<String>, rows: List<Map<String, Any?>>) {
            if (rows.isEmpty()) return
            file.parentFile.mkdirs()
            val old = file.takeIf { it.isFile }?.bufferedReader()?.use { it.readLine() }?.split(",")
            val header = old?.let { it + (columns - it.toSet()) } ?: columns
            if (old != null && header.size > old.size) {
                val lines = file.readLines()
                file.writeText((listOf(header.joinToString(",")) + lines.drop(1)).joinToString("\n", postfix = "\n"))
            }
            file.appendText(buildString {
                if (!file.isFile || file.length() == 0L) append(header.joinToString(",") + "\n")
                else if (!file.readText().endsWith("\n")) append("\n")
                rows.forEach { row -> append(header.joinToString(",") { row[it]?.toString() ?: "" } + "\n") }
            })
        }
    }
}
