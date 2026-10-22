package it.unibo.tests.evaluation

import it.unibo.stats.TestConfig
import it.unibo.tests.smartbench.SmartBenchEvaluation
import java.io.File
import java.util.logging.Logger
import kotlin.system.exitProcess

private val logger = Logger.getLogger("Evaluation")

/**
 * The evaluation configured by the evaluation section of test_config.yml: for each dataset, size and system (one at a
 * time), it downloads the missing data, deploys the system, loads the data, runs the queries and stops the system.
 * The results go to results/ (see evaluation/README.md). Run with ./gradlew evaluate or from the IDE, from the
 * repository root. A system that fails is reported at the end, the others go on.
 */
fun main() {
    val config = TestConfig.loadConfig()
    val evaluation = EvaluationConfig.load()
    val failures = mutableListOf<String>()
    for (dataset in evaluation.datasets) {
        val sizes = config.datasets[dataset]?.sizes?.map { TestConfig.normalizeSize(it) }
            ?: error("No sizes for the dataset $dataset in test_config.yml")
        for (size in sizes) {
            for (system in evaluation.systems) {
                logger.info("=== $dataset, $size: $system ===")
                try {
                    when (dataset) {
                        "smartbench" -> SmartBenchEvaluation(config, evaluation, size).run(system)
                        else -> error("Dataset not supported by the evaluation: $dataset")
                    }
                } catch (e: Exception) {
                    logger.severe("$dataset, $size: $system failed: $e")
                    e.printStackTrace()
                    failures += "$dataset, $size, $system: ${e.message}"
                }
            }
        }
    }
    if (failures.isNotEmpty()) {
        logger.severe("Failed runs:\n" + failures.joinToString("\n"))
        exitProcess(1)
    }
}

/** Runs [command] from the repository root, with the output on the console; fails if it does not succeed */
fun runCommand(command: List<String>, env: Map<String, String> = emptyMap()) {
    logger.info("$ ${command.joinToString(" ")}")
    val process = ProcessBuilder(command).directory(File(".")).inheritIO().apply { environment().putAll(env) }.start()
    val code = process.waitFor()
    check(code == 0) { "Exit code $code: ${command.joinToString(" ")}" }
}

/**
 * Runs [block] on the system of [composeFile] (or its [service]): when [enabled], the system is started before and
 * stopped after, waiting until [ready] succeeds [readyChecks] times in a row (e.g., PostgreSQL restarts once while
 * initializing). Stopped, not removed: the data in the containers survive (e.g., to query them again with deploy: false)
 */
fun <T> deployed(
    enabled: Boolean,
    composeFile: String,
    service: String? = null,
    readyChecks: Int = 1,
    readyTimeoutMs: Long = 15 * 60 * 1000,
    ready: () -> Unit,
    block: () -> T
): T {
    if (!enabled) return block()
    val services = listOfNotNull(service)
    runCommand(listOf("docker", "compose", "-f", composeFile, "up", "-d") + services)
    try {
        val deadline = System.currentTimeMillis() + readyTimeoutMs
        var successes = 0
        while (successes < readyChecks) {
            try {
                ready()
                successes++
            } catch (e: Exception) {
                successes = 0
                check(System.currentTimeMillis() < deadline) { "$composeFile not ready after ${readyTimeoutMs / 1000} s: $e" }
            }
            if (successes < readyChecks) Thread.sleep(2000)
        }
        logger.info("$composeFile ready")
        return block()
    } finally {
        runCommand(listOf("docker", "compose", "-f", composeFile, "stop") + services)
    }
}
