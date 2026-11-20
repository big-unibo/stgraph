package it.unibo.tests.smartbench.queries

import it.unibo.graph.utils.TimeRange

/**
 * The time window of each SmartBench query with one (the others, EnvironmentCoverage and AgentHistory, have none) on
 * each dataset size, the same for every system: STGraph (the queries of this package), Neo4j, PGAge and Graphix
 * ({TSTAMPFROM_PARAM} and {TSTAMPTO_PARAM} in src/test/resources/queries/smartbench), AeonG (as transaction time, see
 * SmartBenchEvaluation.aeongQueries). HyGraph has a copy in evaluate_hygraph.py (TIME_WINDOWS). The windows grow with
 * the size (recorded as querySelectivity "increased", see QUERY_WINDOWS).
 */
object TimeWindows {
    private val windows: Map<String, Map<String, TimeRange>> = mapOf(
        "EnvironmentAggregate" to mapOf(
            "xsmall" to TimeRange(1510700400000, 1511564400000), // 15/11/17 - 25/11/17 - 2880
            "small" to TimeRange(1510700400000, 1511564400000), // 15/11/17 - 25/11/17 - 2880
            "medium" to TimeRange(1510700400000, 1511858400000), // 15/11/17 - 28/11/17 - 5760
            "large" to TimeRange(1531087200000, 1536847200000), // 09/07/18 - 13/09/18 - 28800
            "extralarge" to TimeRange(1531087200000, 1539727200000) // 09/07/18 - 17/10/18 - 172800
        ),
        "MaintenanceOwners" to mapOf(
            "xsmall" to TimeRange(1510700400000, 1513000000000), // 15/11/17 - 11/12/17 - 7488
            "small" to TimeRange(1510700400000, 1513000000000), // 15/11/17 - 11/12/17 - 7488
            "medium" to TimeRange(1510700400000, 1513708800000), // 15/11/17 - 16/12/17 - 14976
            "large" to TimeRange(1510700400000, 1525674800000), // 15/11/17 - 07/05/18 - 74880
            "extralarge" to TimeRange(1510700400000, 1533164400000) // 15/11/17 - 02/08/18 - 449280
        ),
        "EnvironmentOutlier" to mapOf(
            "xsmall" to TimeRange(1510700400000, 1512000000000), // 15/11/17 - 30/11/17 - 4608
            "small" to TimeRange(1510700400000, 1512000000000), // 15/11/17 - 30/11/17 - 4608
            "medium" to TimeRange(1510700400000, 1512566400000), // 15/11/17 - 06/12/17 - 9216
            "large" to TimeRange(1510700400000, 1519916400000), // 15/11/17 - 01/03/18 - 46080
            "extralarge" to TimeRange(1510700400000, 1524524400000) // 15/11/17 - 24/04/18 - 276480
        ),
        "AgentOutlier" to mapOf(
            "xsmall" to TimeRange(1510095600000, 1513000000000), // 08/11/17 - 11/12/17 - 9504
            "small" to TimeRange(1510095600000, 1513000000000), // 08/11/17 - 11/12/17 - 9504
            "medium" to TimeRange(1510095600000, 1513929600000), // 08/11/17 - 22/12/17 - 19008
            "large" to TimeRange(1510095600000, 1529157600000), // 08/11/17 - 16/06/18 - 95040
            "extralarge" to TimeRange(1510095600000, 1538607600000) // 08/11/17 - 04/10/18 - 570240
        )
    )

    /** Whether [queryId] has a time window */
    fun windowed(queryId: String) = queryId in windows

    /** The time window of [queryId] on [size] */
    fun of(queryId: String, size: String): TimeRange =
        windows[queryId]?.get(size) ?: error("No time window for $queryId on $size")

    /** The windows of every query on [size] */
    fun of(size: String): List<TimeRange> = windows.values.mapNotNull { it[size] }
}
