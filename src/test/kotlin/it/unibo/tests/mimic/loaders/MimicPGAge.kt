package it.unibo.tests.mimic.loaders

import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord
import it.unibo.tests.systems.pgAgeConnection
import java.sql.Connection

/**
 * Loads MIMIC-IV into the PGAge of the evaluation (evaluation/architectures/pgage), as SmartBenchPGAge loads
 * SmartBench, with the model queried by TestMimicQuery (Q*PGAge), in the AGE graph [graphName] of the size:
 * - graph (timed by the skeleton): a Person vertex per subject (subject_id, rnd, pId, c) and a TimeSeries vertex per
 *   person (hasParameters), one statement each;
 * - time series (timed by the skeleton): the events in the table <graph>.measurements_csv (ts_id: the id of the
 *   TimeSeries vertex), in batches of [batchSize].
 * The clean-up (not timed) drops only the graph of the size (and its table), not the ones of the other datasets.
 */
class MimicPGAge(
    size: String,
    url: String = "jdbc:postgresql://localhost:5432/smartbench_eval",
    user: String = "postgres",
    password: String = "psw",
    private val batchSize: Int = 20_000,
    postgres: PostgresConfig = PostgresConfig()
) : AbstractMimicIVLoader(mimicLimit(size), threads = 1, postgres = postgres) {
    private val graphName = graphName(size)
    private val measurementTable = "$graphName.measurements_csv"
    // A multi-row INSERT from a batch, as SmartBenchPGAge
    private val conn: Connection = pgAgeConnection(url, user, password, mapOf("reWriteBatchedInserts" to "true"))
    private var pId = 0
    /** The id of the TimeSeries vertex, by the id of the Person vertex returned by [addPerson] */
    private val tsNodes = mutableMapOf<Long, Long>()
    private val pending = mutableListOf<Pair<Long, TSRecord>>()

    init {
        // Fails when the graph does not exist yet
        runCatching { execute("SELECT * FROM ag_catalog.drop_graph('$graphName', true);") }
        execute("CREATE EXTENSION IF NOT EXISTS age;")
        execute("SELECT * FROM ag_catalog.create_graph('$graphName');")
        execute("CREATE TABLE $measurementTable (ts_id BIGINT, timestamp BIGINT, value BIGINT, label TEXT, itemid BIGINT, abbreviation TEXT, category TEXT);")
    }

    override fun addPerson(subjectId: Int, c: Int): Long =
        cypherId("CREATE (p:Person {subject_id: $subjectId, rnd: ${subjectId % 10}, pId: ${pId++}, c: $c}) RETURN id(p)")

    override fun addTimeseries(abbreviation: String, unitname: String?, category: String, label: String, itemid: Int, person: Long): Long {
        if (person !in tsNodes) {
            tsNodes[person] = cypherId("MATCH (p:Person) WHERE id(p) = $person CREATE (p)-[:hasParameters]->(t:TimeSeries) RETURN id(t)")
        }
        return person
    }

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        pending += tsNodes.getValue(tsId) to row
        if (pending.size >= batchSize || isLast) flush()
    }

    private fun flush() {
        if (pending.isEmpty()) return
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                "INSERT INTO $measurementTable (ts_id, timestamp, value, label, itemid, abbreviation, category) VALUES (?, ?, ?, ?, ?, ?, ?)"
            ).use { st ->
                for ((ts, row) in pending) {
                    val item = tsCache.getValue(row.label.toInt())
                    st.setLong(1, ts)
                    st.setLong(2, row.timestamp)
                    st.setLong(3, row.value as Long)
                    st.setString(4, item.abbreviation)
                    st.setLong(5, item.itemid.toLong())
                    st.setString(6, item.abbreviation)
                    st.setString(7, item.category)
                    st.addBatch()
                }
                st.executeBatch()
            }
            conn.commit()
        } finally {
            conn.autoCommit = true
        }
        pending.clear()
    }

    override fun close() {
        flush()
        conn.close()
    }

    /** The id returned by the Cypher [statement] (RETURN id(...)) on the graph */
    private fun cypherId(statement: String): Long =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM cypher('$graphName', $$ $statement $$) AS (id agtype);").use { rs ->
                rs.next()
                rs.getString(1).toLong()
            }
        }

    private fun execute(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    companion object {
        /** The AGE graph of [size]: mimic_<size>, mimic_full for all the events */
        fun graphName(size: String) = "mimic_${if (mimicLimit(size) == Long.MAX_VALUE) "full" else size}"
    }
}
