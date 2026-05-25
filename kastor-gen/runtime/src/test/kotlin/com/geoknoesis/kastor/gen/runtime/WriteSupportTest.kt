package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WriteSupportTest {

    @Test
    fun `replaceValues removes stale triples for subject and predicate then adds new ones`() {
        val graph = Rdf.graph {
            val s = Iri("http://example.org/alice")
            s has FOAF.name with "Alice"
            s has FOAF.name with "Alicia"
            s has FOAF.age with 30
        }

        val alice = Iri("http://example.org/alice")
        val newTriple = RdfTriple(alice, FOAF.name, Literal("Alexandra"))

        graph.replaceValues(alice, FOAF.name, listOf(newTriple))

        val remaining = graph.getTriples()
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.predicate == FOAF.name && (it.obj as? Literal)?.lexical == "Alexandra" })
        assertTrue(remaining.any { it.predicate == FOAF.age })
        assertFalse(remaining.any { it.predicate == FOAF.name && (it.obj as? Literal)?.lexical == "Alice" })
        assertFalse(remaining.any { it.predicate == FOAF.name && (it.obj as? Literal)?.lexical == "Alicia" })
    }

    @Test
    fun `replaceValues with empty newTriples removes all matching triples`() {
        val graph = Rdf.graph {
            val s = Iri("http://example.org/alice")
            s has FOAF.name with "Alice"
            s has FOAF.age with 30
        }

        val alice = Iri("http://example.org/alice")
        graph.replaceValues(alice, FOAF.name, emptyList())

        val remaining = graph.getTriples()
        assertEquals(1, remaining.size)
        assertTrue(remaining.any { it.predicate == FOAF.age })
    }

    @Test
    fun `replaceValues only affects the given predicate for the given subject`() {
        val graph = Rdf.graph {
            val alice = Iri("http://example.org/alice")
            val bob = Iri("http://example.org/bob")
            alice has FOAF.name with "Alice"
            bob has FOAF.name with "Bob"
        }

        val alice = Iri("http://example.org/alice")
        val updated = RdfTriple(alice, FOAF.name, Literal("Alexandra"))
        graph.replaceValues(alice, FOAF.name, listOf(updated))

        val remaining = graph.getTriples()
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.subject == alice && (it.obj as? Literal)?.lexical == "Alexandra" })
        assertTrue(remaining.any { it.subject == Iri("http://example.org/bob") && (it.obj as? Literal)?.lexical == "Bob" })
    }

    @Test
    fun `replaceResource removes all triples for the subject then adds new ones`() {
        val graph = Rdf.graph {
            val alice = Iri("http://example.org/alice")
            val bob = Iri("http://example.org/bob")
            alice has FOAF.name with "Alice"
            alice has FOAF.age with 30
            alice has FOAF.mbox with "alice@example.com"
            bob has FOAF.name with "Bob"
        }

        val alice = Iri("http://example.org/alice")
        val replacement = listOf(
            RdfTriple(alice, FOAF.name, Literal("Alexandra")),
            RdfTriple(alice, FOAF.age, Literal(31)),
        )

        graph.replaceResource(alice, replacement)

        val remaining = graph.getTriples()
        assertEquals(3, remaining.size)
        assertTrue(remaining.any { it.subject == alice && it.predicate == FOAF.name && (it.obj as? Literal)?.lexical == "Alexandra" })
        assertTrue(remaining.any { it.subject == alice && it.predicate == FOAF.age })
        assertFalse(remaining.any { it.subject == alice && it.predicate == FOAF.mbox })
        assertTrue(remaining.any { it.subject == Iri("http://example.org/bob") })
    }

    @Test
    fun `replaceResource with empty triples removes all triples for the subject`() {
        val graph = Rdf.graph {
            val alice = Iri("http://example.org/alice")
            val bob = Iri("http://example.org/bob")
            alice has FOAF.name with "Alice"
            alice has FOAF.age with 30
            bob has FOAF.name with "Bob"
        }

        val alice = Iri("http://example.org/alice")
        graph.replaceResource(alice, emptyList())

        val remaining = graph.getTriples()
        assertEquals(1, remaining.size)
        assertTrue(remaining.all { it.subject == Iri("http://example.org/bob") })
    }
}
