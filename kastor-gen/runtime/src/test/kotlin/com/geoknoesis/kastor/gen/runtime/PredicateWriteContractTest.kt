package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

class PredicateWriteContractTest {
    private val subject = BlankNode("subject")
    private val predicate = Iri("urn:property")
    private val oldLiteral = string("old")
    private val oldObject = Iri("urn:old")
    private val replacementLiteral = LangString("new", "en")
    private val replacementObject = BlankNode("new")
    private val unrelated = setOf(
        RdfTriple(Iri("urn:other"), predicate, oldLiteral),
        RdfTriple(subject, Iri("urn:other-property"), oldObject),
    )
    private val initial = unrelated + setOf(
        RdfTriple(subject, predicate, oldLiteral),
        RdfTriple(subject, predicate, LangString("ancien", "fr")),
        RdfTriple(subject, predicate, oldObject),
        RdfTriple(subject, predicate, BlankNode("old")),
    )
    private data class Edit(val name: String, val literals: Boolean, val replacement: RdfTerm?, val apply: (RdfBacked) -> Unit)
    private val edits = listOf(
        Edit("replace literals", true, replacementLiteral) { it.replacePredicateLiterals(predicate, replacementLiteral) },
        Edit("clear literals", true, null) { it.clearPredicateLiterals(predicate) },
        Edit("replace objects", false, replacementObject) { it.replacePredicateObjectTerm(predicate, replacementObject) },
        Edit("clear objects", false, null) { it.clearPredicateObjects(predicate) },
    )
    private fun view(graph: RdfGraph, node: RdfTerm = subject) = object : RdfBacked {
        override val rdf = DefaultRdfHandle(node, graph, emptySet())
    }

    @TestFactory
    fun `writes preserve other term kinds subjects and predicates and are idempotent`() = edits.map { edit ->
        dynamicTest(edit.name) {
            val graph = MemoryGraph(initial)
            val expected = initial.filterNot {
                it.subject == subject && it.predicate == predicate && (it.obj is Literal) == edit.literals
            }.toSet() + listOfNotNull(edit.replacement?.let { RdfTriple(subject, predicate, it) })
            repeat(2) {
                edit.apply(view(graph))
                assertEquals(expected, graph.getTriples().toSet())
            }
        }
    }

    @TestFactory
    fun `writes handle an absent property without affecting other data`() = edits.map { edit ->
        dynamicTest(edit.name) {
            val graph = MemoryGraph(unrelated)
            edit.apply(view(graph))
            assertEquals(unrelated + listOfNotNull(edit.replacement?.let { RdfTriple(subject, predicate, it) }), graph.getTriples().toSet())
        }
    }

    @TestFactory
    fun `read-only graphs reject every write without modifying their backing graph`() = edits.map { edit ->
        dynamicTest(edit.name) {
            val backing = MemoryGraph(initial)
            val readOnly = object : RdfGraph by backing {}
            val error = assertThrows(IllegalStateException::class.java) { edit.apply(view(readOnly)) }
            assertTrue(error.message.orEmpty().contains("not mutable"))
            assertEquals(initial, backing.getTriples().toSet())
        }
    }

    @TestFactory
    fun `invalid subjects fail before changing data`() = edits.map { edit ->
        dynamicTest(edit.name) {
            val graph = MemoryGraph(initial)
            assertThrows(IllegalStateException::class.java) { edit.apply(view(graph, string("not a subject"))) }
            assertEquals(initial, graph.getTriples().toSet())
        }
    }

    @TestFactory
    fun `writes participate in repository transaction rollback`() = edits.map { edit ->
        dynamicTest(edit.name) {
            MemoryRepository(RdfConfig()).use { repo ->
                val graph = repo.editDefaultGraph()
                graph.addTriples(initial)
                val abort = IllegalArgumentException("abort transaction")
                assertSame(abort, assertThrows(IllegalArgumentException::class.java) {
                    repo.transaction {
                        edit.apply(view(graph))
                        throw abort
                    }
                })
                assertEquals(initial, graph.getTriples().toSet())
            }
        }
    }
}
