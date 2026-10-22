package it.unibo.tests.systems

import it.unibo.graph.query.QueryMode
import it.unibo.graph.utils.getTime
import it.unibo.stats.QueryResultData
import it.unibo.stats.Querying
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

/**
 * A connection to PostgreSQL with Apache AGE (PGAge), with the session settings that the original evaluation
 * (pg_connector.py) applied before each statement, applied here once: AGE loaded, ag_catalog in the search path, no
 * parallel workers. [properties] go to the JDBC driver (e.g., reWriteBatchedInserts)
 */
fun pgAgeConnection(url: String, user: String, password: String, properties: Map<String, String> = emptyMap()): Connection {
    val connection = DriverManager.getConnection(url, Properties().apply {
        setProperty("user", user)
        setProperty("password", password)
        properties.forEach { (key, value) -> setProperty(key, value) }
    })
    connection.createStatement().use { st ->
        st.execute("LOAD 'age';")
        st.execute("SET search_path = ag_catalog, \"\$user\", public;")
        st.execute("SET max_parallel_workers_per_gather = 0;")
        st.execute("SET max_parallel_workers = 0;")
    }
    return connection
}

/**
 * A SQL (and Cypher, through AGE) query on an open [connection] (see [pgAgeConnection]), shared by the datasets: since
 * runQuery times the whole call, the connection is opened by the caller, and only the query is timed (execution and
 * transfer of the whole result).
 */
abstract class PGAgeQuery(private val connection: Connection) : Querying {
    /** The SQL query */
    abstract fun statement(): String

    override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
        var rows = 0
        val time = getTime {
            connection.createStatement().use { st -> st.executeQuery(statement()).use { rs -> while (rs.next()) rows++ } }
        }
        return QueryResultData(time, rows)
    }
}
