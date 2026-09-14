package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class IsomorphismReauditTest {
    private val p = Iri("urn:p")

    @Test
    fun `terms that are equal but of different classes get the same refinement token`() {
        assertEquals(TrueLiteral as Any, TypedLiteral("true", XSD.boolean) as Any) // precondition: the terms are equal
        val left = MemoryGraph(listOf(RdfTriple(BlankNode("a"), p, TrueLiteral), RdfTriple(BlankNode("a"), p, BlankNode("c"))))
        val right = MemoryGraph(listOf(RdfTriple(BlankNode("b"), p, TypedLiteral("true", XSD.boolean)), RdfTriple(BlankNode("b"), p, BlankNode("d"))))

        assertTrue(left.isIsomorphicTo(right))
        assertEquals(mapOf(BlankNode("a") to BlankNode("b"), BlankNode("c") to BlankNode("d")), left.findBlankNodeMapping(right))
    }

    @Test
    fun `language tags differing only in case are isomorphic`() {
        val left = MemoryGraph(listOf(RdfTriple(BlankNode("a"), p, LangString("x", "en-GB"))))
        val right = MemoryGraph(listOf(RdfTriple(BlankNode("b"), p, LangString("x", "en-gb"))))
        assertTrue(left.isIsomorphicTo(right))
        assertFalse(left.isIsomorphicTo(MemoryGraph(listOf(RdfTriple(BlankNode("b"), p, LangString("x", "en-US"))))))
    }

    private fun rdfList(prefix: String, size: Int): MemoryGraph = MemoryGraph(buildList {
        add(RdfTriple(Iri("urn:s"), p, BlankNode("${prefix}0")))
        for (i in 0 until size) {
            val node = BlankNode("$prefix$i")
            add(RdfTriple(node, RDF.first, string("same")))
            add(RdfTriple(node, RDF.rest, if (i == size - 1) RDF.nil else BlankNode("$prefix${i + 1}")))
        }
    })

    @Test
    fun `a list of one hundred thousand identical members is isomorphic under the default limits`() {
        val left = rdfList("a", 100_000)
        val right = rdfList("b", 100_000)
        assertTrue(left.isIsomorphicTo(right))
    }

    @Test
    fun `default work budget scales with graph size and an explicit null timeout disables the wall clock`() {
        assertEquals(50_000_000L, defaultIsomorphismWorkBudget(10))
        assertEquals(4_000_000_000L, defaultIsomorphismWorkBudget(4_000_000))
        assertEquals(Long.MAX_VALUE, defaultIsomorphismWorkBudget(Long.MAX_VALUE))
        val unlimitedClock = WeisfeilerLehmanIsomorphism(10, null, null)
        assertTrue(unlimitedClock.areIsomorphic(rdfList("a", 5), rdfList("b", 5)))
        assertThrows(IllegalArgumentException::class.java) { WeisfeilerLehmanIsomorphism(10, 0L, null) }
    }

    @Test
    fun `limits can be set through the extension functions`() {
        val graph = rdfList("a", 50)
        val error = assertThrows(IllegalStateException::class.java) { graph.isIsomorphicTo(rdfList("b", 50), 1L, null) }
        assertTrue(error.message!!.contains("work limit"))
        assertThrows(IllegalStateException::class.java) { graph.findBlankNodeMapping(rdfList("b", 50), 1L, null) }
        assertTrue(graph.isIsomorphicTo(rdfList("b", 50), null, Duration.ofMinutes(1)))
        assertEquals(50, graph.findBlankNodeMapping(rdfList("b", 50), Long.MAX_VALUE, null)?.size)
    }
}
