package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.string
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A blank node id written with the `_:` of its Turtle label names the same node as the id without it, as on the
 * Jena provider (the reference): `BlankNode("_:a")` and `BlankNode("a")` are one node of a graph, and reads return
 * the id without the prefix.
 */
class Rdf4jBlankNodeIdParityTest {
    @TempDir
    lateinit var tmp: Path

    private val p = Iri("urn:p")
    private val q = Iri("urn:q")
    private val s = Iri("urn:s")
    private val labelled = BlankNode("_:a")
    private val plain = BlankNode("a")

    private fun providers(): Map<String, () -> RdfRepository> = linkedMapOf(
        "jena" to { JenaRepository.MemoryRepository() },
        "rdf4j-memory" to { Rdf4jRepository.MemoryRepository() },
        "rdf4j-native" to { Rdf4jRepository.NativeRepository(tmp.resolve("native").toString()) },
        "rdf4j-wrapped" to {
            Rdf4jRepository(org.eclipse.rdf4j.repository.sail.SailRepository(org.eclipse.rdf4j.sail.memory.MemoryStore()).also { it.init() })
        },
    )

    private fun each(test: (RdfRepository) -> Unit): List<DynamicTest> = providers().map { (name, factory) ->
        DynamicTest.dynamicTest(name) { factory().use(test) }
    }

    @TestFactory
    fun `a labelled id and a plain id are one subject`(): List<DynamicTest> = each { repo ->
        val graph = repo.editDefaultGraph()
        graph.addTriple(RdfTriple(labelled, p, string("x")))
        graph.addTriple(RdfTriple(plain, q, string("y")))
        val expected = setOf(RdfTriple(plain, p, string("x")), RdfTriple(plain, q, string("y")))
        assertEquals(expected, graph.getTriples().toSet(), "reads return the id without the prefix")
        assertEquals(expected, graph.find(plain, null, null).toSet())
        assertEquals(expected, graph.find(labelled, null, null).toSet())
        assertTrue(graph.hasTriple(RdfTriple(labelled, q, string("y"))))
        assertTrue(graph.hasTriple(RdfTriple(plain, p, string("x"))))
        assertEquals(1, repo.select(SparqlSelectQuery("SELECT DISTINCT ?b { ?b ?p ?o }")).toList().size)
        // Adding the same triple under the other spelling adds nothing.
        graph.addTriple(RdfTriple(plain, p, string("x")))
        assertEquals(2, graph.size())
        assertTrue(graph.removeTriple(RdfTriple(labelled, q, string("y"))))
        assertEquals(setOf(RdfTriple(plain, p, string("x"))), graph.getTriples().toSet())
        assertFalse(graph.removeTriple(RdfTriple(labelled, q, string("y"))))
    }

    @TestFactory
    fun `a labelled id and a plain id are one object`(): List<DynamicTest> = each { repo ->
        val graph = repo.editDefaultGraph()
        graph.addTriple(RdfTriple(s, p, labelled))
        assertEquals(listOf(RdfTriple(s, p, plain)), graph.getTriples())
        assertEquals(listOf(RdfTriple(s, p, plain)), graph.find(null, null, labelled))
        assertEquals(listOf(RdfTriple(s, p, plain)), graph.find(null, null, plain))
        assertTrue(graph.hasTriple(RdfTriple(s, p, labelled)))
        graph.addTriple(RdfTriple(s, p, plain))
        assertEquals(1, graph.size())
        assertTrue(graph.removeTriples(listOf(RdfTriple(s, p, labelled))))
        assertEquals(0, graph.size())
    }

    @TestFactory
    fun `a labelled id inside a triple term is the plain node`(): List<DynamicTest> = each { repo ->
        if (!repo.getCapabilities().supportsRdfStar) return@each
        val graph = repo.editDefaultGraph()
        fun term(node: BlankNode) = TripleTerm(RdfTriple(node, p, node))
        graph.addTriple(RdfTriple(s, q, term(labelled)))
        assertEquals(listOf(RdfTriple(s, q, term(plain))), graph.getTriples())
        assertTrue(graph.hasTriple(RdfTriple(s, q, term(labelled))))
        assertEquals(listOf(RdfTriple(s, q, term(plain))), graph.find(null, null, term(labelled)))
        assertTrue(graph.removeTriple(RdfTriple(s, q, term(labelled))))
        assertEquals(0, graph.size())
    }
}
