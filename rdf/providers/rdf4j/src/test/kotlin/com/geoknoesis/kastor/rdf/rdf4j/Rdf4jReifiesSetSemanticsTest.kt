package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An explicitly added `_:r rdf:reifies <<( s p o )>>` triple on an RDF-star (memory) store behaves like any other
 * triple of the graph: it is independent of the annotation statements about the quoted triple, whatever the order.
 */
class Rdf4jReifiesSetSemanticsTest {
    private val ex = "http://example.org/"
    private val vf = SimpleValueFactory.getInstance()
    private val quoted = vf.createTriple(vf.createIRI(ex + "a"), vf.createIRI(ex + "b"), vf.createIRI(ex + "c"))
    private val reifier = Rdf4jTerms.reifierFor(quoted)
    private val reifies = RdfTriple(reifier, RDF.reifies, TripleTerm(RdfTriple(Iri(ex + "a"), Iri(ex + "b"), Iri(ex + "c"))))
    private val annotation = RdfTriple(reifier, Iri(ex + "p"), Literal("o"))
    private val other = RdfTriple(reifier, Iri(ex + "q"), Literal("o2"))

    private fun state(graph: MutableRdfGraph): Set<RdfTriple> = graph.getTriples().toSet()

    private fun assertState(expected: Set<RdfTriple>, graph: MutableRdfGraph, step: String) {
        assertEquals(expected, state(graph), step)
        assertEquals(expected.size, graph.size(), "size after $step")
        for (triple in listOf(reifies, annotation, other)) {
            assertEquals(triple in expected, graph.hasTriple(triple), "hasTriple($triple) after $step")
        }
        assertEquals(expected.filter { it.predicate == RDF.reifies }.toSet(), graph.find(null, RDF.reifies, null).toSet(), "find reifies after $step")
    }

    @Test
    fun `an explicit rdf-reifies added after an annotation survives removing the annotation`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val graph = repo.editDefaultGraph()
            graph.addTriple(annotation)
            graph.addTriple(reifies)
            assertTrue(graph.removeTriple(annotation))
            assertState(setOf(reifies), graph, "remove annotation")
            assertTrue(graph.removeTriple(reifies))
            assertState(emptySet(), graph, "remove reifies")
        }
    }

    @Test
    fun `an explicit rdf-reifies added before an annotation survives removing the annotation`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val graph = repo.editDefaultGraph()
            graph.addTriple(reifies)
            graph.addTriple(annotation)
            assertTrue(graph.removeTriple(annotation))
            assertState(setOf(reifies), graph, "remove annotation")
        }
    }

    @Test
    fun `a removed rdf-reifies is not resurrected by a later annotation`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val graph = repo.editDefaultGraph()
            graph.addTriple(annotation)
            assertTrue(graph.removeTriple(reifies))
            assertState(setOf(annotation), graph, "remove reifies")
            graph.addTriple(other)
            assertState(setOf(annotation, other), graph, "add other annotation")
            graph.addTriple(reifies)
            assertState(setOf(annotation, other, reifies), graph, "re-add reifies")
            assertTrue(graph.removeTriple(annotation))
            assertTrue(graph.removeTriple(other))
            assertState(setOf(reifies), graph, "remove annotations")
        }
    }

    @Test
    fun `removing an explicit rdf-reifies forgets it`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val graph = repo.editDefaultGraph()
            graph.addTriple(annotation)
            graph.addTriple(reifies)
            assertTrue(graph.removeTriple(reifies))
            assertState(setOf(annotation), graph, "remove reifies")
            assertTrue(graph.removeTriple(annotation))
            assertState(emptySet(), graph, "remove annotation")
        }
    }

    @Test
    fun `an explicit rdf-reifies from a rolled-back transaction is forgotten`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val graph = repo.editDefaultGraph()
            assertFailsWith<IllegalStateException> {
                repo.transaction {
                    graph.addTriple(annotation)
                    graph.addTriple(reifies)
                    error("roll back")
                }
            }
            assertState(emptySet(), graph, "rollback")
            graph.addTriple(annotation)
            assertTrue(graph.removeTriple(annotation))
            assertState(emptySet(), graph, "add and remove annotation")
        }
    }

    @Test
    fun `an explicit rdf-reifies survives a SPARQL update that deletes the annotation`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val graph = repo.editDefaultGraph()
            graph.addTriple(annotation)
            graph.addTriple(reifies)
            repo.update(com.geoknoesis.kastor.rdf.UpdateQuery("DELETE WHERE { ?s <${ex}p> ?o }"))
            assertState(setOf(reifies), graph, "SPARQL delete")
        }
    }

    @Test
    fun `clearing the graph forgets explicit rdf-reifies triples`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val graph = repo.editDefaultGraph()
            graph.addTriple(annotation)
            graph.addTriple(reifies)
            assertTrue(graph.clear())
            graph.addTriple(annotation)
            assertTrue(graph.removeTriple(annotation))
            assertState(emptySet(), graph, "after clear")
            assertFalse(graph.hasTriple(reifies))
        }
    }

    /**
     * Every add/remove sequence (up to four operations) matches a set model and the Jena provider. The only difference
     * from Jena is RDF-star's implied `rdf:reifies`: while annotations about the quoted triple are stored in RDF-star
     * form the reifies triple is also present, until it is removed explicitly (which lasts while annotations remain).
     */
    @Test
    fun `every add and remove sequence has set semantics, like the Jena provider`() {
        val triples = listOf(annotation, reifies, other)
        val operations = triples.flatMap { listOf(true to it, false to it) }
        fun sequences(length: Int): List<List<Pair<Boolean, RdfTriple>>> =
            if (length == 0) listOf(emptyList()) else sequences(length - 1).flatMap { prefix -> operations.map { prefix + it } }

        val rdf4j = Rdf4jRepository.MemoryRepository()
        val jena: RdfRepository = JenaRepository.MemoryRepository()
        try {
            val rdf4jGraph = rdf4j.editDefaultGraph()
            val jenaGraph = jena.editDefaultGraph()
            for (sequence in sequences(4)) {
                rdf4jGraph.clear()
                jenaGraph.clear()
                val explicit = HashSet<RdfTriple>()
                var suppressed = false
                fun annotated() = annotation in explicit || other in explicit
                fun view() = if (annotated() && !suppressed) explicit + reifies else explicit.toSet()
                sequence.forEachIndexed { index, (add, triple) ->
                    val step = "${sequence.take(index + 1).map { (a, t) -> (if (a) "+" else "-") + t.predicate.value.substringAfterLast('/') }}"
                    if (add) {
                        explicit.add(triple)
                        if (triple == reifies) suppressed = false
                        rdf4jGraph.addTriple(triple)
                        jenaGraph.addTriple(triple)
                    } else {
                        val present = triple in view()
                        assertEquals(triple in explicit, jenaGraph.removeTriple(triple), "Jena remove result at $step")
                        explicit.remove(triple)
                        if (triple == reifies) suppressed = true
                        assertEquals(present, rdf4jGraph.removeTriple(triple), "RDF4J remove result at $step")
                    }
                    if (!annotated()) suppressed = false
                    assertEquals(view(), state(rdf4jGraph), "RDF4J at $step")
                    assertEquals(view().size, rdf4jGraph.size(), "RDF4J size at $step")
                    assertEquals(explicit.toSet(), state(jenaGraph), "Jena at $step")
                }
            }
        } finally {
            rdf4j.close()
            jena.close()
        }
    }
}
