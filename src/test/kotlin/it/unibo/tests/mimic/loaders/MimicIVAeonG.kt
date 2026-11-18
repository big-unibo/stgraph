package it.unibo.tests.mimic.loaders

import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord
import java.io.File

/**
 * Writes MIMIC-IV as the Cypher dump that AeonG loads (datasets/dump/aeong/mimic/<limit>/<limit>.cypher, one statement
 * per line, each one a transaction of AeonG), with the graph of MimicIVNeo4J, so that the queries are the same:
 * - (:Person {person_id, subject_id, rnd, pId, c})-[:HAS_PARAMETERS]->(:TimeSeries {ts_id}), a time series per person;
 * - (:TimeSeries)-[:HAS_EVENT]->(:<abbreviation> {measurement_id, ts_id, category, abbreviation, itemid, timestamp, value,
 *   unitname (if any)}).
 * The measurements are added as a time series database receives them: in batches of [batchSize] (a batch per statement,
 * written when full and at the end of each time series), not in a single statement. The indexes on the ids of the
 * nodes the statements match come first. Writing the dump is not timed: AeonG times its load (see MimicEvaluation).
 */
class MimicIVAeonG(
    limit: Long,
    postgres: PostgresConfig = PostgresConfig(),
    private val batchSize: Int = 10
) : AbstractMimicIVLoader(limit, threads = 1, postgres = postgres) {

    private val dump = dumpFile(limit).apply { parentFile.mkdirs() }
    private val writer = dump.printWriter()
    private val tsMap = mutableMapOf<Long, Long>()
    private var personCounter = 0L
    private var tsCounter = 0L
    private var measurementCounter = 0L
    private val pending = mutableListOf<Pair<String, String>>() // (label, properties) of the batch of the current series

    init {
        writer.println("CREATE INDEX ON :Person(person_id);")
        writer.println("CREATE INDEX ON :TimeSeries(ts_id);")
    }

    override fun addPerson(subjectId: Int, c: Int): Long {
        val personId = ++personCounter
        writer.println("CREATE (:Person {person_id: $personId, subject_id: $subjectId, rnd: ${subjectId % 10}, pId: ${personId - 1}, c: $c});")
        return personId
    }

    override fun addTimeseries(abbreviation: String, unitname: String?, category: String, label: String, itemid: Int, person: Long): Long {
        if (!tsMap.contains(person)) {
            val tsId = ++tsCounter
            writer.println("MATCH (p:Person {person_id: $person}) CREATE (p)-[:HAS_PARAMETERS]->(:TimeSeries {ts_id: $tsId});")
            tsMap[person] = tsId
        }
        return person
    }

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        val ts = tsMap.getValue(tsId)
        val item = tsCache[row.label.toInt()]!!
        val properties = linkedMapOf<String, Any?>(
            "measurement_id" to ++measurementCounter, "ts_id" to ts, "category" to item.category, "abbreviation" to item.abbreviation,
            "itemid" to item.itemid, "timestamp" to row.timestamp, "value" to row.value, "unitname" to item.unitname
        )
        pending += item.abbreviation to properties.filterValues { it != null && it != "" }
            .entries.joinToString(", ", "{", "}") { (k, v) -> "$k: ${if (v is Number) v.toLong() else "'${escape(v.toString())}'"}" }
        if (pending.size >= batchSize || isLast) flush(ts)
    }

    /** The batch of the series [ts] in one statement (one CREATE per measurement: its label is not a parameter) */
    private fun flush(ts: Long) {
        if (pending.isEmpty()) return
        writer.println("MATCH (t:TimeSeries {ts_id: $ts}) " + pending.joinToString(" ") { (label, props) ->
            "CREATE (t)-[:HAS_EVENT]->(:`${label.replace("`", "``")}` $props)"
        } + ";")
        pending.clear()
    }

    override fun close() {
        writer.flush()
        writer.close()
        File(dump.path + ".done").writeText(DUMP_VERSION) // the dump is complete, in this version
    }

    companion object {
        /** The version of the dumps: one written by an older MimicIVAeonG (another graph) is written again */
        const val DUMP_VERSION = "neo4j-graph-batches"

        fun dumpFile(limit: Long) = File("datasets/dump/aeong/mimic/$limit/$limit.cypher")

        /** Whether the dump of [limit] is complete and of this version */
        fun dumpReady(limit: Long) = File(dumpFile(limit).path + ".done").let { it.isFile && it.readText() == DUMP_VERSION }

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
    }
}
