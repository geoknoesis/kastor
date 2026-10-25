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
    // ------------------------------------------------------------------ what the shared caches hold is charged too

    /** [count] rows, each binding `x` to a literal whose datatype is a distinct IRI of [chars] characters. */
    private fun distinctDatatypes(count: Int, chars: Int) =
        """{"head":{"vars":["x"]},"results":{"bindings":[${
            List(count) { """{"x":{"type":"literal","value":"v","datatype":"urn:${it.toString().padStart(chars - 4, 'd')}"}}""" }.joinToString(",")
        }]}}"""

    /** [count] rows without `head`, each binding [width] variables with names of [chars] characters no other row uses. */
    private fun distinctNames(count: Int, width: Int, chars: Int) =
        """{"results":{"bindings":[${
            List(count) { row ->
                List(width) { v -> "\"${"v${row}_$v".padEnd(chars, 'n')}\":{\"type\":\"uri\",\"value\":\"urn:a\"}" }.joinToString(",", "{", "}")
            }.joinToString(",")
        }]}}"""

    private fun decode(document: String): JsonBindingRows =
        JsonBindingRows(document.byteInputStream()).also { decoder -> decoder.rows().forEach { _ -> } }

    @Test
    fun `distinct long datatype IRIs are charged to the rows that bring them`() {
        // 2 000 distinct datatypes of 1 000 characters: about 4 MB of strings, whether cached or not.
        val decoder = decode(distinctDatatypes(2_000, 1_000))
        assertTrue(decoder.cacheBytes <= RowAssembler.MAX_CACHE_BYTES, "caches hold ${decoder.cacheBytes} bytes")
        assertTrue(decoder.retainedBytes >= 2_000L * 2 * 1_000, "estimate ${decoder.retainedBytes} misses the datatype strings")
        repository(distinctDatatypes(2_000, 1_000), maxBufferedResultBytes = 1024 * 1024).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(e.message!!.contains("maxBufferedResultBytes"), e.message)
        }
    }

    @Test
    fun `wide rows of names no other row uses are charged to the rows`() {
        // 400 rows of 40 variables with 50-character names: 16 000 names, 1.6 MB of name strings.
        val decoder = decode(distinctNames(400, 40, 50))
        assertTrue(decoder.cacheBytes <= RowAssembler.MAX_CACHE_BYTES, "caches hold ${decoder.cacheBytes} bytes")
        assertTrue(decoder.retainedBytes >= 400L * 40 * 2 * 50, "estimate ${decoder.retainedBytes} misses the variable names")
        // Names too long to be shared: 200 rows, each with one name of 3 000 characters.
        val long = decode(distinctNames(200, 1, 3_000))
        assertTrue(long.retainedBytes >= 200L * 2 * 3_000, "estimate ${long.retainedBytes} misses the variable names")
        repository(distinctNames(400, 40, 50), maxBufferedResultBytes = 512 * 1024).use { repo ->
            assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
        }
    }

    @Test
    fun `the caches stop growing at their byte budget`() {
        // 6 000 distinct datatypes of 1 000 characters would take about 12 MB of cache.
        val decoder = decode(distinctDatatypes(6_000, 1_000))
        assertTrue(decoder.cacheBytes <= RowAssembler.MAX_CACHE_BYTES, "caches hold ${decoder.cacheBytes} bytes")
        assertTrue(decoder.retainedBytes >= 6_000L * 2 * 1_000, "estimate ${decoder.retainedBytes}")
    }
}
