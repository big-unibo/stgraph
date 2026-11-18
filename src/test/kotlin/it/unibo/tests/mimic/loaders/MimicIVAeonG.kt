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
 * With [updates], the temporal model of AeonG (datasets/dump/aeong-updates/mimic/<limit>): a measurement node per time
 * series (person and item, with its series_id), created by its first measurement and updated (SET) by each next one,
 * one statement (one transaction, one version) per measurement, so that AeonG keeps the measurements as its history;
 * the number of statements (<limit>.tt: last=<n>) bounds the transaction time of the queries
 */
class MimicIVAeonG(
    limit: Long,
    postgres: PostgresConfig = PostgresConfig(),
    private val batchSize: Int = 10,
    private val updates: Boolean = false
) : AbstractMimicIVLoader(limit, threads = 1, postgres = postgres) {

    private val dump = dumpFile(limit, updates).apply { parentFile.mkdirs() }
    private var statements = 0L
    private val indexed = mutableSetOf<String>() // the labels of the measurements with an index on series_id (updates)
    private val series = HashSet<String>() // the series with a node (updates)
    private val writer = dump.printWriter()
    private val tsMap = mutableMapOf<Long, Long>()
    private var personCounter = 0L
    private var tsCounter = 0L
    private var measurementCounter = 0L
    private val pending = mutableListOf<Pair<String, String>>() // (label, properties) of the batch of the current series

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
        val properties = linkedMapOf<String, Any?>(
            "measurement_id" to ++measurementCounter, "ts_id" to ts, "category" to item.category, "abbreviation" to item.abbreviation,
            "itemid" to item.itemid, "timestamp" to row.timestamp, "value" to row.value, "unitname" to item.unitname
        )
        if (updates) return update(ts, item.abbreviation, item.itemid, properties)
        pending += item.abbreviation to literal(properties)
        if (pending.size >= batchSize || isLast) flush(ts)
    }

    private fun literal(properties: Map<String, Any?>) = properties.filterValues { it != null && it != "" }
        .entries.joinToString(", ", "{", "}") { (k, v) -> "$k: ${if (v is Number) v.toLong() else "'${escape(v.toString())}'"}" }

    /** With [updates]: the node of the series created by its first measurement, then updated by each next one */
    private fun update(ts: Long, abbreviation: String, itemid: Int, properties: Map<String, Any?>) {
        val label = "`${abbreviation.replace("`", "``")}`"
        val seriesId = "'${ts}_$itemid'"
        // The first measurement of the series (isFirst of the loader marks the first of a person, not of an item)
        if (series.add("${ts}_$itemid")) {
            if (indexed.add(abbreviation)) statement("CREATE INDEX ON :$label(series_id);")
            statement("MATCH (t:TimeSeries {ts_id: $ts}) CREATE (t)-[:HAS_EVENT]->(:$label ${literal(mapOf("series_id" to "${ts}_$itemid") + properties)});")
        } else {
            val set = properties.filterKeys { it in listOf("measurement_id", "timestamp", "value") }
                .entries.joinToString(", ") { (k, v) -> "m.$k = ${(v as Number).toLong()}" }
            statement("MATCH (m:$label {series_id: $seriesId}) SET $set;")
        }
    }

    /** The batch of the series [ts] in one statement (one CREATE per measurement: its label is not a parameter) */
    private fun flush(ts: Long) {
        if (pending.isEmpty()) return
        statement("MATCH (t:TimeSeries {ts_id: $ts}) " + pending.joinToString(" ") { (label, props) ->
            "CREATE (t)-[:HAS_EVENT]->(:`${label.replace("`", "``")}` $props)"
        } + ";")
        pending.clear()
    }

    override fun close() {
        writer.flush()
        writer.close()
        if (updates) File(dump.path.removeSuffix(".cypher") + ".tt").writeText("last=$statements\n")
        File(dump.path + ".done").writeText(DUMP_VERSION) // the dump is complete, in this version
    }

    companion object {
        /** The version of the dumps: one written by an older MimicIVAeonG (another graph) is written again */
        const val DUMP_VERSION = "neo4j-graph-batches"

        fun dumpFile(limit: Long, updates: Boolean = false) = File("datasets/dump/${if (updates) "aeong-updates" else "aeong"}/mimic/$limit/$limit.cypher")

        /** Whether the dump of [limit] is complete and of this version */
        fun dumpReady(limit: Long, updates: Boolean = false) = File(dumpFile(limit, updates).path + ".done").let { it.isFile && it.readText() == DUMP_VERSION }

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
    }
}
