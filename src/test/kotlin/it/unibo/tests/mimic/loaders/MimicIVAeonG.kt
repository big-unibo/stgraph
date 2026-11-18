package it.unibo.tests.mimic.loaders

import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord
import java.io.File

/**
 * Writes MIMIC-IV as the Cypher dump that AeonG loads (datasets/dump/aeong/mimic/<limit>/<limit>.cypher, one statement
 * per line, each one a transaction of AeonG), with the graph of MimicIVNeo4J and the temporal model of AeonG:
 * - (:Person {person_id, subject_id, rnd, pId, c})-[:HAS_PARAMETERS]->(:TimeSeries {ts_id}), a time series per person;
 * - (:TimeSeries)-[:HAS_EVENT]->(:<abbreviation> {series_id, measurement_id, ts_id, category, abbreviation, itemid,
 *   timestamp, value, unitname (if any)}): a node per time series (person and item), created by its first measurement
 *   and updated (SET of measurement_id, timestamp and value) by each next one, one statement (one transaction, one
 *   version) per measurement, so that AeonG keeps the measurements as its history.
 * The indexes on the ids of the nodes the statements match come first. <limit>.tt: last=<n>, the number of statements,
 * where the search of the transaction time of the last one starts (measured on the loaded database, see
 * AeonGTransactionTimes and MimicEvaluation.aeongTransactionTimes). Writing the dump is not timed: AeonG times its load.
 */
class MimicIVAeonG(
    limit: Long,
    postgres: PostgresConfig = PostgresConfig()
) : AbstractMimicIVLoader(limit, threads = 1, postgres = postgres) {

    private val dump = dumpFile(limit).apply { parentFile.mkdirs() }
    private val writer = dump.printWriter()
    private var statements = 0L
    private val indexed = mutableSetOf<String>() // the labels of the measurements with an index on series_id
    private val series = HashSet<String>() // the series with a node
    private val tsMap = mutableMapOf<Long, Long>()
    private var personCounter = 0L
    private var tsCounter = 0L
    private var measurementCounter = 0L

    /** A statement: a line of the dump, a transaction of AeonG */
    private fun statement(s: String) {
        writer.println(s)
        statements++
    }

    init {
        statement("CREATE INDEX ON :Person(person_id);")
        statement("CREATE INDEX ON :TimeSeries(ts_id);")
    }

    override fun addPerson(subjectId: Int, c: Int): Long {
        val personId = ++personCounter
        statement("CREATE (:Person {person_id: $personId, subject_id: $subjectId, rnd: ${subjectId % 10}, pId: ${personId - 1}, c: $c});")
        return personId
    }

    override fun addTimeseries(abbreviation: String, unitname: String?, category: String, label: String, itemid: Int, person: Long): Long {
        if (!tsMap.contains(person)) {
            val tsId = ++tsCounter
            statement("MATCH (p:Person {person_id: $person}) CREATE (p)-[:HAS_PARAMETERS]->(:TimeSeries {ts_id: $tsId});")
            tsMap[person] = tsId
        }
        return person
    }

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        val ts = tsMap.getValue(tsId)
        val item = tsCache[row.label.toInt()]!!
        val label = "`${item.abbreviation.replace("`", "``")}`"
        val seriesId = "${ts}_${item.itemid}"
        val measurementId = ++measurementCounter
        // The first measurement of the series (isFirst of the loader marks the first of a person, not of an item)
        if (series.add(seriesId)) {
            if (indexed.add(item.abbreviation)) statement("CREATE INDEX ON :$label(series_id);")
            val properties = linkedMapOf<String, Any?>(
                "series_id" to seriesId, "measurement_id" to measurementId, "ts_id" to ts, "category" to item.category,
                "abbreviation" to item.abbreviation, "itemid" to item.itemid, "timestamp" to row.timestamp, "value" to row.value,
                "unitname" to item.unitname
            ).filterValues { it != null && it != "" }
                .entries.joinToString(", ", "{", "}") { (k, v) -> "$k: ${if (v is Number) v.toLong() else "'${escape(v.toString())}'"}" }
            statement("MATCH (t:TimeSeries {ts_id: $ts}) CREATE (t)-[:HAS_EVENT]->(:$label $properties);")
        } else {
            statement("MATCH (m:$label {series_id: '$seriesId'}) SET m.measurement_id = $measurementId, m.timestamp = ${row.timestamp}, m.value = ${row.value};")
        }
    }

    override fun close() {
        writer.flush()
        writer.close()
        File(dump.path.removeSuffix(".cypher") + ".tt").writeText("last=$statements\n")
        File(dump.path + ".done").writeText(DUMP_VERSION) // the dump is complete, in this version
    }

    companion object {
        /** The version of the dumps: one written by an older MimicIVAeonG (another model) is written again */
        const val DUMP_VERSION = "updates"

        fun dumpFile(limit: Long) = File("datasets/dump/aeong/mimic/$limit/$limit.cypher")

        /** Whether the dump of [limit] is complete and of this version */
        fun dumpReady(limit: Long) = File(dumpFile(limit).path + ".done").let { it.isFile && it.readText() == DUMP_VERSION }

        /** The number of statements of the dump of [limit] */
        fun lastStatement(limit: Long): String =
            File(dumpFile(limit).path.removeSuffix(".cypher") + ".tt").readLines().first { it.startsWith("last=") }.substringAfter("=")

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
    }
}
