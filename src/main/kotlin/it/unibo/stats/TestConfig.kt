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
                    val postgres = PostgresConfig()
                    DatasetConfig(
                        sizes = v["sizes"] as List<Any>,
                        // Each missing key: the one of the PostgreSQL of docker-compose.yaml
                        postgres = PostgresConfig(
                            host = v["postgres_endpoint"]?.toString() ?: postgres.host,
                            port = v["port"]?.toString()?.toInt() ?: postgres.port,
                            database = v["database"]?.toString() ?: postgres.database,
                            user = v["user"]?.toString() ?: postgres.user,
                            password = v["password"]?.toString() ?: postgres.password
                        )
                    )
                }

            // --- setups ---
            val setups = (raw["setups"] as Map<String, Map<String, Any>>)
                .mapValues { (_, v) ->
                    SetupConfig(
                        host = v["host"] as String,
                        port = v["port"] as Int,
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

            // --- runs: the specific runs ---
            val specificRuns = raw["specificRuns"] as? Map<String, Any> ?: error("No specificRuns section in $path: copy it from test_config.example.yml")
            val runs = (specificRuns["runs"] as List<Map<String, Any>>).map { r ->
                RunConfig(
                    approaches = r["approaches"] as List<String>,
                    datasets = r["datasets"] as List<String>,
                    sizes = r["sizes"] as? List<Any>,
                    modes = r["modes"] as? List<String>,
                    threads = (r["threads"] as? List<Number>),
                    setups = r["setups"] as? List<String>
                )
            }

            val config = Config(datasets, setups, defaults, runs)
            return config
        }

        /** The specific runs of STGraph on [dataset]: the tests run only STGraph */
        private fun stgraphRuns(config: Config, dataset: String): List<RunConfig> =
            config.runs.filter { "stgraph" in it.approaches && dataset in it.datasets }

        /** Loads [dataset] into STGraph for every size, setup and number of threads of the specific runs of STGraph on it */
        fun runIngestion(dataset: String, loader: (String, Int, String, List<String>) -> Loader) {
            val config: Config = loadConfig("src/main/resources/test_config.yml")
            val combinations = stgraphRuns(config, dataset).flatMap { run ->
                val sizes = run.sizes ?: config.datasets[dataset]!!.sizes
                val setupNames = run.setups ?: config.defaults.setups
                val threads = run.threads?.map { it.toInt() } ?: config.defaults.threads
                sizes.flatMap { rawSize -> setupNames.flatMap { setupName -> threads.map { Triple(normalizeSize(rawSize), setupName, it) } } }
            }.distinct()
            combinations.forEach { (size, setupName, thread) ->
                val setup = config.setups[setupName] ?: error("Unknown setup: $setupName")
                resetPort()
                logger.info("dataset: $dataset size: $size threads: $thread setup: $setupName")
                loadDataset(loader(size, thread, setup.host, setup.controllerIPs), "stgraph", threads = thread, numMachines = setup.controllerIPs.toSet().size, dataset, size)
            }
        }

        fun runTest(
            dataset: String,
            queryBuilder: (graph: Graph, size: String, mode: QueryMode) -> Querying?,
        ) {
            val config: Config = loadConfig("src/main/resources/test_config.yml")
            stgraphRuns(config, dataset).forEach { run ->
                val modes = (run.modes ?: config.defaults.modes).map { QueryMode.valueOf(it) }
                val threads = run.threads ?: config.defaults.threads
                val setupNames = run.setups ?: config.defaults.setups
                val selectedSetups = setupNames.map { name ->
                    config.setups[name]?: error("Unknown setup: $name")
                }
                config.datasets.filter { (datasetName, _) -> datasetName == dataset }.forEach { (datasetName, datasetConfig) ->
                    matrixFromConfig(
                        dataset = datasetName,
                        sizes = run.sizes ?: datasetConfig.sizes,
                        setups = selectedSetups,
                        threads = threads,
                        modes = modes,
                        approaches = listOf("stgraph"),
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
                                port = setup.port,
                                controllerIps = setup.controllerIPs,
                                dataverse = "${dataset}_$size",
                                multiTs = false // I only need to read the data
                            )
                            graph.tsm = tsm
                            threads.forEach { thread ->
                                val query = queryBuilder(graph, size, mode)
                                // The number of node controllers, as for the ingestion (1 on a local setup)
                                val numMachines = setup.controllerIPs.toSet().size

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
    val runs: List<RunConfig>
)

data class DatasetConfig(
    val sizes: List<Any>,
    val postgres: PostgresConfig = PostgresConfig()
)

/**
 * The PostgreSQL the ingestion reads the dataset from (MIMIC-IV: keys postgres_endpoint, port, database, user and
 * password of datasets.mimic); by default, the mimic-postgres of docker-compose.yaml
 */
data class PostgresConfig(
    val host: String = "localhost",
    val port: Int = 5434,
    val database: String = "mimic-iv",
    val user: String = "postgres",
    val password: String = "password"
)

data class SetupConfig(
    val host: String,
    val port: Int,
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
    val sizes: List<Any>?,      // optional → fallback to datasets.<dataset>.sizes
    val modes: List<String>?,   // optional → fallback to defaults
    val threads: List<Number>?,
    val setups: List<String>?   // optional → fallback to defaults
)