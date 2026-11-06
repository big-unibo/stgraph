package it.unibo.tests.mimic.loaders

import it.unibo.graph.utils.getTime
import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord
import it.unibo.tests.systems.neo4jDriver
import org.neo4j.driver.Driver
import org.neo4j.driver.Session
import java.io.File
import java.io.PrintWriter
import java.io.Writer
import java.util.zip.GZIPOutputStream

/** Whether MIMIC-IV is loaded into Neo4j from CSV files (neo4j-admin import) rather than one node at a time: above the small sizes */
fun neo4jCsv(limit: Long): Boolean = limit >= 5_000_000

/**
 * Loads MIMIC-IV into Neo4j, in two ways that produce the same graph (the one of the CSV files):
 * - (:Person {person_id, subject_id, rnd, pId, c})-[:HAS_PARAMETERS]->(:TimeSeries {ts_id}), a time series per person;
 * - (:TimeSeries)-[:HAS_EVENT]->(:<abbreviation> {measurement_id, ts_id, category, abbreviation, itemid, timestamp, value,
 *   unitname (if any)});
 * the ids and the numbers are integers (typed columns of the CSV files, imported with --id-type=integer), the rest strings.
 * With [csv], the nodes and relationships are written to gzipped CSV files (datasets/dump/neo4j/mimic/<limit>), then
 * [csvImport] (if any) imports them (e.g., neo4j-admin database import full) and returns the time of the import, added
 * to the time-series time. Without [csv], the Person and TimeSeries nodes are created one at a time through [uri], the
 * measurements in batches of [batchSize], as MimicIVPGAge: a batch is sent when full and at the end of each time series,
 * in one transaction (one UNWIND per label, since a label cannot be a parameter).
 * In both ways, after the load (index time), as SmartBenchNeo4J: a range index on the timestamps of the measurements, one
 * per label (a range index has one label), awaited until online; with [csv], through a new connection (Neo4j restarted
 * by the import). The clean-up drops these indexes, so that they are built after the load, not maintained during it.
 */
