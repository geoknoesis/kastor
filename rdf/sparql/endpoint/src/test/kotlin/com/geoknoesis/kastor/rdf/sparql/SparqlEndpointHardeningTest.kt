package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfQueryException
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlAskQuery
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.UpdateQuery
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.http.HttpRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Eighth audit of the SPARQL HTTP adapter: rows with malformed terms, what an HTTP error body may
 * put into a message, headers the adapter sets itself, credentials in custom headers, and rows
 * that are not triples.
 */
class SparqlEndpointHardeningTest {

    private val selectAll = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")
    private val url = "https://sparql.example/sparql"

    private fun answering(status: Int, body: String, sent: MutableList<HttpRequest>? = null) = HttpTransport { request ->
        sent?.add(request)
        CompletableFuture.completedFuture(FakeResponse(request, status, body.toByteArray(Charsets.UTF_8).inputStream()))
    }

    private fun uri(value: String) = """{"type":"uri","value":"$value"}"""

    private fun result(vars: String, vararg rows: String) = """{"head":{"vars":[$vars]},"results":{"bindings":[${rows.joinToString(",")}]}}"""

    /** The warnings the adapter logs while [block] runs. */
    private fun warnings(block: () -> Unit): List<String> {
        val logger = Logger.getLogger(SparqlRepository::class.java.name)
        val records = CopyOnWriteArrayList<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() >= Level.WARNING.intValue()) records += record.message
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            block()
        } finally {
            logger.removeHandler(handler)
        }
        return records
    }

    // ------------------------------------------------------------------ malformed terms

    private val dirty = result(
        "\"x\"",
        """{"x":${uri("urn:a")}}""",
        """{"x":${uri("relative/path")}}""",
        """{"x":${uri("http://example.org/a b")}}""",
        """{"x":${uri("urn:b")}}""",
    )

    @Test
    fun `a row with a malformed term fails the query unless such rows are to be skipped`() {
        val logged = warnings {
            SparqlRepository(SparqlEndpointConfig(url), answering(200, dirty)).use { repo ->
                assertEquals(MalformedTermPolicy.FAIL, repo.config.malformedTerms)
                val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
                assertTrue(e.message!!.contains("invalid term") && e.message!!.contains("relative/path"), e.message)
                // A stream delivers the rows before it.
                val seen = ArrayList<RdfTerm?>()
                assertThrows(RdfQueryException::class.java) { repo.withSelectRows(selectAll) { rows -> rows.forEach { seen += it.get("x") } } }
                assertEquals(listOf<RdfTerm?>(Iri("urn:a")), seen)
            }
        }
        assertEquals(emptyList<String>(), logged.filter { it.contains("Skipped") })
    }

    @Test
    fun `skipped rows are left out and reported once per query`() {
        val config = SparqlEndpointConfig(url, malformedTerms = MalformedTermPolicy.SKIP_ROW)
        val logged = warnings {
            SparqlRepository(config, answering(200, dirty)).use { repo ->
                assertEquals(listOf<RdfTerm?>(Iri("urn:a"), Iri("urn:b")), repo.select(selectAll).map { it.get("x") })
                assertEquals(2, repo.withSelectRows(selectAll) { it.count() })
            }
        }.filter { it.contains("Skipped") }
        assertEquals(2, logged.size, "one warning per query: $logged")
        for (warning in logged) {
            assertTrue(warning.contains("Skipped 2 SPARQL result row(s)"), warning)
            assertTrue(warning.contains("row 2: variable 'x'") && warning.contains("relative/path"), warning)
            assertTrue(warning.all { it in ' '..'~' }, warning)
        }

        // A result without such rows logs nothing, and a result that is not SPARQL JSON still fails.
        val clean = warnings {
            SparqlRepository(config, answering(200, result("\"x\"", """{"x":${uri("urn:a")}}"""))).use { repo ->
                assertEquals(1, repo.select(selectAll).count())
            }
            SparqlRepository(config, answering(200, result("\"x\"", """{"x":{"type":"uri","value":1}}"""))).use { repo ->
                assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            }
        }
        assertEquals(emptyList<String>(), clean.filter { it.contains("Skipped") })

        // A consumer that stops early is told about the rows skipped up to there.
        val early = warnings {
            SparqlRepository(config, answering(200, dirty)).use { repo ->
                assertEquals(Iri("urn:b"), repo.withSelectRows(selectAll) { rows -> rows.drop(1).first().get("x") })
            }
        }.filter { it.contains("Skipped") }
        assertEquals(1, early.size)

        // Graph reads go through the same decoder.
        val triples = result(
            "\"s\",\"p\",\"o\"",
            """{"s":${uri("urn:s")},"p":${uri("urn:p")},"o":${uri("urn:o")}}""",
            """{"s":${uri("urn:s")},"p":${uri("urn:p")},"o":${uri("not an iri")}}""",
        )
        SparqlRepository(config, answering(200, triples)).use { repo ->
            assertEquals(listOf(RdfTriple(Iri("urn:s"), Iri("urn:p"), Iri("urn:o"))), repo.defaultGraph.getTriples())
        }
    }

    @Test
    fun `the policy for malformed terms is a provider option`() {
        fun options(vararg more: Pair<String, String>) = mapOf("location" to url, *more)
        assertEquals(MalformedTermPolicy.FAIL, SparqlEndpointConfig.fromOptions(options()).malformedTerms)
        assertEquals(MalformedTermPolicy.SKIP_ROW, SparqlEndpointConfig.fromOptions(options("malformedTerms" to "SKIP_ROW")).malformedTerms)
        assertEquals(MalformedTermPolicy.SKIP_ROW, SparqlEndpointConfig.fromOptions(options("malformedTerms" to " skip_row ")).malformedTerms)
        assertEquals(MalformedTermPolicy.FAIL, SparqlEndpointConfig.fromOptions(options("malformedTerms" to "fail")).malformedTerms)
        val e = assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig.fromOptions(options("malformedTerms" to "lenient")) }
        assertTrue(e.message!!.contains("malformedTerms") && e.message!!.contains("SKIP_ROW"), e.message)
        val config = RdfConfig(providerId = "sparql", variantId = "sparql", options = options("malformedTerms" to "SKIP_ROW"))
        (SparqlProvider().createRepository("sparql", config) as SparqlRepository).use { repo ->
            assertEquals(MalformedTermPolicy.SKIP_ROW, repo.config.malformedTerms)
            assertTrue(repo.config.toString().contains("malformedTerms=SKIP_ROW"), repo.config.toString())
        }
    }

    // ------------------------------------------------------------------ HTTP error bodies

    @Test
    fun `an HTTP error body is quoted as one short printable line`() {
        val escape = 27.toChar()
        val rightToLeftOverride = 0x202E.toChar()
        val body = "  Parse error at line 1:\r\n\tunexpected '}'" + 0.toChar() + " " + escape + "[31mred" + 0x85.toChar() + "next" +
            0x2028.toChar() + "line" + rightToLeftOverride + "txt " + 0x7F.toChar() + " requ" + 0xEA.toChar() + "te " + "x".repeat(5000)
        SparqlRepository(SparqlEndpointConfig(url), answering(500, body)).use { repo ->
            val calls = listOf<Pair<String, () -> Any>>(
                "select" to { repo.select(selectAll) },
                "stream" to { repo.withSelectRows(selectAll) { it.count() } },
                "ask" to { repo.ask(SparqlAskQuery("ASK { ?s ?p ?o }")) },
                "update" to { repo.update(UpdateQuery("CLEAR DEFAULT")) },
            )
            for ((name, call) in calls) {
                val message = assertThrows(RdfQueryException::class.java, { call() }, name).message!!
                val backslash = 92.toChar()
                val expected = "SPARQL endpoint returned HTTP 500: Parse error at line 1: unexpected '}'${backslash}u0000 ${backslash}u001B[31mred " +
                    "next line${backslash}u202Etxt ${backslash}u007F requ" + 0xEA.toChar() + "te xxx"
                assertTrue(message.startsWith(expected), "$name: $message")
                assertTrue(message.endsWith("xxx..."), "$name: $message")
                assertTrue(message.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }, "$name: $message")
                assertTrue(message.none { it == 0x2028.toChar() || it == 0x2029.toChar() }, "$name: $message")
                assertEquals("SPARQL endpoint returned HTTP 500: ".length + 512 + 3, message.length, "$name: $message")
            }
        }
        // A short body is quoted as it is, and an empty one adds nothing.
        SparqlRepository(SparqlEndpointConfig(url), answering(404, "Unknown graph <urn:g>\n")).use { repo ->
            assertEquals("SPARQL endpoint returned HTTP 404: Unknown graph <urn:g>", assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }.message)
        }
        SparqlRepository(SparqlEndpointConfig(url), answering(503, " \r\n ")).use { repo ->
            assertEquals("SPARQL endpoint returned HTTP 503", assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }.message)
        }
        // Characters beyond the BMP are kept whole; half of one is not.
        val emoji = String(Character.toChars(0x1F600))
        SparqlRepository(SparqlEndpointConfig(url), answering(400, "bad $emoji")).use { repo ->
            assertEquals("SPARQL endpoint returned HTTP 400: bad $emoji", assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }.message)
        }
        SparqlRepository(SparqlEndpointConfig(url), answering(400, "y".repeat(511) + emoji + "z")).use { repo ->
            val message = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }.message!!
            assertTrue(message.endsWith("y..."), message.takeLast(20))
        }
    }

    // ------------------------------------------------------------------ headers

    @Test
    fun `headers the adapter sets itself cannot be configured`() {
        for (name in listOf("Accept", "accept", "Content-Type", "CONTENT-TYPE", "Content-Length", "Host", "Connection", "Transfer-Encoding", "transfer-encoding")) {
            val e = assertThrows(IllegalArgumentException::class.java, { SparqlEndpointConfig(url, headers = mapOf(name to "text/plain")) }, name)
            assertTrue(e.message!!.contains("'$name'") && e.message!!.contains("cannot be set"), e.message)
            assertThrows(IllegalArgumentException::class.java, { SparqlEndpointConfig.fromOptions(mapOf("location" to url, "header.$name" to "x")) }, name)
        }
        val accept = assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig(url, headers = mapOf("Accept" to "text/csv")) }
        assertTrue(accept.message!!.contains("application/sparql-results+json"), accept.message)

        // Other headers are sent next to the adapter's own.
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val headers = mapOf("Accept-Language" to "fr", "X-Request-Id" to "42", "Authorization" to "Bearer abc")
        SparqlRepository(SparqlEndpointConfig(url, headers = headers), answering(200, result("\"x\""), sent)).use { repo ->
            assertEquals(0, repo.select(selectAll).count())
        }
        val request = sent.single()
        assertEquals(listOf("application/sparql-results+json"), request.headers().allValues("Accept"))
        assertEquals(listOf("application/sparql-query"), request.headers().allValues("Content-Type"))
        for ((name, value) in headers) assertEquals(listOf(value), request.headers().allValues(name), name)
    }

    @Test
    fun `an Authorization header and configured credentials exclude each other`() {
        val bearer = mapOf("authorization" to "Bearer abc")
        val both = assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig(url, headers = bearer, username = "u", password = "p") }
        assertTrue(both.message!!.contains("Authorization") && !both.message!!.contains("abc"), both.message)
        val embedded = assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig("https://alice:s3cret@sparql.example/sparql", headers = bearer) }
        assertTrue(embedded.message!!.contains("Authorization") && !embedded.message!!.contains("s3cret"), embedded.message)
        assertThrows(IllegalArgumentException::class.java) {
            SparqlEndpointConfig(url, updateEndpoint = "https://alice:s3cret@sparql.example/update", headers = bearer)
        }
        assertNotNull(SparqlEndpointConfig(url, headers = bearer))
    }

    @Test
    fun `credentials in custom headers over plain http are flagged`() {
        val plain = "http://sparql.example/sparql"
        for (name in listOf("Authorization", "authorization", "Proxy-Authorization", "X-API-Key", "apikey", "Ocp-Apim-Subscription-Key", "X-Auth-Token", "Cookie", "X-Secret")) {
            val warning = SparqlRepository.insecureAuthorizationWarning(SparqlEndpointConfig(plain, headers = mapOf(name to "s3cret-value")))
            assertNotNull(warning, name)
            assertTrue(warning!!.contains("'$name'") && warning.contains("plain http"), warning)
            assertFalse(warning.contains("s3cret-value"), warning)
            assertNull(SparqlRepository.insecureAuthorizationWarning(SparqlEndpointConfig(url, headers = mapOf(name to "s3cret-value"))), name)
        }
        assertNull(SparqlRepository.insecureAuthorizationWarning(SparqlEndpointConfig(plain, headers = mapOf("X-Request-Id" to "1", "Accept-Language" to "fr"))))
        // Only the endpoint that is plain http is named, with everything that travels to it.
        val mixed = SparqlRepository.insecureAuthorizationWarning(
            SparqlEndpointConfig(url, updateEndpoint = "http://bob:pw@sparql.example/update", headers = mapOf("X-API-Key" to "k"))
        )!!
        assertTrue(mixed.contains("http://***@sparql.example/update") && mixed.contains("HTTP Basic") && mixed.contains("'X-API-Key'"), mixed)
        assertFalse(mixed.contains("https://sparql.example/sparql"), mixed)
        val basic = SparqlRepository.insecureAuthorizationWarning(SparqlEndpointConfig(plain, username = "u", password = "hunter2"))!!
        assertTrue(basic.contains("HTTP Basic") && !basic.contains("hunter2"), basic)
    }

    // ------------------------------------------------------------------ rows that are not triples

    @Test
    fun `a graph read rejects rows that are not triples`() {
        val s = uri("urn:s")
        val p = uri("urn:p")
        val o = uri("urn:o")
        val literal = """{"type":"literal","value":"v"}"""
        val bnode = """{"type":"bnode","value":"b0"}"""
        val all = "\"s\",\"p\",\"o\""
        val unexpected = listOf(
            result(all, """{"s":$s,"p":$p}""") to "?o",
            result(all, """{"p":$p,"o":$o}""") to "?s",
            result(all, """{"s":$s,"o":$o}""") to "?p",
            result(all, "{}") to "?s",
            result(all, """{"s":$literal,"p":$p,"o":$o}""") to "?s",
            result(all, """{"s":$s,"p":$bnode,"o":$o}""") to "?p",
            result(all, """{"s":$s,"p":$literal,"o":$o}""") to "?p",
            result(all, """{"s":$s,"p":$p,"o":$o}""", """{"s":$s,"p":$p}""") to "?o",
        )
        for ((body, variable) in unexpected) {
            SparqlRepository(SparqlEndpointConfig(url), answering(200, body)).use { repo ->
                val e = assertThrows(RdfQueryException::class.java, { repo.defaultGraph.getTriples() }, body)
                assertTrue(e.message!!.contains(variable) && e.message!!.contains("not a triple"), "${e.message} for $body")
                assertTrue(e.query!!.startsWith("SELECT"), e.query)
                assertThrows(RdfQueryException::class.java, { repo.getGraph(Iri("urn:g")).find(null, null, null) }, body)
            }
        }
        // What the pattern fixes is not asked of the rows, and every kind of term is taken where it may stand.
        val rows = result(all, """{"s":$bnode,"p":$p,"o":$literal}""", """{"s":$s,"p":$p,"o":$bnode}""", """{"s":$s,"p":$p,"o":$o}""")
        SparqlRepository(SparqlEndpointConfig(url), answering(200, rows)).use { repo ->
            assertEquals(
                listOf(
                    RdfTriple(BlankNode("b0"), Iri("urn:p"), Literal("v", XSD.string)),
                    RdfTriple(Iri("urn:s"), Iri("urn:p"), BlankNode("b0")),
                    RdfTriple(Iri("urn:s"), Iri("urn:p"), Iri("urn:o")),
                ),
                repo.defaultGraph.getTriples(),
            )
        }
        SparqlRepository(SparqlEndpointConfig(url), answering(200, result("\"o\"", """{"o":$o}""", """{"o":$literal}"""))).use { repo ->
            val found = repo.defaultGraph.find(Iri("urn:s"), Iri("urn:p"), null)
            assertEquals(listOf(RdfTriple(Iri("urn:s"), Iri("urn:p"), Iri("urn:o")), RdfTriple(Iri("urn:s"), Iri("urn:p"), Literal("v", XSD.string))), found)
        }
    }
}
