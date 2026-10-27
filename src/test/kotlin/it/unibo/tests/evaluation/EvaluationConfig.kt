package it.unibo.tests.evaluation

import it.unibo.graph.query.QueryMode
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * A configuration of the evaluation, from test_config.yml (see test_config.example.yml): which systems run on which
 * datasets, and how. Two levels, which take the parameters they do not set from the defaults section:
 * - [load]: the evaluation section, i.e., every combination of its parameters (./gradlew evaluate);
 * - [loadSpecificRuns]: the runs of the specificRuns section, one after the other (./gradlew evaluateSpecificRuns),
 *   the same runs that the tests of STGraph read (TestConfig).
 * The setups of STGraph and the sizes of the datasets come from the rest of the file (TestConfig), the data folders of
 * the systems from its volumes section.
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
    val deploy: Boolean,
    /**
     * The data folders of the systems, as the variables of their compose files (e.g., NEO4J_DATA to an absolute path),
     * from the volumes section; a system without one keeps its data in its Docker volume
     */
    val volumes: Map<String, String> = emptyMap()
) {
    companion object {
        val knownSystems = listOf("stgraph", "stgraph-std", "neo4j", "pgage", "graphix", "aeong", "hygraph")

        /** The variable of the compose file of each system of the volumes section (asterixdb: the AsterixDB of STGraph) */
        private val volumeVariables = mapOf(
            "asterixdb" to "ASTERIXDB_DATA",
            "graphix" to "GRAPHIX_DATA",
            "neo4j" to "NEO4J_DATA",
            "pgage" to "PGAGE_DATA"
        )

        /** The evaluation section of [path] */
        @Suppress("UNCHECKED_CAST")
        fun load(path: String = "src/main/resources/test_config.yml"): EvaluationConfig {
            val yaml = yaml(path)
            val section = yaml["evaluation"] as? Map<String, Any?>
                ?: error("No evaluation section in $path: copy it from test_config.example.yml")
            return parse(section, defaults(yaml), path).copy(volumes = volumes(yaml, path))
        }

        /** The runs of the specificRuns section of [path] */
        @Suppress("UNCHECKED_CAST")
        fun loadSpecificRuns(path: String = "src/main/resources/test_config.yml"): List<EvaluationConfig> {
            val yaml = yaml(path)
            val runs = (yaml["specificRuns"] as? Map<String, Any?>)?.get("runs") as? List<Map<String, Any?>>
            require(!runs.isNullOrEmpty()) { "No specificRuns.runs in $path: copy them from test_config.example.yml" }
            val volumes = volumes(yaml, path)
            return runs.map { parse(it, defaults(yaml), path).copy(volumes = volumes) }
        }

        private fun yaml(path: String): Map<String, Any?> = Yaml().load(File(path).inputStream())

        @Suppress("UNCHECKED_CAST")
        private fun defaults(yaml: Map<String, Any?>): Map<String, Any?> = yaml["defaults"] as? Map<String, Any?> ?: emptyMap()

        /**
         * The data folders of the volumes section of [yaml] (relative paths: from the repository root), created if
         * missing, so that Docker does not create them as root
         */
        @Suppress("UNCHECKED_CAST")
        private fun volumes(yaml: Map<String, Any?>, path: String): Map<String, String> {
            val section = yaml["volumes"] as? Map<String, Any?> ?: return emptyMap()
            val unknown = section.keys - volumeVariables.keys
            require(unknown.isEmpty()) { "Unknown systems in the volumes section of $path: $unknown (known: ${volumeVariables.keys})" }
            return section.mapNotNull { (system, folder) ->
                folder?.toString()?.takeIf { it.isNotBlank() }?.let {
                    val dir = File(it).absoluteFile.apply { mkdirs() }
                    volumeVariables.getValue(system) to dir.path
                }
            }.toMap()
        }

        /** The parameters of [map]; the ones it does not set come from [defaults], then from the built-in defaults */
        @Suppress("UNCHECKED_CAST")
        private fun parse(map: Map<String, Any?>, defaults: Map<String, Any?>, path: String): EvaluationConfig {
            fun section(m: Map<String, Any?>, key: String) = m[key] as? Map<String, Any?> ?: emptyMap()
            fun strings(m: Map<String, Any?>, key: String): List<String>? = (m[key] as? List<Any?>)?.map { it.toString() }
            fun strings(key: String) = strings(map, key) ?: strings(defaults, key)
            fun int(sectionKey: String, key: String): Int? = (section(map, sectionKey)[key] ?: section(defaults, sectionKey)[key]) as? Int
            fun boolean(key: String): Boolean? = (map[key] ?: defaults[key]) as? Boolean

            val config = EvaluationConfig(
                // "systems" in the evaluation section, "approaches" in the runs (as in the runs of the tests)
                systems = strings(map, "systems") ?: strings(map, "approaches") ?: emptyList(),
                datasets = strings(map, "datasets") ?: emptyList(),
                sizes = strings(map, "sizes"),
                setups = strings("setups") ?: listOf("c0"),
                threads = strings("threads")?.map { it.toInt() } ?: listOf(1),
                modes = strings("modes")?.map { QueryMode.valueOf(it) } ?: listOf(QueryMode.OPTIMIZED),
                ingestionIterations = int("ingestion", "iterations") ?: 1,
                queryIterations = int("queries", "iterations") ?: 1,
                selectivities = strings(section(map, "queries"), "selectivities") ?: strings(section(defaults, "queries"), "selectivities")
                    ?: listOf("increased"),
                queryIds = strings(section(map, "queries"), "ids") ?: strings(section(defaults, "queries"), "ids") ?: emptyList(),
                download = boolean("download") ?: true,
                deploy = boolean("deploy") ?: true
            )
            val unknown = config.systems - knownSystems.toSet()
            require(unknown.isEmpty()) { "Unknown systems in $path: $unknown (known: $knownSystems)" }
            return config
        }
    }
}
