package it.unibo.tests.pems

import it.unibo.stats.PostgresConfig
import it.unibo.stats.TestConfig
import it.unibo.tests.evaluation.progress
import it.unibo.tests.pems.loaders.AbstractPemsLoader
import it.unibo.tests.pems.loaders.PemsStation
import it.unibo.tests.pems.loaders.PemsStationVersion
import it.unibo.tests.smartbench.loaders.TSRecord

/** The PeMS loader without a system: it only writes the local copy of the data read from PostgreSQL */
internal class PemsCacheLoader(size: String, postgres: PostgresConfig) : AbstractPemsLoader(size, postgres) {
    override fun node(label: String, props: Map<String, Any?>, from: Long?, to: Long?): Long = 0
    override fun edge(label: String, from: Long, to: Long, validFrom: Long, validTo: Long) {}
    override fun addStation(station: PemsStation, versions: List<Pair<PemsStationVersion, Long?>>): Long = 0
    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {}
    override fun loadData() { prepareCache() }
    override fun close() {}
}

/**
 * ./gradlew pemsCache [--args="<size> ..."]: reads PeMS from the PostgreSQL of datasets.pems (test_config.yml), once
 * per size (default: datasets.pems.sizes), into datasets/original/pems/cache/<size>, without loading any system; a
 * size whose copy exists is skipped. The copies can then be pushed to the other machines (evaluation/push_datasets.sh)
 */
fun main(args: Array<String>) {
    val pems = TestConfig.loadConfig().datasets["pems"] ?: error("No datasets.pems in test_config.yml")
    val sizes = args.toList().ifEmpty { pems.sizes.map { TestConfig.normalizeSize(it) } }
    progress("pems cache | sizes ${sizes.joinToString()}, PostgreSQL ${pems.postgres.host}:${pems.postgres.port}/${pems.postgres.database}")
    for (size in sizes) {
        val start = System.currentTimeMillis()
        progress("pems cache | $size: started")
        val dir = PemsCacheLoader(size, pems.postgres).prepareCache()
        progress("pems cache | $size: done in ${(System.currentTimeMillis() - start) / 1000} s, $dir")
    }
}