class MimicIVNeo4J(
    limit: Long,
    private val uri: String = "bolt://localhost:7687",
    private val userNeo4j: String? = "neo4j",
    private val passwordNeo4j: String? = "password",
    val csv: Boolean,
    postgres: PostgresConfig = PostgresConfig(),
    private val csvImport: ((File) -> Long)? = null,
    private val batchSize: Int = 20_000
) : AbstractMimicIVLoader(limit, threads = 1, postgres = postgres) {

    private val driver: Driver? = if (csv) null else neo4jDriver(uri, userNeo4j, passwordNeo4j)
    private val session: Session? = driver?.session()
    val tsMap = mutableMapOf<Long, Long>()
    // Without csv: the element ids of the Person and TimeSeries nodes, by their person_id and ts_id
    private val personNodes = mutableMapOf<Long, String>()
    private val tsNodes = mutableMapOf<Long, String>()
    // Without csv: the measurements not created yet, as (label, element id of the time series, properties)
    private val pending = mutableListOf<Triple<String, String, Map<String, Any>>>()

    private val baseDir = File("datasets/dump/neo4j/mimic/$limit").apply {
        mkdirs()
    }

    // Only with csv (gzipped, as SmartBenchNeo4J): otherwise the CSV files of a previous load (also read by HyGraph) are kept as they are
    private fun writer(name: String): PrintWriter = if (csv) PrintWriter(GZIPOutputStream(File("$baseDir/$name").outputStream()).bufferedWriter()) else PrintWriter(Writer.nullWriter())
    private val tsWriter = writer("ts.csv.gz")
    private val hasParamsWriter = writer("has_parameters.csv.gz")
    private val measurementWriter = writer("measurement.csv.gz")
    private val hasMeasurementWriter = writer("has_measurement.csv.gz")
    private val personWriter = writer("person.csv.gz")

    private var tsCounter: Long = 0
    private var measurementCounter: Long = 0
    private var personCounter: Long = 0

    init {
        clearGraph()
        baseDir.mkdirs()
        tsWriter.println("ts_id:ID(TimeSeries),:LABEL")
        hasParamsWriter.println(":START_ID(Person),:END_ID(TimeSeries),:TYPE")
        measurementWriter.println("measurement_id:ID(Measurement),ts_id:long,category,abbreviation,itemid:long,timestamp:long,value:long,unitname,:LABEL")
        hasMeasurementWriter.println(":START_ID(TimeSeries),:END_ID(Measurement),:TYPE")
        personWriter.println("person_id:ID(Person),subject_id:long,rnd:long,pId:long,c:long,:LABEL")

        // flush immediately to lock headers
        tsWriter.flush()
        hasParamsWriter.flush()
        measurementWriter.flush()
        hasMeasurementWriter.flush()
        personWriter.flush()
    }

    /**
     * Deletes the range indexes on the timestamps (built after the load, see [createIndexes]) and all nodes and
     * relationships in the database (in batches: a single transaction may not fit the heap)
     */
    private fun clearGraph() {
        val session = session ?: return
        session.run("SHOW RANGE INDEXES YIELD name, properties WHERE properties = ['timestamp'] RETURN name").list()
            .forEach { session.run("DROP INDEX `${it[0].asString()}` IF EXISTS").consume() }
        session.run("MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS").consume()
    }

    // The labels of the measurements loaded (their abbreviations), for the indexes
    private val labels = mutableSetOf<String>()
    private var indexTime = 0L
    override fun getIndexTime(): Long = indexTime

    /** A range index on the timestamps of each label of the measurements, awaited until online */
    private fun createIndexes(session: Session) {
        labels.forEach { session.run("CREATE RANGE INDEX IF NOT EXISTS FOR (n:${label(it)}) ON (n.timestamp)").consume() }
        session.run("CALL db.awaitIndexes(3600)").consume()
    }

    /**
     * Creates a node with [labels] and the [properties] as the CSV import stores them (numbers as integers, the rest as
     * strings, no property for an empty field), linked by [type] from the node [from], if any; returns its element id
     */
    private fun createNode(labels: String, properties: Map<String, Any?>, from: String? = null, type: String? = null): String {
        val props = props(properties)
        val query = if (from == null) "CREATE (n:$labels) SET n = \$props RETURN elementId(n)"
        else "MATCH (f) WHERE elementId(f) = \$from CREATE (f)-[:$type]->(n:$labels) SET n = \$props RETURN elementId(n)"
        return session!!.run(query, mapOf("props" to props, "from" to from)).single()[0].asString()
    }

    /** The [properties] as the CSV import stores them: numbers as integers, the rest as strings, no empty field */
    private fun props(properties: Map<String, Any?>): Map<String, Any> = properties
        .mapValues { (_, v) -> if (v is Number) v.toLong() else clean(v) }
        .filterValues { it != "" }

    /** The label of a measurement, escaped */
    private fun label(abbreviation: String) = "`${abbreviation.replace("`", "``")}`"

    override fun addPerson(subjectId: Int, c: Int): Long {
        val personId = ++personCounter
        if (!csv) {
            personNodes[personId] = createNode(
                "Person",
                linkedMapOf("person_id" to personId, "subject_id" to subjectId, "rnd" to subjectId % 10, "pId" to personId - 1, "c" to c)
            )
        } else {
            personWriter.write("$personId,$subjectId,${subjectId % 10},${personId-1},$c,Person\n")
        }
        return personId
    }

    fun clean(v: Any?): String {
        return v?.toString()
            ?.replace("\"", "")   // remove quotes
            ?.replace("\n", " ")  // optional safety
            ?: ""
    }

    override fun addTimeseries(
        abbreviation: String,
        unitname: String?,
        category: String,
        label: String,
        itemid: Int,
        person: Long
    ): Long {
        if (!tsMap.contains(person)) {
            val tsId = ++tsCounter
            if (!csv) {
                tsNodes[tsId] = createNode("TimeSeries", mapOf("ts_id" to tsId), from = personNodes.getValue(person), type = "HAS_PARAMETERS")
            } else {
                hasParamsWriter.write("$person,$tsId,HAS_PARAMETERS\n")
                tsWriter.println(
                    listOf(
                        clean(tsId),
                        "TimeSeries"
                    ).joinToString(",")
                )
            }
            tsMap[person] = tsId
        }
        return person
    }

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        val ts = tsMap[tsId]!!
        val tmp = tsCache[row.label.toInt()]!!
        val measurementId = ++measurementCounter
        labels += tmp.abbreviation
        if (!csv) {
            pending += Triple(
                label(tmp.abbreviation),
                tsNodes.getValue(ts),
                props(linkedMapOf(
                    "measurement_id" to measurementId, "ts_id" to ts, "category" to tmp.category, "abbreviation" to tmp.abbreviation,
                    "itemid" to tmp.itemid, "timestamp" to row.timestamp, "value" to row.value, "unitname" to tmp.unitname
                ))
            )
            if (pending.size >= batchSize || isLast) flush()
        } else {
            // write node
            measurementWriter.write(
                "$measurementId,$ts,${tmp.category},${tmp.abbreviation},${tmp.itemid},${row.timestamp},${row.value},${clean(tmp.unitname)},${tmp.abbreviation}\n"
            )
            // write relationship
            hasMeasurementWriter.write(
                "$ts,$measurementId,HAS_EVENT\n"
            )
            // optional: flush occasionally (or based on isLast)
            if (isLast) {
                measurementWriter.flush()
                hasMeasurementWriter.flush()
            }
        }
    }

    /** Without csv: creates the [pending] measurements in one transaction, one UNWIND per label */
    private fun flush() {
        if (pending.isEmpty()) return
        session!!.beginTransaction().use { tx ->
            pending.groupBy { it.first }.forEach { (label, rows) ->
                tx.run(
                    "UNWIND \$rows AS row MATCH (t) WHERE elementId(t) = row.ts CREATE (t)-[:HAS_EVENT]->(n:$label) SET n = row.props",
                    mapOf("rows" to rows.map { (_, ts, props) -> mapOf("ts" to ts, "props" to props) })
                ).consume()
            }
            tx.commit()
        }
        pending.clear()
    }

    /**
     * As the skeleton; with [csv], the CSV files are then imported by [csvImport], if any (time-series time); then the
     * indexes (index time)
     */
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
        flush()
        session?.close()
        driver?.close()

        tsWriter.close()
        hasParamsWriter.close()
        personWriter.close()
        measurementWriter.close()
        hasMeasurementWriter.close()
    }
}
