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