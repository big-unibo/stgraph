package it.unibo.tests.evaluation

import it.unibo.graph.query.QueryMode
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * The evaluation section of test_config.yml (see test_config.example.yml): which systems run on which datasets, and
 * how. The sizes of the datasets and the setups of STGraph come from the rest of the file (TestConfig).
 */
data class EvaluationConfig(
    val systems: List<String>,
    val datasets: List<String>,
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
    /** shared_buffers and effective_cache_size of PGAge (null: the values of the paper, in its postgresql.conf) */
    val pgSharedBuffers: String? = null,
    val pgEffectiveCacheSize: String? = null
) {
    companion object {
        val knownSystems = listOf("stgraph", "stgraph-std", "neo4j", "pgage", "graphix", "aeong", "hygraph")

        @Suppress("UNCHECKED_CAST")
        fun load(path: String = "src/main/resources/test_config.yml"): EvaluationConfig {
            val raw = Yaml().load<Map<String, Any?>>(File(path).inputStream())
            val section = raw["evaluation"] as? Map<String, Any?>
                ?: error("No evaluation section in $path: copy it from test_config.example.yml")
            val ingestion = section["ingestion"] as? Map<String, Any?> ?: emptyMap()
            val queries = section["queries"] as? Map<String, Any?> ?: emptyMap()
            val pgage = section["pgage"] as? Map<String, Any?> ?: emptyMap()
            fun strings(map: Map<String, Any?>, key: String, default: List<String>) = (map[key] as? List<Any?>)?.map { it.toString() } ?: default

            val config = EvaluationConfig(
                systems = strings(section, "systems", emptyList()),
                datasets = strings(section, "datasets", emptyList()),
                setups = strings(section, "setups", listOf("c0")),
                threads = strings(section, "threads", listOf("1")).map { it.toInt() },
                modes = strings(section, "modes", listOf("OPTIMIZED")).map { QueryMode.valueOf(it) },
                ingestionIterations = (ingestion["iterations"] as? Int) ?: 1,
                queryIterations = (queries["iterations"] as? Int) ?: 1,
                selectivities = strings(queries, "selectivities", listOf("increased")),
                queryIds = strings(queries, "ids", emptyList()),
                download = (section["download"] as? Boolean) ?: true,
                deploy = (section["deploy"] as? Boolean) ?: true,
                pgSharedBuffers = pgage["sharedBuffers"]?.toString(),
                pgEffectiveCacheSize = pgage["effectiveCacheSize"]?.toString()
            )
            val unknown = config.systems - knownSystems.toSet()
            require(unknown.isEmpty()) { "Unknown systems in $path: $unknown (known: $knownSystems)" }
            return config
        }
    }
}
