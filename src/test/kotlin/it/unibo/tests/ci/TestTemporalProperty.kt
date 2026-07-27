package it.unibo.tests.ci

import it.unibo.graph.asterixdb.AsterixDBTSM
import it.unibo.graph.inmemory.MemoryGraphACID
import it.unibo.graph.interfaces.Graph
import it.unibo.graph.interfaces.PropType
import it.unibo.graph.query.*
import it.unibo.graph.utils.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestTemporalProperty {

    private fun init(): Graph {
        val g = MemoryGraphACID()
        g.tsm = AsterixDBTSM.createDefault(g)
        g.clear()
        g.getTSM().clear()
        return g
    }

    private fun setup(): Graph {
        val g = init()
        val a = g.addNode(A)
        val b1 = g.addNode(B)
        val b2 = g.addNode(B)
        val b3 = g.addNode(B)
        val c1 = g.addNode(C)
        val c2 = g.addNode(C)

        g.addEdge(Foo, a.id, b1.id, from = 0, to = 2)
        g.addEdge(Foo, a.id, b2.id, from = 1, to = 1)
        g.addEdge(Foo, a.id, b3.id, from = 2, to = 2)
        g.addEdge(Foo, b1.id, c1.id, from = 0, to = 0)
        g.addEdge(Foo, b1.id, c2.id, from = 1)

        g.addProperty(a.id,"name", "Foo", PropType.STRING, from = 0, to = 0)
        g.addProperty(a.id,"name", "Bar", PropType.STRING, from = 1, to = 1)
        g.addProperty(b1.id,"name", "b1", PropType.STRING)
        g.addProperty(b2.id,"name", "b2", PropType.STRING)
        g.addProperty(b3.id,"name", "b3", PropType.STRING)
        g.addProperty(c1.id,"name", "c1", PropType.STRING)
        g.addProperty(c1.id,"lastname", "c1.lastname", PropType.STRING, from = 1)
        g.addProperty(c2.id,"name", "c2", PropType.STRING)
        g.addProperty(c2.id,"lastname", "c2.lastname", PropType.STRING, from = 1)

        return g
    }

    @Test
    fun test1() {
        val g = setup()
        // MATCH (a:A)-->(b:B) RETURN a.name, b.name
        assertEquals(
            setOf(listOf("Foo", "b1"), listOf("Bar", "b1"), listOf("Bar", "b2"), listOf("null", "b3")),
            query(g, listOf(Step(A, alias = "a"), null, Step(B, alias = "b")), by = listOf(Aggregate("a", property = "name"), Aggregate("b", property = "name"))).toSet()
        )
        g.close()
    }

    @Test
    fun test2() {
        val g = setup()
        // MATCH (a:A)-->(b:B)-->(c:C) RETURN a.name, b.name, c.name
        assertEquals(
            setOf(listOf("Foo", "b1", "c1"), listOf("Bar", "b1", "c2")),
            query(g, listOf(Step(A, alias = "a"), null, Step(B, alias = "b"), null, Step(C, alias = "c")), by = listOf(Aggregate("a", property = "name"), Aggregate("b", property = "name"), Aggregate("c", property = "name"))).toSet()
        )

        // MATCH (a:A)-->(b:B)-->(c:C) RETURN a.name, b.name, c.lastname
        assertEquals(
            setOf(listOf("Foo", "b1", "null"), listOf("Bar", "b1", "c2.lastname")),
            query(g, listOf(Step(A, alias = "a"), null, Step(B, alias = "b"), null, Step(C, alias = "c")), by = listOf(Aggregate("a", property = "name"), Aggregate("b", property = "name"), Aggregate("c", property = "lastname"))).toSet()
        )
        g.close()
    }

    @Test
    fun test3() {
        val g = init()
        val a1 = g.addNode(A)
        val b1 = g.addNode(B)
        val e1 = g.addEdge(Foo, a1.id, b1.id, from = 0, to = 2)
        g.addProperty(e1.id, "name", "Foo", PropType.STRING, from = 0, to = 1, sourceType = EDGE)
        g.addProperty(e1.id, "name", "Bar", PropType.STRING, from = 1, to = 2, sourceType = EDGE)

        val pattern = listOf(Step(A), Step(Foo, alias = "e"), Step(B))
        val pattern1 = listOf(Step(A), Step(Foo, alias = "e", properties = listOf(Filter("name", Operators.EQ, "Foo"))), Step(B))
        val pattern2 = listOf(Step(A), Step(Foo, alias = "e", properties = listOf(Filter("name", Operators.EQ, "Bar"))), Step(B))

        // MATCH (a:A)-(e)->(b:B)-->(c:C) RETURN a, b, e
        assertEquals(1, query(g, pattern).size)
        // MATCH (a:A)-(e)->(b:B)-->(c:C) WHERE timestamp in [2, 3) RETURN a, b, e
        assertEquals(0, query(g, pattern, from = 2, to = 3).size)
        // MATCH (a:A)-(e {"name": "Foo"})->(b:B)-->(c:C) RETURN a, b, e
        assertEquals(1, query(g, pattern1).size)
        // MATCH (a:A)-(e {"name": "Bar"})->(b:B)-->(c:C) RETURN a, b, e
        assertEquals(1, query(g, pattern2).size)
        // MATCH (a:A)-(e {"name": "Bar"})->(b:B)-->(c:C) WHERE timestamp in [0, 1) RETURN a, b, e
        assertEquals(0, query(g, pattern2, from = 0, to = 1).size)
        // MATCH (a:A)-(e {"name": "Bar"})->(b:B)-->(c:C) WHERE timestamp in [1, 2) RETURN a, b, e
        assertEquals(1, query(g, pattern2, from = 1, to = 2).size)
        // MATCH (a:A)-(e {"name": "Foo"})->(b:B)-->(c:C) WHERE timestamp in [0, 1) RETURN a, b, e
        assertEquals(1, query(g, pattern1, from = 0, to = 1).size)
        // MATCH (a:A)-(e {"name": "Foo"})->(b:B)-->(c:C) WHERE timestamp in [1, 2) RETURN a, b, e
        assertEquals(0, query(g, pattern1, from = 1, to = 2).size)

        // MATCH (a:A)-(e)->(b:B)-->(c:C) RETURN e.name
        assertEquals(setOf("Foo", "Bar"), query(g, pattern, by = listOf(Aggregate("e", property = "name"))).toSet())
        // MATCH (a:A)-(e)->(b:B)-->(c:C) WHERE timestamp in [0, 1) RETURN e.name
        assertEquals(setOf("Foo"), query(g, pattern, by = listOf(Aggregate("e", property = "name")), from = 0, to = 1).toSet())
        // MATCH (a:A)-(e)->(b:B)-->(c:C) WHERE timestamp in [1, 2) RETURN e.name
        assertEquals(setOf("Bar"), query(g, pattern, by = listOf(Aggregate("e", property = "name")), from = 1, to = 2).toSet())
        g.close()
    }

    @Test
    fun test4() {
        val g = init()
        val a1 = g.addNode(A)
        val b1 = g.addNode(B)
        val e1 = g.addEdge(Foo, a1.id, b1.id, from = 0, to = 2)
        g.addProperty(a1.id, "name", "A1", PropType.STRING)
        g.addProperty(b1.id, "name", "B1-Foo", PropType.STRING, from = 0, to = 1)
        g.addProperty(b1.id, "name", "B1-Bar", PropType.STRING, from = 1, to = 2)
        g.addProperty(e1.id, "name", "E1-Foo", PropType.STRING, from = 0, to = 1, sourceType = EDGE)
        g.addProperty(e1.id, "name", "E1-Bar", PropType.STRING, from = 1, to = 2, sourceType = EDGE)

        assertEquals(Long.MIN_VALUE, a1.getProps(name="name").first().fromTimestamp)
        assertEquals(Long.MAX_VALUE, a1.getProps(name="name").first().toTimestamp)
        assertEquals(1, a1.getProps(name = "name", fromTimestamp = 0, toTimestamp = 1).size)
        assertEquals(0, b1.getProps(name="name").minOf { it.fromTimestamp })
        assertEquals(2, b1.getProps(name="name").maxOf { it.toTimestamp })
        assertEquals(0, e1.getProps(name="name").minOf { it.fromTimestamp })
        assertEquals(2, e1.getProps(name="name").maxOf { it.toTimestamp })

        val pattern = listOf(Step(A, alias = "a"), Step(Foo, alias = "e"), Step(B, alias = "b"))

        // MATCH (a:A)-(e)->(b:B)-->(c:C) RETURN a.name, e.name, b.name
        assertEquals(
            setOf(listOf("A1", "E1-Foo", "B1-Foo"), listOf("A1", "E1-Bar", "B1-Bar")),
            query(g, pattern,
                by = listOf(
                    Aggregate("a", property = "name"),
                    Aggregate("e", property = "name"),
                    Aggregate("b", property = "name")
                )
            ).toSet()
        )
        g.close()
    }

    @Test
    fun test5() {
        val g = init()
        // CREATE (a:A {name: 'a2'}), (b:B {name: 'b2'}), (a)-[:foo {from: "-inf", to: "0"}]->(b), (a)-[:foo {from: "-inf", to: "0"}]->(b), (a)-[:foo {from: "0", to: "2"}]->(b), (a)-[:foo {from: "3", to: "4"}]->(b), (a)-[:foo {from: "4", to: "+inf"}]->(b)
        // MATCH (a:A {name: "a2"})-[e]->(b:B) return a, e, b, count(*)
        val a1 = g.addNode(A)
        val b1 = g.addNode(B)
        g.addEdge(Foo, a1.id, b1.id)
        g.addEdge(Foo, a1.id, b1.id, from = 0, to = 2)
        g.addEdge(Foo, a1.id, b1.id, from = 4, to = 5)
        g.addEdge(Foo, a1.id, b1.id, from = 5)

        val pattern = listOf(Step(A, alias = "a"), Step(Foo, alias = "e"), Step(B, alias = "b"))

        // MATCH (a:A)-(e)->(b:B)-->(c:C) RETURN a, e, b
        assertEquals(4, query(g, pattern).size)
        // MATCH (a:A)-(e)->(b:B)-->(c:C) WHERE timestamp in [-1, 0) RETURN a, e, b
        assertEquals(1, query(g, pattern, from = -1, to = 0).size)
        // MATCH (a:A)-(e)->(b:B)-->(c:C) WHERE timestamp in [-1, inf) RETURN a, e, b
        assertEquals(4, query(g, pattern, from = -1).size)
        // MATCH (a:A)-(e)->(b:B)-->(c:C) WHERE timestamp in [1, 2) RETURN a, e, b
        assertEquals(2, query(g, pattern, from = 1, to = 3).size)
        // MATCH (a:A)-(e)->(b:B)-->(c:C) WHERE timestamp in [2, 3) RETURN a, e, b
        assertEquals(1, query(g, pattern, from = 2, to = 4).size) // it still exists the first edge! the one spanning -inf, +inf
        // MATCH (a:A)-(e)->(b:B)-->(c:C) WHERE timestamp in [4, inf) RETURN a, e, b
        assertEquals(2, query(g, pattern, from = 5).size)
        g.close()
    }

    @Test
    fun test5bis() {
        val g = init()
        val a1 = g.addNode(A)
        g.addProperty(a1.id, "lastName", "foo", PropType.STRING)
        g.addProperty(a1.id, "name", "foo", PropType.STRING)
        g.addProperty(a1.id, "name", "foo", PropType.STRING, from = 0, to = 2)
        g.addProperty(a1.id, "name", "foo", PropType.STRING, from = 5)
        g.addProperty(a1.id, "name", "foo", PropType.STRING, from = 4, to = 5)
        g.addProperty(a1.id, "name", "foo", PropType.STRING, from = 1, to = 2)

        val props = g.getNode(a1.id).getProps()
        assertEquals(6, props.size)
        // assertTrue(props.zipWithNext().all { (a, b) -> a.toTimestamp <= b.toTimestamp }, props.toString())
        assertTrue(props.all { a -> a.fromTimestamp < a.toTimestamp }, props.toString())

        assertEquals(5, g.getNode(a1.id).getProps(name = "name", fromTimestamp = -4).size)
        assertEquals(2, g.getNode(a1.id).getProps(name = "name", fromTimestamp = 3).size)
        assertEquals(4, g.getNode(a1.id).getProps(name = "name", fromTimestamp = 0).size)
        assertEquals(3, g.getNode(a1.id).getProps(name = "name", fromTimestamp = 1).size)
        assertEquals(4, g.getNode(a1.id).getProps(fromTimestamp = 1).size)
        g.close()
    }

    @Test
    fun test7() {
        val g = init()
        val a1 = g.addNode(A)
        val b1 = g.addNode(B)
        val e1 = g.addEdge(Foo, a1.id, b1.id)
        g.addProperty(a1.id, "name", "A1", PropType.STRING)
        g.addProperty(b1.id, "name", "B1", PropType.STRING)
        g.addProperty(e1.id, "name", "E1-Foo", PropType.STRING, from = 0, to = 1, sourceType = EDGE)
        g.addProperty(e1.id, "name", "E1-Bar", PropType.STRING, from = 1, to = 2, sourceType = EDGE)

        val pattern1 = listOf(Step(A, alias = "a1"), Step(Foo, alias = "e1"), Step(B, alias = "b1"))
        val pattern2 = listOf(Step(A, alias = "a2"), Step(Foo, alias = "e2"), Step(B, alias = "b2"))
        val by = listOf(
            Aggregate("a1", property = "name"),
            Aggregate("e1", property = "name"),
            Aggregate("b1", property = "name"),
            Aggregate("a2", property = "name"),
            Aggregate("e2", property = "name"),
            Aggregate("b2", property = "name")
        )
        val where = listOf(Compare("a1", "a2", "name", Operators.EQ))

        // MATCH (a:A)-(e)->(b:B), (a:A)-(e)->(b:B) WHERE a.name = b RETURN a.name, e.name, b.name
        assertEquals(
            setOf(
                listOf("A1", "E1-Foo", "B1", "A1", "E1-Foo", "B1"),
                listOf("A1", "E1-Foo", "B1", "A1", "E1-Bar", "B1"),
                listOf("A1", "E1-Bar", "B1", "A1", "E1-Foo", "B1"),
                listOf("A1", "E1-Bar", "B1", "A1", "E1-Bar", "B1")
            ),
            query(g, listOf(pattern1, pattern2), by = by, where = where, timeaware = false).toSet()
        )

        assertEquals(
            setOf(
                listOf("A1", "E1-Foo", "B1", "A1", "E1-Foo", "B1"),
                listOf("A1", "E1-Bar", "B1", "A1", "E1-Bar", "B1")
            ),
            query(g, listOf(pattern1, pattern2), by = by, where = where, timeaware = true).toSet()
        )
        g.close()
    }

    @Test
    fun test8() {
        val g = init()
        val a1 = g.addNode(A)
        val b1 = g.addNode(B)
        val e1 = g.addEdge(Foo, a1.id, b1.id)
        g.addProperty(a1.id, "name", "A1-Foo", PropType.STRING, to = 1) // Change wrt test7
        g.addProperty(a1.id, "name", "A1-Bar", PropType.STRING, from = 1) // Change wrt test7
        g.addProperty(b1.id, "name", "B1", PropType.STRING)
        g.addProperty(e1.id, "name", "E1-Foo", PropType.STRING, from = 0, to = 2, sourceType = EDGE) // Change wrt test7
        g.addProperty(e1.id, "name", "E1-Bar", PropType.STRING, from = 2, to = 3, sourceType = EDGE) // Change wrt test7

        val pattern1 = listOf(Step(A, alias = "a1"), Step(Foo, alias = "e1"), Step(B, alias = "b1"))
        val pattern2 = listOf(Step(A, alias = "a2"), Step(Foo, alias = "e2"), Step(B, alias = "b2"))
        val by = listOf(
            Aggregate("a1", property = "name"),
            Aggregate("e1", property = "name"),
            Aggregate("b1", property = "name"),
            Aggregate("a2", property = "name"),
            Aggregate("e2", property = "name"),
            Aggregate("b2", property = "name")
        )
        val where = listOf(Compare("a1", "a2", "name", Operators.EQ))

         // MATCH (a:A)-(e)->(b:B), (a:A)-(e)->(b:B) WHERE a.name = b RETURN a.name, e.name, b.name
        assertEquals(
            setOf(
                listOf("A1-Foo", "E1-Foo", "B1", "A1-Foo", "E1-Foo", "B1"),
                listOf("A1-Bar", "E1-Foo", "B1", "A1-Bar", "E1-Foo", "B1"),
                listOf("A1-Bar", "E1-Bar", "B1", "A1-Bar", "E1-Bar", "B1")
            ),
            query(g, listOf(pattern1, pattern2), by = by, where = where, timeaware = true).toSet()
        )
        g.close()
    }

    @Test
    fun `check node in shared paths`() {
        val g = init()
        val a1 = g.addNode(A)
        val b1 = g.addNode(B)
        val b2 = g.addNode(B)
        val c1 = g.addNode(C)
        val d1 = g.addNode(D)
        g.addEdge(Foo, a1.id, b1.id)
        g.addEdge(Foo, a1.id, b2.id)
        g.addEdge(Foo, b1.id, b1.id)
        g.addEdge(Foo, b1.id, c1.id)
        g.addEdge(Foo, b2.id, c1.id)
        g.addEdge(Foo, c1.id, d1.id)
        // MATCH (a:A)-->(b:B)-->(c:C)-->(d:D) RETURN a, b, c, d
        val pattern = listOf(Step(A), null, Step(B), null, Step(C), null, Step(D))
        assertEquals(2, query(g, pattern, timeaware = true).size)
        assertEquals(2, query(g, pattern, timeaware = false).size)
        g.close()
    }

    @Test
    fun `invert compare aliases`() {
        val g = init()
        val a1 = g.addNode(A)
        val b1 = g.addNode(B)
        val c1 = g.addNode(C)
        val d1 = g.addNode(D)

        g.addProperty(a1.id, "name", "foo", PropType.STRING)
        g.addProperty(c1.id, "name", "bar", PropType.STRING)
        g.addProperty(d1.id, "name", "foo", PropType.STRING)
        g.addEdge(Foo, a1.id, b1.id)
        g.addEdge(Foo, b1.id, c1.id)
        g.addEdge(Foo, c1.id, d1.id)

        // MATCH (a:A)-->(b:B)-->(c:C)-->(d:D) WHERE a.name == d.name RETURN a, b, c, d
        val pattern = listOf(Step(A, alias = "a"), null, Step(B), null, Step(C, alias = "c"), null, Step(D, alias = "d"))
        assertEquals(1, query(g, pattern, where = listOf(Compare("a", "d", "name", Operators.EQ))).size)
        assertEquals(1, query(g, pattern, where = listOf(Compare("d", "a", "name", Operators.EQ))).size)
        assertEquals(0, query(g, pattern, where = listOf(Compare("a", "c", "name", Operators.EQ))).size)
        assertEquals(0, query(g, pattern, where = listOf(Compare("c", "a", "name", Operators.EQ))).size)
        g.close()
    }
}