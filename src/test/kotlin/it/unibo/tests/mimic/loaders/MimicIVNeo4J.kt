package it.unibo.tests.mimic.loaders

import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord
import it.unibo.tests.systems.neo4jDriver
import org.neo4j.driver.Driver
import org.neo4j.driver.Session
import java.io.File

/** Whether MIMIC-IV is loaded into Neo4j from CSV files (neo4j-admin import) rather than one node at a time: above the small sizes */
fun neo4jCsv(limit: Long): Boolean = limit >= 5_000_000

/**
 * Loads MIMIC-IV into Neo4j, in two ways that produce the same graph (the one of the CSV files):
 * - (:Person {person_id, subject_id, rnd, pId, c})-[:HAS_PARAMETERS]->(:TimeSeries {ts_id}), a time series per person;
 * - (:TimeSeries)-[:HAS_EVENT]->(:<abbreviation> {measurement_id, ts_id, category, abbreviation, itemid, timestamp, value});
 * every property is a string, as neo4j-admin import stores the untyped columns.
 * With [csv], the nodes and relationships are written to CSV files (datasets/dump/neo4j/mimic/<limit>), then
 * [csvImport] (if any) imports them (e.g., neo4j-admin database import full) and returns the time of the import, added
 * to the time-series time. Without [csv], the nodes are created one at a time through [uri].
 */
class MimicIVNeo4J(
    limit: Long,
    uri: String = "bolt://localhost:7687",
    user: String? = "neo4j",
    password: String? = "password",
    val csv: Boolean,
    postgres: PostgresConfig = PostgresConfig(),
    private val csvImport: ((File) -> Long)? = null,
) : AbstractMimicIVLoader(limit, threads = 1, postgres = postgres) {

    private val driver: Driver? = if (csv) null else neo4jDriver(uri, user, password)
    private val session: Session? = driver?.session()
    val tsMap = mutableMapOf<Long, Long>()
    // Without csv: the element ids of the Person and TimeSeries nodes, by their person_id and ts_id
    private val personNodes = mutableMapOf<Long, String>()
    private val tsNodes = mutableMapOf<Long, String>()

    private val baseDir = File("datasets/dump/neo4j/mimic/$limit").apply {
        mkdirs()
    }

    private val tsWriter = File("$baseDir/ts.csv").printWriter()
    private val hasParamsWriter = File("$baseDir/has_parameters.csv").printWriter()
    private val measurementWriter = File("$baseDir/measurement.csv").printWriter()
    private val hasMeasurementWriter = File("$baseDir/has_measurement.csv").printWriter()
    private val personWriter = File("$baseDir/person.csv").printWriter()

    private var tsCounter: Long = 0
    private var measurementCounter: Long = 0
    private var personCounter: Long = 0

    init {
        clearGraph()
        baseDir.mkdirs()
        tsWriter.println("ts_id:ID(TimeSeries),:LABEL")
        hasParamsWriter.println(":START_ID(Person),:END_ID(TimeSeries),:TYPE")
        measurementWriter.println("measurement_id:ID(Measurement),ts_id,category,abbreviation,itemid,timestamp,value,:LABEL")
        hasMeasurementWriter.println(":START_ID(TimeSeries),:END_ID(Measurement),:TYPE")
        personWriter.println("person_id:ID(Person),subject_id,rnd,pId,c,:LABEL")

        // flush immediately to lock headers
        tsWriter.flush()
        hasParamsWriter.flush()
        measurementWriter.flush()
        hasMeasurementWriter.flush()
        personWriter.flush()
    }

    /** Deletes all nodes and relationships in the database (in batches: a single transaction may not fit the heap) */
    private fun clearGraph() {
        session?.run("MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS")?.consume()
    }

    /**
     * Creates a node with [labels] and the [properties] as the CSV import stores them (strings, no property for an empty
     * field), linked by [type] from the node [from], if any; returns its element id
     */
    private fun createNode(labels: String, properties: Map<String, Any?>, from: String? = null, type: String? = null): String {
        val props = properties.mapValues { clean(it.value) }.filterValues { it.isNotEmpty() }
        val query = if (from == null) "CREATE (n:$labels) SET n = \$props RETURN elementId(n)"
        else "MATCH (f) WHERE elementId(f) = \$from CREATE (f)-[:$type]->(n:$labels) SET n = \$props RETURN elementId(n)"
        return session!!.run(query, mapOf("props" to props, "from" to from)).single()[0].asString()
    }

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
        if (!csv) {
            createNode(
                "`${tmp.abbreviation.replace("`", "``")}`",
                linkedMapOf(
                    "measurement_id" to measurementId, "ts_id" to ts, "category" to tmp.category, "abbreviation" to tmp.abbreviation,
                    "itemid" to tmp.itemid, "timestamp" to row.timestamp, "value" to row.value
                ),
                from = tsNodes.getValue(ts),
                type = "HAS_EVENT"
            )
        } else {
            // write node
            measurementWriter.write(
                "$measurementId,$ts,${tmp.category},${tmp.abbreviation},${tmp.itemid},${row.timestamp},${row.value},${tmp.abbreviation}\n"
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

    /** As the skeleton; with [csv], the CSV files are then imported by [csvImport], if any (time-series time) */
    override fun loadData() {
        addGs()
        addTs()
        close()
        if (csv && csvImport != null) tsTime += csvImport.invoke(baseDir)
    }

    override fun close() {
        session?.close()
        driver?.close()

        tsWriter.close()
        hasParamsWriter.close()
        personWriter.close()
        measurementWriter.close()
        hasMeasurementWriter.close()
    }
}
