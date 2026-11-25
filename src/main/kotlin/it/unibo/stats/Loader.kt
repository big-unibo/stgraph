package it.unibo.stats

import it.unibo.graph.query.QueryMode
import java.io.File
import java.util.*
import kotlin.math.round

interface Loader {
    fun getGSTime(): Long
    fun getTSTime(): Long
    fun getIndexTime(): Long
    fun getGSCardinality(): Long
    fun getTSCardinality(): Long
    fun loadData()
    fun close()
}

private const val resultPath = "results/dt_graph/ingestion_time"
private val resultFolder = File(resultPath)
private val statisticsFile = File(resultFolder, "ingestion_statistics.csv")

private fun getFolderSize(folderPath: String): Long {
    val folder = File(folderPath)
    val sizeBytes = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    return round(sizeBytes.toDouble() / (1024 * 1024)).toLong() // Converti in MB
}

/**
 * Loads the data with [loader] and appends its statistics to the ingestion statistics; [configuration]: the configuration
 * of the system (conf or conf-free, see evaluation/README.md), empty when not known (e.g., the tests)
 */
fun loadDataset(loader: Loader, model: String, threads: Int, numMachines: Int, dataset: String, size: String, configuration: String = "") {
    if (!resultFolder.exists()) resultFolder.mkdirs()
    val startTimestamp = System.currentTimeMillis()
    loader.loadData()
    val endTimestamp = System.currentTimeMillis()
    val gsStorage = -1
    val tsStorage = -1
    val row = linkedMapOf(
        "test_id" to UUID.randomUUID().toString(),
        "model" to model,
        "startTimestamp" to startTimestamp,
        "endTimestamp" to endTimestamp,
        "dataset" to dataset,
        "datasetSize" to size,
        "threads" to threads,
        "graphElapsedTime" to loader.getGSTime(),
        "tsElapsedTime" to loader.getTSTime(),
        "indexTime" to loader.getIndexTime(),
        "elapsedTime" to loader.getGSTime() + loader.getTSTime() + loader.getIndexTime(),
        "numMachines" to numMachines,
        "storage" to gsStorage + tsStorage,
        "tsStorage" to tsStorage,
        "gsStorage" to gsStorage,
        "tsCard" to loader.getTSCardinality(),
        "gsCard" to loader.getGSCardinality(),
        "configuration" to configuration
    )
    // A file written before the last columns were added: extend its header (the old rows leave them empty), as runQuery
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
    loader.close()
}