package it.unibo.stats

import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.interfaces.Graph
import it.unibo.graph.query.QueryMode
import it.unibo.graph.utils.TimeRange
import it.unibo.graph.utils.resetPort
import java.util.logging.Logger
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.get

class TestConfig {
    companion object {
        val logger = Logger.getLogger(this::class.java.toString())

        fun loadTemporalRanges(
            constraintType: String,
            queryName: String,
            datasetSize: String
        ): Map<Int, TimeRange> {
            val resourcePath = "time_constraints.yaml"
            val inputStream = this::class.java.classLoader.getResourceAsStream(resourcePath) ?: throw IllegalArgumentException("$resourcePath not found in classpath")
            val yamlText = inputStream.bufferedReader().use { it.readText() }
            val yamlMap = Yaml().load<Map<String, Any>>(yamlText) ?: emptyMap()

            val temporalConstraints = yamlMap["temporalConstraints"] as? Map<*, *>
                ?: throw IllegalArgumentException("'temporalConstraints' not found in YAML")
            val constraintMap = temporalConstraints[constraintType] as? Map<*, *>
                ?: throw IllegalArgumentException("Constraint type '$constraintType' not found in YAML")
            val queryMap = constraintMap[queryName] as? Map<*, *>
                ?: throw IllegalArgumentException("Query '$queryName' not found under '$constraintType'")
            val datasetMap = queryMap[datasetSize] as? Map<*, *>
                ?: throw IllegalArgumentException("Dataset size '$datasetSize' not found under '$queryName'")

            return datasetMap.entries.associate { (k, v) ->
                val index = k.toString().toInt()
                val raw = v as List<*>
                index to TimeRange(
                    from = raw[0].toString().toLong(),
                    to = raw[1].toString().toLong()
                )
            }
        }

        fun normalizeSize(size: Any): String {
            return when (size) {
                is Long -> if (size == Long.MAX_VALUE) "full" else size.toString()
                else -> size.toString()
            }
        }

        fun loadConfig(path: String = "src/main/resources/test_config.yml"): Config {
            val yaml = Yaml()
            val raw = yaml.load<Map<String, Any>>(File(path).inputStream())

            // --- datasets ---
            val datasets = (raw["datasets"] as Map<String, Map<String, Any>>)
                .mapValues { (_, v) ->
                    DatasetConfig(
                        sizes = v["sizes"] as List<Any>
                    )
                }

            // --- setups ---
            val setups = (raw["setups"] as Map<String, Map<String, Any>>)
                .mapValues { (_, v) ->
                    SetupConfig(
                        host = v["host"] as String,
                        controllerIPs = v["controllerIPs"] as List<String>
                    )
                }

            // --- defaults ---
            val defaultsRaw = raw["defaults"] as? Map<String, Any> ?: emptyMap()
            val defaults = DefaultsConfig(
                modes = (defaultsRaw["modes"] as List<String>),
                setups = (defaultsRaw["setups"] as List<String>),
                threads = (defaultsRaw["threads"] as List<Int>)
            )

            // --- runs ---
            val runs = (raw["runs"] as List<Map<String, Any>>).map { r ->
                RunConfig(
                    approaches = r["approaches"] as List<String>,
                    datasets = r["datasets"] as List<String>,
                    modes = r["modes"] as? List<String>,
                    threads = (r["threads"] as? List<Number>),
                    setups = r["setups"] as? List<String>
                )
            }

            val ingestionRaw = raw["ingestion"] as Map<String, Any>
            val ingestion = ingestionRaw.let {
                IngestionConfig(
                    enabled = it["enabled"] as Boolean,
                    datasets = it["datasets"] as List<String>,
                    setups = it["setups"] as List<String>,
                    threads = it["threads"] as List<Int>,
                )
            }

            val config = Config(datasets, setups, defaults, runs, ingestion)
            return config
        }

        fun runIngestion(dataset: String, loader: (String, Int, String, List<String>) -> Loader) {
            val config: Config = loadConfig("src/main/resources/test_config.yml")
            val ingestion = config.ingestion ?: return
            if (!ingestion.enabled) return
            val setups = ingestion.setups.map { name ->
                name to config.setups[name]!!
            }
            ingestion.datasets.filter { it == dataset }.forEach { datasetName ->
                val dataset = config.datasets[datasetName]!!
                dataset.sizes.forEach { rawSize ->
                    val size = normalizeSize(rawSize)
                    setups.forEach { (setupName, setup) ->
                        ingestion.threads.forEach { thread ->
                            resetPort()
                            logger.info("dataset: $datasetName size: $size threads: $thread setup: $setupName")
                            loadDataset(loader(size, thread, setup.host, setup.controllerIPs), "stgraph", threads = thread, numMachines = setup.controllerIPs.toSet().size, datasetName, size)
                        }
                    }
                }
            }
        }

        fun runTest(
            dataset: String,
            queryBuilder: (graph: Graph, size: String, mode: QueryMode) -> Querying?,
        ) {
            val config: Config = loadConfig("src/main/resources/test_config.yml")
            config.runs.forEach { run ->
                val modes = (run.modes ?: config.defaults.modes).map { QueryMode.valueOf(it) }
                val threads = run.threads ?: config.defaults.threads
                val setupNames = run.setups ?: config.defaults.setups
                val selectedSetups = setupNames.map { name ->
                    config.setups[name]?: error("Unknown setup: $name")
                }
                config.datasets.filter { (datasetName, _) -> datasetName == dataset }.forEach { (datasetName, datasetConfig) ->
                    matrixFromConfig(
                        dataset = datasetName,
                        sizes = datasetConfig.sizes,
                        setups = selectedSetups,
                        threads = threads,
                        modes = modes,
                        approaches = run.approaches,
                        queryBuilder = queryBuilder
                    )
                }
            }
        }

        private fun matrixFromConfig(
            dataset: String,
            sizes: List<Any>,
            setups: List<SetupConfig>,
            threads: List<Number>,
            modes: List<QueryMode>,
            approaches: List<String>,
            queryBuilder: (graph: Graph, size: String, mode: QueryMode) -> Querying?
        ) {
            approaches.forEach { approach ->
                setups.forEach { setup ->
                    sizes.forEach { rawSize ->
                        modes.forEach { mode ->
                            val size = normalizeSize(rawSize)
                            val path = "datasets/dump/$dataset/$size/"
                            val graph = MemoryGraphACID.readFromDisk(path)
                            val tsm = AsterixDBTSM.createDefault(
                                graph,
                                host = setup.host,
                                controllerIps = setup.controllerIPs,
                                dataverse = "${dataset}_$size",
                                multiTs = false // I only need to read the data
                            )
                            graph.tsm = tsm
                            threads.forEach { thread ->
                                val query = queryBuilder(graph, size, mode)
                                val numMachines =
                                    if (setup.host == "localhost") -1
                                    else setup.controllerIPs.toSet().size

                                resetPort()
                                if (query != null) {
                                    logger.info(
                                        "${query.queryId} dataset: $dataset mode: $mode size: $size, " +
                                                "threads: $thread, numMachines: $numMachines " +
                                                "(${setup.host} : ${setup.controllerIPs})"
                                    )
                                    runQuery(
                                        query,
                                        approach,
                                        threads = thread.toInt(),
                                        numMachines = numMachines,
                                        dataset,
                                        size = size,
                                        mode = mode
                                    )
                                }
                            }
                            graph.close()
                        }
                    }
                }
            }
        }
    }
}

data class Config(
    val datasets: Map<String, DatasetConfig>,
    val setups: Map<String, SetupConfig>,
    val defaults: DefaultsConfig,
    val runs: List<RunConfig>,
    val ingestion: IngestionConfig
)

data class IngestionConfig(
    val enabled: Boolean = false,
    val datasets: List<String> = emptyList(),
    val setups: List<String> = emptyList(),
    val threads: List<Int> = emptyList(),
)

data class DatasetConfig(
    val sizes: List<Any>
)

data class SetupConfig(
    val host: String,
    val controllerIPs: List<String>
)

data class DefaultsConfig(
    val modes: List<String> = listOf("OPTIMIZED"),
    val setups: List<String> = listOf("c0"),
    val threads: List<Int> = listOf(1)
)

data class RunConfig(
    val approaches: List<String>,
    val datasets: List<String>,
    val modes: List<String>?,   // optional → fallback to defaults
    val threads: List<Number>?,
    val setups: List<String>?   // optional → fallback to defaults
)