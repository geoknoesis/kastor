package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RdfDatasetIsomorphismTest {
    private val g1 = Iri("http://example.org/g1")
    private val g2 = Iri("http://example.org/g2")
    private val p = Iri("http://example.org/p")
    private val q = Iri("http://example.org/q")
    private val o = Iri("http://example.org/o")

    private fun dataset(first: String, second: String, defaultLabel: String? = null): Map<Iri?, RdfGraph> = buildMap {
        put(g1, MemoryGraph(listOf(RdfTriple(BlankNode(first), p, o))))
        put(g2, MemoryGraph(listOf(RdfTriple(BlankNode(second), q, o))))
        put(null, MemoryGraph(defaultLabel?.let { listOf(RdfTriple(BlankNode(it), q, p)) } ?: emptyList()))
    }

    @Test
    fun `relabelled blank nodes shared across graphs are isomorphic`() {
        assertTrue(RdfDatasetIsomorphism.isIsomorphic(dataset("a", "a", "a"), dataset("x", "x", "x")))
        assertTrue(RdfDatasetIsomorphism.isIsomorphic(dataset("a", "b"), dataset("y", "x")))
    }

    @Test
    fun `graph-by-graph isomorphism is not enough when blank nodes are shared`() {
        val shared = dataset("a", "a")
        val separate = dataset("x", "y")
        listOf(g1, g2).forEach { assertTrue(RdfGraphIsomorphism.isIsomorphic(shared.getValue(it), separate.getValue(it))) }
        assertFalse(RdfDatasetIsomorphism.isIsomorphic(shared, separate))
        assertFalse(RdfDatasetIsomorphism.isIsomorphic(separate, shared))
        assertFalse(RdfDatasetIsomorphism.isIsomorphic(dataset("a", "b", "a"), dataset("x", "y", "y")))
    }

    @Test
    fun `repositories parsed from TriG compare as datasets`() {
        fun load(trig: String) = JenaRepository.MemoryRepository().also { JenaProvider().parseDataset(it, trig.byteInputStream(), "TRIG") }
        val shared = load("<http://example.org/g1> { _:b <http://example.org/p> 1 } <http://example.org/g2> { _:b <http://example.org/p> 2 }")
        val relabelled = load("<http://example.org/g1> { _:z <http://example.org/p> 1 } <http://example.org/g2> { _:z <http://example.org/p> 2 }")
        val separate = load("<http://example.org/g1> { _:z <http://example.org/p> 1 } <http://example.org/g2> { _:w <http://example.org/p> 2 }")
        try {
            assertTrue(RdfDatasetIsomorphism.isIsomorphic(shared, relabelled))
            assertFalse(RdfDatasetIsomorphism.isIsomorphic(shared, separate))
        } finally {
            listOf(shared, relabelled, separate).forEach { it.close() }
        }
    }
}
