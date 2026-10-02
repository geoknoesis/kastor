package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.GraphIsomorphismLimitException
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.string
import org.apache.jena.graph.NodeFactory
import org.apache.jena.rdf.model.Model
import org.apache.jena.sparql.core.DatasetGraph
import org.apache.jena.sparql.core.DatasetGraphFactory
import org.apache.jena.sparql.core.Quad
import org.apache.jena.sparql.util.IsoMatcher
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.Duration
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The isomorphism checks of the testkit are bounded: a limit (work, time, an interrupt) stops them with a
 * [GraphIsomorphismLimitException] that says so, instead of a matcher that runs for as long as it takes. The answers
 * are those of Jena's matcher, which the testkit used before and which is the oracle here.
 */
class BoundedIsomorphismTest {
    private val p = Iri("urn:p")
    private val g1 = Iri("urn:g1")
    private val g2 = Iri("urn:g2")

    /** A ring of [size] blank nodes labelled with [prefix]. */
    private fun ring(prefix: String, size: Int): RdfGraph =
        MemoryGraph((0 until size).map { RdfTriple(BlankNode("$prefix$it"), p, BlankNode("$prefix${(it + 1) % size}")) })

    private fun interrupted(check: () -> Unit) {
        Thread.currentThread().interrupt()
        try {
            val error = assertFailsWith<GraphIsomorphismLimitException> { check() }
            assertEquals(GraphIsomorphismLimitException.Reason.INTERRUPTED, error.reason)
            assertTrue(error.message!!.contains("Could not decide"), error.message)
            assertTrue(error.message!!.contains("not a \"not isomorphic\" answer"), error.message)
            assertTrue(Thread.currentThread().isInterrupted, "the interrupt is left for the caller")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `an interrupted check stops with a limit instead of running on`() {
        val left = ring("a", 12)
        val right = ring("b", 12)
        interrupted { RdfGraphIsomorphism.isIsomorphic(left, right) }
        interrupted { RdfDatasetIsomorphism.isIsomorphic(mapOf(null to left, g1 to left), mapOf(null to right, g1 to right)) }
        interrupted { assertGraphIsomorphic(left, right) }
        // Without the interrupt the same checks have an answer.
        assertTrue(RdfGraphIsomorphism.isIsomorphic(left, right))
        assertTrue(RdfDatasetIsomorphism.isIsomorphic(mapOf(null to left, g1 to left), mapOf(null to right, g1 to right)))
    }

    @Test
    fun `a check that needs more than its budget fails with the limit that stopped it`() {
        val left = ring("a", 40)
        val right = ring("b", 40)
        val graph = assertFailsWith<GraphIsomorphismLimitException> {
            RdfGraphIsomorphism.isIsomorphic(left, right, maxWork = 10, timeout = null)
        }
        assertEquals(GraphIsomorphismLimitException.Reason.WORK, graph.reason)
        assertTrue(graph.message!!.contains("graphs") && graph.message!!.contains("WORK"), graph.message)
        val dataset = assertFailsWith<GraphIsomorphismLimitException> {
            RdfDatasetIsomorphism.isIsomorphic(mapOf(g1 to left), mapOf(g1 to right), maxWork = 10, timeout = null)
        }
        assertEquals(GraphIsomorphismLimitException.Reason.WORK, dataset.reason)
        assertTrue(dataset.message!!.contains("datasets"), dataset.message)
        // With a budget that is enough, and a generous time limit, there is an answer.
        assertTrue(RdfGraphIsomorphism.isIsomorphic(left, right, maxWork = null, timeout = Duration.ofMinutes(5)))
        assertFalse(RdfGraphIsomorphism.isIsomorphic(left, ring("b", 39), maxWork = null, timeout = Duration.ofMinutes(5)))
        assertTrue(RdfDatasetIsomorphism.isIsomorphic(mapOf(g1 to left), mapOf(g1 to right), maxWork = null, timeout = null))
    }

    @Test
    fun `datasets are compared as quads - graph names, the default graph and triple terms`() {
        val a = BlankNode("a")
        val x = BlankNode("x")
        fun graph(node: BlankNode) = MemoryGraph(listOf(RdfTriple(node, p, string("v"))))
        // The same triple in a named graph and in the default graph are different quads.
        assertFalse(RdfDatasetIsomorphism.isIsomorphic(mapOf(null to graph(a)), mapOf(g1 to graph(x))))
        // A named graph is not taken for the default graph, whatever its name.
        val reserved = Iri("urn:x-kastor:testkit:default-graph")
        assertFalse(RdfDatasetIsomorphism.isIsomorphic(mapOf(null to graph(a)), mapOf(reserved to graph(x))))
        assertTrue(RdfDatasetIsomorphism.isIsomorphic(mapOf(reserved to graph(a)), mapOf(reserved to graph(x))))
        // The name of the graph counts.
        assertFalse(RdfDatasetIsomorphism.isIsomorphic(mapOf(g1 to graph(a)), mapOf(g2 to graph(x))))
        // An empty graph has no quads.
        assertTrue(RdfDatasetIsomorphism.isIsomorphic(mapOf(g1 to graph(a), g2 to MemoryGraph()), mapOf(g1 to graph(x))))
        // Blank nodes inside triple terms are relabelled with the others.
        fun annotated(node: BlankNode, other: BlankNode) = MemoryGraph(
            listOf(
                RdfTriple(node, p, string("v")),
                RdfTriple(other, Iri("urn:says"), TripleTerm(RdfTriple(node, p, string("v")))),
            ),
        )
        assertTrue(RdfDatasetIsomorphism.isIsomorphic(mapOf(g1 to annotated(a, BlankNode("b"))), mapOf(g1 to annotated(x, BlankNode("y")))))
        assertFalse(RdfDatasetIsomorphism.isIsomorphic(mapOf(g1 to annotated(a, BlankNode("b"))), mapOf(g1 to annotated(x, x))))
    }

    // ---- the answers are those of Jena's matcher ----

    private fun jenaDataset(graphs: Map<Iri?, RdfGraph>, copies: MutableList<Model>): DatasetGraph {
        val dataset = DatasetGraphFactory.create()
        for ((name, graph) in graphs) {
            val copy = JenaBridge.copyToJenaModel(graph).also(copies::add)
            val graphNode = name?.let { NodeFactory.createURI(it.value) } ?: Quad.defaultGraphIRI
            copy.graph.find().forEachRemaining { dataset.add(Quad(graphNode, it)) }
        }
        return dataset
    }

    private fun jenaIsomorphic(expected: Map<Iri?, RdfGraph>, actual: Map<Iri?, RdfGraph>): Boolean {
        val copies = mutableListOf<Model>()
        try {
            return IsoMatcher.isomorphic(jenaDataset(expected, copies), jenaDataset(actual, copies))
        } finally {
            copies.forEach { it.close() }
        }
    }

    @TestFactory
    fun `random datasets get the answer of Jena's matcher`(): List<DynamicTest> = (0 until 60).map { seed ->
        DynamicTest.dynamicTest("seed $seed") {
            val random = Random(seed)
            val nodes = List(6) { BlankNode("a$it") }
            val names = listOf(null, g1, g2)
            // Blank nodes are shared between the graphs of the dataset.
            val quads = List(18) {
                names.random(random) to RdfTriple(
                    nodes.random(random), Iri("urn:p${random.nextInt(2)}"),
                    if (random.nextBoolean()) nodes.random(random) else string("literal${random.nextInt(3)}"),
                )
            }.toSet()
            val renamed = nodes.shuffled(random).mapIndexed { i, node -> node to BlankNode("renamed$i") }.toMap()
            fun rename(term: RdfTerm): RdfTerm = if (term is BlankNode) renamed.getValue(term) else term
            var copy = quads.map { (name, t) -> name to RdfTriple(rename(t.subject) as RdfResource, t.predicate, rename(t.obj)) }
            when (seed % 3) {
                // A quad moved to another graph: every graph may still be isomorphic to one of the other dataset.
                1 -> copy = copy.drop(1) + (names.first { it != copy[0].first } to copy[0].second)
                // One blank node split in two in one graph only.
                2 -> copy = copy.drop(1) + (copy[0].first to copy[0].second.copy(subject = BlankNode("split")))
            }
            fun dataset(of: Collection<Pair<Iri?, RdfTriple>>): Map<Iri?, RdfGraph> =
                of.groupBy({ it.first }, { it.second }).mapValues { MemoryGraph(it.value) }
            val left = dataset(quads)
            val right = dataset(copy)
            assertEquals(jenaIsomorphic(left, right), RdfDatasetIsomorphism.isIsomorphic(left, right), "seed=$seed")
            if (seed % 3 == 0) assertTrue(RdfDatasetIsomorphism.isIsomorphic(left, right), "a relabelled dataset is isomorphic")
        }
    }
}
