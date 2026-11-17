package it.unibo.tests.ci

import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraph
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.inmemory.MemoryTSM
import it.unibo.graph.interfaces.*
import it.unibo.graph.query.*
import it.unibo.graph.rocksdb.RocksDBGraph
import it.unibo.graph.utils.*
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class TestKotlin {
    private val logger = org.slf4j.LoggerFactory.getLogger(this::class.java)
    /**
     * Runs a test matrix on different combinations of Graph and TSM implementations.
     *
     * For each combination:
     * - Sets up a Graph with a given TSM.
     * - Optionally flushes and reloads if the Graph supports persistence.
     * - Invokes the given function [f] with the prepared Graph.
     * - Closes the Graph afterward to release resources.
     */
    private fun matrix(f: (Graph) -> Unit) {
        listOf(MemoryGraph::class, MemoryGraphACID::class,  RocksDBGraph::class)
            .forEach { graphClass ->
                listOf(
                    { graph: Graph -> AsterixDBTSM.createDefault(graph) },
                    { graph: Graph -> MemoryTSM(graph) }
                ).forEach { tsmFactory ->
                    val g1: Graph = graphClass.java.getDeclaredConstructor().newInstance()
                    val tsm = tsmFactory(g1)
                    logger.info("Graph: ${g1.javaClass}, TSM: ${tsm.javaClass}...")
                    var g: Graph = setup(g1, tsm)
                    if (g1 is MemoryGraphACID) {
                        g1.flushToDisk()
                        g1.close()
                        g = MemoryGraphACID.readFromDisk(g1.path)
                        g.tsm = tsm
                    }
                    try {
                        f(g)
                    } catch (e: Exception) {
                        g.close()
                        e.printStackTrace()
                        fail(e.message)
                    }
                    g.close()
                    logger.info("Done.")
                }
        }
    }

    private fun setup(g: Graph, tsm: TSManager): Graph {
        g.tsm = tsm
        g.clear()
        g.getTSM().clear()

        val n0 = g.addNode(AgriFarm)
        val n1 = g.addNode(AgriParcel)
        val n2 = g.addNode(Device)
        val n3 = g.addNode(Device)
        val n4 = g.addNode(Person)
        val n5 = g.addNode(Person)

        g.addProperty(n0.id, "name", "Errano", PropType.STRING)
        g.addProperty(n0.id, LOCATION, "POLYGON ((11.798105 44.234354, 11.801217 44.237683, 11.805286 44.235809, 11.803987 44.234851, 11.804789 44.233683, 11.80268 44.231419, 11.798105 44.234354))\n", PropType.GEOMETRY)
        g.addProperty(n1.id, "name", "T0", PropType.STRING)
        g.addProperty(n1.id, LOCATION, "POLYGON ((11.79915 44.235384, 11.799412 44.23567, 11.801042 44.234555, 11.800681 44.234343, 11.79915 44.235384))", PropType.GEOMETRY)
        g.addProperty(n2.id, "name", "GB", PropType.STRING)
        g.addProperty(n2.id, LOCATION, "POINT (11.799328 44.235394)", PropType.GEOMETRY)
        g.addProperty(n3.id, LOCATION, "POINT (14.800711 44.234904)", PropType.GEOMETRY)

        g.addEdge(HasParcel, n0.id, n1.id)
        g.addEdge(HasDevice, n0.id, n2.id)
        g.addEdge(HasDevice, n1.id, n2.id)
        g.addEdge(Foo, n1.id, n1.id)
        g.addEdge(HasDevice, n1.id, n3.id)
        g.addEdge(HasOwner, n2.id, n4.id)
        g.addEdge(HasOwner, n3.id, n4.id)
        g.addEdge(HasManutentor, n2.id, n5.id)
        g.addEdge(HasManutentor, n3.id, n5.id)
        g.addEdge(HasFriend, n4.id, n5.id)

        var timestamp = 0L

        val n6 = g.addNode(Humidity, isTs = true)
        val ts1: TS = g.getTSM().addTS(n6.id)
        val m1 = ts1.add(Measurement, timestamp = timestamp, value = 10)
        g.addProperty(m1.id, "unit", "Celsius", PropType.STRING, id = DUMMY_ID, from = timestamp, to = timestamp)
        timestamp += 1
        ts1.add(Measurement, timestamp = timestamp++, value = 11)
        ts1.add(Measurement, timestamp = timestamp++, value = 12)
        g.addEdge(HasHumidity, n3.id, n6.id)
        g.addEdge(HasOwner, m1.id, n4.id, id = DUMMY_ID)
        g.addEdge(HasManutentor, m1.id, n4.id, id = DUMMY_ID)

        val n7 = g.addNode(Temperature, isTs = true)
        val ts2 = g.getTSM().addTS(n7.id)
        ts2.add(Measurement, timestamp = timestamp++, value = 10)
        ts2.add(Measurement, timestamp = timestamp++, value = 11)
        ts2.add(Measurement, timestamp = timestamp++, value = 12)
        g.addEdge(HasTemperature, n3.id, n7.id)

        ts1.add(Measurement, timestamp = timestamp++, value = 13)
        ts1.add(Measurement, timestamp = timestamp++, value = 14)
        ts1.add(Measurement, timestamp = timestamp++, value = 15)

        val n8 = g.addNode(SolarRadiation, isTs = true)
        val ts3 = g.getTSM().addTS(n8.id)
        val m2 = ts3.add(Measurement, timestamp = timestamp++, value = 23, location = "POINT (11.799328 44.235394)")
        ts3.add(Measurement, timestamp = timestamp++, value = 24, location = "POINT (11.799328 44.235394)")
        ts3.add(Measurement, timestamp = timestamp, value = 25, location = "POINT (11.799328 44.235394)")
        g.addEdge(HasSolarRadiation, n2.id, n8.id)
        g.addEdge(HasManutentor, m2.id, n4.id, id = DUMMY_ID)

        return g
    }

    private fun setup1(): Graph {
        val g = MemoryGraph()
        g.tsm = AsterixDBTSM.createDefault(g)
        g.clear()
        g.getTSM().clear()

        val n10 = g.addNode(A)
        val n11 = g.addNode(B)
        val n12 = g.addNode(C)
        val n20 = g.addNode(A)
        val n21 = g.addNode(B)
        val n22 = g.addNode(C)

        val n13 = g.addNode(D)
        val n14 = g.addNode(E)
        val n15 = g.addNode(F)
        val n23 = g.addNode(D)
        val n24 = g.addNode(E)
        val n25 = g.addNode(F)

        g.addProperty(n10.id, "name", "Errano", PropType.STRING)
        g.addProperty(n10.id, "lastname", "Errano", PropType.STRING)
        g.addProperty(n11.id, "name", "Bar", PropType.STRING)
        g.addProperty(n13.id, "name", "Errano", PropType.STRING)
        g.addProperty(n14.id, "lastname", "Errano", PropType.STRING)

        g.addEdge(Foo, n10.id, n11.id)
        g.addEdge(Foo, n11.id, n12.id)
        g.addEdge(Foo, n13.id, n14.id)
        g.addEdge(Foo, n14.id, n15.id)
        g.addEdge(Foo, n20.id, n21.id)
        g.addEdge(Foo, n21.id, n22.id)
        g.addEdge(Foo, n23.id, n24.id)
        g.addEdge(Foo, n24.id, n25.id)

        return g
    }

    @Test
    fun `graph de-serialization`() {
        val g = MemoryGraphACID()
        setup(g, MemoryTSM(g))
        g.flushToDisk()
        g.close()

        val g1 = MemoryGraphACID.readFromDisk(PATH + "graph_acid/")
        assertEquals(g, g1)
        assertEquals(g.hashCode(), g1.hashCode())
        g1.close()
    }

    @Test
    fun testID() {
        val n = 100L
        assertEquals(Pair(n, n), decodeBitwise(encodeBitwise(n, n)))
        val ts = System.currentTimeMillis()
        assertEquals(Pair(n, ts), decodeBitwise(encodeBitwise(n, ts)))
    }

    @Test
    fun `test size`() {
        val g = MemoryGraph()
        val tsm = MemoryTSM(g)
        setup(g, tsm)
        assertEquals(NODE_SIZE, g.getNode(N0).serialize().size)
        assertEquals(EDGE_SIZE, g.getEdge(N0).serialize().size)
        assertEquals(PROPERTY_SIZE, g.getProp(N0).serialize().size)
        g.close()
    }

    @Test
    fun testRel() {
        matrix { g ->
            listOf(
                Pair(g.getNode(N0), 2),
                Pair(g.getNode(N1), 4),
                Pair(g.getNode(N2), 5),
                Pair(g.getNode(N3), 5),
                Pair(g.getNode(N4), 3)
            ).forEach {
                assertEquals(it.second, it.first.getEdges().size, it.first.getEdges().toString())
            }
        }
    }

    @Test
    fun testProps() {
        matrix { g ->
            listOf(
                Pair(g.getNode(N0), 2),
                Pair(g.getNode(N1), 2),
                Pair(g.getNode(N2), 2),
                Pair(g.getTSM().getTS(N6 + 1).get(0), 1) // first measurement of the TS corresponding to N6
            ).forEach {
                assertEquals(it.second, it.first.getProps().size, it.first.toString())
            }
        }
    }

    @Test
    fun testTS() {
        matrix { g ->
            listOf(
                Pair(g.getNode(N6), 6),
                Pair(g.getNode(N7), 3),
                Pair(g.getNode(N8), 3)
            ).forEach {
                assertTrue(it.first.isTs, "The node should be a time series")
                assertEquals(it.second, it.first.getTS().size, it.first.getTS().toString())
            }
        }
    }

    @Test
    fun testTSAsNode0() {
        matrix {
            assertEquals(1, query(it, listOf(Step(Device), Step(HasHumidity), Step(Humidity))).size)
        }
    }

    @Test
    fun testSearch0() {
        matrix { g ->
            QueryMode.entries.forEach {
                mode -> assertEquals(1, search(g, listOf(Step(Device), Step(HasHumidity), Step(Humidity)), mode = mode).size)
            }
        }
    }

    @Test
    fun testJoin() {
        val g = setup1()
        listOf(1, 2, 4).forEach { threads ->
            var patterns = listOf(
                listOf(Step(A, alias = "a"), null, Step(B), null, Step(C)),
                listOf(Step(D, alias = "d"), null, Step(E), null, Step(F))
            )
            assertEquals(1, query(g, patterns, where = listOf(Compare("a", "d", "name", Operators.EQ)), threads = threads).size)
            assertEquals(1, query(g, patterns, where = listOf(Compare("d", "a", "name", Operators.EQ)), threads = threads).size)
            patterns = listOf(
                listOf(Step(A, alias = "a"), null, Step(B), null, Step(C)),
                listOf(Step(D), null, Step(E, alias = "d"), null, Step(F))
            )
            assertEquals(1, query(g, patterns, where = listOf(Compare("a", "d", "lastname", Operators.EQ)), threads = threads).size)
            assertEquals(1, query(g, patterns, where = listOf(Compare("d", "a", "lastname", Operators.EQ)), threads = threads).size)
            assertEquals(
                listOf(listOf("Errano", "Errano", "Errano")),
                query(g,
                    listOf(
                        listOf(Step(A, alias = "a"), null, Step(B), null, Step(C)),
                        listOf(Step(D, alias = "d"), null, Step(E, alias = "e"), null, Step(F))
                    ),
                    where = listOf(Compare("e", "a", "lastname", Operators.EQ)),
                    by = listOf(Aggregate("a", property = "name"), Aggregate("d", property = "name"), Aggregate("e", property = "lastname")),
                    threads = threads
                )
            )
        }
        g.close()
    }

    @Test
    fun testJoin1() {
        val g = setup1()
        listOf(1, 2, 4).forEach { threads ->
            val res =
                query(g,
                    listOf(
                        listOf(Step(A, alias= "a"), null, Step(B), null, Step(C)),
                        listOf(Step(D), null, Step(E), null, Step(F))
                    ),
                    threads = threads
                )
            assertEquals(4, res.size, res.toString())
        }
        g.close()
    }

    @Test
    fun testTSAsNode1() {
        matrix {
            assertEquals(6, query(it, listOf(Step(Device), Step(HasHumidity), Step(Humidity), Step(HasTS), Step(Measurement))).size)
        }
    }

    @Test
    fun testSearch1() {
        matrix {
            QueryMode.entries.forEach { mode ->
                assertEquals(1, search(it, listOf(Step(Device), Step(HasHumidity), Step(Humidity), Step(HasTS)), mode = mode).size)
            }
        }
    }

    @Test
    fun testSearch2() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                val pattern = listOf(Step(AgriFarm), Step(HasParcel), Step(AgriParcel, alias = "p"), Step(HasDevice), Step(Device, alias = "d"))
                assertEquals(2, search(g, pattern, mode = mode).size)
                assertEquals(1, search(g, pattern, listOf(Compare("p", "d", LOCATION, Operators.ST_CONTAINS)), mode = mode).size)
            }
        }
    }

    @Test
    fun testSearchTS() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                val pattern = listOf(
                    Step(AgriFarm),
                    Step(HasParcel),
                    Step(AgriParcel, alias = "p"),
                    Step(HasDevice),
                    Step(Device),
                    Step(HasSolarRadiation),
                    Step(SolarRadiation),
                    Step(HasTS),
                    Step(Measurement, alias = "m")
                )
                assertEquals(
                    3,
                    search(g, pattern, listOf(Compare("p", "m", LOCATION, Operators.ST_CONTAINS)), mode = mode).size)
            }
        }
    }

    @Test
    fun testSearch1bis() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                assertEquals(6, search(g, listOf(Step(Device), Step(HasHumidity), Step(Humidity), Step(HasTS), Step(Measurement)), mode = mode).size)
                assertEquals(1, search(g, listOf(Step(Device), Step(HasHumidity), Step(Humidity), Step(HasTS), Step(Measurement, listOf(Filter("unit", Operators.EQ, "Celsius")))), mode = mode).size)
            }
        }
    }

    @Test
    fun testTSAsNode4() {
        matrix { g ->
            assertEquals(
                listOf(12.5 as Any),
                query(g,
                    listOf(Step(Device), Step(HasHumidity), Step(Humidity), Step(HasTS), Step(Measurement, alias = "Measurement")),
                    by = listOf(Aggregate("Measurement", VALUE, AggOperator.AVG))
                )
            )

            assertEquals(
                listOf(15.0 as Any),
                query(g,
                    listOf(Step(AgriParcel), null, Step(Device), null, null, Step(HasTS), Step(Measurement, alias = "Measurement")),
                    by = listOf(Aggregate("Measurement", VALUE, AggOperator.AVG))
                )
            )
        }
    }

    @Test
    fun testReturn() {
        val g = setup1()
        assertEquals(
            listOf(listOf("Errano", "Bar"), listOf("null", "null")),
            query(g, listOf(Step(A, alias = "n"), null, Step(B, alias = "m")), by = listOf(Aggregate("n", "name"), Aggregate("m", "name")))
        )

        assertEquals(
            listOf("Errano" as Any, "null" as Any),
            query(g, listOf(Step(A, alias = "n")), by = listOf(Aggregate("n", "name")))
        )
        g.close()
    }

    @Test
    fun testSearch5() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                assertEquals(12, search(g, listOf(null, Step(HasTS), Step(Measurement)), mode = mode).size)
            }
        }
    }

    @Test
    fun `graph to TS`() {
        matrix { g ->
            assertEquals(
                listOf(24.0),
                query(g, listOf(Step(Device), Step(HasSolarRadiation), Step(SolarRadiation), Step(HasTS), Step(Measurement, alias = "m")), by= listOf(Aggregate("m", VALUE, AggOperator.AVG)))
            )
        }
    }

    @Test
    fun `graph to TS to graph 1`() {
        matrix { g ->
            assertEquals(
                1,
                query(g, listOf(Step(Device), Step(HasSolarRadiation), Step(SolarRadiation), Step(HasTS), Step(Measurement), Step(HasManutentor))).size
            )
        }
    }

    @Test
    fun `graph to TS to graph 2`() {
        matrix { g ->
            assertEquals(
                1,
                query(g, listOf(Step(Device), Step(HasSolarRadiation), Step(SolarRadiation), Step(HasTS), Step(Measurement), Step(HasManutentor), Step(Person), Step(HasFriend))).size
            )
        }
    }

    @Test
    fun `serialize and deserialize`() {
        matrix { g ->
            assertEquals(g.getNode(N0), N.fromByteArray(g.getNode(N0).serialize(), g))
        }
    }

    @Test
    fun `ACID graph`() {
        val g = MemoryGraphACID()
        g.tsm = AsterixDBTSM.createDefault(g)
        g.clear()
        g.getTSM().clear()
        val n0 = g.addNode(AgriFarm)
        val n1 = g.addNode(Device)
        val e1 = g.addEdge(HasDevice, n0.id, n1.id)
        val p1 = g.addProperty(n0.id, "name", "N0", PropType.STRING)
        val p2 = g.addProperty(n1.id, "name", "N1", PropType.STRING)
        val p3 = g.addProperty(e1.id, "name", "E1", PropType.STRING, sourceType = EDGE)
        val p4 = g.addProperty(e1.id, "p4", 1, PropType.INT, sourceType = EDGE)
        val p5 = g.addProperty(e1.id, "p5", "A".repeat(MAX_LENGTH_VALUE + 1), PropType.STRING, sourceType = EDGE)
        val p6 = g.addProperty(e1.id, "p6", 1L, PropType.LONG, sourceType = EDGE)
        val p7 = g.addProperty(e1.id, "p7", 1.0, PropType.DOUBLE, sourceType = EDGE)
        val p8 = g.addProperty(e1.id, LOCATION, "POINT (11.799328 44.235394)", PropType.GEOMETRY, sourceType = EDGE)
        val p9 = g.addProperty(e1.id, "p9", "A".repeat(MAX_LENGTH_VALUE), PropType.STRING, sourceType = EDGE)
        val p10 = g.addProperty(e1.id, "p10", "A".repeat(MAX_LENGTH_VALUE / 2), PropType.STRING, sourceType = EDGE)
        g.flushToDisk()
        assertEquals(g.getNode(N0), g.getNodeFromDisk(N0))
        assertEquals(n1, g.getNodeFromDisk(N1))
        assertEquals(e1, g.getEdgeFromDisk(e1.id))
        listOf(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10).forEachIndexed {
            i, p -> assertEquals(p, g.getPropertyFromDisk(p.id), "p${i + 1}")
        }
        g.close()
    }

    @Test
    fun `filter on label`() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                g.clear()
                g.getTSM().clear()
                val n10 = g.addNode(A)
                g.addProperty(n10.id, "name", A, PropType.STRING)
                val n11 = g.addNode(B, isTs = true)
                g.addEdge(Foo, n10.id, n11.id)
                val ts = g.getTSM().addTS(n11.id)

                ts.add(C, timestamp = 0, value = 1)
                ts.add(D, timestamp = 1, value = 10)
                ts.add(C, timestamp = 2, value = 2)
                ts.add(D, timestamp = 3, value = 20)

                val noFilter: () -> List<Step?> = { listOf(Step(alias = "a", label = A), null, Step(label = B), null, Step(alias = "c")) }
                val filterOnValue: (f: Filter) -> List<Step?> = { f -> listOf(Step(alias = "a", label = A), null, Step(label = B), null, Step(alias = "c", properties = listOf(f))) }
                val filterOnLabel: (l: String) -> List<Step?> = { l -> listOf(Step(alias = "a", label = A), null, Step(label = B), null, Step(alias = "c", label = l)) }
                val filterOnLabelAndValue: (l: String, f: Filter) -> List<Step?> = { l, f -> listOf(Step(alias = "a", label = A), null, Step(label = B), null, Step(alias = "c", label = l, properties = listOf(f))) }

                assertEquals(4, search(g, noFilter(), mode = mode).size)
                assertEquals(2, search(g, filterOnLabel(C), mode = mode).size)
                assertEquals(2, search(g, filterOnLabel(D), mode = mode).size)
                assertEquals(3, search(g, filterOnValue(Filter(VALUE, Operators.GTE, 2L)), mode = mode).size)
                assertEquals(1, search(g, filterOnLabelAndValue(C, Filter(VALUE, Operators.GTE, 2L)), mode = mode).size)
                assertEquals(2, search(g, filterOnLabelAndValue(D, Filter(VALUE, Operators.GTE, 2L)), mode = mode).size)

                val sumByLabel = listOf(Aggregate(n = "c", property = LABEL), Aggregate(n = "c", property = VALUE, AggOperator.SUM))
                val sumByName = listOf(Aggregate(n = "a", property = "name"), Aggregate(n = "c", property = VALUE, AggOperator.SUM))
                assertEquals(setOf(listOf(labelFromString(C), 3.0), listOf(labelFromString(D), 30.0)), query(g, match = noFilter(), by = sumByLabel, mode = mode).toSet())
                assertEquals(setOf(listOf(labelFromString(C), 2.0), listOf(labelFromString(D), 30.0)), query(g, match = filterOnValue(Filter(VALUE, Operators.GTE, 2L)), by = sumByLabel, mode = mode).toSet())
                assertEquals(setOf(listOf(labelFromString(D), 20.0)), query(g, match = filterOnLabelAndValue(D, Filter(VALUE, Operators.GTE, 15L)), by = sumByLabel, mode = mode).toSet())
                assertEquals(setOf(listOf(A, 32.0)), query(g, match = filterOnValue(Filter(VALUE, Operators.GTE, 2L)), by = sumByName, mode = mode).toSet())
            }
        }
    }

    @Test
    fun `compare order`() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                g.clear()
                g.getTSM().clear()
                val n10 = g.addNode(A)
                val n11 = g.addNode(B)
                g.addProperty(n10.id, LOCATION, POINT_IN_T0, PropType.GEOMETRY)
                g.addProperty(n11.id, LOCATION, T0_LOCATION, PropType.GEOMETRY)
                g.addEdge(Foo, n10.id, n11.id)

                val w1 = listOf(Compare("b", "a", LOCATION, Operators.ST_CONTAINS))
                val w2 = listOf(Compare("a", "b", LOCATION, Operators.ST_CONTAINS))
                assertEquals(1, search(g, listOf(Step(alias = "a", label = A), null, Step(alias = "b", label = B)), where = w1, mode = mode).size)
                assertEquals(0, search(g, listOf(Step(alias = "a", label = A), null, Step(alias = "b", label = B)), where = w2, mode = mode).size)
                assertEquals(0, search(g, listOf(Step(alias = "b", label = A), null, Step(alias = "a", label = B)), where = w1, mode = mode).size)
                assertEquals(1, search(g, listOf(Step(alias = "b", label = A), null, Step(alias = "a", label = B)), where = w2, mode = mode).size)

                val n12 = g.addNode(C, isTs = true)
                val ts = g.getTSM().addTS(n12.id)
                ts.add(Measurement, timestamp = 0, value = 23, location = POINT_IN_T0)
                g.addEdge(Foo, n11.id, n12.id)

                assertEquals(1, search(g, listOf(Step(alias = "b", label = B), null, Step(label = C), null, Step(alias="a", label = Measurement)), where = w1, mode = mode).size)
                assertEquals(0, search(g, listOf(Step(alias = "b", label = B), null, Step(label = C), null, Step(alias="a", label = Measurement)), where = w2, mode = mode).size)
                assertEquals(0, search(g, listOf(Step(alias = "a", label = B), null, Step(label = C), null, Step(alias="b", label = Measurement)), where = w1, mode = mode).size)
                assertEquals(1, search(g, listOf(Step(alias = "a", label = B), null, Step(label = C), null, Step(alias="b", label = Measurement)), where = w2, mode = mode).size)

                assertEquals(1, search(g,listOf(Step(alias = "a", label = B), null, Step(label = C), null, Step(Measurement, properties = listOf(Filter(VALUE, Operators.GTE, 22L, attrFirst = true)))), mode = mode).size)
                assertEquals(0, search(g,listOf(Step(alias = "a", label = B), null, Step(label = C), null, Step(Measurement, properties = listOf(Filter(VALUE, Operators.GTE, 25L, attrFirst = true)))), mode = mode).size)
            }
        }
    }

    @Test
    fun `push down external group by`() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                g.clear()
                g.getTSM().clear()
                val b1 = g.addNode(B, isTs = true)
                val ts1 = g.getTSM().addTS(b1.id)
                var timestamp = 0L
                ts1.add(C, timestamp = timestamp++, value = 1, location = POINT_IN_T0)
                ts1.add(C, timestamp = timestamp++, value = 2, location = POINT_IN_T0)
                ts1.add(C, timestamp = timestamp, value = 3, location = POINT_IN_T0)

                val b2 = g.addNode(B, isTs = true)
                val ts2 = g.getTSM().addTS(b2.id)
                timestamp = 0L
                ts2.add(C, timestamp = timestamp++, value = 10, location = POINT_IN_T0)
                ts2.add(C, timestamp = timestamp++, value = 20, location = POINT_IN_T0)
                ts2.add(C, timestamp = timestamp, value = 30, location = POINT_IN_T0)

                val a1 = g.addNode(A)
                g.addProperty(a1.id, "name", "a", PropType.STRING)
                g.addProperty(b1.id, "name", "foo", PropType.STRING)
                g.addProperty(b1.id, "lastname", "b", PropType.STRING)
                g.addProperty(b2.id, "name", "foo", PropType.STRING)
                g.addProperty(b2.id, "lastname", "c", PropType.STRING)
                g.addEdge(Foo, a1.id, b1.id)
                g.addEdge(Foo, a1.id, b2.id)

                val pattern1 = listOf(Step(A, alias = "a"), null, Step(B, alias = "b"), null, Step(C, alias = "c"))

                val gb1 = listOf(Aggregate("c", property = VALUE, operator = AggOperator.SUM))
                assertEquals(listOf(66.0), query(g, pattern1, by = gb1, mode = mode))
                val gb2 = listOf(Aggregate("b", property = "name"), Aggregate("c", property = VALUE, operator = AggOperator.SUM))
                assertEquals(listOf(listOf("foo", 66.0)), query(g, pattern1, by = gb2, mode = mode))
                val gb3 = listOf(Aggregate("b", property = "lastname"), Aggregate("c", property = VALUE, operator = AggOperator.SUM))
                assertEquals(setOf(listOf("b", 6.0), listOf("c", 60.0)), query(g, pattern1, by = gb3, mode = mode).toSet())
                val gb4 = listOf(Aggregate("a", property = "name"), Aggregate("c", property = VALUE, operator = AggOperator.SUM))
                assertEquals(listOf(listOf("a", 66.0)), query(g, pattern1, by = gb4, mode = mode))

                val gb5 = listOf(Aggregate("a", property = "name"), Aggregate("c", property = VALUE, operator = AggOperator.AVG))
                assertEquals(listOf(listOf("a", 11.0)), query(g, pattern1, by=gb5, mode = mode))
                val gb6 = listOf(Aggregate("b", property = "lastname"), Aggregate("c", property = VALUE, operator = AggOperator.AVG))
                assertEquals(setOf(listOf("b", 2.0), listOf("c", 20.0)), query(g, pattern1, by = gb6, mode = mode).toSet())
            }
        }
    }

    /**
     * Grouping by a property whose value changes over time, while the aggregation is pushed down to the time series
     * (the projection before the expansion towards TS, Query.queryTSOpt). Each event must be grouped with the value of the property at its own timestamp, in both
     * modes. Before the fix, OPTIMIZED aggregated the whole series once, [0, 5], and that partial aggregate overlapped
     * the three versions of a.name, so the whole sum (21) was counted once per version.
     */
    @Test
    fun `push down group by on temporal properties`() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                g.clear()
                g.getTSM().clear()
                val b1 = g.addNode(B, isTs = true)
                val ts1 = g.getTSM().addTS(b1.id)
                (0L..5L).forEach { ts1.add(C, timestamp = it, value = it + 1, location = POINT_IN_T0) } // values 1..6 at 0..5
                val a1 = g.addNode(A)
                g.addProperty(a1.id, "name", "x", PropType.STRING, from = 0, to = 2) // events 0, 1
                g.addProperty(a1.id, "name", "y", PropType.STRING, from = 3, to = 4) // event 3 (event 2: no name)
                g.addProperty(a1.id, "name", "x", PropType.STRING, from = 4, to = 4) // event 4 (event 5: no name)
                g.addEdge(Foo, a1.id, b1.id)

                // Versions of a.name: x [0,2), gap [2,3), y [3,4), x at the instant 4 ([4, 5)), gap after 4
                // MATCH (a:A)-->(b:B)-->(c:C) RETURN a.name, sum/avg(c.value): each event with the name of its time
                val pattern = listOf(Step(A, alias = "a"), null, Step(B), null, Step(C, alias = "c"))
                val sum = listOf(Aggregate("a", property = "name"), Aggregate("c", property = VALUE, operator = AggOperator.SUM))
                val avg = listOf(Aggregate("a", property = "name"), Aggregate("c", property = VALUE, operator = AggOperator.AVG))
                // x: events 0, 1, 4 (values 1 + 2 + 5); y: event 3 (value 4); no name: events 2, 5 (values 3 + 6). The two
                // versions named x fall in two sub-intervals: their partial aggregates are merged in the same group
                assertEquals(setOf(listOf("x", 8.0), listOf("y", 4.0), listOf("null", 9.0)), query(g, pattern, by = sum, mode = mode).toSet())
                // AVG from the merged (sum, count) of the partial aggregates: x = (3 + 5) / (2 + 1), not the average of averages
                assertEquals(setOf(listOf("x", 8.0 / 3), listOf("y", 4.0), listOf("null", 4.5)), query(g, pattern, by = avg, mode = mode).toSet())
                // ... WHERE timestamp in [1, 4): the sub-intervals are clipped to the window (events 1, 2, 3 only)
                assertEquals(setOf(listOf("x", 2.0), listOf("y", 4.0), listOf("null", 3.0)), query(g, pattern, by = sum, from = 1, to = 4, mode = mode).toSet())
            }
        }
    }

    /**
     * TS.getValues on several intervals (used by Query.search when a pushed-down aggregation is split): one partial
     * aggregate per non-empty interval, each with the first timestamp of its events (fromTimestamp), equal to one request
     * per interval. AsterixDB answers with one request (CASE on the timestamp in the GROUP BY), the other stores with one
     * request per interval (the default of TS)
     */
    /**
     * MIN and COUNT pushed down to the time series (aggregateNumbers): the partial aggregates must be combined as the
     * events one by one (NAIVE). Before, the partial minimums from AsterixDB could not be combined (no MIN on pairs), and
     * the partial counts from MemoryTS were counted instead of summed (the number of partial aggregates)
     */
    @Test
    fun `push down min and count`() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                g.clear()
                g.getTSM().clear()
                val a1 = g.addNode(A)
                g.addProperty(a1.id, "name", "x", PropType.STRING, from = 0, to = 3) // events 0, 1, 2 of both time series
                g.addProperty(a1.id, "name", "y", PropType.STRING, from = 3, to = Long.MAX_VALUE) // events 3, 4, 5
                listOf(0L, 10L).forEach { offset -> // two time series: values 1..6 and 11..16 at 0..5
                    val b = g.addNode(B, isTs = true)
                    val ts = g.getTSM().addTS(b.id)
                    (0L..5L).forEach { ts.add(C, timestamp = it, value = offset + it + 1, location = POINT_IN_T0) }
                    g.addEdge(Foo, a1.id, b.id)
                }
                val pattern = listOf(Step(A, alias = "a"), null, Step(B), null, Step(C, alias = "c"))
                fun by(operator: AggOperator, grouped: Boolean) = (if (grouped) listOf(Aggregate("a", property = "name")) else emptyList()) + Aggregate("c", property = VALUE, operator = operator)
                // All the events: one partial aggregate per time series
                assertEquals(listOf(1.0), query(g, pattern, by = by(AggOperator.MIN, false), mode = mode))
                assertEquals(listOf(12), query(g, pattern, by = by(AggOperator.COUNT, false), mode = mode))
                // By name: one partial aggregate per time series and version of the name
                assertEquals(setOf(listOf("x", 1.0), listOf("y", 4.0)), query(g, pattern, by = by(AggOperator.MIN, true), mode = mode).toSet())
                assertEquals(setOf(listOf("x", 6), listOf("y", 6)), query(g, pattern, by = by(AggOperator.COUNT, true), mode = mode).toSet())
            }
        }
    }

    @Test
    fun `push down aggregation on several intervals`() {
        matrix { g ->
            g.clear()
            g.getTSM().clear()
            val b1 = g.addNode(B, isTs = true)
            val ts1 = g.getTSM().addTS(b1.id)
            (0L..5L).forEach { ts1.add(C, timestamp = it, value = it + 1, location = POINT_IN_T0) } // values 1..6 at 0..5
            val sum = listOf(Aggregate("c", property = VALUE, operator = AggOperator.SUM))
            // (first timestamp, sum) of each partial aggregate: AsterixDB returns the value as a pair (sum, count)
            fun partials(nodes: List<N>) = nodes.map { n ->
                val v = n.getProps(name = VALUE).first().value
                n.fromTimestamp to ((if (v is Pair<*, *>) v.first else v) as Number).toDouble()
            }.toSet()
            val intervals = listOf(Long.MIN_VALUE to 2L, 2L to 4L, 4L to Long.MAX_VALUE)
            // One partial aggregate per interval, with the first timestamp of its events: 1 + 2, 3 + 4, 5 + 6
            assertEquals(setOf(0L to 3.0, 2L to 7.0, 4L to 11.0), partials(ts1.getValues(sum, emptyList(), true, intervals)))
            // The same as one request per interval
            val one = intervals.flatMap { (from, to) ->
                ts1.getValues(sum, listOfNotNull(
                    if (from != Long.MIN_VALUE) Filter(FROM_TIMESTAMP, Operators.GTE, from) else null,
                    if (to != Long.MAX_VALUE) Filter(FROM_TIMESTAMP, Operators.LT, to) else null
                ), true)
            }
            assertEquals(partials(one), partials(ts1.getValues(sum, emptyList(), true, intervals)))
            // Consecutive intervals restricted by a time filter (events 1..4 only), with the instant 2 ([2, 3)): [1, 2) -> 2,
            // [2, 3) -> 3, [3, +inf) -> 4 + 5
            assertEquals(setOf(1L to 2.0, 2L to 3.0, 3L to 9.0), partials(ts1.getValues(sum, listOf(Filter(FROM_TIMESTAMP, Operators.LT, 5L)), true, listOf(1L to 2L, 2L to 3L, 3L to Long.MAX_VALUE))))
            // An interval without events returns nothing: [6, 10)
            assertEquals(setOf(0L to 21.0), partials(ts1.getValues(sum, emptyList(), true, listOf(Long.MIN_VALUE to 6L, 6L to 10L))))
            // A single interval: the usual request, the sum of all the events
            assertEquals(setOf(0L to 21.0), partials(ts1.getValues(sum, emptyList(), true, listOf(Long.MIN_VALUE to Long.MAX_VALUE))))
        }
    }

    /**
     * Filtering on a property whose value changes over time (the selection after the projection, Query.select): the path holds only while the
     * property passes the filter, so only the events of those periods are returned, in both modes. Before the fix, a
     * node passed if any of its versions passed, and all the events (sum 21) were returned.
     */
    @Test
    fun `filter on temporal properties`() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                g.clear()
                g.getTSM().clear()
                val b1 = g.addNode(B, isTs = true)
                val ts1 = g.getTSM().addTS(b1.id)
                (0L..5L).forEach { ts1.add(C, timestamp = it, value = it + 1, location = POINT_IN_T0) } // values 1..6 at 0..5
                val a1 = g.addNode(A)
                g.addProperty(a1.id, "type", "ML", PropType.STRING, from = 0, to = 2) // events 0, 1
                g.addProperty(a1.id, "type", "HV", PropType.STRING, from = 2, to = 4) // events 2, 3
                g.addProperty(a1.id, "type", "ML", PropType.STRING, from = 4, to = Long.MAX_VALUE) // events 4, 5
                g.addEdge(Foo, a1.id, b1.id)

                // MATCH (a:A {type: "ML"})-->(b:B)-->(c:C) RETURN sum(c.value): only the events while a is ML
                val ml = listOf(Step(A, alias = "a", properties = listOf(Filter("type", Operators.EQ, "ML"))), null, Step(B), null, Step(C, alias = "c"))
                val sum = listOf(Aggregate("c", property = VALUE, operator = AggOperator.SUM))
                // a is ML in [0, 2) and [4, +inf): events 0, 1, 4, 5 (values 1 + 2 + 5 + 6); not 2, 3 (HV)
                assertEquals(listOf(14.0), query(g, ml, by = sum, mode = mode))
                // Without aggregation, the four events: the path of a is split into [0, 2) and [4, +inf)
                assertEquals(4, query(g, ml, mode = mode).size)
                // Grouping by the filtered property: a single group, ML
                assertEquals(listOf(listOf("ML", 14.0)), query(g, ml, by = listOf(Aggregate("a", property = "type")) + sum, mode = mode))
                // ... WHERE timestamp in [1, 5): events 1 and 4 (values 2 + 5)
                assertEquals(listOf(7.0), query(g, ml, by = sum, from = 1, to = 5, mode = mode))
                // Without the filter, all the events (1 + ... + 6)
                assertEquals(listOf(21.0), query(g, listOf(Step(A, alias = "a"), null, Step(B), null, Step(C, alias = "c")), by = sum, mode = mode))
            }
        }
    }

    /**
     * The queries with the same number of threads share one pool (Query.searchPool): running the same query several times
     * creates no further threads, and the threads are daemons (no query has to close the pool, and they do not keep the
     * JVM alive). Before, each search created a pool of new threads and closed it at the end.
     */
    /**
     * Instants: an element valid in [t, t] is valid in [t, t + 1) (see halfOpen), the instant t only, so a path through it
     * reaches the events at t only, in both modes (pushed down: t <= ts < t + 1, Query.getPredicates); a filter passing
     * only in an instant limits the path to it (Query.select)
     */
    @Test
    fun `instants as in timeOverlap`() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                g.clear()
                g.getTSM().clear()
                val b1 = g.addNode(B, isTs = true)
                val ts1 = g.getTSM().addTS(b1.id)
                (0L..5L).forEach { ts1.add(C, timestamp = it, value = it + 1, location = POINT_IN_T0) } // values 1..6 at 0..5
                val sum = listOf(Aggregate("c", property = VALUE, operator = AggOperator.SUM))
                val pattern = listOf(Step(A, alias = "a"), null, Step(B), null, Step(C, alias = "c"))

                // An edge valid in the instant 2: only the event at 2 (value 3)
                val a1 = g.addNode(A)
                g.addEdge(Foo, a1.id, b1.id, from = 2, to = 2)
                assertEquals(listOf(3.0), query(g, pattern, by = sum, mode = mode))
                assertEquals(1, query(g, pattern, mode = mode).size)

                // A filter passing only in the instant 4 (type = ML in [4, 4], HV in [0, 4) and [5, +inf)), on an edge valid
                // always: only the event at 4 (value 5)
                g.clear()
                g.getTSM().clear()
                val b2 = g.addNode(B, isTs = true)
                val ts2 = g.getTSM().addTS(b2.id)
                (0L..5L).forEach { ts2.add(C, timestamp = it, value = it + 1, location = POINT_IN_T0) }
                val a2 = g.addNode(A)
                g.addProperty(a2.id, "type", "HV", PropType.STRING, from = 0, to = 4)
                g.addProperty(a2.id, "type", "ML", PropType.STRING, from = 4, to = 4)
                g.addProperty(a2.id, "type", "HV", PropType.STRING, from = 5, to = Long.MAX_VALUE)
                g.addEdge(Foo, a2.id, b2.id)
                val ml = listOf(Step(A, alias = "a", properties = listOf(Filter("type", Operators.EQ, "ML"))), null, Step(B), null, Step(C, alias = "c"))
                assertEquals(listOf(5.0), query(g, ml, by = sum, mode = mode))
                assertEquals(1, query(g, ml, mode = mode).size)
                // ... grouped by type, without the filter: the instant 4 is its own group (ML), the others HV
                assertEquals(setOf(listOf("ML", 5.0), listOf("HV", 16.0)), query(g, pattern, by = listOf(Aggregate("a", property = "type")) + sum, mode = mode).toSet())
            }
        }
    }

    /**
     * The temporal property join (Query.temporalPropertyJoin, Algorithm 1 on any number of properties): the slices of time
     * in which every property keeps the same value, with their versions (null where a property has none), consecutive
     * slices with the same values merged (an instant [t, t] is [t, t + 1), see halfOpen). The projection, the selection, the
     * expansion towards TS with pushdown, and the join use it
     */
    @Test
    fun `project temporal properties`() {
        val g = MemoryGraph()
        g.tsm = MemoryTSM(g)
        g.clear()
        val a = g.addNode(A)
        val b = g.addNode(B)
        g.addProperty(a.id, "v", 1L, PropType.LONG, from = 0, to = 2)
        g.addProperty(a.id, "v", 2L, PropType.LONG, from = 2, to = 4)
        g.addProperty(a.id, "v", 1L, PropType.LONG, from = 6, to = 6) // the instant 6 ([6, 7)), after a gap [4, 6)
        g.addProperty(b.id, "v", 3L, PropType.LONG, from = 1) // always 3 from 1
        fun slices(from: Long, to: Long) = temporalPropertyJoin(listOf(a to "v", b to "v"), from, to).map { (slice, v) -> slice to v.map { it?.value } }
        assertEquals(
            listOf(
                (Long.MIN_VALUE to 0L) to listOf(null, null), (0L to 1L) to listOf(1L, null), (1L to 2L) to listOf(1L, 3L),
                (2L to 4L) to listOf(2L, 3L), (4L to 6L) to listOf(null, 3L), (6L to 7L) to listOf(1L, 3L), (7L to Long.MAX_VALUE) to listOf(null, 3L)
            ),
            slices(Long.MIN_VALUE, Long.MAX_VALUE)
        )
        // Within the tuple: [1, 3)
        assertEquals(listOf((1L to 2L) to listOf(1L, 3L), (2L to 3L) to listOf(2L, 3L)), slices(1, 3))
        // In the instant 6: one version each
        assertEquals(listOf((6L to 7L) to listOf(1L, 3L)), slices(6, 7))
        // Without properties: the whole tuple
        assertEquals(listOf((0L to 5L) to emptyList<P?>()), temporalPropertyJoin(emptyList(), 0, 5))
        // Consecutive versions with the same value: one slice (1 in [0, 2) and 1 in [2, 4) -> [0, 4))
        val c = g.addNode(C)
        g.addProperty(c.id, "v", 1L, PropType.LONG, from = 0, to = 2)
        g.addProperty(c.id, "v", 1L, PropType.LONG, from = 2, to = 4)
        assertEquals(listOf(0L to 4L), temporalPropertyJoin(listOf(c to "v"), 0, 4).map { it.first })
        // isOk: some pair of versions valid at the same time satisfies a.v < b.v (1 < 3 in [1, 2)), within [4, 6) none
        val lt = Compare("a", "b", "v", Operators.LT)
        assertTrue(lt.isOk(a, b, timeaware = true))
        assertTrue(!lt.isOk(a, b, timeaware = true, from = 4, to = 6))
        assertTrue(lt.isOk(a, b, timeaware = false))
        g.close()
    }

    /**
     * A where clause comparing the temporal properties of two elements of the same path (Compare.isOk, Query.select):
     * the path holds only while the comparison holds, so only the events of those periods are returned, in both modes
     */
    @Test
    fun `where clause on temporal properties`() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                g.clear()
                g.getTSM().clear()
                val b1 = g.addNode(B, isTs = true)
                val ts1 = g.getTSM().addTS(b1.id)
                (0L..5L).forEach { ts1.add(C, timestamp = it, value = it + 1, location = POINT_IN_T0) } // values 1..6 at 0..5
                g.addProperty(b1.id, "v", 3L, PropType.LONG) // always 3
                val a1 = g.addNode(A)
                g.addProperty(a1.id, "v", 1L, PropType.LONG, from = 0, to = 4) // a.v < b.v in [0, 4)
                g.addProperty(a1.id, "v", 5L, PropType.LONG, from = 4, to = Long.MAX_VALUE) // a.v > b.v in [4, MAX)
                g.addEdge(Foo, a1.id, b1.id)

                // MATCH (a:A)-->(b:B)-->(c:C) WHERE a.v < b.v RETURN sum(c.value)
                val pattern = listOf(Step(A, alias = "a"), null, Step(B, alias = "b"), null, Step(C, alias = "c"))
                val sum = listOf(Aggregate("c", property = VALUE, operator = AggOperator.SUM))
                // Events 0..3 (values 1 + 2 + 3 + 4); before, no result (only the last version of a.v, 5, was compared)
                assertEquals(listOf(10.0), query(g, pattern, where = listOf(Compare("a", "b", "v", Operators.LT)), by = sum, mode = mode))
                assertEquals(4, query(g, pattern, where = listOf(Compare("a", "b", "v", Operators.LT)), mode = mode).size)
                // a.v > b.v: events 4, 5 (values 5 + 6)
                assertEquals(listOf(11.0), query(g, pattern, where = listOf(Compare("a", "b", "v", Operators.GT)), by = sum, mode = mode))
                // ... WHERE timestamp in [2, 5): events 2, 3 for <, event 4 for >
                assertEquals(listOf(7.0), query(g, pattern, where = listOf(Compare("a", "b", "v", Operators.LT)), by = sum, from = 2, to = 5, mode = mode))
                assertEquals(listOf(5.0), query(g, pattern, where = listOf(Compare("a", "b", "v", Operators.GT)), by = sum, from = 2, to = 5, mode = mode))
            }
        }
    }

    /**
     * The projection (Query.project) on more than two grouping properties of the same element: one row per combination of
     * the versions valid at the same time (null where a property has none), instead of failing with "Too many props".
     * The tuple is valid only with the versions valid with its other elements (e.g., an edge valid from 2)
     */
    @Test
    fun `group by several temporal properties of the same element`() {
        QueryMode.entries.forEach { mode ->
            val g = MemoryGraph()
            g.tsm = MemoryTSM(g)
            g.clear()
            val a = g.addNode(A)
            val b = g.addNode(B)
            g.addProperty(a.id, "p1", "x", PropType.STRING, from = 0, to = 2)
            g.addProperty(a.id, "p1", "y", PropType.STRING, from = 2, to = 4)
            g.addProperty(a.id, "p2", "u", PropType.STRING, from = 0, to = 4)
            g.addProperty(a.id, "p3", "k", PropType.STRING, from = 1, to = 3)
            g.addEdge(Foo, a.id, b.id)
            val pattern = listOf(Step(A, alias = "a"), null, Step(B))
            val keys = listOf(Aggregate("a", property = "p1"), Aggregate("a", property = "p2"), Aggregate("a", property = "p3"))
            // MATCH (a:A)-->(b:B) RETURN a.p1, a.p2, a.p3: [0, 1) x u -, [1, 2) x u k, [2, 3) y u k, [3, 4) y u -
            assertEquals(
                setOf(listOf("x", "u", "null"), listOf("x", "u", "k"), listOf("y", "u", "k"), listOf("y", "u", "null")),
                query(g, pattern, by = keys, mode = mode).toSet()
            )
            // An edge valid in [2, 4), then in the instant 2: only the versions of p1 valid with it (y), not x, which ends at 2
            val c = g.addNode(C)
            g.addEdge(Foo, a.id, c.id, from = 2, to = 4)
            assertEquals(setOf("y"), query(g, listOf(Step(A, alias = "a"), null, Step(C)), by = listOf(Aggregate("a", property = "p1")), mode = mode).toSet())
            val d = g.addNode(D)
            g.addEdge(Foo, a.id, d.id, from = 2, to = 2)
            assertEquals(setOf("y"), query(g, listOf(Step(A, alias = "a"), null, Step(D)), by = listOf(Aggregate("a", property = "p1")), mode = mode).toSet())
            g.close()
        }
    }

    /**
     * A comparison between the events and a temporal property of the path, pushed down to the time series
     * (Query.queryTSOpt, getPredicates): one path per version of the property, each with its own value, in both modes.
     * Before, OPTIMIZED failed with "Too many property values in the same temporal range"
     */
    @Test
    fun `compare events with temporal properties`() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                g.clear()
                g.getTSM().clear()
                val b1 = g.addNode(B, isTs = true)
                val ts1 = g.getTSM().addTS(b1.id)
                (0L..5L).forEach { ts1.add(C, timestamp = it, value = it + 1, location = POINT_IN_T0) } // values 1..6 at 0..5
                val a1 = g.addNode(A)
                g.addProperty(a1.id, VALUE, 2L, PropType.LONG, from = 0, to = 3) // the threshold of events 0, 1, 2
                g.addProperty(a1.id, VALUE, 5L, PropType.LONG, from = 3, to = Long.MAX_VALUE) // ... of events 3, 4, 5
                g.addEdge(Foo, a1.id, b1.id)
                // MATCH (a:A)-->(b:B)-->(c:C) WHERE c.value > a.value RETURN c: 3 > 2 (event 2), 6 > 5 (event 5)
                val pattern = listOf(Step(A, alias = "a"), null, Step(B), null, Step(C, alias = "c"))
                val events = query(g, pattern, where = listOf(Compare("c", "a", VALUE, Operators.GT)), mode = mode)
                assertEquals(setOf(3L, 6L), events.map { ((it as Path).result.last() as N).value }.toSet())
                // ... WHERE timestamp in [3, 5): event 4 (5 > 5 does not hold), nothing
                assertEquals(0, query(g, pattern, where = listOf(Compare("c", "a", VALUE, Operators.GT)), from = 3, to = 5, mode = mode).size)
            }
        }
    }

    /**
     * Unsupported queries fail instead of returning wrong results: two comparisons between the elements of the same pattern
     * (only the last one was applied) and two join comparisons on the same step (only the first one was applied).
     * Grouping by a property that changes in time of the first pattern of a join, with an aggregation of the second one
     * pushed down, is supported: the property is projected before the join (one search of the second pattern per version)
     */
    @Test
    fun `unsupported queries fail`() {
        matrix { g ->
            g.clear()
            g.getTSM().clear()
            val b1 = g.addNode(B, isTs = true)
            val ts1 = g.getTSM().addTS(b1.id)
            (0L..5L).forEach { ts1.add(C, timestamp = it, value = it + 1, location = POINT_IN_T0) } // values 1..6 at 0..5
            g.addProperty(b1.id, "code", "p", PropType.STRING)
            g.addProperty(b1.id, "w", "q", PropType.STRING)
            val a1 = g.addNode(A)
            g.addProperty(a1.id, "code", "p", PropType.STRING)
            g.addProperty(a1.id, "w", "q", PropType.STRING)
            g.addProperty(a1.id, "name", "x", PropType.STRING, from = 0, to = 3) // events 0, 1, 2
            g.addProperty(a1.id, "name", "y", PropType.STRING, from = 3, to = Long.MAX_VALUE) // events 3, 4, 5
            g.addEdge(Foo, a1.id, b1.id)
            val two = listOf(Compare("a", "b", "code", Operators.EQ), Compare("a", "b", "w", Operators.EQ))
            // Two comparisons in the same pattern
            kotlin.test.assertFailsWith<UnsupportedOperationException> { query(g, listOf(Step(A, alias = "a"), null, Step(B, alias = "b")), where = two) }
            // Two join comparisons on the same step
            kotlin.test.assertFailsWith<UnsupportedOperationException> { query(g, listOf(listOf(Step(A, alias = "a")), listOf(Step(B, alias = "b"))), where = two) }
            // Grouping by a.name (x, then y) of the first pattern, sum of the events of the second one
            val join = listOf(listOf(Step(A, alias = "a")), listOf(Step(B, alias = "b"), null, Step(C, alias = "c")))
            val where = listOf(Compare("a", "b", "code", Operators.EQ))
            val by = listOf(Aggregate("a", property = "name"), Aggregate("c", property = VALUE, operator = AggOperator.SUM))
            QueryMode.entries.forEach { mode -> // x: events 0, 1, 2 (1 + 2 + 3), y: events 3, 4, 5 (4 + 5 + 6)
                assertEquals(setOf(listOf("x", 6.0), listOf("y", 15.0)), query(g, join, where = where, by = by, mode = mode).toSet())
            }
        }
    }

    @Test
    fun `queries share a bounded thread pool`() {
        val g = MemoryGraph()
        g.tsm = MemoryTSM(g)
        g.clear()
        g.getTSM().clear()
        repeat(4) { // four time series, read in parallel
            val b = g.addNode(B, isTs = true)
            g.getTSM().addTS(b.id).add(C, timestamp = 0, value = 1, location = POINT_IN_T0)
        }
        val pattern = listOf(Step(B), null, Step(C, alias = "c"))
        // The same results as before, with one event per time series
        repeat(5) { assertEquals(4, search(g, pattern, threads = 3).size) }
        // The same pool, every time
        assertTrue(searchPool(3) === searchPool(3))
        // At most 3 threads for all the queries with 3 threads, and they do not keep the JVM alive
        val workers = Thread.getAllStackTraces().keys.filter { it.name.startsWith("stgraph-search-3-") }
        assertTrue(workers.size in 1..3, "$workers")
        assertTrue(workers.all { it.isDaemon })
        g.close()
    }

    /**
     * Join (non-recursive): the join comparison binds each version of a.code, a (code 1 in [0, 5), 2 in [5, 10)) joins
     * b1 (code 1) with the event at 1 and b2 (code 2) with the event at 6, not the events outside these versions. The
     * tuples are joined concurrently on the pool of the join, closed at the end of the query (no thread left behind)
     */
    @Test
    fun `join binds each version of the compared property`() {
        val g = MemoryGraph()
        g.tsm = MemoryTSM(g)
        g.clear()
        g.getTSM().clear()
        val a = g.addNode(A)
        g.addProperty(a.id, "code", 1L, PropType.LONG, from = 0, to = 5)
        g.addProperty(a.id, "code", 2L, PropType.LONG, from = 5, to = 10)
        listOf(1L, 2L).forEach { code ->
            val b = g.addNode(B, isTs = true)
            g.addProperty(b.id, "code", code, PropType.LONG)
            val ts = g.getTSM().addTS(b.id)
            listOf(1L, 6L).forEach { t -> ts.add(C, timestamp = t, value = t, location = POINT_IN_T0) } // events at 1 and 6
        }
        val join = listOf(listOf(Step(A, alias = "a")), listOf(Step(B, alias = "b"), null, Step(C, alias = "c")))
        val where = listOf(Compare("a", "b", "code", Operators.EQ))
        val pools = { Thread.getAllStackTraces().keys.count { it.name.startsWith("pool-") } } // the threads of an unnamed pool
        val before = pools()
        QueryMode.entries.forEach { mode ->
            // The events valid with the version of a.code equal to the code of their b: 1 (b1) and 6 (b2)
            assertEquals(listOf(7.0), query(g, join, where = where, by = listOf(Aggregate("c", property = VALUE, operator = AggOperator.SUM)), threads = 2, mode = mode))
        }
        Thread.sleep(100) // the threads of the closed pools terminate
        assertTrue(pools() <= before, "the pool of the join is closed") // <=: threads of earlier tests may still be terminating
        g.close()
    }

    /**
     * aggregateNumbers on values and on partial aggregates as (value, count) pairs (e.g., AVG as (sum, count) from TS):
     * a value counts as (value, 1). Partial aggregations return AVG and COUNT as pairs, final ones the result
     */
    @Test
    fun `aggregate values and partial aggregates`() {
        val values = listOf(1L, 2L, 6L, "null") // "null": no value (ignored)
        assertEquals(9.0, aggregateNumbers(values, AggOperator.SUM, true))
        assertEquals(3, aggregateNumbers(values, AggOperator.COUNT, true))
        assertEquals(3.0, aggregateNumbers(values, AggOperator.AVG, true))
        assertEquals(1.0, aggregateNumbers(values, AggOperator.MIN, true))
        assertEquals(6.0, aggregateNumbers(values, AggOperator.MAX, true))
        // Partial: (sum, count) for AVG and (count, count) for COUNT
        assertEquals(9.0 to 3, aggregateNumbers(values, AggOperator.AVG, false))
        assertEquals(3.0 to 3, aggregateNumbers(values, AggOperator.COUNT, false))
        // Final aggregation of the partial aggregates: (9, 3) and (1, 1) -> AVG 10 / 4, COUNT 4
        val partials = listOf(9.0 to 3, 1.0 to 1)
        assertEquals(2.5, aggregateNumbers(partials, AggOperator.AVG, true))
        assertEquals(4, aggregateNumbers(partials, AggOperator.COUNT, true))
        assertEquals(10.0, aggregateNumbers(partials, AggOperator.SUM, true))
    }

    /**
     * Random graphs (property histories with gaps, edges with limited validity, events with two labels): the NAIVE and
     * OPTIMIZED modes must return the same result for grouping, filter, time window and join queries. NAIVE reads the
     * events one by one and resolves the properties at the timestamp of each event, so it is the reference: any pushdown
     * of OPTIMIZED (filters, time filters, aggregations, grouping intervals) must not change the result. The seeds are
     * fixed, so a failure is reproducible; the message names the seed and the query.
     * Not covered: grouping by a temporal property of the first pattern of a join (see unsupported queries fail).
     */
    @Test
    fun `naive and optimized agree on random graphs`() {
        matrix { g ->
            (1..3).forEach { seed ->
                val rnd = kotlin.random.Random(seed)
                g.clear()
                g.getTSM().clear()
                // Consecutive versions of [key] on [0, 20), each missing with probability 1/4 (a gap)
                fun history(id: Long, key: String, values: List<String>) {
                    var t = 0L
                    while (t < 20) {
                        val length = rnd.nextLong(1, 8)
                        if (rnd.nextInt(4) > 0) g.addProperty(id, key, values.random(rnd), PropType.STRING, from = t, to = t + length)
                        t += length
                    }
                }
                // Three time series (B), each with 10 events at distinct timestamps in [0, 20), labelled C or D, values 0..9
                val bs = (0 until 3).map { i ->
                    val b = g.addNode(B, isTs = true)
                    val ts = g.getTSM().addTS(b.id)
                    (0L until 20L).shuffled(rnd).take(10).forEach { ts.add(if (rnd.nextBoolean()) C else D, timestamp = it, value = rnd.nextLong(0, 10), location = POINT_IN_T0) }
                    g.addProperty(b.id, "name", "b$i", PropType.STRING)
                    g.addProperty(b.id, "code", listOf("p", "q").random(rnd), PropType.STRING)
                    b
                }
                // Two A nodes, with random histories of name and type, linked to a random subset of the time series by edges
                // valid always or only in a random interval
                repeat(2) {
                    val a = g.addNode(A)
                    history(a.id, "name", listOf("x", "y"))
                    history(a.id, "type", listOf("ML", "HV"))
                    g.addProperty(a.id, "code", listOf("p", "q").random(rnd), PropType.STRING)
                    bs.filter { rnd.nextBoolean() }.forEach { b ->
                        if (rnd.nextBoolean()) g.addEdge(Foo, a.id, b.id) else rnd.nextLong(0, 15).let { from -> g.addEdge(Foo, a.id, b.id, from = from, to = from + rnd.nextLong(1, 10)) }
                    }
                }

                // (a:A)-->(b:B)-->(c), with or without the filter a.type = "ML", and the join of (a:A {type: "ML"}) with
                // (b:B)-->(c) on a.code = b.code
                val path = listOf(Step(A, alias = "a"), null, Step(B, alias = "b"), null, Step(alias = "c"))
                val ml = listOf(Step(A, alias = "a", properties = listOf(Filter("type", Operators.EQ, "ML"))), null, Step(B, alias = "b"), null, Step(alias = "c"))
                val join = listOf(listOf(Step(A, alias = "a", properties = listOf(Filter("type", Operators.EQ, "ML")))), listOf(Step(B, alias = "b"), null, Step(alias = "c")))
                fun sum(vararg keys: Aggregate) = keys.toList() + Aggregate("c", property = VALUE, operator = AggOperator.SUM)
                val from = rnd.nextLong(0, 10)
                val to = from + rnd.nextLong(1, 11)
                val queries: List<Pair<String, (QueryMode) -> List<Any>>> = listOf(
                    "sum by a.name" to { m -> query(g, path, by = sum(Aggregate("a", property = "name")), mode = m) },
                    "avg by a.name" to { m -> query(g, path, by = listOf(Aggregate("a", property = "name"), Aggregate("c", property = VALUE, operator = AggOperator.AVG)), mode = m) },
                    "max by a.type" to { m -> query(g, path, by = listOf(Aggregate("a", property = "type"), Aggregate("c", property = VALUE, operator = AggOperator.MAX)), mode = m) },
                    "min by a.type" to { m -> query(g, path, by = listOf(Aggregate("a", property = "type"), Aggregate("c", property = VALUE, operator = AggOperator.MIN)), mode = m) },
                    "count by a.name" to { m -> query(g, path, by = listOf(Aggregate("a", property = "name"), Aggregate("c", property = VALUE, operator = AggOperator.COUNT)), mode = m) },
                    "sum by label" to { m -> query(g, path, by = sum(Aggregate("c", property = LABEL)), mode = m) },
                    "sum by a.name, b.name" to { m -> query(g, path, by = sum(Aggregate("a", property = "name"), Aggregate("b", property = "name")), mode = m) },
                    "sum by a.name in [$from, $to)" to { m -> query(g, path, by = sum(Aggregate("a", property = "name")), from = from, to = to, mode = m) },
                    "filter, sum by a.name" to { m -> query(g, ml, by = sum(Aggregate("a", property = "name")), mode = m) },
                    "filter, rows" to { m -> listOf(query(g, ml, mode = m).size) },
                    "join, sum by b.name" to { m -> query(g, join, where = listOf(Compare("a", "b", property = "code", operator = Operators.EQ)), by = sum(Aggregate("b", property = "name")), mode = m) }
                )
                // Results as sets: the order of the rows is not defined
                queries.forEach { (name, run) ->
                    assertEquals(run(QueryMode.NAIVE).toSet(), run(QueryMode.OPTIMIZED).toSet(), "seed $seed: $name")
                }
            }
        }
    }

    @Test
    fun `push down internal group by`() {
        matrix { g ->
            QueryMode.entries.forEach { mode ->
                g.clear()
                g.getTSM().clear()
                val b1 = g.addNode(B, isTs = true)
                val ts1 = g.getTSM().addTS(b1.id)
                var timestamp = 0L
                val m1 = ts1.add(C, timestamp = timestamp, value = 1, location = POINT_IN_T0)
                g.addProperty(m1.id, "name", "a", PropType.STRING, from = timestamp, to = timestamp)

                timestamp++

                val m2 = ts1.add(C, timestamp = timestamp, value = 2, location = POINT_IN_T0)
                g.addProperty(m2.id, "name", "b", PropType.STRING, from = timestamp, to = timestamp)

                timestamp++
                g.addProperty(
                    ts1.add(C, timestamp = timestamp, value = 3, location = POINT_IN_T0).id,
                    "name",
                    "a",
                    PropType.STRING,
                    from = timestamp,
                    to = timestamp
                )

                val b2 = g.addNode(B, isTs = true)
                val ts2 = g.getTSM().addTS(b2.id)
                timestamp = 0L
                g.addProperty(
                    ts2.add(C, timestamp = timestamp, value = 10, location = POINT_IN_T0).id,
                    "name",
                    "b",
                    PropType.STRING,
                    from = timestamp,
                    to = timestamp
                )
                timestamp++
                g.addProperty(
                    ts2.add(C, timestamp = timestamp, value = 20, location = POINT_IN_T0).id,
                    "name",
                    "a",
                    PropType.STRING,
                    from = timestamp,
                    to = timestamp
                )
                timestamp++
                g.addProperty(
                    ts2.add(C, timestamp = timestamp, value = 30, location = POINT_IN_T0).id,
                    "name",
                    "b",
                    PropType.STRING,
                    from = timestamp,
                    to = timestamp
                )

                val a1 = g.addNode(A)
                g.addEdge(Foo, a1.id, b1.id)
                g.addEdge(Foo, a1.id, b2.id)

                val pattern1 = listOf(Step(A, alias = "a"), null, Step(B, alias = "b"), null, Step(C, alias = "c"))

                val gb1 = listOf(
                    Aggregate("c", property = "name"),
                    Aggregate("c", property = VALUE, operator = AggOperator.AVG)
                )
                assertEquals(setOf(listOf("a", 8.0), listOf("b", 14.0)), query(g, pattern1, by = gb1, mode = mode).toSet())
            }
        }
    }

    @Test
    fun `return multiple properties`() {
        matrix { g ->
            g.clear()
            g.getTSM().clear()

            val a1 = g.addNode(A)
            g.addProperty(a1.id, "name", "a", PropType.STRING, from = 0, to = 2)
            g.addProperty(a1.id, "lastname", "b", PropType.STRING, from = 1, to = 3)
            val b1 = g.addNode(B)
            g.addProperty(b1.id, "name", "c", PropType.STRING)
            g.addEdge(Foo, a1.id, b1.id)

            val pattern1 = listOf(Step(A, alias = "a"), null, Step(B, alias = "b"))
            val gb1 = listOf(Aggregate("a", property = "name"), Aggregate("a", property = "lastname"), Aggregate("b", property = "name"))

            assertEquals(setOf(listOf("a", "null", "c"), listOf("a", "b", "c"), listOf("null", "b", "c")), query(g, pattern1, by=gb1, timeaware = true).toSet())
            assertEquals(setOf(listOf("a", "b", "c")), query(g, pattern1, by=gb1, timeaware = false).toSet())
        }
    }

    @Test
    fun `aggregate custom properties`() {
        matrix { g ->
            g.clear()
            g.getTSM().clear()
             val a1 = g.addNode(A)
             g.addProperty(a1.id, "name", "a", PropType.STRING, from = 0, to = 2)
             g.addProperty(a1.id, "v", 1, PropType.INT, from = 1, to = 3)
             g.addProperty(a1.id, "v", 2, PropType.INT, from = 3, to = 4)
             val pattern1 = listOf(Step(A, alias = "a"))
             val gb1 = listOf(Aggregate("a", property = "name"), Aggregate("a", property = "v", operator = AggOperator.AVG))
             assertEquals(setOf(listOf("a", 1.0), listOf("null", 1.5)), query(g, pattern1, by=gb1, timeaware = true).toSet())
             assertEquals(setOf(listOf("a", 1.5)), query(g, pattern1, by=gb1, timeaware = false).toSet())

            val b1 = g.addNode(B, isTs = true)
            val ts1 = g.getTSM().addTS(b1.id)
            var timestamp = 0L
            g.addProperty(ts1.add(C, timestamp = timestamp, value = 10, location = POINT_IN_T0).id, "v", 2, PropType.INT, from = timestamp, to = timestamp)
            timestamp++
            g.addProperty(ts1.add(C, timestamp = timestamp, value = 20, location = POINT_IN_T0).id, "v", 20, PropType.INT, from = timestamp, to = timestamp)
            timestamp++
            g.addProperty(ts1.add(C, timestamp = timestamp, value = 30, location = POINT_IN_T0).id, "v", 20, PropType.INT, from = timestamp, to = timestamp)
            g.addProperty(b1.id, "name", "foo", PropType.STRING, from = 0, to = 4)

            val pattern2 = listOf(Step(B, alias = "b"), null, Step(C, alias = "c"))
            val gb2 = listOf(Aggregate("b", property = "name"), Aggregate("c", property = "v", operator = AggOperator.AVG))
            assertEquals(setOf(listOf("foo", 14.0)), query(g, pattern2, by=gb2, timeaware = true).toSet())
            val gb3 = listOf(Aggregate("b", property = "name"), Aggregate("c", property = VALUE, operator = AggOperator.AVG))
            assertEquals(setOf(listOf("foo", 20.0)), query(g, pattern2, by=gb3, timeaware = true).toSet())
        }
    }

    @Test
    fun `double edge`() {
        val g = MemoryGraph()
        g.tsm = MemoryTSM(g)
        g.clear()
        g.getTSM().clear()
        g.clear()
        g.getTSM().clear()
        val a1 = g.addNode(A)
        g.addProperty(a1.id, "name", "a", PropType.STRING, from = 0, to = 2)
        val b1 = g.addNode(B)
        g.addProperty(b1.id, "name", "b", PropType.STRING, from = 0, to = 2)
        val c1 = g.addNode(C)
        g.addProperty(c1.id, "name", "c", PropType.STRING, from = 0, to = 2)

        g.addEdge(Foo, a1.id, b1.id)
        g.addEdge(Foo, a1.id, c1.id)

        val gb1 = listOf(Aggregate("n1", property = "name"), Aggregate("n2", property = "name"))
        val pattern1 = listOf(Step(A, alias = "n1"), EdgeStep(direction = Direction.OUT), Step(B, alias = "n2"))
        assertEquals(setOf(listOf("a", "b")), query(g, pattern1, by=gb1, timeaware = true).toSet())

        val pattern2 = listOf(Step(A, alias = "n1"), EdgeStep(direction = Direction.IN), Step(B, alias = "n2"))
        assertEquals(emptySet<Any>(), query(g, pattern2, by=gb1, timeaware = true).toSet())

        val gb2 = listOf(Aggregate("n3", property = "name"), Aggregate("n1", property = "name"), Aggregate("n2", property = "name"))
        val pattern3 = listOf(Step(C, alias = "n3"), EdgeStep(direction = Direction.IN), Step(A, alias = "n1"), EdgeStep(direction = Direction.OUT), Step(B, alias = "n2"))
        assertEquals(setOf(listOf("c", "a", "b")), query(g, pattern3, by=gb2, timeaware = true).toSet())
        g.close()
    }

    @Test
    fun `join commutativity`() {
        val g = MemoryGraph()
        g.clear()
        g.tsm = AsterixDBTSM.createDefault(g)
        (g.tsm as AsterixDBTSM).clear()

        val n11 = g.addNode(C, isTs = true)
        val ts = g.getTSM().addTS(n11.id)
        ts.add(Measurement, timestamp = 0, value = 23, location = POINT_IN_T0)

        val n10 = g.addNode(A)

        g.addProperty(n10.id, LOCATION, T0_LOCATION, PropType.GEOMETRY)
        g.addEdge(Foo, n10.id, n11.id)

        val w1 = listOf(Compare("Environment", "Measurement", LOCATION, Operators.ST_CONTAINS))

        val pattern1 = listOf(
            listOf(Step(A, alias = "Environment")),
            listOf(Step(C), null, Step(alias="Measurement", label = Measurement)),
        )
        val pattern2 = listOf(
            listOf(Step(C), null, Step(alias="Measurement", label = Measurement)),
            listOf(Step(A, alias = "Environment"))
        )
        assertEquals(1, query(g, pattern1, where = w1).size)
        assertEquals(1, query(g, pattern2, where = w1).size)
        g.close()
    }
}