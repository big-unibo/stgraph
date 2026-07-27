package it.unibo.tests.mimic.loaders

import it.unibo.tests.smartbench.loaders.TSRecord
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Session
import java.io.File

fun limitToPort(limit: Long): Int {
    return if (limit < 5_000_000) {
        8687
    } else if (limit < 50_000_000) {
        8688
    } else {
        8689
    }
}

class MimicIVNeo4J(
    limit: Long,
    uri: String = "bolt://localhost:${limitToPort(limit)}",
    user: String = "neo4j",
    password: String = "password",
    val csv: Boolean,
) : AbstractMimicIVLoader(limit, threads = 1) {

    private val driver: Driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password))
    private val session: Session = driver.session()
    val tsMap = mutableMapOf<Long, Long>()

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

    /** Deletes all nodes and relationships in the database */
    private fun clearGraph() {
        if (!csv) session.run("MATCH (n) DETACH DELETE n")
    }

    override fun addPerson(subjectId: Int, c: Int): Long {
        if (!csv) {
            val result = session.run(
                "CREATE (p:Person {subject_id: ${subjectId}}) RETURN id(p) AS id"
            )
            return result.single()["id"].asLong()
        } else {
            val personId = ++personCounter
            personWriter.write("$personId,$subjectId,${subjectId % 10},${personId-1},$c,Person\n")
            return personId
        }
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
        if (!csv) {
            val result = session.run(
                """
            MATCH (p) WHERE id(p) = $person
            CREATE (ts:TimeSeries {
                abbreviation: '${abbreviation}',
                unitname: '${unitname}',
                category: '${category}',
                label: '${label}',
                itemid: '${itemid}'
            })
            CREATE (p)-[:HAS_PARAMETERS]->(ts)
            RETURN id(ts) AS id
            """.trimIndent()
            )
            return result.single()["id"].asLong()
        } else {
            if (!tsMap.contains(person)) {
                val tsId = ++tsCounter
                hasParamsWriter.write("$person,$tsId,HAS_PARAMETERS\n")
                tsWriter.println(
                    listOf(
                        clean(tsId),
                        "TimeSeries"
                    ).joinToString(",")
                )
                tsMap[person] = tsId
            }
            return person
        }
    }

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        if (!csv) {
            session.run(
                """
            MATCH (ts) WHERE id(ts) = $tsId
            CREATE (m:${row.label} {
                timestamp: ${row.timestamp},
                value: ${row.value}
            })
            CREATE (ts)-[:HAS_MEASUREMENT]->(m)
            """.trimIndent()
            )
        } else {
            val ts = tsMap[tsId]!!
            val tmp = tsCache[row.label.toInt()]!!
            val measurementId = ++measurementCounter
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

    override fun close() {
        if (!csv) {
            session.close()
            driver.close()
        }

        tsWriter.close()
        hasParamsWriter.close()
        personWriter.close()
        measurementWriter.close()
        hasMeasurementWriter.close()
    }
}