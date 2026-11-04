package it.unibo.tests.mimic

import it.unibo.stats.Config
import it.unibo.tests.evaluation.DatasetEvaluation
import it.unibo.tests.evaluation.EvaluationConfig
import java.io.File

/**
 * The MIMIC-IV evaluation of one [size] (see it.unibo.tests.evaluation.main), for AeonG only: as for SmartBench, it
 * loads the data and runs the queries inside its container, with the query files of [queriesFolder] mounted as a volume
 * (the query_mimic.sh of its container, for DATASET=mimic, runs the files of [queryFiles] as they are). The size is the
 * one of the name of its dataset (<size>.cypher, e.g. 1692200).
 */
class MimicEvaluation(config: Config, evaluation: EvaluationConfig, size: String) :
    DatasetEvaluation(config, evaluation, "mimic", size) {
    private val queriesFolder = "queries/mimic"
    private val queryFiles = listOf("q0.txt", "q1.txt", "q2.txt", "q3.txt")

    override fun run(system: String) {
        require(system == "aeong") { "$system is not evaluated on $dataset by ./gradlew evaluate: only aeong" }
        val folder = File("evaluation/architectures/aeong/$queriesFolder")
        val missing = queryFiles.filterNot { File(folder, it).isFile }
        require(missing.isEmpty()) { "Missing query files of AeonG in $folder: $missing" }
        selfContained(system, size, queryFiles.map { it.removeSuffix(".txt") }, mapOf("AEONG_QUERIES" to "./$queriesFolder"))
    }
}
