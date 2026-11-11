package it.unibo.tests.mimic.loaders

import it.unibo.graph.asterixdb.AsterixDBTS
import it.unibo.graph.utils.getTime
import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord
import it.unibo.tests.systems.pgAgeConnection
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import java.io.StringReader
import java.sql.*

/** Whether MIMIC-IV is loaded into PGAge with COPY (CSV from STDIN) rather than with batches of INSERTs: above the small sizes, and always in the CI (CI=true), as [neo4jCsv] */
fun pgageCsv(limit: Long): Boolean = System.getenv("CI") == "true" || limit >= 5_000_000

/**
 * Loads MIMIC-IV into PGAge, in two ways that produce the same database: the graph (Person and TimeSeries vertices) is
 * always created through the driver, one Cypher statement each; the measurements go to <graph>.measurements:
 * - without [csv], with batches of [batchSize] INSERTs through the driver;
 * - with [csv], with batches of [copyBatchSize] streamed by COPY ... FROM STDIN (CSV, text fields quoted, so that COPY reads back
 *   the same strings), as SmartBenchPGAge.
 * No primary key: in both ways, the only index of the measurements is the time index of TimescaleDB on timestamp,
 * built after the load (index time), as SmartBenchPGAge and the range indexes on the timestamps of MimicIVNeo4J.
 */
