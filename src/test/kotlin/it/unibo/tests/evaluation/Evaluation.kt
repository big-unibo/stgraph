package it.unibo.tests.evaluation

import it.unibo.stats.Config
import it.unibo.stats.TestConfig
import it.unibo.tests.smartbench.SmartBenchEvaluation
import it.unibo.tests.synth.SynthEvaluation
import java.io.File
import kotlin.system.exitProcess

/**
 * The evaluation configured by the evaluation section of test_config.yml: for each dataset, size and system (one at a
 * time), it downloads the missing data, deploys the system, loads the data, runs the queries and stops the system.
 * Without arguments (./gradlew evaluate), every combination of the parameters of the section; with the argument
 * "specificRuns" (./gradlew evaluateSpecificRuns), the runs of the specificRuns section, one after the other. With
 * "ingestion" or "queries" (./gradlew evaluateIngestion, evaluateQueries, evaluateSpecificRunsIngestion,
 * evaluateSpecificRunsQueries), only that phase: the queries run on the data already loaded (see the volumes section).
 * With "fastLoad" and a system (./gradlew fastLoadNeo4j, fastLoadPgAge), the bulk load of SmartBench into it, for each
 * size of the evaluation section (see SmartBenchEvaluation.fastLoad). The results go to results/
 * (see evaluation/README.md). Run from the repository root. A system that fails is reported at the end, the others go on.
 */
fun main(args: Array<String>) {
    val config = TestConfig.loadConfig()
    if ("fastLoad" in args) fastLoad(config, args.getOrNull(args.indexOf("fastLoad") + 1) ?: error("fastLoad needs a system"))
    val byRuns = "specificRuns" in args
    val onlyIngestion = "ingestion" in args
    val onlyQueries = "queries" in args
    require(!(onlyIngestion && onlyQueries)) { "Either ingestion or queries, not both (both: no argument)" }
    // Only one phase: the other one runs zero times
    val runs = (if (byRuns) EvaluationConfig.loadSpecificRuns() else listOf(EvaluationConfig.load())).map {
        when {
            onlyIngestion -> it.copy(queryIterations = 0)
            onlyQueries -> it.copy(ingestionIterations = 0)
            else -> it
        }
    }
    val failures = mutableListOf<String>()
    runs.forEachIndexed { index, evaluation ->
        val run = if (byRuns) "specific run ${index + 1}/${runs.size}" else "evaluation"
        val sizes = evaluation.datasets.associateWith { dataset ->
            evaluation.sizes ?: config.datasets[dataset]?.sizes?.map { TestConfig.normalizeSize(it) }
                ?: error("No sizes for the dataset $dataset in test_config.yml")
        }
        logParameters(run, evaluation, sizes)
        for ((dataset, datasetSizes) in sizes) {
            for (size in datasetSizes) {
                for (system in evaluation.systems) {
                    progress("=== $run: $system | $dataset $size ===")
                    try {
                        when (dataset) {
                            "smartbench" -> SmartBenchEvaluation(config, evaluation, size).run(system)
                            "synth" -> SynthEvaluation(config, evaluation, size).run(system)
                            else -> error("Dataset not supported by the evaluation: $dataset")
                        }
                    } catch (e: Exception) {
                        progress("$system | $dataset $size: FAILED ($e)")
                        e.printStackTrace()
                        failures += "$run, $system | $dataset $size: ${e.message}"
                    }
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
    // Explicit exit: the JVM would otherwise wait for the threads left alive (e.g., the thread pools of the queries of
    // STGraph, see Query.kt) and ./gradlew would never end
    exitProcess(0)
}

/**
 * The bulk load of SmartBench into [system] (neo4j or pgage), for each size of the evaluation section (default:
 * datasets.smartbench.sizes), to query it afterwards; then the process ends
 */
private fun fastLoad(config: Config, system: String): Nothing {
    val evaluation = EvaluationConfig.load()
    val sizes = evaluation.sizes ?: config.datasets["smartbench"]?.sizes?.map { TestConfig.normalizeSize(it) }
        ?: error("No sizes for the dataset smartbench in test_config.yml")
    progress("fast load | $system | smartbench (sizes: ${sizes.joinToString()}), deploy: ${evaluation.deploy}, data folders: " +
        evaluation.volumeFolders.entries.joinToString { (k, v) -> "$k=$v" }.ifEmpty { "the Docker volumes" } +
        ", resources: " + evaluation.resources.entries.joinToString { (k, v) -> "$k=$v" }.ifEmpty { "those of the compose files" })
    val failures = sizes.filter { size ->
        runCatching { SmartBenchEvaluation(config, evaluation, size).fastLoad(system) }
            .onFailure { progress("fast load | $system | smartbench $size: FAILED ($it)"); it.printStackTrace() }
            .isFailure
    }
    progress("fast load done" + if (failures.isEmpty()) "" else ", failed: ${failures.joinToString()}")
    exitProcess(if (failures.isEmpty()) 0 else 1)
}

private fun logParameters(run: String, evaluation: EvaluationConfig, sizes: Map<String, List<String>>) {
    progress("$run, parameters (test_config.yml):")
    progress(
        "  phases: " + when {
            evaluation.queryIterations == 0 -> "ingestion only"
            evaluation.ingestionIterations == 0 -> "queries only, on the data already loaded"
            else -> "ingestion and queries"
        }
    )
    progress("  systems: ${evaluation.systems.joinToString()}")
    progress("  datasets: " + sizes.entries.joinToString("; ") { (dataset, s) -> "$dataset (sizes: ${s.joinToString()})" })
    progress("  ingestion: ${evaluation.ingestionIterations} iteration(s)")
    progress(
        "  queries: ${evaluation.queryIterations} iteration(s), selectivities: ${evaluation.selectivities.joinToString()}, " +
            "ids: ${evaluation.queryIds.ifEmpty { listOf("all") }.joinToString()}"
    )
    progress("  STGraph: setups ${evaluation.setups.joinToString()}, threads ${evaluation.threads.joinToString()}, modes ${evaluation.modes.joinToString()}")
    progress("  download: ${evaluation.download}, deploy: ${evaluation.deploy}")
    progress("  label: " + (evaluation.label?.let { "$it (models of the results: ${evaluation.model("<system>")})" } ?: "none"))
    progress("  data folders: " + evaluation.volumeFolders.entries.joinToString { (k, v) -> "$k=$v" }.ifEmpty { "the Docker volumes" })
    progress("  resources: " + evaluation.resources.entries.joinToString { (k, v) -> "$k=$v" }.ifEmpty { "those of the compose files" })
    progress("  heap of this JVM (STGraph): ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB, processors: ${Runtime.getRuntime().availableProcessors()}")
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
        runCommand(listOf("docker", "compose", "-f", composeFile, "stop") + services, env)
    }
}
