package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream

/**
 * Who closes a stream handed to a parse entry point: the caller keeps a stream it passed to `parseFromInputStream`,
 * `parseStreaming` and `parseDataset`; the scoped `openTripleStream` owns the stream it was given.
 */
class ParseStreamOwnershipTest {
    /** Records calls of `close()`; reading after a close fails, as it does for a real stream. */
    private class Recording(text: String) : FilterInputStream(ByteArrayInputStream(text.toByteArray())) {
        var closes = 0
        override fun close() {
            closes++
            super.close()
        }
    }

    private val turtle = "<urn:s> <urn:p> <urn:o> .\n"
    private val quads = "<urn:s> <urn:p> <urn:o> <urn:g> .\n"
    private val broken = "<urn:s> <urn:p> .\n"

    @Test
    fun `parseFromInputStream leaves the caller's stream open, whether parsing succeeds or fails`() {
        for (format in listOf(RdfFormat.TURTLE, RdfFormat.N_TRIPLES, RdfFormat.RDF_XML, RdfFormat.JSON_LD)) {
            val text = when (format) {
                RdfFormat.RDF_XML ->
                    "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns:u=\"urn:\">" +
                        "<rdf:Description rdf:about=\"urn:s\"><u:p rdf:resource=\"urn:o\"/></rdf:Description></rdf:RDF>"
                RdfFormat.JSON_LD -> "{\"@id\":\"urn:s\",\"urn:p\":{\"@id\":\"urn:o\"}}"
                else -> turtle
            }
            val stream = Recording(text)
            assertEquals(1, Rdf.parseFromInputStream(stream, format).size(), format.formatName)
            assertEquals(0, stream.closes, "${format.formatName}: the caller owns the stream")
        }
        val withBase = Recording("<> <urn:p> <#o> .")
        assertEquals(1, Rdf.parseFromInputStream(withBase, RdfFormat.TURTLE, "http://example.org/doc").size())
        assertEquals(0, withBase.closes)

        val failing = Recording(broken)
        assertThrows(RdfFormatException::class.java) { Rdf.parseFromInputStream(failing, "TURTLE") }
        assertEquals(0, failing.closes, "a failed parse leaves the stream to the caller as well")
    }

    @Test
    fun `parseStreaming and parseDataset leave the caller's stream open`() {
        val streamed = Recording(turtle)
        assertEquals(1, Rdf.parseStreaming(streamed, RdfFormat.TURTLE).toList().size)
        assertEquals(0, streamed.closes)

        val dataset = Recording(quads)
        Rdf.memory().use { repo ->
            Rdf.parseDataset(repo, dataset, RdfFormat.N_QUADS)
            assertEquals(1, repo.getGraph(Iri("urn:g")).size())
        }
        assertEquals(0, dataset.closes)

        val failing = Recording("<urn:s> <urn:p> <urn:g> .\n<broken")
        Rdf.memory().use { repo ->
            assertThrows(RdfFormatException::class.java) { Rdf.parseDataset(repo, failing, "N-QUADS") }
        }
        assertEquals(0, failing.closes)
    }

    @Test
    fun `the scoped triple stream owns and closes the stream it was given`() {
        val scoped = Recording(turtle)
        Rdf.openTripleStream(scoped, RdfFormat.TURTLE).use { triples -> assertEquals(1, triples.count()) }
        assertTrue(scoped.closes >= 1, "openTripleStream closes its input")

        val unsupported = Recording(turtle)
        assertThrows(RdfFormatException::class.java) { Rdf.openTripleStream(unsupported, "no-such-format") }
        assertTrue(unsupported.closes >= 1, "also when no parser can be opened")
    }
}
