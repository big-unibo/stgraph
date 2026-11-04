package it.unibo.tests.mimic.loaders

import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord
import it.unibo.tests.systems.neo4jDriver
import org.neo4j.driver.Driver
import org.neo4j.driver.Session

/**
 * Loads MIMIC-IV into the Neo4j of the evaluation (evaluation/architectures/neo4j), as SmartBenchNeo4J loads
 * SmartBench, with the model queried by TestMimicQuery (Q*Neo4J):
 * - graph (timed by the skeleton): a Person vertex per subject (subject_id, rnd, pId, c) and a TimeSeries vertex per
 *   person (hasParameters), one statement each;
 * - time series (timed by the skeleton): a vertex per event, labelled with the abbreviation of its item (category,
 *   abbreviation, itemid, timestamp, value), linked to the time series of its person (hasMeasurement), in batches of
 *   [batchSize] events of the same time series and item, with UNWIND.
 * Every vertex is also labelled Mimic: the clean-up (not timed) removes only the vertices of MIMIC-IV, not the ones of
 * the other datasets.
 */
class MimicNeo4J(
    size: String,
    uri: String = "bolt://localhost:7687",
    private val batchSize: Int = 10_000,
    postgres: PostgresConfig = PostgresConfig()
) : AbstractMimicIVLoader(mimicLimit(size), threads = 1, postgres = postgres) {
    private val driver: Driver = neo4jDriver(uri)
    private val session: Session = driver.session()
    private var persons = 0L
    /** The element ids of the Person and TimeSeries vertices, by the key of the person returned by [addPerson] */
    private val personNodes = mutableMapOf<Long, String>()
    private val tsNodes = mutableMapOf<Long, String>()
    /** The events not sent yet, all of the time series [pendingTs] and of the item [pendingLabel] */
    private val pending = mutableListOf<Map<String, Any?>>()
    private var pendingTs: String? = null
    private var pendingLabel: String? = null

    init {
        session.run("MATCH (n:$MIMIC) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS").consume()
    }

    override fun addPerson(subjectId: Int, c: Int): Long {
        val key = persons++
        personNodes[key] = session.run(
            "CREATE (p:$MIMIC:Person {subject_id: \$subjectId, rnd: \$rnd, pId: \$pId, c: \$c}) RETURN elementId(p)",
            mapOf("subjectId" to subjectId, "rnd" to subjectId % 10, "pId" to key, "c" to c)
        ).single()[0].asString()
        return key
    }

    override fun addTimeseries(abbreviation: String, unitname: String?, category: String, label: String, itemid: Int, person: Long): Long {
        if (person !in tsNodes) {
            tsNodes[person] = session.run(
                "MATCH (p) WHERE elementId(p) = \$person CREATE (p)-[:hasParameters]->(t:$MIMIC:TimeSeries) RETURN elementId(t)",
                mapOf("person" to personNodes.getValue(person))
            ).single()[0].asString()
        }
        return person
    }

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        val ts = tsNodes.getValue(tsId)
        val item = tsCache.getValue(row.label.toInt())
        if (ts != pendingTs || item.abbreviation != pendingLabel || pending.size >= batchSize) flush()
        pendingTs = ts
        pendingLabel = item.abbreviation
        pending += mapOf(
            "category" to item.category,
            "abbreviation" to item.abbreviation,
            "itemid" to item.itemid,
            "timestamp" to row.timestamp,
            "value" to row.value as Long
        )
        if (isLast) flush()
    }

    /** Sends the [pending] events: the label (the abbreviation) cannot be a parameter, hence a batch per item */
    private fun flush() {
        if (pending.isEmpty()) return
        val label = pendingLabel!!.replace("`", "``")
        session.run(
            "MATCH (t) WHERE elementId(t) = \$ts UNWIND \$rows AS row " +
                "CREATE (t)-[:hasMeasurement]->(:$MIMIC:`$label` {category: row.category, abbreviation: row.abbreviation, " +
                "itemid: row.itemid, timestamp: row.timestamp, value: row.value})",
            mapOf("ts" to pendingTs, "rows" to pending.toList())
        ).consume()
        pending.clear()
    }

    override fun close() {
        flush()
        session.close()
        driver.close()
    }

    companion object {
        private const val MIMIC = "Mimic"
    }
}
