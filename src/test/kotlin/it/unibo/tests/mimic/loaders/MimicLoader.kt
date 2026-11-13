package it.unibo.tests.mimic.loaders
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import it.unibo.graph.asterixdb.dateToTimestamp
import it.unibo.graph.utils.getTime
import it.unibo.stats.Loader
import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
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

/**
 * Reads the MIMIC-IV data from the PostgreSQL [postgres] (datasets.mimic of test_config.yml), once: what it reads (the
 * items, d_items, and the events of the selected time series, chartevents_unique) is saved, gzipped, in a local copy
 * ([cacheDir]: datasets/original/mimic/cache/<limit>), which the next loads read instead of PostgreSQL; it is written
 * in full before it is used (an interrupted read leaves none). Delete the folder to read PostgreSQL again (e.g., after
 * changing the list of subjects and time series or the PostgreSQL).
 * The copy can be copied to the other machines (same folder): then no machine queries PostgreSQL. Reading the data,
 * from PostgreSQL or from the copy, is not timed.
 */
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

    private val subjectsFile = File("src/main/resources/mimic-iv_subjectids_tsids.csv")
    private val mapper = jacksonObjectMapper()

    /** The local copy of the data read from PostgreSQL, one folder per limit */
    val cacheDir = File("datasets/original/mimic/cache/${if (limit == Long.MAX_VALUE) "full" else limit}")

    /** Writes [file] through a gzipped [write], in a temporary file renamed at the end: no partial copy */
    private fun writeGzipped(file: File, write: (BufferedWriter) -> Unit) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        GZIPOutputStream(tmp.outputStream(), 1 shl 16).bufferedWriter().use(write)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun gzippedReader(file: File): BufferedReader = GZIPInputStream(file.inputStream(), 1 shl 16).bufferedReader()

    /** The items (d_items): from the local copy, otherwise from PostgreSQL, then saved in the copy */
    private fun loadItems(): List<MIMICTS> {
        val file = File(cacheDir, "items.jsonl.gz")
        if (!file.exists()) {
            writeGzipped(file) { out ->
                DriverManager.getConnection(url, user, password).use { conn ->
                    conn.prepareStatement("SELECT itemid, unitname, category, label, abbreviation FROM d_items").use { stmt ->
                        val rs = stmt.executeQuery()
                        while (rs.next()) {
                            val ts = MIMICTS(rs.getString("abbreviation"), rs.getString("unitname"), rs.getString("category"), rs.getString("label"), rs.getInt("itemid"))
                            out.write(mapper.writeValueAsString(ts))
                            out.write("\n")
                        }
                    }
                }
            }
        }
        return gzippedReader(file).useLines { lines -> lines.filter { it.isNotBlank() }.map { mapper.readValue<MIMICTS>(it) }.toList() }
    }

    /**
     * The events of the time series [pairs] (subject, item) in the local copy, read from PostgreSQL if missing, with the
     * queries of the loaders before the copy (one per chunk of 100,000 time series, the rows of the other time series of
     * the chunk skipped): one line per event (subject, item, timestamp in ms, value rounded as the loaders load it), in
     * the order of the queries
     */
    private fun ensureEvents(pairs: List<Pair<Int, Int>>): File {
        val file = File(cacheDir, "events.csv.gz")
        if (file.exists()) return file
        writeGzipped(file) { out ->
            pairs.chunked(100_000).forEach { chunk ->
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
                        stmt.fetchSize = 10_000
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
                            val timestamp = dateToTimestamp(rs.getString("charttime"))
                            val value = rs.getString("valuenum").toDouble().roundToLong()
                            out.write("$subjectId,$itemId,$timestamp,$value\n")
                        }
                    }
                }
            }
        }
        return file
    }

    /**
     * Only the reads from PostgreSQL, without loading any system: the selection of the subjects and time series, then the
     * local copy of the items and of their events (see [cacheDir]), if missing. Returns the folder of the copy
     */
    fun prepareCache(): File {
        addGs()
        ensureEvents(users)
        return cacheDir
    }

    fun addGs() {
        run {
            val rows = subjectsFile
                .useLines { lines ->
                    lines
                        // .drop(1) // uncomment if header exists
                        .map { line ->
                            val (subjectId, itemId, c) = line.trim().split(",")
                            Triple(subjectId.toInt(), itemId.toInt(), c.toInt())
                        }
                        .toList()
                }
            loadItems().forEach { tsCache[it.itemid] = it }
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

    /** Loads the events of the time series [pairs] (subject, item) of a worker, read from the local copy */
    fun loadTS(pairs: List<Pair<Int, Int>>): Long {
        val events = Events()
        val wanted = HashSet<Long>(pairs.size * 2).apply { pairs.forEach { (subjectId, itemId) -> add(pairKey(subjectId, itemId)) } }
        gzippedReader(File(cacheDir, "events.csv.gz")).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                val (subjectId, itemId, timestamp, value) = line.split(",")
                val s = subjectId.toInt()
                val i = itemId.toInt()
                if (pairKey(s, i) !in wanted) continue
                events.add(item2nodeid.getValue(s.toLong() to i.toLong()), i, timestamp.toLong(), value.toLong())
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

    private fun pairKey(subjectId: Int, itemId: Int): Long = (subjectId.toLong() shl 32) or (itemId.toLong() and 0xffffffffL)

    fun addTs() {
        // The events of all the time series in the local copy (read from PostgreSQL if missing), before the workers
        ensureEvents(users)
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

