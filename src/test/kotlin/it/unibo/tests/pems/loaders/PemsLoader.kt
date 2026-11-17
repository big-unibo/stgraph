package it.unibo.tests.pems.loaders

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import it.unibo.graph.asterixdb.dateToTimestamp
import it.unibo.graph.interfaces.halfOpen
import it.unibo.graph.utils.getTime
import it.unibo.stats.Loader
import it.unibo.stats.PostgresConfig
import it.unibo.tests.smartbench.loaders.TSRecord
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/** Number of measurements (events) of a PeMS size of test_config.yml: xsmall, small, medium, large, full or a number */
fun pemsLimit(size: String): Long = when (size) {
    "xsmall" -> 1_000L
    "small" -> 1_000_000L
    "medium" -> 10_000_000L
    "large" -> 100_000_000L
    "full" -> Long.MAX_VALUE
    else -> size.toLong()
}

/** Whether Neo4j and PGAge are bulk loaded (CSV import, COPY) rather than one statement at a time: from medium on, and always in the CI (CI=true) */
fun pemsBulk(size: String): Boolean = System.getenv("CI") == "true" || pemsLimit(size) >= pemsLimit("medium")

data class PemsStation(val stationId: Int, val districtId: Int)
data class PemsRoadInDistrict(val districtId: Int, val fwy: String, val dir: String)
data class PemsSegment(val fwy: String, val dir: String, val mile: Int, val fromPm: Double?, val toPm: Double?)
data class PemsStationVersion(
    val stationId: Int, val from: Long, val to: Long, val fwy: String?, val dir: String?, val mile: Int?,
    val absPm: Double?, val latitude: Double?, val longitude: Double?, val type: String?, val lanes: Int?, val name: String?
) {
    /** The attributes that change over time, as properties */
    fun attributes(): Map<String, Any?> =
        linkedMapOf("abs_pm" to absPm, "latitude" to latitude, "longitude" to longitude, "type" to type, "lanes" to lanes, "name" to name)
}
data class PemsIncident(
    val incidentId: Long, val from: Long, val to: Long, val fwy: String?, val dir: String?, val mile: Int?,
    val districtId: Int?, val severity: String?, val description: String?
)

/**
 * Loads PeMS (schema pems_cleaned of the PostgreSQL [postgres], hourly measurements only) as the MIMIC-IV loaders:
 * - stations in a fixed random order (seed 42), each with all its measurements (by lane, then time), until [limit]
 *   measurements; the graph is the one of the selected stations: their versions, segments, roads, districts, and the
 *   incidents on their segments;
 * - one time series per (station, lane), lane 0 the station total; one event per measure, labelled lane<n>_flow,
 *   lane<n>_avg_occupancy, lane<n>_avg_speed, with the value cast to Long (occupancy, 0-1, multiplied by 100 first);
 * - what is read from PostgreSQL is saved, gzipped, in [cacheDir] (datasets/original/pems/cache/<size>), which the
 *   next loads read instead (not timed). Delete the folder to read PostgreSQL again.
 * Graph:
 *   (:Road)-[:inDistrict]->(:District), (:Road)-[:hasSegment]->(:Segment), (:Incident)-[:locatedIn]->(:Segment),
 *   (:Station)-[:hasTimeSeries]->(:TimeSeries)-[:hasMeasurement]->(measurement).
 * The stations, by default (the competitors): (:Station)-[:hasVersion]->(:StationVersion {valid_from, valid_to})-[:inSegment]->(:Segment);
 * STGraph overrides [addStation] with temporal properties and edges. A missing start is Long.MIN_VALUE, a missing end Long.MAX_VALUE.
 */
abstract class AbstractPemsLoader(val size: String, postgres: PostgresConfig = PostgresConfig()) : Loader {
    val limit = pemsLimit(size)
    private val url = "jdbc:postgresql://${postgres.host}:${postgres.port}/${postgres.database}"
    private val user = postgres.user
    private val password = postgres.password
    var gsTime = 0L
    var tsTime = 0L
    var gsCard = 0L
    var tsCard = 0L
    override fun getGSCardinality(): Long = gsCard
    override fun getTSCardinality(): Long = tsCard
    override fun getGSTime(): Long = gsTime
    override fun getTSTime(): Long = tsTime
    override fun getIndexTime(): Long = 0L

