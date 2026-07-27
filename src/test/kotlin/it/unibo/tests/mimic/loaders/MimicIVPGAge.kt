package it.unibo.tests.mimic.loaders

import it.unibo.graph.asterixdb.AsterixDBTS
import it.unibo.tests.smartbench.loaders.TSRecord
import java.io.File
import java.sql.*

class MimicIVPGAge(
    limit: Long,
    url: String = "jdbc:postgresql://localhost:5435/mimic-iv",
    user: String = "postgres",
    password: String = "password"
) : AbstractMimicIVLoader(limit, threads = 1)  {
    private val graphName = "mimic_${if (limit == Long.MAX_VALUE) "full" else limit}"
    private val conn: Connection = DriverManager.getConnection(url, user, password)
    private val baseDir = File("datasets/dump/pgage/mimic/$limit").apply { mkdirs() }
    private val tsWriter = File("$baseDir/ts.csv").printWriter()

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
                    primary key (ts_id, label, timestamp)
                )""".trimIndent())

            safeExec("TRUNCATE TABLE Measurements")

            // Convert table to hypertable (Timescale)
            safeExec("""
                SELECT create_hypertable(
                    'measurements',
                    'timestamp',
                    if_not_exists => TRUE
                )
                """.trimIndent())
        }
    }

    override fun addPerson(subjectId: Int, c: Int): Long {
        println("addPerson: $subjectId")
        conn.createStatement().use { st ->
            st.execute("LOAD 'age';")
            st.execute("SET search_path = ag_catalog, public;")
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
            println("addTimeseries: $person")
            conn.createStatement().use { st ->
                st.execute("LOAD 'age';")
                st.execute("SET search_path = ag_catalog, public;")
                val rs = st.executeQuery(
                    """
                SELECT *
                FROM cypher('$graphName', $$
                    MATCH (p) WHERE id(p) = $person
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
        tsWriter.write(
            listOf(
                (++measurementCounter).toString(),
                ts.toString(),
                row.timestamp.toString(),
                (row.value as Long).toString(),
                tmp.abbreviation,
                tmp.itemid.toString(),
                tmp.abbreviation,
                tmp.category
            ).joinToString(",") + "\n"
        )
    }

    override fun close() {
        conn.close()
        tsWriter.close()
    }
}