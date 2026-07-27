package it.unibo.tests.ci

import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.interfaces.Graph
import it.unibo.graph.interfaces.PropType
import it.unibo.graph.interfaces.TS
import it.unibo.graph.interfaces.timeOverlap
import it.unibo.graph.query.Filter
import it.unibo.graph.query.Operators
import it.unibo.graph.query.Step
import it.unibo.graph.query.search
import it.unibo.graph.utils.*
import org.junit.jupiter.api.Assertions.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestTemporal {

    private fun setup(): Graph {
        val g = MemoryGraphACID()
        g.tsm = AsterixDBTSM.createDefault(g)
        g.clear()
        g.getTSM().clear()

        val f1 = g.addNode(AgriFarm)
        val p1 = g.addNode(AgriParcel)
        val p2 = g.addNode(AgriParcel)
        val d1 = g.addNode(Device)
        val d2 = g.addNode(Device)
        val h1 = g.addNode(Person, from = 0)
        val h2 = g.addNode(Person, from = 2)

        val r1 = g.addEdge(HasParcel, f1.id, p1.id)
        g.addProperty(r1.id,"dateChanged", "today", PropType.STRING, from = 0, to = 1, sourceType = EDGE)
        g.addProperty(r1.id,"dateChanged", "yesterday", PropType.STRING, from = 2, sourceType = EDGE)
        g.addEdge(HasParcel, f1.id, p2.id)
        g.addEdge(HasDevice, p1.id, d1.id, from = 0, to = 2)
        g.addEdge(HasDevice, p1.id, d2.id, from = 2, to = 4)
        g.addEdge(HasDevice, p2.id, d2.id, from = 0, to = 2)
        g.addEdge(HasDevice, p2.id, d1.id, from = 2, to = 4)
        g.addEdge(HasManutentor, d1.id, h1.id, from = 0, to = 2)
        g.addEdge(HasManutentor, d1.id, h2.id, from = 2, to = 4)
        g.addProperty(h1.id, "name", "Alice", PropType.STRING, from = 0)
        g.addProperty(h1.id, "address", "Foo", PropType.STRING, from = 0, to = 1)
        g.addProperty(h1.id, "address", "Bar", PropType.STRING, from = 2)

        val t1 = g.addNode(Temperature, isTs = true)
        val ts1: TS = g.getTSM().addTS(t1.id)
        val m1 = ts1.add(Measurement, timestamp = 0, value = 0)
        ts1.add(Measurement, timestamp = 1, value = 1)
        ts1.add(Measurement, timestamp = 2, value = 2)
        g.addEdge(HasTemperature, d1.id, t1.id)
        g.addEdge(HasOwner, m1.id, h1.id, from = 0, to = 2, id = DUMMY_ID)
        g.addEdge(HasOwner, m1.id, h2.id, from = 2, to = 4, id = DUMMY_ID)

        val t2 = g.addNode(Temperature, isTs = true)
        val ts2 = g.getTSM().addTS(t2.id)
        ts2.add(Measurement, timestamp = 0, value = 10)
        ts2.add(Measurement, timestamp = 1, value = 11)
        ts2.add(Measurement, timestamp = 2, value = 12)
        g.addEdge(HasTemperature, d2.id, t2.id)

        return g
    }

    @Test
    fun testSearch0() {
        val g = setup()
        assertEquals(
            6,
            search(g, 
                listOf(
                    Step(Device),
                    Step(HasTemperature),
                    Step(Temperature),
                    Step(HasTS),
                    Step(Measurement)
                ), timeaware = true
            ).size
        )
        g.close()
    }

    @Test
    fun testSearch1() {
        val g = setup()
        assertEquals(
            2,
            search(g, 
                listOf(
                    Step(Device),
                    Step(HasTemperature),
                    Step(Temperature),
                    Step(HasTS),
                    Step(Measurement)
                ), timeaware = true, from = 0, to = 1
            ).size
        )
        g.close()
    }

    @Test
    fun testSearch2() {
        val g = setup()
        assertEquals(
            1,
            search(g, 
                listOf(
                    Step(Device),
                    Step(HasManutentor),
                    Step(Person),
                ), timeaware = true, from = 0, to = 1
            ).size
        )
        g.close()
    }

    @Test
    fun testSearch3() {
        val g = setup()
        assertEquals(
            1,
            search(g, listOf(Step(Person)), timeaware = true, from = 0, to = 1).size
        )
        g.close()
    }

    @Test
    fun testSearch4() {
        val g = setup()
        val steps = listOf(
            Step(Device),
            Step(HasTemperature),
            Step(Temperature),
            Step(HasTS),
            Step(Measurement),
            Step(HasOwner),
            Step(Person)
        )
        assertEquals(1, search(g, steps, timeaware = true).size)
        assertEquals(1, search(g, steps, timeaware = true, from = 0, to = 4 + EPSILON).size)
        assertEquals(1, search(g, steps, timeaware = true, from = 0, to = 0 + EPSILON).size)
        assertEquals(0, search(g, steps, timeaware = true, from = 1, to = 1 + EPSILON).size)
        assertEquals(2, search(g, steps, timeaware = false).size)
        g.close()
    }

    @Test
    fun testSearch5() {
        val g = setup()
        val steps = listOf(
            Step(Device),
            Step(HasTemperature),
            Step(Temperature),
            Step(HasTS),
            Step(Measurement),
            Step(HasOwner),
            Step(Person, listOf(Filter("name", Operators.EQ, "Alice")))
        )
        assertEquals(1, search(g, steps, timeaware = true).size)
        assertEquals(1, search(g, steps, timeaware = false).size)
        g.close()
    }

    @Test
    fun testSearch6() {
        val g = setup()
        val steps = listOf(Step(Person, listOf(Filter("name", Operators.EQ, "Alice"), Filter("address", Operators.EQ, "Foo"))))
        assertEquals(1, search(g, steps, timeaware = true, from = 0, to = 0).size)
        assertEquals(1, search(g, steps, timeaware = false).size)
        assertEquals(0, search(g, steps, timeaware = true, from = 2).size)
        assertEquals(0, search(g, steps, timeaware = true, to = -1).size)
        g.close()
    }

    @Test
    fun testSearch7() {
        val g = setup()
        var steps = listOf(Step(AgriFarm), Step(HasParcel), Step(AgriParcel))
        assertEquals(2, search(g, steps, timeaware = false).size)

        steps = listOf(Step(AgriFarm), Step(HasParcel, listOf(Filter("dateChanged", Operators.EQ, "today"))), Step(AgriParcel))
        assertEquals(1, search(g, steps, timeaware = false).size)
        assertEquals(1, search(g, steps, timeaware = true, from = 0, to = 0).size)
        assertEquals(0, search(g, steps, timeaware = true, from = 2).size)
        g.close()
    }

    @Test
    fun testTimeOverlap() {
        assertTrue(timeOverlap(0, 0, 0, 1)) // [0, 0) and [0, 1)
        assertTrue(timeOverlap(0, 2, 0, 1)) // [0, 2) and [0, 1)
        assertTrue(timeOverlap(0, 1, 0, 1)) // [0, 1) and [0, 1)
        assertTrue(timeOverlap(0, 0, 0, 0)) // [0, 0) and [0, 0)
        assertFalse(timeOverlap(0, 0, 1, 2)) // [0, 0) and [1, 2)
        assertFalse(timeOverlap(1, 2, 0, 0)) // [1, 2) and [0, 0)
        assertFalse(timeOverlap(0, 1, 1, 2)) // [0, 1) and [1, 2)
        assertFalse(timeOverlap(1, 2, 0, 1)) // [1, 2) and [0, 1)
        assertTrue(timeOverlap(0, 0, 0, 1)) // [0, 0) and [0, 1)
        assertTrue(timeOverlap(Long.MIN_VALUE, Long.MAX_VALUE, 0, 1)) // [-inf, +inf) and [0, 1)
        assertTrue(timeOverlap(0, 1, Long.MIN_VALUE, Long.MAX_VALUE)) // [0, 1) and [-inf, +inf)
        assertTrue(timeOverlap(Long.MIN_VALUE, Long.MAX_VALUE, 0, 0)) // [-inf, +inf) and [0, 0)
        assertFalse(timeOverlap(0, 2, 2, 2))
        assertFalse(timeOverlap(2, 2, 0, 2))
        assertTrue(timeOverlap(0, 2, 0, 0))
        assertTrue(timeOverlap(0, 0, Long.MIN_VALUE, Long.MAX_VALUE)) // [0, 0) and [-inf, +inf)
    }
}