    /** A node; [from] and [to] (both null or both set) are its validity: a temporal node */
    abstract fun node(label: String, props: Map<String, Any?>, from: Long? = null, to: Long? = null): Long
    /** An edge, valid in [[validFrom], [validTo]) */
    abstract fun edge(label: String, from: Long, to: Long, validFrom: Long = Long.MIN_VALUE, validTo: Long = Long.MAX_VALUE)
    abstract fun addMeasurement(tsId: Long, row: TSRecord, isFirst: Boolean, isLast: Boolean)
    /** Waits until the last measurement [row] is stored (timed as time-series time) */
    open fun awaitLast(tsId: Long, row: TSRecord) {}

    /** The station and a node per version, linked to its segment (if any) */
    open fun addStation(station: PemsStation, versions: List<Pair<PemsStationVersion, Long?>>): Long {
        val id = node("Station", linkedMapOf("station_id" to station.stationId, "district_id" to station.districtId))
        for ((v, segment) in versions) {
            val version = node("StationVersion", linkedMapOf<String, Any?>("station_id" to station.stationId) + v.attributes(), v.from, v.to)
            edge("hasVersion", id, version)
            if (segment != null) edge("inSegment", version, segment)
        }
        return id
    }

    open fun addTimeseries(station: Long, lane: Int): Long =
        node("TimeSeries", linkedMapOf("lane" to lane)).also { edge("hasTimeSeries", station, it) }

    // ---------------------------------------------------------------------------------------------------------------
    // Local copy of the data read from PostgreSQL
    // ---------------------------------------------------------------------------------------------------------------

    val cacheDir = File("datasets/original/pems/cache/$size")
    private val mapper = jacksonObjectMapper()

