package it.unibo.tests.pems.loaders

import it.unibo.graph.utils.getTime
import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord
import it.unibo.tests.systems.neo4jDriver
import org.neo4j.driver.Session
import java.io.File
import java.io.PrintWriter
import java.util.zip.GZIPOutputStream

/**
 * Loads PeMS into Neo4j, as MimicIVNeo4J: with [csv], gzipped CSV files (datasets/dump/neo4j/pems/<size>: a file
 * nodes_<label>.csv.gz per label, rels.csv.gz) imported by [csvImport]; otherwise one node or edge at a time, the
 * measurements in batches of [batchSize]. Every node has an integer id; a measurement has the label of its event
 * (e.g., lane1_avg_speed). After the load (index time), a range index on the timestamps of each measurement label.
 */
class PemsNeo4J(
    size: String,
    private val uri: String = "bolt://localhost:7687",
    private val userNeo4j: String? = "neo4j",
    private val passwordNeo4j: String? = "password",
    val csv: Boolean = pemsBulk(size),
    postgres: PostgresConfig = PostgresConfig(),
    private val csvImport: ((File) -> Long)? = null,
    private val batchSize: Int = 10
) : AbstractPemsLoader(size, postgres) {
    private val driver = if (csv) null else neo4jDriver(uri, userNeo4j, passwordNeo4j)
    private val session: Session? = driver?.session()
    val baseDir = File("datasets/dump/neo4j/pems/$size")
    private val writers = linkedMapOf<String, PrintWriter>()
    private val elementIds = HashMap<Long, String>()
    private val pending = mutableListOf<Triple<String, String, Map<String, Any>>>()
    private val labels = mutableSetOf<String>()
    private var counter = 0L

    init {
        session?.let { s ->
            s.run("SHOW RANGE INDEXES YIELD name, properties WHERE properties = ['timestamp'] RETURN name").list()
                .forEach { s.run("DROP INDEX `${it[0].asString()}` IF EXISTS").consume() }
            s.run("MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS").consume()
        }
        if (csv) { baseDir.deleteRecursively(); baseDir.mkdirs() }
    }

    private fun writer(file: String, header: () -> String): PrintWriter =
        writers.getOrPut(file) { PrintWriter(GZIPOutputStream(File(baseDir, file).outputStream()).bufferedWriter()).apply { println(header()) } }

    private fun csvValue(v: Any?): String = when (v) {
        null -> ""
        is Number -> v.toString()
        else -> "\"" + v.toString().replace("\"", "\"\"").replace("\n", " ") + "\""
    }

    private fun csvType(key: String): String = when (key) {
        in DOUBLE_KEYS -> ":double"
        in STRING_KEYS -> ""
        else -> ":long"
    }

    override fun node(label: String, props: Map<String, Any?>, from: Long?, to: Long?): Long {
        val id = ++counter
        val all = props + (if (from != null) mapOf("valid_from" to from, "valid_to" to to) else emptyMap<String, Long?>())
        if (csv) {
            writer("nodes_$label.csv.gz") { (listOf("id:ID") + all.keys.map { it + csvType(it) } + ":LABEL").joinToString(",") }
                .println((listOf(id.toString()) + all.values.map(::csvValue) + label).joinToString(","))
        } else {
            val p = (mapOf("id" to id) + all).filterValues { it != null }
            elementIds[id] = session!!.run("CREATE (n:`$label`) SET n = \$props RETURN elementId(n)", mapOf("props" to p)).single()[0].asString()
        }
        return id
    }

    override fun edge(label: String, from: Long, to: Long, validFrom: Long, validTo: Long) {
        if (csv) {
            writer("rels.csv.gz") { ":START_ID,:END_ID,:TYPE" }.println("$from,$to,$label")
        } else {
            session!!.run(
                "MATCH (a), (b) WHERE elementId(a) = \$a AND elementId(b) = \$b CREATE (a)-[:`$label`]->(b)",
                mapOf("a" to elementIds.getValue(from), "b" to elementIds.getValue(to))
            ).consume()
        }
    }

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        val id = ++counter
        labels += row.label
        if (csv) {
            writer("nodes_Measurement.csv.gz") { "id:ID,ts_id:long,timestamp:long,value:long,:LABEL" }
                .println("$id,$tsId,${row.timestamp},${row.value},${row.label}")
            edge("hasMeasurement", tsId, id)
        } else {
            pending += Triple(row.label, elementIds.getValue(tsId), mapOf("id" to id, "ts_id" to tsId, "timestamp" to row.timestamp, "value" to row.value))
            if (pending.size >= batchSize || isLast) flush()
        }
    }

    /** Without csv: creates the pending measurements in one transaction, one UNWIND per label */
    private fun flush() {
        if (pending.isEmpty()) return
        session!!.beginTransaction().use { tx ->
            pending.groupBy { it.first }.forEach { (label, rows) ->
                tx.run(
                    "UNWIND \$rows AS row MATCH (t) WHERE elementId(t) = row.ts CREATE (t)-[:hasMeasurement]->(n:`$label`) SET n = row.props",
                    mapOf("rows" to rows.map { (_, ts, props) -> mapOf("ts" to ts, "props" to props) })
                ).consume()
            }
            tx.commit()
        }
        pending.clear()
    }

    private var indexTime = 0L
    override fun getIndexTime(): Long = indexTime

    private fun createIndexes(session: Session) {
        labels.forEach { session.run("CREATE RANGE INDEX IF NOT EXISTS FOR (n:`$it`) ON (n.timestamp)").consume() }
        session.run("CALL db.awaitIndexes(3600)").consume()
    }

    /** As MimicIVNeo4J: with [csv], the import is time-series time; then the indexes (index time) */
    override fun loadData() {
        addGs()
        addTs()
        if (!csv) {
            flush()
            indexTime = getTime { createIndexes(session!!) }
        }
        close()
        if (csv && csvImport != null) {
            tsTime += csvImport.invoke(baseDir)
            indexTime = getTime { neo4jDriver(uri, userNeo4j, passwordNeo4j).use { d -> d.session().use { createIndexes(it) } } }
        }
    }

    override fun close() {
        if (!csv) flush()
        session?.close()
        driver?.close()
        writers.values.forEach { it.close() }
    }
}
