package it.unibo.tests.systems

import it.unibo.graph.query.QueryMode
import it.unibo.graph.utils.getTime
import it.unibo.stats.QueryResultData
import it.unibo.stats.Querying
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Session

/** A driver of the Neo4j at [uri], without authentication when [user] is null */
fun neo4jDriver(uri: String, user: String? = null, password: String? = null): Driver =
    GraphDatabase.driver(uri, if (user == null) AuthTokens.none() else AuthTokens.basic(user, password))

/**
 * A Cypher query on an open [session], shared by the datasets: since runQuery times the whole call, the session is
 * opened by the caller, and only the query is timed (execution and transfer of the whole result).
 */
abstract class Neo4JQuery(private val session: Session) : Querying {
    /** The Cypher query */
    abstract fun statement(): String

    override fun runQuery(threads: Int, queryMode: QueryMode): QueryResultData {
        var rows = 0
        val time = getTime { rows = session.run(statement()).list().size }
        return QueryResultData(time, rows)
    }
}
