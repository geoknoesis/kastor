package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

class ReleaseRegressionTest {
    @Test fun `flow early termination closes parser input`() = runBlocking {
        var closed = false
        val data = (0 until 10_000).joinToString("\n") { "<urn:s$it> <urn:p> <urn:o> ." }.toByteArray()
        val input = object : java.io.ByteArrayInputStream(data) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(1, Rdf.parseStreamingFlow(input, RdfFormat.N_TRIPLES).take(1).toList().size)
        assertTrue(closed)
    }
    @Test fun `isomorphism preserves fixed terms and returns the blank node bijection`() {
        val p = Iri("urn:p")
        val a = MemoryGraph(listOf(RdfTriple(Iri("urn:a"), p, Literal("Alice"))))
        val b = MemoryGraph(listOf(RdfTriple(Iri("urn:b"), Iri("urn:q"), Literal("Bob"))))
        assertFalse(a.isIsomorphicTo(b))
        val x = BlankNode("x"); val y = BlankNode("y")
        val left = MemoryGraph(listOf(RdfTriple(x, p, TripleTerm(RdfTriple(x, p, Literal("v"))))))
        val right = MemoryGraph(listOf(RdfTriple(y, p, TripleTerm(RdfTriple(y, p, Literal("v"))))))
        assertEquals(mapOf(x to y), left.findBlankNodeMapping(right))
    }
    @Test fun `one six-cycle is not two three-cycles`() {
        fun cycles(lengths: List<Int>): MemoryGraph {
            var offset = 0
            val triples = lengths.flatMap { n ->
                val base = offset; offset += n
                (0 until n).map { RdfTriple(BlankNode("b${base + it}"), Iri("urn:p"), BlankNode("b${base + (it + 1) % n}")) }
            }
            return MemoryGraph(triples)
        }
        assertFalse(cycles(listOf(6)).isIsomorphicTo(cycles(listOf(3, 3))))
        assertTrue(cycles(listOf(100)).isIsomorphicTo(cycles(listOf(100))))
    }
    @Test fun `memory rollback restores graphs and indexes and closed views reject access`() {
        val repo = MemoryRepository(RdfConfig())
        val view = repo.editDefaultGraph()
        val triple = RdfTriple(Iri("urn:s"), Iri("urn:p"), Literal("v"))
        view.addTriple(triple)
        assertThrows(IllegalStateException::class.java) { repo.transaction { clear(); editGraph(Iri("urn:g")).addTriple(triple); error("rollback") } }
        assertEquals(listOf(triple), view.find(triple.subject, triple.predicate))
        assertTrue(repo.listGraphs().isEmpty())
        assertThrows(IllegalStateException::class.java) { repo.readTransaction { view.clear() } }
        repo.close()
        assertThrows(IllegalStateException::class.java) { view.addTriple(triple) }
    }
}
