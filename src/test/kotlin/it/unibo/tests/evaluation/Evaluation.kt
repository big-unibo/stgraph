package it.unibo.tests.evaluation

import it.unibo.stats.TestConfig
import it.unibo.tests.smartbench.SmartBenchEvaluation
import java.io.File
import kotlin.system.exitProcess

/**
 * The evaluation configured by the evaluation section of test_config.yml: for each dataset, size and system (one at a
 * time), it downloads the missing data, deploys the system, loads the data, runs the queries and stops the system.
 * The results go to results/ (see evaluation/README.md). Run with ./gradlew evaluate or from the IDE, from the
 * repository root. A system that fails is reported at the end, the others go on.
 */
fun main() {
    val config = TestConfig.loadConfig()
    val evaluation = EvaluationConfig.load()
    val sizes = evaluation.datasets.associateWith { dataset ->
        config.datasets[dataset]?.sizes?.map { TestConfig.normalizeSize(it) } ?: error("No sizes for the dataset $dataset in test_config.yml")
    }
    logParameters(evaluation, sizes)
    val failures = mutableListOf<String>()
    for ((dataset, datasetSizes) in sizes) {
        for (size in datasetSizes) {
            for (system in evaluation.systems) {
                progress("=== $system | $dataset $size ===")
                try {
                    when (dataset) {
                        "smartbench" -> SmartBenchEvaluation(config, evaluation, size).run(system)
                        else -> error("Dataset not supported by the evaluation: $dataset")
                    }
                } catch (e: Exception) {
                    progress("$system | $dataset $size: FAILED ($e)")
                    e.printStackTrace()
                    failures += "$system | $dataset $size: ${e.message}"
                }
            }
        }
    }
    if (failures.isNotEmpty()) {
        progress("evaluation done, ${failures.size} failed run(s):")
        failures.forEach { progress("  $it") }
        exitProcess(1)
    }
    progress("evaluation done")
}

private fun logParameters(evaluation: EvaluationConfig, sizes: Map<String, List<String>>) {
    progress("evaluation parameters (evaluation section of test_config.yml):")
    progress("  systems: ${evaluation.systems.joinToString()}")
    progress("  datasets: " + sizes.entries.joinToString("; ") { (dataset, s) -> "$dataset (sizes: ${s.joinToString()})" })
    progress("  ingestion: ${evaluation.ingestionIterations} iteration(s)")
    progress(
        "  queries: ${evaluation.queryIterations} iteration(s), selectivities: ${evaluation.selectivities.joinToString()}, " +
            "ids: ${evaluation.queryIds.ifEmpty { listOf("all") }.joinToString()}"
    )
    progress("  STGraph: setups ${evaluation.setups.joinToString()}, threads ${evaluation.threads.joinToString()}, modes ${evaluation.modes.joinToString()}")
    progress(
        "  download: ${evaluation.download}, deploy: ${evaluation.deploy}, PGAge memory: " +
            "shared_buffers ${evaluation.pgSharedBuffers ?: "of the paper"}, effective_cache_size ${evaluation.pgEffectiveCacheSize ?: "of the paper"}"
    )
}

/** Runs [command] from the repository root, with the output on the console; fails if it does not succeed */
fun runCommand(command: List<String>, env: Map<String, String> = emptyMap()) {
    progress("  $ ${command.joinToString(" ")}")
    val process = ProcessBuilder(command).directory(File(".")).inheritIO().apply { environment().putAll(env) }.start()
    val code = process.waitFor()
    check(code == 0) { "Exit code $code: ${command.joinToString(" ")}" }
}

/**
 * Runs [block] on the system of [composeFile] (or its [service]): when [enabled], the system is started before (with
 * the variables [env] for the compose file) and stopped after, waiting until [ready] succeeds [readyChecks] times in a
 * row (e.g., PostgreSQL restarts once while
 * initializing). Stopped, not removed: the data in the containers survive (e.g., to query them again with deploy: false)
 */
fun <T> deployed(
    enabled: Boolean,
    composeFile: String,
    service: String? = null,
    readyChecks: Int = 1,
    readyTimeoutMs: Long = 15 * 60 * 1000,
    env: Map<String, String> = emptyMap(),
    ready: () -> Unit,
    block: () -> T
): T {
    val system = listOfNotNull(composeFile, service).joinToString(" ")
    if (!enabled) {
        progress("deploy | $system: not managed (deploy: false), expected to be running")
        return block()
    }
    val services = listOfNotNull(service)
    progress("deploy | $system: starting")
    runCommand(listOf("docker", "compose", "-f", composeFile, "up", "-d") + services, env)
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
        progress("deploy | $system: ready")
        return block()
    } finally {
        progress("deploy | $system: stopping")
        runCommand(listOf("docker", "compose", "-f", composeFile, "stop") + services)
    }
}
