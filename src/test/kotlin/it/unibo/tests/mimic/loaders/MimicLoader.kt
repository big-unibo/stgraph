package it.unibo.tests.mimic.loaders
import it.unibo.graph.asterixdb.dateToTimestamp
import it.unibo.graph.utils.getTime
import it.unibo.stats.Loader
import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord
import java.io.File
import java.sql.DriverManager
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Maximum number of events loaded for a MIMIC size of test_config.yml: "full" (all the events), a number
 * (e.g. "1692200"), or "x" followed by a number (e.g. "x1692200") for the reduced dump used by the CI (xsmall),
 * loaded with the same limit. The size itself, prefix included, names the dump folder and the dataverse.
 */
fun mimicLimit(size: String): Long = when {
    size == "full" -> Long.MAX_VALUE
    else -> size.replace("x", "").toLong()
}

interface MimicIVLoader: Loader {
    fun addPerson(subjectId: Int, c: Int): Long
    fun addTimeseries(abbreviation: String, unitname: String?, category: String, label: String, itemid: Int, person: Long): Long
    fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean)
}

/** Reads the MIMIC-IV data from the PostgreSQL [postgres] (datasets.mimic of test_config.yml) */
abstract class AbstractMimicIVLoader(val limit: Long, val threads: Int, postgres: PostgresConfig = PostgresConfig()): MimicIVLoader {
    val url = "jdbc:postgresql://${postgres.host}:${postgres.port}/${postgres.database}"
    val user = postgres.user
    val password = postgres.password
    var gsTime: Long = 0
    var tsTime: Long = 0
    var gsCard: Long = 0
    var tsCard: Long = 0
    val item2nodeid: MutableMap<Pair<Long, Long>, Long> = mutableMapOf()
    var users = mutableListOf<Pair<Int, Int>>()

    override fun getGSCardinality(): Long = gsCard
    override fun getTSCardinality(): Long = tsCard
    override fun getGSTime(): Long = gsTime
    override fun getTSTime(): Long = tsTime
    override fun getIndexTime(): Long = 0L

    data class MIMICTS(
        val abbreviation: String,
        val unitname: String?,
        val category: String,
        val label: String,
        val itemid: Int
    )
    val tsCache = mutableMapOf<Int, MIMICTS>()

