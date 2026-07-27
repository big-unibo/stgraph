package it.unibo.tests.ci

import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.interfaces.Graph
import it.unibo.graph.interfaces.PropType
import it.unibo.graph.query.*
import it.unibo.graph.utils.A
import it.unibo.graph.utils.B
import kotlin.test.Test
import kotlin.test.assertEquals

class TestTemporalJoin {

    private fun setup(): Graph {
        val g = MemoryGraphACID()
        g.tsm = AsterixDBTSM.createDefault(g)
        g.clear()
        g.getTSM().clear()
        val a1 = g.addNode(A)
        val b2 = g.addNode(B)
        g.addProperty(a1.id, "name", "Foo", PropType.STRING, from = 0, to = 1)
        g.addProperty(a1.id, "name", "Bar", PropType.STRING, from = 1, to = 2)
        g.addProperty(b2.id, "name", "Bar", PropType.STRING, from = 1, to = 2)
        return g
    }

    @Test
    fun test1() {
        val g = setup()
        // MATCH (a:A)-->(b:B) WHERE a.name = b.name RETURN a.name, b.name
        assertEquals(
            setOf(listOf("Bar", "Bar")),
            query(g,
                listOf(
                    listOf(Step(A, alias = "a")),
                    listOf(Step(B, alias = "b"))
                ),
                where = listOf(Compare("a", "b", property = "name", operator = Operators.EQ)),
                by = listOf(Aggregate("a", property = "name"), Aggregate("b", property = "name"))
            ).toSet()
        )
        g.close()
    }

    @Test
    fun test2() {
        val g = setup()
        // MATCH (a:A)-->(b:B) WHERE a.name = b.name and timestamp in [0, 1) RETURN a.name, b.name
        assertEquals(
            setOf(),
            query(
                g,
                listOf(
                    listOf(Step(A, alias = "a")),
                    listOf(Step(B, alias = "b"))
                ),
                from = 0,
                to = 1,
                where = listOf(Compare("a", "b", property = "name", operator = Operators.EQ)),
                by = listOf(Aggregate("a", property = "name"), Aggregate("b", property = "name"))
            ).toSet()
        )
        g.close()
    }
}