package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WriteSupportAtomicityTest {
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")
    private val old = RdfTriple(s, p, string("old"))
    private val replacement = old.copy(obj = string("new"))

    @Test
    fun `object replacements reject literals and quoted triples before entering an edit`() {
        val resource = old.copy(obj = Iri("urn:object"))
        val backing = MemoryGraph(listOf(old, resource))
        val stamp = backing.modificationStamp
        var edits = 0
        val graph = object : AtomicMutableRdfGraph by backing {
            override fun mutateAtomically(operations: MutableRdfGraph.() -> Unit) {
                edits++
                backing.mutateAtomically(operations)
            }
        }
        val view = object : RdfBacked { override val rdf = DefaultRdfHandle(s, graph, emptySet()) }
        listOf(string("invalid"), TripleTerm(resource)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { view.replacePredicateObjectTerm(p, invalid) }
            assertEquals(setOf(old, resource), backing.getTriples().toSet())
            assertEquals(stamp, backing.modificationStamp)
        }
        assertEquals(0, edits)
        listOf(Iri("urn:new"), BlankNode("new")).forEach { valid ->
            view.replacePredicateObjectTerm(p, valid)
            assertEquals(setOf(old, resource.copy(obj = valid)), backing.getTriples().toSet())
        }
        assertEquals(2, edits)
    }

    @Test
    fun `invalid replacement subjects and predicates fail without deleting existing data`() {
        val graph = MemoryGraph(listOf(old))
        val otherSubject = replacement.copy(subject = Iri("urn:other"))
        val otherPredicate = replacement.copy(predicate = Iri("urn:other"))
        assertThrows(IllegalArgumentException::class.java) { graph.replaceResource(s, listOf(replacement, otherSubject)) }
        assertEquals(listOf(old), graph.getTriples())
        listOf(otherSubject, otherPredicate).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { graph.replaceValues(s, p, listOf(replacement, invalid)) }
            assertEquals(listOf(old), graph.getTriples())
        }
    }

    @Test
    fun `graphs without atomic support reject replacements before any write`() {
        val backing = MemoryGraph(listOf(old))
        val graph = object : MutableRdfGraph by backing {}
        assertThrows(UnsupportedOperationException::class.java) { graph.replaceResource(s, listOf(replacement)) }
        assertThrows(UnsupportedOperationException::class.java) { graph.replaceValues(s, p, listOf(replacement)) }
        assertEquals(listOf(old), backing.getTriples())
    }

    @Test
    fun `partial insertion failure restores old data on standalone and repository graphs`() {
        MemoryRepository(RdfConfig()).use { repo ->
            Rdf.repository { providerId = "jena"; variantId = "memory" }.use { jena ->
                val graphs = listOf(MemoryGraph(), repo.editDefaultGraph(), repo.editGraph(Iri("urn:g")), jena.editDefaultGraph())
                graphs.forEach { backing ->
                    listOf<(MutableRdfGraph) -> Unit>(
                        { it.replaceResource(s, listOf(replacement)) },
                        { it.replaceValues(s, p, listOf(replacement)) },
                    ).forEach { edit ->
                        backing.addTriple(old)
                        val graph = object : AtomicMutableRdfGraph by (backing as AtomicMutableRdfGraph) {
                            override fun addTriples(triples: Collection<RdfTriple>) {
                                backing.addTriples(triples)
                                error("insert failed after writing")
                            }
                        }
                        assertThrows(IllegalStateException::class.java) { edit(graph) }
                        assertEquals(setOf(old), backing.getTriples().toSet())
                    }
                }
            }
        }
    }

    @Test
    fun `predicate setters roll back insertion failure and preserve other object kinds`() {
        val backing = MemoryGraph(listOf(old, RdfTriple(s, p, Iri("urn:object"))))
        val initial = backing.getTriples().toSet()
        val graph = object : AtomicMutableRdfGraph by backing {
            override fun addTriple(triple: RdfTriple) { backing.addTriple(triple); error("insert failed") }
        }
        val view = object : RdfBacked { override val rdf = DefaultRdfHandle(s, graph, emptySet()) }
        assertThrows(IllegalStateException::class.java) { view.replacePredicateLiterals(p, string("new")) }
        assertEquals(initial, backing.getTriples().toSet())
        assertThrows(IllegalStateException::class.java) { view.replacePredicateObjectTerm(p, Iri("urn:new")) }
        assertEquals(initial, backing.getTriples().toSet())
    }

    @Test
    fun `replacePredicates replaces only the listed predicates and keeps every other triple`() {
        val q = Iri("urn:q")
        val type = RdfTriple(s, Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), Iri("urn:Agent"))
        val link = RdfTriple(s, q, Iri("urn:o"))
        val graph = MemoryGraph(listOf(old, link, type))
        val newType = type.copy(obj = Iri("urn:Person"))
        graph.replacePredicates(s, listOf(p), listOf(replacement, newType))
        assertEquals(setOf(replacement, link, type, newType), graph.getTriples().toSet())
        assertThrows(IllegalArgumentException::class.java) {
            graph.replacePredicates(s, listOf(p), listOf(old.copy(subject = Iri("urn:other"))))
        }
        assertEquals(setOf(replacement, link, type, newType), graph.getTriples().toSet())
        val plain = object : MutableRdfGraph by graph {}
        assertThrows(UnsupportedOperationException::class.java) { plain.replacePredicates(s, listOf(p), emptyList()) }
        assertTrue(graph.hasTriple(replacement))
    }

    @Test
    fun `Resource setValues is atomic and rejects non-atomic editors before any change`() {
        Rdf.repository { providerId = "jena"; variantId = "memory" }.use { jena ->
            jena.editDefaultGraph().addTriple(old)
            val failing = object : AtomicMutableRdfGraph by (jena.editDefaultGraph() as AtomicMutableRdfGraph) {
                override fun addTriples(triples: Collection<RdfTriple>) {
                    jena.editDefaultGraph().addTriples(triples)
                    error("insert failed after writing")
                }
            }
            val resource = resource(s, DefaultGraphAccess(jena, jena.defaultGraph, failing))
            assertThrows(IllegalStateException::class.java) { resource.setLiteral(p, "new") }
            assertEquals(setOf(old), jena.defaultGraph.getTriples().toSet())

            val backing = MemoryGraph(listOf(old))
            val plain = resource(s, DefaultGraphAccess(jena, backing, object : MutableRdfGraph by backing {}))
            assertThrows(UnsupportedOperationException::class.java) { plain.setLiteral(p, "new") }
            assertEquals(listOf(old), backing.getTriples())

            resource(s, DefaultGraphAccess(jena)).setValues(p, listOf(string("a"), string("b")))
            assertEquals(setOf(string("a"), string("b")), jena.defaultGraph.find(s, p).map { it.obj }.toSet())
        }
    }

    @Test
    fun `concurrent readers never observe a replaced predicate without values`() {
        MemoryRepository(RdfConfig()).use { repo ->
            Rdf.repository { providerId = "jena"; variantId = "memory" }.use { jena ->
                listOf(MemoryGraph(), repo.editDefaultGraph(), jena.editDefaultGraph()).forEach { backing ->
                    backing.addTriple(old)
                    val removed = java.util.concurrent.CountDownLatch(1)
                    val graph = object : AtomicMutableRdfGraph by (backing as AtomicMutableRdfGraph) {
                        override fun addTriples(triples: Collection<RdfTriple>) {
                            removed.countDown()
                            // Leave the gap open long enough for the reader to try to look into it.
                            Thread.sleep(300)
                            backing.addTriples(triples)
                        }
                    }
                    val seen = java.util.concurrent.atomic.AtomicReference<List<RdfTriple>>()
                    val reader = Thread {
                        assertTrue(removed.await(30, java.util.concurrent.TimeUnit.SECONDS))
                        seen.set(backing.find(s, p).toList())
                    }
                    reader.start()
                    graph.replaceValues(s, p, listOf(replacement))
                    reader.join(60_000)
                    assertFalse(reader.isAlive)
                    val observed = seen.get()
                    assertNotNull(observed)
                    assertTrue(observed == listOf(old) || observed == listOf(replacement), "reader saw $observed")
                    backing.removeTriple(replacement)
                }
            }
        }
    }
}
