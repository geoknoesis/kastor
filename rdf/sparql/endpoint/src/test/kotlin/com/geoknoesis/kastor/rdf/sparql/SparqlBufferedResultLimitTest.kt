package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfQueryException
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * [SparqlEndpointConfig.maxBufferedResultBytes]: the heap a buffered result retains is bounded, not only the bytes of
 * the response. A response of nothing but empty rows (`{}` is 3 bytes) used to retain about 80 bytes of heap per row.
 */
class SparqlBufferedResultLimitTest {

    private val url = "https://sparql.example/sparql"
    private val selectAll = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")

    private fun answering(body: String) = HttpTransport { request ->
        CompletableFuture.completedFuture(FakeResponse(request, 200, body.toByteArray(Charsets.UTF_8).inputStream()))
    }

    private fun repository(body: String, maxBufferedResultBytes: Long = SparqlEndpointConfig.DEFAULT_MAX_BUFFERED_RESULT_BYTES) =
        SparqlRepository(SparqlEndpointConfig(url, maxBufferedResultBytes = maxBufferedResultBytes), answering(body))

    private fun emptyRows(count: Int) = """{"head":{"vars":["x"]},"results":{"bindings":[${List(count) { "{}" }.joinToString(",")}]}}"""

    private fun literalRows(count: Int) =
        """{"head":{"vars":["x"]},"results":{"bindings":[${
            List(count) { """{"x":{"type":"literal","value":"$it","datatype":"http://www.w3.org/2001/XMLSchema#integer"}}""" }.joinToString(",")
        }]}}"""

    @Test
    fun `a buffered select that would retain more than the limit fails and names the limit`() {
        // 100 000 empty rows: 300 KB of JSON, below maxResponseBytes, but more than 64 KiB of retained rows.
        repository(emptyRows(100_000), maxBufferedResultBytes = 64 * 1024).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(e.message!!.contains("maxBufferedResultBytes"), e.message)
            assertTrue(e.message!!.contains("withSelectRows"), e.message)
        }
    }

    @Test
    fun `the limit holds for rows with bindings too`() {
        repository(literalRows(20_000), maxBufferedResultBytes = 256 * 1024).use { repo ->
            assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
        }
    }

    @Test
    fun `graph reads and listGraphs are buffered selects and keep to the limit`() {
        val triples = """{"head":{"vars":["s","p","o"]},"results":{"bindings":[${
            List(5_000) { """{"s":{"type":"uri","value":"urn:s$it"},"p":{"type":"uri","value":"urn:p"},"o":{"type":"uri","value":"urn:o"}}""" }
                .joinToString(",")
        }]}}"""
        repository(triples, maxBufferedResultBytes = 32 * 1024).use { repo ->
            assertThrows(RdfQueryException::class.java) { repo.defaultGraph.getTriples() }
        }
        val graphs = """{"head":{"vars":["g"]},"results":{"bindings":[${List(5_000) { """{"g":{"type":"uri","value":"urn:g$it"}}""" }.joinToString(",")}]}}"""
        repository(graphs, maxBufferedResultBytes = 32 * 1024).use { repo ->
            assertThrows(RdfQueryException::class.java) { repo.listGraphs() }
        }
    }

    @Test
    fun `streamed rows are not held to the buffered limit`() {
        repository(emptyRows(100_000), maxBufferedResultBytes = 64 * 1024).use { repo ->
            assertEquals(100_000, repo.withSelectRows(selectAll) { rows -> rows.count() })
        }
    }

    @Test
    fun `within the limit every row is delivered`() {
        repository(emptyRows(1_000)).use { repo -> assertEquals(1_000, repo.select(selectAll).count()) }
        repository(literalRows(1_000)).use { repo ->
            val rows = repo.select(selectAll).toList()
            assertEquals(1_000, rows.size)
            assertEquals("999", rows.last().getString("x"))
        }
    }

    @Test
    fun `decoded rows share what repeats instead of copying it per row`() {
        repository(emptyRows(3)).use { repo ->
            val rows = repo.select(selectAll).toList()
            assertSame(rows[0], rows[1], "empty rows are one shared instance")
            assertTrue(rows[0].getVariableNames().isEmpty())
        }
        repository(literalRows(3)).use { repo ->
            val rows = repo.select(selectAll).toList()
            // The variable name and the datatype IRI are decoded once, not once per row.
            assertSame(rows[0].getVariableNames().single(), rows[2].getVariableNames().single())
            assertSame((rows[0].get("x") as Literal).datatype.value, (rows[2].get("x") as Literal).datatype.value)
            assertSame(rows[0].getVariableNames(), rows[2].getVariableNames(), "rows that bind the same variables share one name set")
        }
    }

    @Test
    fun `the limit is validated and is a provider option`() {
        assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig(url, maxBufferedResultBytes = 0) }
        val config = SparqlEndpointConfig.fromOptions(mapOf("location" to url, "maxBufferedResultBytes" to "1234"))
        assertEquals(1234L, config.maxBufferedResultBytes)
        assertEquals(SparqlEndpointConfig.DEFAULT_MAX_BUFFERED_RESULT_BYTES, SparqlEndpointConfig(url).maxBufferedResultBytes)
        assertTrue(config.toString().contains("maxBufferedResultBytes=1234"), config.toString())
    }
}
