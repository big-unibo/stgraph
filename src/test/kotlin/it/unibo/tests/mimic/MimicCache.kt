package it.unibo.tests.mimic

import it.unibo.stats.PostgresConfig
import it.unibo.stats.TestConfig
import it.unibo.tests.evaluation.progress
import it.unibo.tests.mimic.loaders.AbstractMimicIVLoader
import it.unibo.tests.mimic.loaders.mimicLimit
import it.unibo.tests.smartbench.loaders.TSRecord

/**
 * The MIMIC-IV loader without a system: it only selects the subjects and time series, as every loader, and writes the
 * local copy of the data read from PostgreSQL (see AbstractMimicIVLoader.cacheDir)
 */
private class MimicIVCache(limit: Long, postgres: PostgresConfig) : AbstractMimicIVLoader(limit, threads = 1, postgres = postgres) {
    private var persons = 0L
    override fun addPerson(subjectId: Int, c: Int): Long = ++persons
    override fun addTimeseries(abbreviation: String, unitname: String?, category: String, label: String, itemid: Int, person: Long): Long = person
    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {}
    override fun loadData() { prepareCache() }
    override fun close() {}
}

/**
 * ./gradlew mimicCache [--args="<size> ..."]: reads MIMIC-IV from the PostgreSQL of datasets.mimic (test_config.yml),
 * once per size (default: datasets.mimic.sizes), into the local copies of datasets/original/mimic/cache, without loading
 * any system; a size whose copy exists is skipped. Then the ingestions read the copies (also on the other machines,
 * once copied there, e.g., with evaluation/push_datasets.sh). The PostgreSQL must be reachable (on localhost: the
 * mimic-postgres of docker-compose.yaml, e.g., docker compose up -d mimic-postgres)
 */
fun main(args: Array<String>) {
    val config = TestConfig.loadConfig()
    val mimic = config.datasets["mimic"] ?: error("No datasets.mimic in test_config.yml")
    val sizes = args.toList().ifEmpty { mimic.sizes.map { TestConfig.normalizeSize(it) } }
    progress("mimic cache | sizes ${sizes.joinToString()}, PostgreSQL ${mimic.postgres.host}:${mimic.postgres.port}/${mimic.postgres.database}")
    for (size in sizes) {
        val start = System.currentTimeMillis()
        progress("mimic cache | $size: started")
        val dir = MimicIVCache(mimicLimit(size), mimic.postgres).prepareCache()
        progress("mimic cache | $size: done in ${(System.currentTimeMillis() - start) / 1000} s, $dir")
    }
}
