package it.unibo.tests.evaluation

import it.unibo.tests.systems.neo4jDriver
import org.neo4j.driver.Session
import java.io.File

/**
 * The transaction times of AeonG, measured on its loaded database. AeonG keeps the history with the logical clock of
 * Memgraph, which advances at the start of every transaction and at the commit of every write: the transaction time of
 * a statement of a dump is not its number. After the ingestion, AeonG serves its loaded database (AEONG_MODE=serve of
 * its compose file, Bolt on [PORT]) and each transaction time is searched (binary search) with TT AS t, the database as
 * of t: the dumps write the events in the order of their time, so what the database holds as of t only grows with t.
 * Not timed. The times are cached next to the dump (<dump>.aeong, name=transaction time): they hold as long as the
 * database is the one loaded from the dump, so each ingestion removes them ([invalidate]).
 */
object AeonGTransactionTimes {
    private const val COMPOSE = "evaluation/architectures/aeong/docker-compose.yaml"

    /** The port of the host on which AeonG serves its database (AEONG_PORT of its compose file) */
    const val PORT = 7690

    fun cacheFile(dump: File) = File(dump.path.removeSuffix(".cypher") + ".aeong")

    /** Removes the cached times of [dump]: its database is loaded again */
    fun invalidate(dump: File) {
        cacheFile(dump).delete()
    }

    /**
     * The transaction times of [dump] (name -> time): the cached ones, or measured by [measure] on the database loaded
     * from it, served by the container of AeonG with [env] (as the container of its runs), and cached
     */
    fun of(dump: File, env: Map<String, String>, measure: (Search) -> Map<String, Long>): Map<String, Long> {
        val cache = cacheFile(dump)
        if (cache.isFile) {
            progress("aeong | ${dump.name}: transaction times of ${cache.path} (cached)")
            return cache.readLines().filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=").toLong() }
        }
        val uri = "bolt://localhost:$PORT"
        val serve = env + mapOf("AEONG_MODE" to "serve", "AEONG_PORT" to PORT.toString())
        progress("aeong | ${dump.name}: measuring the transaction times on the loaded database")
        val times = try {
            deployed(true, COMPOSE, env = serve, ready = { neo4jDriver(uri).use { it.verifyConnectivity() } }) {
                neo4jDriver(uri).use { driver -> driver.session().use { measure(Search(it)) } }
            }
        } finally {
            runCommand(listOf("docker", "compose", "-f", COMPOSE, "down"), serve)
        }
        cache.writeText(times.entries.joinToString("") { (name, time) -> "$name=$time\n" })
        progress("aeong | ${dump.name}: transaction times $times, cached in ${cache.path}")
        return times
    }

    /** The searches on the served database */
    class Search(private val session: Session) {
        /** The single value of [query] as a number (0 when null) */
        fun value(query: String): Long = session.run(query).single()[0].let { if (it.isNull) 0L else it.asNumber().toLong() }

        /** The single value of [query] as a string */
        fun text(query: String): String = session.run(query).single()[0].asString()

        /**
         * The first transaction time at which [condition] holds, which only turns true with the time (false, then true):
         * doubling from [start] (e.g., twice the statements of the dump) to a time at which it holds, then a binary search
         */
        fun first(start: Long, condition: (Long) -> Boolean): Long {
            var hi = maxOf(start, 1L)
            while (!condition(hi)) {
                hi *= 2
                check(hi < (1L shl 50)) { "No transaction time at which the condition holds" }
            }
            var lo = 0L
            while (lo < hi) {
                val mid = lo + (hi - lo) / 2
                if (condition(mid)) hi = mid else lo = mid + 1
            }
            return lo
        }
    }
}
