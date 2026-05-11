package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfFormatException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Streaming parse tests live in :rdf:examples (not :rdf:core) because they require
 * a real parsing-capable provider (Jena or RDF4J) on the test classpath.
 */
class StreamingParseTest {

    private val turtle = """
        @prefix ex: <http://example.org/> .
        ex:subject ex:predicate "object" .
    """.trimIndent()

    @Test
    fun `parseStreaming returns the parsed triples`() {
        // First verify regular parsing works.
        val graph = Rdf.parseFromInputStream(turtle.byteInputStream(), RdfFormat.TURTLE)
        assertEquals(1, graph.size())

        // Now exercise streaming.
        val triples = Rdf.parseStreaming(turtle.byteInputStream(), RdfFormat.TURTLE).toList()
        assertEquals(1, triples.size)
    }

    @Test
    fun `parseStreaming works with string format`() {
        val triples = Rdf.parseStreaming(turtle.byteInputStream(), "TURTLE").toList()
        assertEquals(1, triples.size)
    }

    @Test
    fun `parseStreaming throws RdfFormatException for unknown format`() {
        assertThrows(RdfFormatException::class.java) {
            Rdf.parseStreaming("some data".byteInputStream(), "UNKNOWN_FORMAT").toList()
        }
    }
}