class MimicIVPGAge(
    limit: Long,
    url: String = "jdbc:postgresql://localhost:5435/mimic-iv",
    user: String = "postgres",
    password: String = "password",
    // The PostgreSQL of MIMIC-IV the data is read from (url, user and password: the one of PGAge)
    postgres: PostgresConfig = PostgresConfig(),
    val csv: Boolean = pgageCsv(limit),
    private val batchSize: Int = 10,
    // Rows per COPY (with csv, above the small sizes)
    private val copyBatchSize: Int = 20_000
) : AbstractMimicIVLoader(limit, threads = 1, postgres = postgres)  {
    private val graphName = graphName(limit)
    // With the session settings of the evaluation (AGE loaded, ag_catalog in the search path),
    // applied once: not at every statement, inside the timed ingestion. Without csv, multi-row INSERTs from a batch
    private val conn: Connection = pgAgeConnection(url, user, password, mapOf("reWriteBatchedInserts" to "true"))
    // The measurements not loaded yet
    private val pending = mutableListOf<List<Any?>>()

    var pId = 0
    val tsMap = mutableMapOf<Long, Long>()

    init {
        conn.createStatement().use { st ->
            // Helper function for safe execution
            fun safeExec(sql: String) {
                try {
                    st.execute(sql)
                } catch (e: SQLException) {
                    println("Warning executing SQL: $sql")
                    e.printStackTrace()
                }
            }

            // Load extensions
            safeExec("CREATE EXTENSION IF NOT EXISTS age")
            safeExec("CREATE EXTENSION IF NOT EXISTS timescaledb")
            safeExec("LOAD 'age';")
            // Set search path to allow AGE operations
            safeExec("SET search_path = ag_catalog, \"$user\", public;")

            // Drop the graph if it exists
            try {
                st.execute("SELECT drop_graph('$graphName', true)")
            } catch (e: SQLException) {
                println("Graph $graphName did not exist, skipping drop.")
            }

            // Create graph safely
            safeExec("SELECT create_graph('$graphName')")

            // Switch to the graph schema for subsequent operations
            safeExec("SET search_path = $graphName, \"$user\", public;")

            safeExec("""
                CREATE TABLE IF NOT EXISTS measurements (
                    measurement_id BIGINT,
                    ts_id BIGINT,
                    timestamp BIGINT,
                    value BIGINT,
                    label text,
                    itemid BIGINT,
                    abbreviation text,
                    category text,
                    unitname text
                )""".trimIndent())

            safeExec("TRUNCATE TABLE Measurements")

            // Convert table to hypertable (Timescale). The timestamps are epoch milliseconds (BIGINT): without an
            // interval, the default one of integer columns makes tiny chunks, and a batch touching thousands of them
            // exceeds max_locks_per_transaction; 7 days, the default of TimescaleDB for the time columns
            safeExec("""
                SELECT create_hypertable(
                    'measurements',
                    'timestamp',
                    chunk_time_interval => 604800000,
                    if_not_exists => TRUE,
                    create_default_indexes => FALSE
                )
                """.trimIndent())

            // Its default index on timestamp is created after the load (see loadData), not maintained during it

            // Back to AGE for the Cypher statements of the ingestion
            safeExec("SET search_path = ag_catalog, public;")
        }
    }

    override fun addPerson(subjectId: Int, c: Int): Long {
        conn.createStatement().use { st ->
            val rs = st.executeQuery(
                """
                SELECT *
                FROM cypher('$graphName', $$
                    CREATE (p:Person {subject_id:$subjectId,rnd:${subjectId % 10},pId:${pId++},c:$c})
                    RETURN id(p)
                $$) as (id agtype)
                """.trimIndent())
            rs.next()
            return rs.getString(1).replace("\"", "").toLong()
        }
    }

    override fun addTimeseries(abbreviation: String, unitname: String?, category: String, label: String, itemid: Int, person: Long): Long {
        if (!tsMap.contains(person)) {
            conn.createStatement().use { st ->
                val rs = st.executeQuery(
                    """
                SELECT *
                FROM cypher('$graphName', $$
                    MATCH (p:Person) WHERE id(p) = $person
                    CREATE (ts:TimeSeries)
                    CREATE (p)-[:HAS_PARAMETERS]->(ts)
                    RETURN id(ts)
                $$) as (id agtype)""".trimIndent()
                )
                rs.next()
                val id = rs.getString(1).replace("\"", "").toLong()
                tsMap[person] = id
            }
        }
        return person
    }

    var measurementCounter = 0
    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        val ts = tsMap[tsId]!!
        val tmp = tsCache[row.label.toInt()]!!
        // The columns of measurements: measurement_id, ts_id, timestamp, value, label, itemid, abbreviation, category,
        // unitname (null if the item has none)
        val values = listOf<Any?>(
            (++measurementCounter).toLong(), ts, row.timestamp, row.value as Long, tmp.abbreviation, tmp.itemid.toLong(), tmp.abbreviation, tmp.category, tmp.unitname
        )
        pending += values
        if (pending.size >= (if (csv) copyBatchSize else batchSize) || isLast) flush()
    }

    /** A CSV field: quoted, quotes doubled (COPY reads back the same string, also when empty or with commas) */
    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    /** Loads the [pending] measurements: with [csv] one COPY, otherwise one batch of INSERTs */
    private fun flush() {
        if (pending.isEmpty()) return
        if (csv) {
            // A null as an unquoted empty field, which COPY reads as NULL
            val rows = pending.joinToString("") { values -> values.joinToString(",") { if (it is String) quote(it) else it?.toString() ?: "" } + "\n" }
            CopyManager(conn.unwrap(BaseConnection::class.java))
                .copyIn("COPY $graphName.measurements ($COLUMNS) FROM STDIN WITH (FORMAT csv)", StringReader(rows))
            pending.clear()
            return
        }
        conn.autoCommit = false
        try {
            conn.prepareStatement("INSERT INTO $graphName.measurements ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)").use { st ->
                for (values in pending) {
                    values.forEachIndexed { i, v -> if (v == null) st.setNull(i + 1, Types.VARCHAR) else if (v is String) st.setString(i + 1, v) else st.setLong(i + 1, v as Long) }
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

    private var indexTime = 0L
    override fun getIndexTime(): Long = indexTime

    /** As the skeleton, then the default index of TimescaleDB on timestamp, same name and definition (index time) */
    override fun loadData() {
        addGs()
        addTs()
        flush()
        indexTime = getTime {
            conn.createStatement().use { it.execute("CREATE INDEX measurements_timestamp_idx ON $graphName.measurements (\"timestamp\" DESC)") }
        }
        close()
    }

    override fun close() {
        flush()
        conn.close()
    }

    companion object {
        const val COLUMNS = "measurement_id, ts_id, timestamp, value, label, itemid, abbreviation, category, unitname"

        /** The AGE graph of [limit], whose schema holds the table measurements */
        fun graphName(limit: Long) = "mimic_${if (limit == Long.MAX_VALUE) "full" else limit}"
    }
}