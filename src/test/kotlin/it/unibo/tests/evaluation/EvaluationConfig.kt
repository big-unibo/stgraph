package it.unibo.tests.evaluation

import it.unibo.graph.query.QueryMode
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * A configuration of the evaluation, from test_config.yml (see test_config.example.yml): which systems run on which
 * datasets, and how. The setups of STGraph come from the rest of the file (TestConfig).
 * - [load]: the evaluation section, i.e., every combination of its parameters (./gradlew evaluate);
 * - [loadSpecificRuns]: the entries of the specificRuns section, each one with all its parameters, independent of the
 *   evaluation section (./gradlew evaluateSpecificRuns).
 */
data class EvaluationConfig(
    val systems: List<String>,
    val datasets: List<String>,
    /** Sizes of every dataset (null: the ones of datasets.<dataset>.sizes) */
    val sizes: List<String>?,
    /** Setups (AsterixDB) of STGraph */
    val setups: List<String>,
    /** Threads of STGraph; the competitors run single-threaded */
    val threads: List<Int>,
    /** Query modes of STGraph */
    val modes: List<QueryMode>,
    val ingestionIterations: Int,
    val queryIterations: Int,
    /** Selectivities of time_constraints.yaml: each query runs on every time window of each one */
    val selectivities: List<String>,
    /** Query ids to run (empty: all) */
    val queryIds: List<String>,
    /** Downloads the missing datasets */
    val download: Boolean,
    /** Starts each system before its runs and stops it after, one system at a time */
    val deploy: Boolean
) {
    companion object {
        val knownSystems = listOf("stgraph", "stgraph-std", "neo4j", "pgage", "graphix", "aeong", "hygraph")

        /** The evaluation section of [path] */
        @Suppress("UNCHECKED_CAST")
        fun load(path: String = "src/main/resources/test_config.yml"): EvaluationConfig {
            val section = yaml(path)["evaluation"] as? Map<String, Any?>
                ?: error("No evaluation section in $path: copy it from test_config.example.yml")
            return parse(section, path)
        }

        /** The runs of the specificRuns section of [path] */
        @Suppress("UNCHECKED_CAST")
        fun loadSpecificRuns(path: String = "src/main/resources/test_config.yml"): List<EvaluationConfig> {
            val runs = yaml(path)["specificRuns"] as? List<Map<String, Any?>>
            require(!runs.isNullOrEmpty()) { "No specificRuns section in $path: copy it from test_config.example.yml" }
            return runs.map { parse(it, path) }
        }

        private fun yaml(path: String): Map<String, Any?> = Yaml().load(File(path).inputStream())

        /** The parameters of [map], with the defaults for the ones it does not set */
        @Suppress("UNCHECKED_CAST")
        private fun parse(map: Map<String, Any?>, path: String): EvaluationConfig {
            val ingestion = map["ingestion"] as? Map<String, Any?> ?: emptyMap()
            val queries = map["queries"] as? Map<String, Any?> ?: emptyMap()
            fun strings(m: Map<String, Any?>, key: String): List<String>? = (m[key] as? List<Any?>)?.map { it.toString() }

            val config = EvaluationConfig(
                systems = strings(map, "systems") ?: emptyList(),
                datasets = strings(map, "datasets") ?: emptyList(),
                sizes = strings(map, "sizes"),
                setups = strings(map, "setups") ?: listOf("c0"),
                threads = strings(map, "threads")?.map { it.toInt() } ?: listOf(1),
                modes = strings(map, "modes")?.map { QueryMode.valueOf(it) } ?: listOf(QueryMode.OPTIMIZED),
                ingestionIterations = ingestion["iterations"] as? Int ?: 1,
                queryIterations = queries["iterations"] as? Int ?: 1,
                selectivities = strings(queries, "selectivities") ?: listOf("increased"),
                queryIds = strings(queries, "ids") ?: emptyList(),
                download = map["download"] as? Boolean ?: true,
                deploy = map["deploy"] as? Boolean ?: true
            )
            val unknown = config.systems - knownSystems.toSet()
            require(unknown.isEmpty()) { "Unknown systems in $path: $unknown (known: $knownSystems)" }
            return config
        }
    }
}
