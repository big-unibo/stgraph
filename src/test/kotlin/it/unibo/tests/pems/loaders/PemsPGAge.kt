package it.unibo.tests.pems.loaders

import it.unibo.graph.utils.getTime
import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord
import it.unibo.tests.systems.pgAgeConnection
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import java.io.StringReader
import java.sql.Connection
import java.sql.SQLException

/**
 * Loads PeMS into PGAge, as MimicIVPGAge: the graph through Cypher, one statement per node or edge; the measurements
 * in the hypertable <graph>.measurements, with batches of INSERTs or, with [csv], COPY ... FROM STDIN; the time index
 * built after the load (index time)
 */
class PemsPGAge(
    size: String,
    url: String = "jdbc:postgresql://localhost:5432/smartbench_eval",
    user: String = "postgres",
    password: String = "psw",
    postgres: PostgresConfig = PostgresConfig(),
    val csv: Boolean = pemsBulk(size),
    private val batchSize: Int = 10,
    private val copyBatchSize: Int = 20_000
) : AbstractPemsLoader(size, postgres) {
    private val graphName = graphName(size)
    private val conn: Connection = pgAgeConnection(url, user, password, mapOf("reWriteBatchedInserts" to "true"))
    private val labels = HashMap<Long, String>()
    private val pending = mutableListOf<List<Any>>()
    private var counter = 0L

    init {
        conn.createStatement().use { st ->
            fun safeExec(sql: String) = try { st.execute(sql) } catch (e: SQLException) { println("Warning executing SQL: $sql: ${e.message}") }
            safeExec("CREATE EXTENSION IF NOT EXISTS age")
            safeExec("CREATE EXTENSION IF NOT EXISTS timescaledb")
            // The hypertable first: drop_graph drops the schema without the hooks of TimescaleDB (see MimicIVPGAge)
            safeExec("DROP TABLE IF EXISTS $graphName.measurements")
            safeExec("SELECT drop_graph('$graphName', true)")
            safeExec("SELECT create_graph('$graphName')")
            safeExec("CREATE TABLE $graphName.measurements (measurement_id BIGINT, ts_id BIGINT, timestamp BIGINT, value BIGINT, label text)")
            safeExec("SELECT create_hypertable('$graphName.measurements', 'timestamp', chunk_time_interval => 604800000, if_not_exists => TRUE, create_default_indexes => FALSE)")
        }
    }

    /** A Cypher literal; Long.MIN_VALUE as an expression (its absolute value is not an integer literal) */
    private fun literal(v: Any): String = when (v) {
        Long.MIN_VALUE -> "(${Long.MIN_VALUE + 1} - 1)"
        is Number -> v.toString()
        else -> "'" + v.toString().replace("\\", "\\\\").replace("'", "\\'").replace("$$", "$ $") + "'"
    }

    private fun cypher(query: String): Long = conn.createStatement().use { st ->
        st.executeQuery("SELECT * FROM cypher('$graphName', $$ $query $$) AS (id agtype)").use { rs ->
            if (rs.next()) rs.getString(1).replace("\"", "").toLong() else -1
        }
    }

    override fun node(label: String, props: Map<String, Any?>, from: Long?, to: Long?): Long {
        val all = props + (if (from != null) mapOf("valid_from" to from, "valid_to" to to) else emptyMap<String, Long?>())
        val map = all.entries.filter { it.value != null }.joinToString(", ") { (k, v) -> "$k: ${literal(v!!)}" }
        return cypher("CREATE (n:$label {$map}) RETURN id(n)").also { labels[it] = label }
    }

    override fun edge(label: String, from: Long, to: Long, validFrom: Long, validTo: Long) {
        cypher("MATCH (a:${labels.getValue(from)}), (b:${labels.getValue(to)}) WHERE id(a) = $from AND id(b) = $to CREATE (a)-[e:$label]->(b) RETURN id(e)")
    }

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        pending += listOf(++counter, tsId, row.timestamp, row.value, row.label)
        if (pending.size >= (if (csv) copyBatchSize else batchSize) || isLast) flush()
    }

    private fun flush() {
        if (pending.isEmpty()) return
        if (csv) {
            val rows = pending.joinToString("") { it.joinToString(",") + "\n" }
            CopyManager(conn.unwrap(BaseConnection::class.java)).copyIn("COPY $graphName.measurements ($COLUMNS) FROM STDIN WITH (FORMAT csv)", StringReader(rows))
        } else {
            conn.autoCommit = false
            try {
                conn.prepareStatement("INSERT INTO $graphName.measurements ($COLUMNS) VALUES (?, ?, ?, ?, ?)").use { st ->
                    for (values in pending) {
                        for (i in 0..3) st.setLong(i + 1, values[i] as Long)
                        st.setString(5, values[4] as String)
                        st.addBatch()
                    }
                    st.executeBatch()
                }
                conn.commit()
            } finally {
                conn.autoCommit = true
            }
        }
        pending.clear()
    }

    private var indexTime = 0L
    override fun getIndexTime(): Long = indexTime

    override fun loadData() {
        addGs()
        addTs()
        flush()
        indexTime = getTime { conn.createStatement().use { it.execute("CREATE INDEX measurements_timestamp_idx ON $graphName.measurements (\"timestamp\" DESC)") } }
        close()
    }

    override fun close() {
        if (!conn.isClosed) flush()
        conn.close()
    }

    companion object {
        const val COLUMNS = "measurement_id, ts_id, timestamp, value, label"
        fun graphName(size: String) = "pems_$size"
    }
}