    fun addGs() {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            val rows = File("src/main/resources/mimic-iv_subjectids_tsids.csv")
                .useLines { lines ->
                    lines
                        // .drop(1) // uncomment if header exists
                        .map { line ->
                            val (subjectId, itemId, c) = line.trim().split(",")
                            Triple(subjectId.toInt(), itemId.toInt(), c.toInt())
                        }
                        .toList()
                }
            val sql = """SELECT itemid, unitname, category, label, abbreviation FROM d_items""".trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val ts = MIMICTS(
                        rs.getString("abbreviation"),
                        rs.getString("unitname"),
                        rs.getString("category"),
                        rs.getString("label"),
                        rs.getInt("itemid")
                    )
                    tsCache[ts.itemid] = ts
                }
            }
            val grouped: Map<Int, List<Pair<MIMICTS, Int>>> = rows.groupBy({ it.first }) { row ->
                val ts = tsCache[row.second] ?: error("Missing TS for itemId ${row.second}")
                Pair(ts, row.third)
            }
            var acc = 0
            gsTime += getTime {
                for ((subjectId: Int, items: List<Pair<MIMICTS, Int>>) in grouped.entries.shuffled(Random(42))) {
                    if (acc > limit) break
                    val c = items.sumOf { it.second }
                    acc += c
                    users.addAll(items.map { Pair(subjectId, it.first.itemid) })
                    val person = addPerson(subjectId, c)
                    gsCard++
                    for ((itemId, _) in items) {
                        val tsId = addTimeseries(itemId.abbreviation, itemId.unitname, itemId.category, itemId.label, itemId.itemid, person)
                        gsCard++
                        item2nodeid[Pair(subjectId.toLong(), itemId.itemid.toLong())] = tsId
                    }
                }
            }
        }
    }

    /**
     * The events read from PostgreSQL, before they are loaded: one primitive array per field (time series, item,
     * timestamp, value), about 28 bytes per event instead of the ~150 of a Pair with a TSRecord, so that the large sizes
     * fit in the heap; the TSRecord of an event is created when it is loaded
     */
    private class Events {
        var size = 0
            private set
        private var tsIds = LongArray(1024)
        private var itemIds = IntArray(1024)
        private var timestamps = LongArray(1024)
        private var values = LongArray(1024)

        fun add(tsId: Long, itemId: Int, timestamp: Long, value: Long) {
            if (size == tsIds.size) {
                val capacity = tsIds.size * 2
                tsIds = tsIds.copyOf(capacity); itemIds = itemIds.copyOf(capacity)
                timestamps = timestamps.copyOf(capacity); values = values.copyOf(capacity)
            }
            tsIds[size] = tsId; itemIds[size] = itemId; timestamps[size] = timestamp; values[size] = value
            size++
        }

        fun tsId(i: Int) = tsIds[i]
        fun record(i: Int) = TSRecord(itemIds[i].toString(), timestamp = timestamps[i], location = "", value = values[i])
    }

    fun loadTS(pairs: List<Pair<Int, Int>>): Long {
        val events = Events()
        val chunkSize = 100_000
        pairs.chunked(chunkSize).forEach { chunk ->
            val chunk = chunk.toSet()
            val subjectIds = chunk.map { (subjectId, _) -> subjectId }.toSet().joinToString { "'$it'" }
            val itemIds = chunk.map { (_, itemId) -> itemId }.toSet().joinToString { "'$it'" }
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.autoCommit = false
                val sql = """
                    SELECT subject_id, itemid, charttime, valuenum
                    FROM chartevents_unique c 
                    WHERE c.subject_id IN ($subjectIds) AND c.itemid IN ($itemIds)
                    ORDER BY subject_id, itemid, charttime
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.fetchSize = 10_000   // smaller is safer
                    val rs = stmt.executeQuery()
                    var prevItemId = -1L
                    var prevSubjectId = -1L
                    var skip = false
                    while (rs.next()) {
                        val subjectId = rs.getLong("subject_id")
                        val itemId = rs.getLong("itemid")
                        if (subjectId != prevSubjectId || itemId != prevItemId) {
                            skip = !chunk.contains(subjectId.toInt() to itemId.toInt())
                            prevItemId = itemId
                            prevSubjectId = subjectId
                        }
                        if (skip) continue
                        val tsId = item2nodeid[subjectId to itemId]
                        val chartTime = rs.getString("charttime")
                        val valueNum = rs.getString("valuenum")
                        events.add(tsId!!, itemId.toInt(), dateToTimestamp(chartTime), valueNum.toDouble().roundToLong())
                    }
                }
            }
        }
        tsCard += events.size
        val time = getTime {
            for (index in 0 until events.size) {
                val isFirst = index == 0 || events.tsId(index - 1) != events.tsId(index)
                val isLast = index + 1 == events.size || events.tsId(index) != events.tsId(index + 1)
                addMeasurement(events.tsId(index), events.record(index), isFirst = isFirst, isLast = isLast)
            }
            if (events.size > 0) awaitLast(events.tsId(events.size - 1), events.record(events.size - 1))
        }
        return time
    }

    /** Waits until the last measurement [row] of the worker is stored (timed as time-series time, as in SmartBench) */
    open fun awaitLast(tsId: Long, row: TSRecord) {}

    fun addTs() {
        val chunkSize = users.size / threads
        val results = LongArray(threads)
        val workers = mutableListOf<Thread>()
        for (i in 0 until threads) {
            val start = i * chunkSize
            val end = if (i == threads - 1) users.size else (i + 1) * chunkSize
            val subList = users.subList(start, end)
            val t = Thread {
                val time = loadTS(subList)
                results[i] = time
            }
            workers += t
            t.start()
        }
        workers.forEach { it.join() }
        tsTime = results.maxOrNull() ?: -1L
    }

    override fun loadData() {
        addGs()
        addTs()
        close()
    }
}

