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
}
