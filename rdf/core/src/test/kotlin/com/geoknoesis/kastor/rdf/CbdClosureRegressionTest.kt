package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CbdClosureRegressionTest {
    private val p = Iri("urn:p")
    private val root = Iri("urn:root")

    @Test
    fun `follows blank nodes nested inside triple terms`() {
        val b = BlankNode("b")
        val graph = MemoryGraph(listOf(
            RdfTriple(root, p, TripleTerm(RdfTriple(b, p, string("quoted")))),
            RdfTriple(b, p, string("described")),
            RdfTriple(Iri("urn:other"), p, string("unrelated")),
        ))
        assertEquals(2, graph.getCbdClosure(root).size)
    }

    @Test
    fun `long blank node chains are traversed without recursion`() {
        val size = 20_000
        val graph = MemoryGraph(buildList {
            add(RdfTriple(root, p, BlankNode("b0")))
            for (i in 0 until size) add(RdfTriple(BlankNode("b$i"), p, BlankNode("b${i + 1}")))
        })
        assertEquals(size + 1, graph.getCbdClosure(root).size)
    }

    @Test
    fun `uses subject lookups instead of scanning the whole graph`() {
        val b = BlankNode("b")
        val backing = MemoryGraph(listOf(RdfTriple(root, p, b), RdfTriple(b, p, string("x"))))
        val lookupOnly = object : RdfGraph by backing {
            override fun getTriples(): List<RdfTriple> = error("full scan")
            override fun getTriplesSequence(): Sequence<RdfTriple> = error("full scan")
        }
        assertEquals(2, lookupOnly.getCbdClosure(root).size)
    }
}
