package it.unibo.tests.mimic.loaders

import it.unibo.tests.smartbench.loaders.TSRecord
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Session
import java.io.File


class MimicIVAeonG(
    limit: Long,
) : AbstractMimicIVLoader(limit, threads = 1) {

    private val baseDir = File("datasets/dump/aeong/mimic/$limit").apply {
        mkdirs()
    }

    private val cypherWriter = File("$baseDir/$limit.cypher").printWriter()

    init {
        clearGraph()
        baseDir.mkdirs()
    }

    /** Deletes all nodes and relationships in the database */
    private fun clearGraph() {
    }

    private fun writeCypher(query: String) {
        cypherWriter.appendLine(query.replace("\\s+".toRegex(), " ").replace("Return ", "", ignoreCase = true))
    }

    override fun addPerson(subjectId: Int, c: Int): Long {
        writeCypher(createPerson(subjectId, c))
        return subjectId.toLong()
    }

    private fun createPerson(subjectId: Int, c: Int): String = "CREATE (p:Person {subject_id: ${subjectId}, rnd: ${subjectId % 10}, pId: ${subjectId}, c: $c});"

    override fun addTimeseries(
        abbreviation: String,
        unitname: String?,
        category: String,
        label: String,
        itemid: Int,
        person: Long
    ): Long {
        writeCypher(createTS(person, abbreviation, unitname, category, label, itemid))
        return (person.toString() + abbreviation).hashCode().toLong()
    }

    private fun createTS(
        person: Long,
        abbreviation: String,
        unitname: String?,
        category: String,
        label: String,
        itemid: Int
    ): String = """
        MATCH (p: Person {subject_id: $person})
        CREATE (ts:TimeSeries {
            tsId: '${(person.toString() + abbreviation).hashCode()}',
            abbreviation: '${abbreviation}',
            unitname: '${unitname}',
            category: '${category}',
            label: '${label}',
            itemid: '${itemid}'
        })
        CREATE (p)-[:HAS_PARAMETERS]->(ts);
    """.trimIndent()

    override fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean) {
        //val query =
        //    if (isFirst) createMeasurement(tsId, row)
        //    else updateMeasurement(tsId, row)
        val query = createMeasurement2(tsId, row)
        writeCypher(query)
    }

    private fun createMeasurement2(tsId: Long, row: TSRecord): String = """
        MATCH (ts: TimeSeries {tsId: '$tsId'})
        CREATE (m:Measurement {
            timestamp: ${row.timestamp},
            value: ${row.value}
        })
        CREATE (ts)-[:HAS_MEASUREMENT]->(m);
        """.trimIndent()

    private fun createMeasurement(tsId: Long, row: TSRecord): String = """
        MATCH (ts: TimeSeries {tsId: '$tsId'})
        CREATE (m:Measurement {
            mId: '${tsId}',
            timestamp: ${row.timestamp},
            value: ${row.value}
        })
        CREATE (ts)-[:HAS_MEASUREMENT]->(m);
        """.trimIndent()

    private fun updateMeasurement(tsId: Long, row: TSRecord): String = """
        MATCH (m:Measurement { mId: '${tsId}' })
        SET m.timestamp = ${row.timestamp},
            m.value = ${row.value};
        """.trimIndent()

    override fun close() {
        cypherWriter.flush()
        cypherWriter.close()
    }
}