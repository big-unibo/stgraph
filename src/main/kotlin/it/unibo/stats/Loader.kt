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

fun loadDataset(loader: Loader, model: String, threads: Int, numMachines: Int, dataset: String, size: String) {
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
        "gsCard" to loader.getGSCardinality()
    )
    val writeHeader = !statisticsFile.exists()
    statisticsFile.appendText(buildString {
        if (writeHeader) append(row.keys.joinToString(",") + "\n")
        append(row.values.joinToString(",") + "\n")
    })
    loader.close()
}