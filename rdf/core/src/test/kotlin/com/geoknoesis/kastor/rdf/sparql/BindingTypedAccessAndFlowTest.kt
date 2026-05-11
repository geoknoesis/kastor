package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.MapBindingSet
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.asRdfTriplesFlow
import com.geoknoesis.kastor.rdf.parseStreamingFlow
import com.geoknoesis.kastor.rdf.sparqlQueryResult
import com.geoknoesis.kastor.rdf.string
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BindingTypedAccessAndFlowTest {

    @Test
    fun `getAs extracts IRI and lexical String`() {
        val row = MapBindingSet(
            mapOf(
                "s" to Iri("http://example.org/s"),
                "label" to string("Hello"),
            ),
        )
        assertEquals(Iri("http://example.org/s"), row.getAs<Iri>("s"))
        assertEquals("Hello", row.getAs<String>("label"))
        assertNull(row.getAs<Iri>("label"))
        assertNull(row.getAs<String>("s"))
    }

    @Test
    fun `getAsOrThrow fails on missing or wrong type`() {
        val row = MapBindingSet(mapOf("s" to Iri("http://example.org/s")))
        assertEquals(Iri("http://example.org/s"), row.getAsOrThrow<Iri>("s"))
        assertThrows<IllegalArgumentException> { row.getAsOrThrow<Iri>("missing") }
        assertThrows<IllegalArgumentException> { row.getAsOrThrow<String>("s") }
    }

    @Test
    fun `requireVariables detects missing columns`() {
        val row = MapBindingSet(mapOf("s" to Iri("http://example.org/s")))
        assertThrows<IllegalStateException> {
            row.requireVariables("s", "p", "o")
        }
        row.requireVariables("s")
    }

    @Test
    fun `SparqlQueryResult asFlow validates each row`() = runBlocking {
        val rows = listOf(
            MapBindingSet(
                mapOf(
                    "s" to Iri("http://example.org/s"),
                    "p" to Iri("http://example.org/p"),
                    "o" to string("x"),
                ),
            ),
        )
        val result = sparqlQueryResult(rows)
        val collected = result.asFlow("s", "p", "o").toList()
        assertEquals(1, collected.size)
        assertEquals("x", collected[0].getAsOrThrow<String>("o"))
    }

    @Test
    fun `asFlow fails when a row lacks a required variable`() = runBlocking {
        val rows = listOf(
            MapBindingSet(mapOf("s" to Iri("http://example.org/s"))),
        )
        val result = sparqlQueryResult(rows)
        assertThrows<IllegalStateException> {
            result.asFlow("s", "p").toList()
        }
    }

    @Test
    fun `parseStreamingFlow emits triples`() = runBlocking {
        val nt = """
            <http://a> <http://b> "1" .
            <http://a> <http://c> "2" .
        """.trimIndent()
        val triples = Rdf.parseStreamingFlow(nt.byteInputStream(), "NTRIPLES").toList()
        assertEquals(2, triples.size)
        val byPred = triples.associateBy { it.predicate.value }
        assertEquals(string("1"), byPred["http://b"]?.obj)
        assertEquals(string("2"), byPred["http://c"]?.obj)
    }

    @Test
    fun `Iterable asRdfTriplesFlow`() = runBlocking {
        val t = RdfTriple(Iri("http://a"), Iri("http://b"), string("c"))
        val out = listOf(t).asRdfTriplesFlow().toList()
        assertEquals(listOf(t), out)
    }
}
