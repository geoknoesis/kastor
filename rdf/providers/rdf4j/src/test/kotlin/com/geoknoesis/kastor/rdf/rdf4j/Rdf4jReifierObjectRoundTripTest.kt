package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A reifier blank node (the `kastor-star-...` id standing for an RDF-star quoted-triple subject) used as an *object*
 * is an ordinary blank node there: the triple written is the triple read back. Only in subject position does the
 * reifier stand for its quoted triple.
 */
class Rdf4jReifierObjectRoundTripTest {
    private val ex = "http://example.org/"
    private val vf = SimpleValueFactory.getInstance()
    private val quoted = vf.createTriple(vf.createIRI(ex + "a"), vf.createIRI(ex + "b"), vf.createIRI(ex + "c"))
    private val reifier = Rdf4jTerms.reifierFor(quoted)
    private val quotedTerm = TripleTerm(RdfTriple(Iri(ex + "a"), Iri(ex + "b"), Iri(ex + "c")))
    private val s = Iri(ex + "s")
    private val p = Iri(ex + "p")

    /** Tracked (factory-made) and untracked (wrapped) RDF-star stores, and a native store, take different code paths. */
    private fun stores(): Map<String, () -> Rdf4jRepository> = linkedMapOf(
        "memory" to { Rdf4jRepository.MemoryRepository() },
        "wrapped memory" to { Rdf4jRepository(SailRepository(MemoryStore()).also { it.init() }) },
        "native" to { Rdf4jRepository.NativeRepository(java.nio.file.Files.createTempDirectory("kastor-reifier-object").toString()) },
    )

    private fun each(body: (Rdf4jRepository) -> Unit): List<DynamicTest> = stores().map { (name, factory) ->
        DynamicTest.dynamicTest(name) { factory().use(body) }
    }

    private fun assertRoundTrip(repo: Rdf4jRepository, written: List<RdfTriple>, expected: Set<RdfTriple> = written.toSet()) {
        val graph = repo.editDefaultGraph()
        graph.addTriples(written)
        assertEquals(expected, graph.getTriples().toSet())
        assertEquals(expected.size, graph.size())
        for (triple in written) {
            assertTrue(graph.hasTriple(triple), "hasTriple($triple)")
            assertTrue(triple in graph.find(triple.subject, triple.predicate, triple.obj), "find($triple)")
            assertTrue(triple in graph.find(null, null, triple.obj), "find by object ($triple)")
        }
        for (triple in written) assertTrue(graph.removeTriple(triple), "removeTriple($triple)")
        assertEquals(emptySet(), graph.getTriples().toSet())
    }

    @TestFactory
    fun `a reifier blank node as object is read back as that blank node`() = each { repo ->
        val triple = RdfTriple(s, p, reifier)
        assertRoundTrip(repo, listOf(triple))
        // Not as the triple term it stands for in subject position.
        repo.editDefaultGraph().addTriple(triple)
        assertIs<BlankNode>(repo.defaultGraph.getTriples().single().obj)
        if (repo.starCapable) assertFalse(repo.defaultGraph.hasTriple(RdfTriple(s, p, quotedTerm)))
    }

    @TestFactory
    fun `a triple term object is not confused with its reifier`() = each { repo ->
        if (!repo.starCapable) return@each
        val triple = RdfTriple(s, p, quotedTerm)
        assertRoundTrip(repo, listOf(triple))
        repo.editDefaultGraph().addTriple(triple)
        assertFalse(repo.defaultGraph.hasTriple(RdfTriple(s, p, reifier)))
        assertEquals(emptyList(), repo.defaultGraph.find(null, null, reifier))
    }

    @TestFactory
    fun `a reifier used as object next to statements about it`() = each { repo ->
        val annotation = RdfTriple(reifier, Iri(ex + "q"), Literal("v"))
        val reifies = RdfTriple(reifier, RDF.reifies, quotedTerm)
        val about = RdfTriple(s, Iri(ex + "about"), reifier)
        val graph = repo.editDefaultGraph()
        // A native store cannot hold the triple term of `rdf:reifies`.
        val others = if (repo.starCapable) setOf(annotation, reifies) else setOf(annotation)
        graph.addTriples(others + about)
        assertEquals(others + about, graph.getTriples().toSet())
        assertEquals(others.size + 1, graph.size())
        assertTrue(graph.hasTriple(about))
        assertEquals(listOf(about), graph.find(null, null, reifier))
        assertTrue(graph.removeTriple(about))
        assertEquals(others, graph.getTriples().toSet())
    }

    @TestFactory
    fun `a reifier inside a triple term round-trips in both positions`() = each { repo ->
        if (!repo.starCapable) return@each
        val asObject = RdfTriple(s, p, TripleTerm(RdfTriple(Iri(ex + "x"), Iri(ex + "y"), reifier)))
        assertRoundTrip(repo, listOf(asObject))
        // In subject position of a triple term the reifier stands for its quoted triple, so `rdf:reifies` is implied.
        val asSubject = RdfTriple(s, p, TripleTerm(RdfTriple(reifier, Iri(ex + "y"), Iri(ex + "z"))))
        val graph = repo.editDefaultGraph()
        graph.addTriple(asSubject)
        assertEquals(setOf(asSubject, RdfTriple(reifier, RDF.reifies, quotedTerm)), graph.getTriples().toSet())
        assertTrue(graph.hasTriple(asSubject))
        val both = RdfTriple(s, Iri(ex + "both"), TripleTerm(RdfTriple(reifier, Iri(ex + "y"), reifier)))
        graph.addTriple(both)
        assertTrue(graph.hasTriple(both))
        assertTrue(both in graph.getTriples())
        assertTrue(graph.removeTriple(both))
        assertFalse(graph.hasTriple(both))
    }
}
