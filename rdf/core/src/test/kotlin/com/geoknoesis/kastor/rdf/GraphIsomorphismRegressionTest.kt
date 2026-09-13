package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GraphIsomorphismRegressionTest {

    /** `urn:s urn:p ( v v v ... )` with [size] members; the last member can be varied. */
    private fun rdfList(prefix: String, size: Int, last: String = "same"): MemoryGraph = MemoryGraph(buildList {
        add(RdfTriple(Iri("urn:s"), Iri("urn:p"), BlankNode("${prefix}0")))
        for (i in 0 until size) {
            val node = BlankNode("$prefix$i")
            add(RdfTriple(node, RDF.first, string(if (i == size - 1) last else "same")))
            add(RdfTriple(node, RDF.rest, if (i == size - 1) RDF.nil else BlankNode("$prefix${i + 1}")))
        }
    })

    @Test
    fun `long list of identical values is isomorphic to itself and to a relabelled copy`() {
        val left = rdfList("a", 1000)
        val right = rdfList("b", 1000)

        assertTrue(left.isIsomorphicTo(left))
        assertTrue(left.isIsomorphicTo(right))
        val mapping = left.findBlankNodeMapping(right)!!
        assertEquals(1000, mapping.size)
        assertEquals(BlankNode("b0"), mapping[BlankNode("a0")])
        assertEquals(BlankNode("b999"), mapping[BlankNode("a999")])
    }

    @Test
    fun `very long list of identical values stays within the default work budget`() {
        // Refinement needs ~n/2 rounds to separate a path's nodes, so charging every round in full made
        // the cost quadratic and exhausted the default budget (throwing) for identical graphs.
        assertTrue(rdfList("a", 3000).isIsomorphicTo(rdfList("b", 3000)))
    }

    @Test
    fun `long lists differing only in the last member are not isomorphic`() {
        assertFalse(rdfList("a", 1000).isIsomorphicTo(rdfList("b", 1000, last = "different")))
    }
}