    private fun writeGzipped(name: String, write: (BufferedWriter) -> Unit) {
        val file = File(cacheDir, name).apply { parentFile.mkdirs() }
        val tmp = File(cacheDir, "$name.tmp")
        GZIPOutputStream(tmp.outputStream(), 1 shl 16).bufferedWriter().use(write)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun <T> lines(name: String, block: (Sequence<String>) -> T): T =
        GZIPInputStream(File(cacheDir, name).inputStream(), 1 shl 16).bufferedReader().useLines { block(it.filter { l -> l.isNotEmpty() }) }

    private inline fun <reified T> read(name: String): List<T> = lines(name) { it.map { l -> mapper.readValue<T>(l) }.toList() }

    private fun <T> writeJson(name: String, rows: List<T>) = writeGzipped(name) { out -> rows.forEach { out.write(mapper.writeValueAsString(it)); out.write("\n") } }

    private fun <T : Any> query(conn: Connection, sql: String, stations: List<Int>? = null, row: (ResultSet) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            st.fetchSize = 10_000
            if (stations != null) st.setArray(1, conn.createArrayOf("integer", stations.toTypedArray()))
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) row(rs) else null }.toList() }
        }

    private fun ResultSet.int(c: String): Int? = getInt(c).takeUnless { wasNull() }
    private fun ResultSet.double(c: String): Double? = getDouble(c).takeUnless { wasNull() }
    /** A timestamp as text: null -> [missing], -infinity -> Long.MIN_VALUE, infinity -> Long.MAX_VALUE */
    private fun ResultSet.time(c: String, missing: Long): Long = when (val s = getString(c)) {
        null -> missing
        "-infinity" -> Long.MIN_VALUE
        "infinity" -> Long.MAX_VALUE
        else -> dateToTimestamp(s)
    }

    /** Reads the stations, their measurements and their graph from PostgreSQL into [cacheDir], if missing */
    fun prepareCache(): File {
        if (FILES.all { File(cacheDir, it).exists() }) return cacheDir
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false // streams the results (fetchSize)
            val all = query(conn, "SELECT station_id, district_id FROM $SCHEMA.station ORDER BY station_id") { PemsStation(it.getInt(1), it.getInt(2)) }
            val stations = mutableListOf<PemsStation>()
            var count = 0L
            // One line per event: station, lane, measure (index of MEASURES), timestamp, value
            writeGzipped(EVENTS) { out ->
                for (station in all.shuffled(Random(42))) {
                    if (count >= limit) break
                    val before = count
                    conn.prepareStatement("SELECT lane, ts::text AS ts, flow, occupancy, speed FROM $SCHEMA.measurement WHERE station_id = ? AND resolution_min = 60 ORDER BY lane, ts").use { st ->
                        st.fetchSize = 10_000
                        st.setInt(1, station.stationId)
                        st.executeQuery().use { rs ->
                            while (count < limit && rs.next()) {
                                val lane = rs.getInt("lane")
                                val timestamp = rs.time("ts", Long.MIN_VALUE)
                                listOf(rs.double("flow"), rs.double("occupancy")?.times(100), rs.double("speed")).forEachIndexed { measure, value ->
                                    if (value != null && count < limit) {
                                        out.write("${station.stationId},$lane,$measure,$timestamp,${value.toLong()}\n")
                                        count++
                                    }
                                }
                            }
                        }
                    }
                    if (count > before) stations += station
                }
            }
            val ids = stations.map { it.stationId }
            writeJson(STATIONS, stations)
            writeJson(VERSIONS, query(conn, """
                SELECT station_id, valid_from::text AS valid_from, valid_to::text AS valid_to, fwy, dir, mile, abs_pm, latitude, longitude, type, lanes, name
                FROM $SCHEMA.station_in_segment WHERE station_id = ANY(?) ORDER BY station_id, valid_from""", ids) {
                PemsStationVersion(it.getInt("station_id"), it.time("valid_from", Long.MIN_VALUE), it.time("valid_to", Long.MAX_VALUE),
                    it.getString("fwy"), it.getString("dir"), it.int("mile"), it.double("abs_pm"), it.double("latitude"),
                    it.double("longitude"), it.getString("type"), it.int("lanes"), it.getString("name"))
            })
            writeJson(SEGMENTS, query(conn, """
                SELECT DISTINCT g.fwy, g.dir, g.mile, g.from_pm, g.to_pm FROM $SCHEMA.segment g JOIN $SCHEMA.station_in_segment s USING (fwy, dir, mile)
                WHERE s.station_id = ANY(?) ORDER BY g.fwy, g.dir, g.mile""", ids) {
                PemsSegment(it.getString("fwy"), it.getString("dir"), it.getInt("mile"), it.double("from_pm"), it.double("to_pm"))
            })
            writeJson(ROADS, query(conn, """
                SELECT DISTINCT r.district_id, r.fwy, r.dir FROM $SCHEMA.road_in_district r JOIN $SCHEMA.station_in_segment s USING (fwy, dir)
                WHERE s.station_id = ANY(?) ORDER BY r.district_id, r.fwy, r.dir""", ids) {
                PemsRoadInDistrict(it.getInt("district_id"), it.getString("fwy"), it.getString("dir"))
            })
            writeJson(INCIDENTS, query(conn, """
                SELECT DISTINCT i.incident_id, i.time_from::text AS time_from, i.time_to::text AS time_to, i.fwy, i.dir, i.mile, i.district_id, i.severity, i.description
                FROM $SCHEMA.incident i JOIN $SCHEMA.station_in_segment s USING (fwy, dir, mile)
                WHERE s.station_id = ANY(?) ORDER BY i.incident_id""", ids) {
                val from = it.time("time_from", Long.MIN_VALUE)
                PemsIncident(it.getLong("incident_id"), from, halfOpen(from, it.time("time_to", Long.MAX_VALUE)), // zero minutes: [t, t + 1)
                    it.getString("fwy"), it.getString("dir"), it.int("mile"), it.int("district_id"), it.getString("severity"), it.getString("description"))
            })
        }
        return cacheDir
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ingestion
    // ---------------------------------------------------------------------------------------------------------------

    /** The events, one primitive array per field (as the MIMIC-IV loader), so that the large sizes fit in the heap */
    private class Events {
        var size = 0
        var ts = IntArray(1024); var measure = ByteArray(1024); var timestamp = LongArray(1024); var value = LongArray(1024)
        fun add(t: Int, m: Int, time: Long, v: Long) {
            if (size == ts.size) {
                val c = size * 2
                ts = ts.copyOf(c); measure = measure.copyOf(c); timestamp = timestamp.copyOf(c); value = value.copyOf(c)
            }
            ts[size] = t; measure[size] = m.toByte(); timestamp[size] = time; value[size] = v
            size++
        }
    }

    private val events = Events()
    /** The (station, lane) of each time series, by index, and their ids in the system */
    private val tsKeys = mutableListOf<Pair<Int, Int>>()
    private val tsIds = mutableListOf<Long>()

    private fun record(i: Int): TSRecord {
        val lane = tsKeys[events.ts[i]].second
        return TSRecord("lane${lane}_${MEASURES[events.measure[i].toInt()]}", events.timestamp[i], location = "", value = events.value[i])
    }

    fun addGs() {
        prepareCache()
        val index = HashMap<Pair<Int, Int>, Int>()
        lines(EVENTS) { lines ->
            lines.forEach { line ->
                val (station, lane, measure, timestamp, value) = line.split(",")
                val key = station.toInt() to lane.toInt()
                val t = index.getOrPut(key) { tsKeys += key; tsKeys.size - 1 }
                events.add(t, measure.toInt(), timestamp.toLong(), value.toLong())
            }
        }
        val roadsInDistrict = read<PemsRoadInDistrict>(ROADS)
        val segments = read<PemsSegment>(SEGMENTS)
        val stations = read<PemsStation>(STATIONS)
        val versions = read<PemsStationVersion>(VERSIONS).groupBy { it.stationId }
        val incidents = read<PemsIncident>(INCIDENTS)
        gsTime += getTime {
            val districts = roadsInDistrict.map { it.districtId }.distinct().associateWith { node("District", linkedMapOf("district_id" to it)) }
            val roads = (roadsInDistrict.map { it.fwy to it.dir } + segments.map { it.fwy to it.dir }).distinct()
                .associateWith { (fwy, dir) -> node("Road", linkedMapOf("fwy" to fwy, "dir" to dir)) }
            roadsInDistrict.forEach { edge("inDistrict", roads.getValue(it.fwy to it.dir), districts.getValue(it.districtId)) }
            val segmentIds = segments.associate { s ->
                val id = node("Segment", linkedMapOf("segment_id" to "${s.fwy}_${s.dir}_${s.mile}", "fwy" to s.fwy, "dir" to s.dir, "mile" to s.mile, "from_pm" to s.fromPm, "to_pm" to s.toPm))
                edge("hasSegment", roads.getValue(s.fwy to s.dir), id)
                Triple<String?, String?, Int?>(s.fwy, s.dir, s.mile) to id
            }
            val stationIds = stations.associate { s ->
                s.stationId to addStation(s, versions[s.stationId].orEmpty().map { v -> v to segmentIds[Triple(v.fwy, v.dir, v.mile)] })
            }
            incidents.forEach { i ->
                val id = node("Incident", linkedMapOf("incident_id" to i.incidentId, "district_id" to i.districtId, "severity" to i.severity, "description" to i.description), i.from, i.to)
                segmentIds[Triple(i.fwy, i.dir, i.mile)]?.let { edge("locatedIn", id, it, i.from, i.to) }
            }
            tsKeys.forEach { (station, lane) -> tsIds += addTimeseries(stationIds.getValue(station), lane) }
            gsCard = (districts.size + roads.size + segments.size + stations.size + versions.values.sumOf { it.size } + incidents.size + tsKeys.size).toLong()
        }
    }

    fun addTs() {
        tsCard = events.size.toLong()
        tsTime += getTime {
            for (i in 0 until events.size) {
                val isFirst = i == 0 || events.ts[i - 1] != events.ts[i]
                val isLast = i + 1 == events.size || events.ts[i + 1] != events.ts[i]
                addMeasurement(tsIds[events.ts[i]], record(i), isFirst, isLast)
            }
            if (events.size > 0) awaitLast(tsIds[events.ts[events.size - 1]], record(events.size - 1))
        }
    }

    override fun loadData() {
        addGs()
        addTs()
        close()
    }

    companion object {
        const val SCHEMA = "pems_cleaned"
        val MEASURES = listOf("flow", "avg_occupancy", "avg_speed")
        const val EVENTS = "events.csv.gz"
        const val STATIONS = "stations.jsonl.gz"
        const val VERSIONS = "versions.jsonl.gz"
        const val SEGMENTS = "segments.jsonl.gz"
        const val ROADS = "roads.jsonl.gz"
        const val INCIDENTS = "incidents.jsonl.gz"
        val FILES = listOf(EVENTS, STATIONS, VERSIONS, SEGMENTS, ROADS, INCIDENTS)
        /** The properties that are not Long (for the typed CSV files of Neo4j) */
        val DOUBLE_KEYS = setOf("from_pm", "to_pm", "abs_pm", "latitude", "longitude")
        val STRING_KEYS = setOf("segment_id", "fwy", "dir", "type", "name", "severity", "description")
    }
}
