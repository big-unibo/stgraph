package it.unibo.tests.smartbench

import it.unibo.graph.query.QueryMode
import it.unibo.stats.TestConfig
import it.unibo.stats.runQuery
import it.unibo.tests.graphix.GraphixQuery
import org.json.JSONArray

/**
 * The SmartBench queries of STGraph (same query ids, parameters and time windows) written in Graphix (gSQL++),
 * over the graph created by SmartBenchGraphixLoader. Not tests, since the CI covers STGraph only: run [main].
 */
class TestSmartBenchGraphixQuery {

    abstract class SmartBenchQuery(val size: String) : GraphixQuery("smartbench_graphix_$size") {
        protected fun window(): Pair<Long, Long> =
            TestConfig.loadTemporalRanges("increased", queryId, size)[0]!!.let { it.from to it.to }
    }

    companion object {
        /**
         * Environment locations are stored as the WKT strings of the dataset, with a Z coordinate that AsterixDB rejects:
         * drop it, as STGraph does before pushing spatial filters. Event locations are already 2D geometries.
         */
        fun geometry2D(field: String) = "st_geom_from_text(regexp_replace($field, \"(-?[0-9.]+) (-?[0-9.]+) -?[0-9.]+\", \"\$1 \$2\"))"
    }

    /** Devices covering environment 3042 that have a temperature time series */
    class EnvironmentCoverage(size: String) : SmartBenchQuery(size) {
        override val queryId = "EnvironmentCoverage"
        override fun statement() = """
            FROM GRAPH SmartBench
            MATCH (i:`Infrastructure`)<-[:`hasCoverage`]-(s:`Sensor`)-[:`hasTemperature`]->(m:`Temperature`)
            WHERE i.id = "3042"
            GROUP BY i.id AS environment, s.id AS device
            SELECT environment, device;
        """.trimIndent()
    }

    /** For each platform type, owner and environment: average temperature in [tA, tB) of the owner's sensors */
    class EnvironmentAggregate(size: String) : SmartBenchQuery(size) {
        override val queryId = "EnvironmentAggregate"
        override fun statement(): String {
            val (tA, tB) = window()
            return """
                FROM GRAPH SmartBench
                MATCH (pt:`PlatformType`)<-[:`hasType_`]-(p:`Platform`)-[:`hasOwner`]->(u:`User`)<-[:`hasOwner`]-(s:`Sensor`),
                      (it:`InfrastructureType`)<-[:`hasType_`]-(i:`Infrastructure`)<-[:`hasCoverage`]-(s)-[:`hasTemperature`]->(m:`Temperature`)
                WHERE m.`timestamp` >= $tA AND m.`timestamp` < $tB
                GROUP BY pt.id AS platformType, u.id AS owner, i.id AS environment
                SELECT platformType, owner, environment, AVG(m.`value`) AS avgValue;
            """.trimIndent()
        }
    }

    /** Devices (with environment and owner) that measured at least 65 degrees in [tA, tB) */
    class MaintenanceOwners(size: String) : SmartBenchQuery(size) {
        override val queryId = "MaintenanceOwners"
        override fun statement(): String {
            val (tA, tB) = window()
            return """
                FROM GRAPH SmartBench
                MATCH (i:`Infrastructure`)<-[:`hasCoverage`]-(s:`Sensor`)-[:`hasTemperature`]->(m:`Temperature`),
                      (s)-[:`hasOwner`]->(u:`User`)
                WHERE m.`value` >= 65 AND m.`timestamp` >= $tA AND m.`timestamp` < $tB
                GROUP BY s.id AS device, i.id AS environment, u.id AS owner
                SELECT device, environment, owner;
            """.trimIndent()
        }
    }

    /** Environments whose average temperature in [tA, tB), measured inside them, is above 50.5 */
    class EnvironmentOutlier(size: String) : SmartBenchQuery(size) {
        override val queryId = "EnvironmentOutlier"
        override fun statement(): String {
            val (tA, tB) = window()
            return """
                FROM GRAPH SmartBench
                MATCH (i:`Infrastructure`)<-[:`hasCoverage`]-(s:`Sensor`)-[:`hasTemperature`]->(m:`Temperature`)
                WHERE m.`timestamp` >= $tA AND m.`timestamp` < $tB
                  AND st_intersects(${geometry2D("i.location")}, m.location)
                GROUP BY i.id AS environment
                SELECT environment, AVG(m.`value`) AS avgValue;
            """.trimIndent()
        }

        // As in STGraph, the threshold on the average is applied to the result, outside the timed query
        override fun cardinality(rows: JSONArray): Int =
            (0 until rows.length()).count { rows.getJSONObject(it).optDouble("avgValue", Double.NaN) > 50.5 }
    }

    /** For each device and environment: maximum temperature in [tA, tB) */
    class AgentOutlier(size: String) : SmartBenchQuery(size) {
        override val queryId = "AgentOutlier"
        override fun statement(): String {
            val (tA, tB) = window()
            return """
                FROM GRAPH SmartBench
                MATCH (i:`Infrastructure`)<-[:`hasCoverage`]-(s:`Sensor`)-[:`hasTemperature`]->(m:`Temperature`)
                WHERE m.`timestamp` >= $tA AND m.`timestamp` < $tB
                GROUP BY s.id AS device, i.id AS environment
                SELECT device, environment, MAX(m.`value`) AS maxValue;
            """.trimIndent()
        }
    }

    /** Environments covered by device thermometer3, which has a temperature time series */
    class AgentHistory(size: String) : SmartBenchQuery(size) {
        override val queryId = "AgentHistory"
        override fun statement() = """
            FROM GRAPH SmartBench
            MATCH (i:`Infrastructure`)<-[:`hasCoverage`]-(s:`Sensor`)-[:`hasTemperature`]->(m:`Temperature`)
            WHERE s.id = "thermometer3"
            GROUP BY s.id AS device, i.id AS environment
            SELECT device, environment;
        """.trimIndent()
    }
}

/** Runs the Graphix queries for each SmartBench size of test_config.yml. Graphix must be running, with the data loaded */
fun main() {
    TestConfig.loadConfig().datasets["smartbench"]!!.sizes.forEach { rawSize ->
        val size = TestConfig.normalizeSize(rawSize)
        listOf(
            TestSmartBenchGraphixQuery.EnvironmentCoverage(size),
            TestSmartBenchGraphixQuery.EnvironmentAggregate(size),
            TestSmartBenchGraphixQuery.MaintenanceOwners(size),
            TestSmartBenchGraphixQuery.EnvironmentOutlier(size),
            TestSmartBenchGraphixQuery.AgentOutlier(size),
            TestSmartBenchGraphixQuery.AgentHistory(size)
        ).forEach { query ->
            runQuery(query, "graphix", threads = 1, numMachines = 1, "smartbench", size, QueryMode.OPTIMIZED)
        }
    }
}
