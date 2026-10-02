package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InputStream
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Seventh audit of the SPARQL HTTP adapter: unbounded timeouts, failures around the exchange,
 * the read watchdog and the strictness of the result decoder as seen through the repository.
 */
class SparqlEndpointRobustnessTest {

    private val selectAll = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")
    private val askAny = SparqlAskQuery("ASK { ?s ?p ?o }")
    private val clear = UpdateQuery("CLEAR DEFAULT")
    private val url = "http://sparql.example/sparql"

    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** The four operations, by name. */
    private fun calls(repo: SparqlRepository): List<Pair<String, () -> Any>> = listOf(
        "select" to { repo.select(selectAll).count() },
        "stream" to { repo.withSelectRows(selectAll) { it.count() } },
        "ask" to { repo.ask(askAny) },
        "update" to { repo.update(clear) },
    )

    /** Answers every request with what a well-behaved endpoint would send; the bodies are recorded. */
    private class Answering(private val rows: Int = 2) : HttpTransport {
        val bodies = CopyOnWriteArrayList<TrackedBody>()

        override fun send(request: HttpRequest): CompletableFuture<HttpResponse<InputStream>> {
            val accept = request.headers().firstValue("Accept").isPresent
            val sent = request.bodyPublisher().map { it.contentLength() }.orElse(0)
            val text = when {
                !accept -> ""
                sent == "ASK { ?s ?p ?o }".length.toLong() -> "{\"head\":{},\"boolean\":true}"
                else -> rowsJson(rows)
            }
            val body = TrackedBody(text.toByteArray())
            bodies += body
            return CompletableFuture.completedFuture(FakeResponse(request, 200, body))
        }
    }

    private fun canned(bytes: ByteArray, type: String = "application/sparql-results+json") = HttpTransport { request ->
        CompletableFuture.completedFuture(FakeResponse(request, 200, bytes.inputStream(), mapOf("Content-Type" to type)))
    }

    private fun canned(text: String, type: String = "application/sparql-results+json") = canned(text.toByteArray(Charsets.UTF_8), type)

    // ------------------------------------------------------------------ unbounded timeouts

    private val huge = listOf(Duration.ofMillis(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE), ChronoUnit.FOREVER.duration)

    @Test
    fun `effectively infinite timeouts mean unbounded for every operation`() {
        for (h in huge) {
            val base = SparqlEndpointConfig(url)
            val configs = listOf(
                base.copy(readTimeout = h),
                base.copy(requestTimeout = h),
                base.copy(streamingRequestTimeout = h),
                base.copy(readTimeout = h, requestTimeout = null),
                base.copy(readTimeout = h, requestTimeout = h, streamingRequestTimeout = h),
            )
            for (config in configs) {
                val transport = Answering()
                SparqlRepository(config, transport).use { repo ->
                    assertEquals(2, repo.select(selectAll).count(), "$config")
                    assertEquals(2, repo.withSelectRows(selectAll) { it.count() }, "$config")
                    assertEquals(2, repo.withSelectRows(selectAll, emptyMap(), h) { it.count() }, "$config")
                    assertTrue(repo.ask(askAny), "$config")
                    repo.update(clear)
                }
                assertEquals(5, transport.bodies.size)
                assertTrue(transport.bodies.all { it.closed }, "a response body was left open with $config")
            }
            // A per-call timeout of that size with ordinary configured timeouts.
            val transport = Answering()
            SparqlRepository(base.copy(streamingRequestTimeout = Duration.ofMinutes(1)), transport).use { repo ->
                assertEquals(2, repo.withSelectRows(selectAll, emptyMap(), h) { it.count() })
            }
            assertTrue(transport.bodies.single().closed)
        }
    }

    @Test
    fun `an effectively infinite connect timeout works with the real HTTP client`() {
        LocalEndpoint { exchange, request ->
            if (request.contentType == "application/sparql-update") exchange.respond(200, "") else exchange.respond(200, rowsJson(3))
        }.use { endpoint ->
            for (h in huge) {
                SparqlRepository(SparqlEndpointConfig(endpoint.url, connectTimeout = h, readTimeout = h, requestTimeout = h)).use { repo ->
                    assertEquals(3, repo.select(selectAll).count(), "$h")
                    repo.update(clear)
                }
            }
        }
    }

    @Test
    fun `timeouts must be positive wherever they are given`() {
        for (bad in listOf(Duration.ZERO, Duration.ofMillis(-1), Duration.ofSeconds(Long.MIN_VALUE))) {
            assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig(url, connectTimeout = bad) }
            assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig(url, readTimeout = bad) }
            assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig(url, requestTimeout = bad) }
            assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig(url, streamingRequestTimeout = bad) }
            SparqlRepository(SparqlEndpointConfig(url), Answering()).use { repo ->
                assertThrows(IllegalArgumentException::class.java) { repo.withSelectRows(selectAll, emptyMap(), bad) { it.count() } }
            }
        }
        for (h in huge) {
            val config = SparqlEndpointConfig(url, connectTimeout = h, readTimeout = h, requestTimeout = h, streamingRequestTimeout = h)
            assertTrue(config.toString().contains("readTimeout="), "toString must not overflow either")
        }
        val options = mapOf("location" to url, "readTimeoutMillis" to "${Long.MAX_VALUE}", "requestTimeoutMillis" to "${Long.MAX_VALUE}")
        assertEquals(Duration.ofMillis(Long.MAX_VALUE), SparqlEndpointConfig.fromOptions(options).readTimeout)
        assertThrows(IllegalArgumentException::class.java) { SparqlEndpointConfig.fromOptions(options + ("readTimeoutMillis" to "0")) }
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a huge read timeout does not hide a short request deadline`() {
        val limit = Duration.ofMillis(400)
        val transport = HttpTransport { request ->
            CompletableFuture.completedFuture(FakeResponse(request, 200, BlockingBody(STALL_MILLIS)))
        }
        for (h in huge) {
            SparqlRepository(SparqlEndpointConfig(url, readTimeout = h, requestTimeout = limit), transport).use { repo ->
                for ((name, call) in calls(repo).filter { it.first != "stream" }) {
                    val start = System.nanoTime()
                    val e = assertThrows(RdfQueryException::class.java, { call() }, name)
                    assertTrue(e.message!!.contains("400 ms deadline"), "$name: ${e.message}")
                    assertEndedAtLimit(limit, start, name)
                }
            }
        }
    }

    // ------------------------------------------------------------------ failures around the exchange

    @Test
    fun `a response is closed when anything fails after it was received`() {
        val bodies = CopyOnWriteArrayList<TrackedBody>()
        val transport = HttpTransport { request ->
            val body = TrackedBody(rowsJson(1).toByteArray()).also { bodies += it }
            CompletableFuture.completedFuture(FakeResponse(request, 200, body) { throw IllegalStateException("headers unavailable") })
        }
        // strictContentType = false: UPDATE and the lenient mode read no header before the body.
        for (config in listOf(SparqlEndpointConfig(url), SparqlEndpointConfig(url, strictContentType = false))) {
            bodies.clear()
            SparqlRepository(config, transport).use { repo ->
                for ((name, call) in calls(repo)) {
                    // Reading the headers fails where they are read; the lenient mode gets a body it may not understand.
                    val outcome = runCatching { call() }
                    if (config.strictContentType && name != "update") {
                        assertEquals("SPARQL request failed: headers unavailable", outcome.exceptionOrNull()?.message, name)
                        assertTrue(outcome.exceptionOrNull() is RdfQueryException, name)
                    } else {
                        assertTrue(outcome.isSuccess || outcome.exceptionOrNull() is RdfQueryException, "$name: ${outcome.exceptionOrNull()}")
                    }
                }
            }
            assertEquals(4, bodies.size)
            assertTrue(bodies.all { it.closed }, "a response body was left open (strictContentType=${config.strictContentType})")
        }
    }

    @Test
    fun `a synchronous transport failure is reported like any other request failure`() {
        val refusing = HttpTransport { throw IllegalArgumentException("invalid header value") }
        SparqlRepository(SparqlEndpointConfig(url), refusing).use { repo ->
            for ((name, call) in calls(repo)) {
                val e = assertThrows(RdfQueryException::class.java, { call() }, name)
                assertEquals("SPARQL request failed: invalid header value", e.message, name)
                assertTrue(e.cause is IllegalArgumentException, name)
                assertNotNull(e.query, name)
            }
        }
        val dying = HttpTransport { throw StackOverflowError("simulated") }
        SparqlRepository(SparqlEndpointConfig(url), dying).use { repo ->
            for ((name, call) in calls(repo)) assertThrows(StackOverflowError::class.java, { call() }, name)
        }
    }

    @Test
    fun `header names and values are validated when the configuration is built`() {
        val invalidValues = listOf("a\u0000b", "a\u0001b", "a\u001Fb", "a\u007Fb", "a\rb", "a\nb", "a\u0100b", "\u20AC", " leading", "trailing ", "\ttab", "tab\t")
        for (value in invalidValues) {
            val e = assertThrows(IllegalArgumentException::class.java, { SparqlEndpointConfig(url, headers = mapOf("X-Key" to value)) }, value)
            assertTrue(e.message!!.contains("X-Key"), e.message)
            assertFalse(e.message!!.contains(value), "the message must not repeat the value: ${e.message}")
        }
        for (name in listOf("", "X Key", "X:Key", "X\u0000", "X(1)", "Cl\u00E9", "X\tKey", "X/Key", "\"X\"")) {
            assertThrows(IllegalArgumentException::class.java, { SparqlEndpointConfig(url, headers = mapOf(name to "v")) }, name)
        }
        val accepted = mapOf("X-Empty" to "", "X-Inner" to "a b\tc", "X-Latin1" to "caf\u00E9", "X-Token.!#$%&'*+^_`|~9" to "~!@#$%^&*()_+{}|:\"<>?")
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val transport = HttpTransport { request ->
            sent += request
            CompletableFuture.completedFuture(FakeResponse(request, 200, rowsJson(1).byteInputStream()))
        }
        // Whatever the configuration accepts, the JDK request builder accepts too.
        SparqlRepository(SparqlEndpointConfig(url, headers = accepted), transport).use { repo ->
            assertEquals(1, repo.select(selectAll).count())
        }
        for ((name, value) in accepted) assertEquals(value, sent.single().headers().firstValue(name).orElse(null), name)
        assertThrows(IllegalArgumentException::class.java) {
            SparqlEndpointConfig.fromOptions(mapOf("location" to url, "header.X-Key" to "a\u0007b"))
        }
    }

    // ------------------------------------------------------------------ interrupt, cancellation, failed exchanges

    @Test
    fun `an interrupt while waiting for the response cancels the exchange and keeps the interrupt flag`() {
        val pending = CopyOnWriteArrayList<CompletableFuture<HttpResponse<InputStream>>>()
        val transport = HttpTransport { CompletableFuture<HttpResponse<InputStream>>().also { pending += it } }
        SparqlRepository(SparqlEndpointConfig(url), transport).use { repo ->
            for ((name, call) in calls(repo)) {
                Thread.currentThread().interrupt()
                try {
                    val e = assertThrows(RdfQueryException::class.java, { call() }, name)
                    assertEquals("SPARQL request was interrupted", e.message, name)
                    assertTrue(e.cause is InterruptedException, name)
                    assertTrue(Thread.currentThread().isInterrupted, "$name: the interrupt flag was lost")
                } finally {
                    Thread.interrupted()
                }
            }
        }
        assertEquals(4, pending.size)
        assertTrue(pending.all { it.isCancelled }, "an interrupted exchange was not cancelled")
    }

    @Test
    fun `a cancelled exchange is reported as cancelled`() {
        val transport = HttpTransport { CompletableFuture<HttpResponse<InputStream>>().apply { cancel(true) } }
        SparqlRepository(SparqlEndpointConfig(url), transport).use { repo ->
            for ((name, call) in calls(repo)) {
                val e = assertThrows(RdfQueryException::class.java, { call() }, name)
                assertEquals("SPARQL request was cancelled", e.message, name)
            }
        }
    }

    @Test
    fun `an exchange that fails is reported with its cause whatever the cause is`() {
        fun failing(cause: Throwable) = HttpTransport { CompletableFuture.failedFuture(cause) }
        val causes = listOf(
            IllegalStateException("client is shut down") to "SPARQL request failed: client is shut down",
            NullPointerException() to "SPARQL request failed: NullPointerException",
            java.io.IOException("connection reset") to "SPARQL request failed: connection reset",
            HttpConnectTimeoutException("connect timed out") to "SPARQL request failed: connect timed out after 30000 ms",
        )
        for ((cause, message) in causes) {
            SparqlRepository(SparqlEndpointConfig(url), failing(cause)).use { repo ->
                for ((name, call) in calls(repo)) {
                    val e = assertThrows(RdfQueryException::class.java, { call() }, name)
                    assertEquals(message, e.message, name)
                    assertSame(cause, e.cause, name)
                }
            }
        }
        // An Error is never wrapped.
        val error = OutOfMemoryError("simulated")
        SparqlRepository(SparqlEndpointConfig(url), failing(error)).use { repo ->
            for ((name, call) in calls(repo)) assertSame(error, assertThrows(OutOfMemoryError::class.java, { call() }, name), name)
        }
        // An unbounded connect timeout is described, not converted (which would overflow).
        SparqlRepository(SparqlEndpointConfig(url, connectTimeout = Duration.ofSeconds(Long.MAX_VALUE)), failing(HttpConnectTimeoutException("x"))).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(e.message!!.startsWith("SPARQL request failed: connect timed out"), e.message)
        }
    }

    @Test
    fun `a response that arrives just as the header wait ends is closed`() {
        val body = TrackedBody(rowsJson(1).toByteArray())
        // Completed, but its timed get reports a timeout: the race between the wait ending and the response arriving.
        class Late(response: HttpResponse<InputStream>) : CompletableFuture<HttpResponse<InputStream>>() {
            init {
                complete(response)
            }

            override fun get(timeout: Long, unit: TimeUnit): HttpResponse<InputStream> = throw TimeoutException()
        }
        val transport = HttpTransport { request -> Late(FakeResponse(request, 200, body)) }
        SparqlRepository(SparqlEndpointConfig(url, requestTimeout = Duration.ofSeconds(7)), transport).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(e.message!!.contains("exceeded its 7000 ms deadline waiting for the response"), e.message)
        }
        assertTrue(body.closed, "the late response was left open")
    }

    // ------------------------------------------------------------------ read watchdog

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a response stream whose close fails does not stop later read timeouts`() {
        val limit = Duration.ofMillis(300)
        var failClose = true
        val transport = HttpTransport { request ->
            val body = if (failClose) BlockingBody(STALL_MILLIS) { throw IllegalStateException("close failed") } else BlockingBody(SLOW_HOST_SLACK_MILLIS * 2)
            CompletableFuture.completedFuture(FakeResponse(request, 200, body))
        }
        SparqlRepository(SparqlEndpointConfig(url, readTimeout = limit, requestTimeout = null), transport).use { repo ->
            // The stream is closed by the watchdog (which unblocks the read) and its close() throws there.
            val first = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(first.message!!.contains("read timed out after 300 ms"), first.message)
            failClose = false
            for ((name, call) in calls(repo)) {
                val start = System.nanoTime()
                val e = assertThrows(RdfQueryException::class.java, { call() }, name)
                assertTrue(e.message!!.contains("read timed out after 300 ms"), "$name: the read timeout was not enforced: ${e.message}")
                assertEndedAtLimit(limit, start, name)
            }
        }
    }

    /** A watched stream whose pending read expires [arm]ed milliseconds later, running [onExpire] in the watchdog thread. */
    private class Due(private val onExpire: () -> Unit = {}) : ReadWatchdog.Watched {
        val expired = CountDownLatch(1)
        @Volatile private var deadline = Long.MAX_VALUE

        fun arm(watchdog: ReadWatchdog, millis: Long) {
            val at = System.nanoTime() + millis * 1_000_000
            deadline = at
            watchdog.published(at)
        }

        override fun expireIfDue(now: Long): Long {
            val at = deadline
            if (at == Long.MAX_VALUE || now < at) return at
            deadline = Long.MAX_VALUE
            expired.countDown()
            onExpire()
            return Long.MAX_VALUE
        }
    }

    private fun assertExpires(stream: Due, what: String) =
        assertTrue(stream.expired.await(SLOW_HOST_SLACK_MILLIS, TimeUnit.MILLISECONDS), "$what: the deadline was not enforced")

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `the watchdog outlives streams that fail and is replaced when its thread ends`() {
        // Whatever a stream throws is reported, and the watch over the others goes on in the same thread.
        val reported = CopyOnWriteArrayList<Throwable>()
        val watchdog = ReadWatchdog { reported.add(it) }
        val failing = Due { throw IllegalStateException("close failed") }
        val fatal = Due { throw StackOverflowError("simulated") }
        val healthy = Due()
        listOf(failing, fatal, healthy).forEach(watchdog::register)
        failing.arm(watchdog, 20)
        fatal.arm(watchdog, 40)
        assertExpires(failing, "failing")
        assertExpires(fatal, "fatal")
        healthy.arm(watchdog, 60)
        assertExpires(healthy, "after two failures")
        assertEquals(listOf("close failed", "simulated"), reported.map { it.message })
        assertEquals(1, watchdog.threadsStarted, "the thread must survive a failing stream")

        // A thread that ends all the same (here: reporting the failure fails too) is replaced.
        val unreportable = ReadWatchdog { throw IllegalStateException("the report failed") }
        val first = Due { throw IllegalStateException("close failed") }
        unreportable.register(first)
        first.arm(unreportable, 20)
        assertExpires(first, "first")
        repeat(3) { round ->
            val later = Due()
            unreportable.register(later)
            later.arm(unreportable, 40)
            assertExpires(later, "after the thread ended (round $round)")
            unreportable.unregister(later)
        }
        assertEquals(2, unreportable.threadsStarted, "one replacement for the one thread that ended")
    }

    // ------------------------------------------------------------------ decoder strictness, through the repository

    @Test
    fun `a leading byte order mark is accepted for SELECT and ASK`() {
        SparqlRepository(SparqlEndpointConfig(url), canned(bom + rowsJson(2).toByteArray())).use { repo ->
            assertEquals(2, repo.select(selectAll).count())
            assertEquals(2, repo.withSelectRows(selectAll) { it.count() })
        }
        SparqlRepository(SparqlEndpointConfig(url), canned(bom + "{\"head\":{},\"boolean\":true}".toByteArray())).use { repo ->
            assertTrue(repo.ask(askAny))
        }
        SparqlRepository(SparqlEndpointConfig(url), canned(bom + " false\r\n".toByteArray(), "text/plain")).use { repo ->
            assertFalse(repo.ask(askAny))
        }
        // Only at the very start.
        SparqlRepository(SparqlEndpointConfig(url), canned(bom + bom + rowsJson(2).toByteArray())).use { repo ->
            assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
        }
        SparqlRepository(SparqlEndpointConfig(url), canned("{\"boolean\":\uFEFFtrue}")).use { repo ->
            assertThrows(RdfQueryException::class.java) { repo.ask(askAny) }
        }
    }

    @Test
    fun `only JSON white space separates tokens`() {
        for (space in listOf("\u00A0", "\u2028", "\u2029", "\u000B", "\u000C", "\u0085", "\u3000", "\u1680", "\u001C")) {
            val code = "U+%04X".format(space[0].code)
            for (body in listOf(
                rowsJson(1).replace("\"results\":", "\"results\":$space"),
                rowsJson(1).replace("{\"x\":", "{$space\"x\":"),
                space + rowsJson(1),
                rowsJson(1) + space,
            )) {
                SparqlRepository(SparqlEndpointConfig(url), canned(body)).use { repo ->
                    assertThrows(RdfQueryException::class.java, { repo.select(selectAll) }, "$code in $body")
                }
            }
            for (body in listOf("{$space\"boolean\":true}", "{\"boolean\":true}$space", "${space}true", "true$space")) {
                SparqlRepository(SparqlEndpointConfig(url), canned(body, "text/plain")).use { repo ->
                    assertThrows(RdfQueryException::class.java, { repo.ask(askAny) }, "$code in ASK $body")
                }
            }
        }
        val spaced = " \t\r\n" + rowsJson(1).replace(":", " :\t\r\n").replace(",", "\n,\t") + " \t\r\n"
        SparqlRepository(SparqlEndpointConfig(url), canned(spaced)).use { repo -> assertEquals(1, repo.select(selectAll).count()) }
        SparqlRepository(SparqlEndpointConfig(url), canned(" \t\r\n{ \"boolean\"\t:\r\ntrue } \n")).use { repo -> assertTrue(repo.ask(askAny)) }
        SparqlRepository(SparqlEndpointConfig(url), canned("\r\n TRUE \t", "text/plain")).use { repo -> assertTrue(repo.ask(askAny)) }
    }

    @Test
    fun `malformed UTF-8 is reported instead of being replaced`() {
        val row = rowsJson(1) { "caf\u00E9" }.toByteArray(Charsets.UTF_8)
        val at = row.indexOfFirst { it == 0xC3.toByte() }
        val invalid = listOf(
            // a lead byte without its continuation, an overlong form, a stray continuation, a lone surrogate, a byte that never occurs
            row.copyOf().also { it[at + 1] = '('.code.toByte() },
            row.copyOf().also { it[at] = 0xC0.toByte(); it[at + 1] = 0xAF.toByte() },
            row.copyOf().also { it[at] = 0x80.toByte() },
            row.copyOfRange(0, at) + byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) + row.copyOfRange(at + 2, row.size),
            row.copyOf().also { it[at] = 0xFF.toByte() },
            row.copyOfRange(0, at + 1),
        )
        for (bytes in invalid) {
            SparqlRepository(SparqlEndpointConfig(url), canned(bytes)).use { repo ->
                for (call in listOf<() -> Any>({ repo.select(selectAll).count() }, { repo.withSelectRows(selectAll) { it.count() } })) {
                    val e = assertThrows(RdfQueryException::class.java) { call() }
                    assertTrue(e.message!!.contains("not valid UTF-8"), e.message)
                }
            }
        }
        val ask = "{\"note\":\"caf\u00E9\",\"boolean\":true}".toByteArray(Charsets.UTF_8)
        SparqlRepository(SparqlEndpointConfig(url), canned(ask)).use { repo -> assertTrue(repo.ask(askAny)) }
        val broken = ask.copyOf().also { it[it.indexOfFirst { b -> b == 0xC3.toByte() } + 1] = '('.code.toByte() }
        SparqlRepository(SparqlEndpointConfig(url), canned(broken)).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.ask(askAny) }
            assertTrue(e.message!!.contains("not valid UTF-8"), e.message)
        }
    }

    @Test
    fun `ASK responses are decoded strictly and errors never quote the body`() {
        fun ask(body: String, type: String = "application/sparql-results+json"): Boolean =
            SparqlRepository(SparqlEndpointConfig(url), canned(body, type)).use { it.ask(askAny) }

        assertTrue(ask("{\"head\":{\"link\":[\"urn:l\"]},\"boolean\":true}"))
        assertFalse(ask("{\"boolean\":false,\"head\":{}}"))
        assertFalse(ask("{\"boolean\" : false , \"extra\" : [1,{\"boolean\":true}] }"))
        assertTrue(ask("true", "text/plain"))
        assertFalse(ask("False\n", "text/plain"))

        val secret = "SECRET-session-token-0123456789"
        val rejected = listOf(
            "",
            "   ",
            "{}",
            "{\"head\":{}}",
            "{\"boolean\":$secret}",
            "{\"boolean\":\"$secret\"}",
            "{\"boolean\":null}",
            "{\"boolean\":1}",
            "{\"boolean\":[true]}",
            "{\"boolean\":true",
            "{\"boolean\":true}}",
            "{\"boolean\":true} $secret",
            "{\"boolean\":true,}",
            "{\"boolean\":tru}",
            "[true]",
            "\"true\"",
            "truee",
            "true false",
            "yes",
            "<html><body>$secret</body></html>",
            "{\"head\":{\"$secret\":\"$secret\"},\"boolean\":maybe-$secret}",
            "{\"$secret\" \"boolean\":true}",
        )
        for (body in rejected) {
            val e = assertThrows(RdfQueryException::class.java, { ask(body, "text/plain") }, body)
            assertFalse(e.message!!.contains("SECRET"), "the error quotes the response body: ${e.message}")
            assertTrue(e.message!!.length < 300, e.message)
        }
    }

    @Test
    fun `RDF 1_2 result terms are rejected with an explicit message`() {
        val triple = "{\"head\":{\"vars\":[\"t\"]},\"results\":{\"bindings\":[{\"t\":{\"type\":\"triple\",\"value\":{" +
            "\"subject\":{\"type\":\"uri\",\"value\":\"urn:s\"},\"predicate\":{\"type\":\"uri\",\"value\":\"urn:p\"}," +
            "\"object\":{\"type\":\"literal\",\"value\":\"o\"}}}}]}}"
        val tripleFirst = triple.replace("\"type\":\"triple\",\"value\":{", "\"value\":{").replace("}}}}]}}", "}},\"type\":\"triple\"}}]}}")
        for (body in listOf(triple, tripleFirst)) {
            SparqlRepository(SparqlEndpointConfig(url), canned(body)).use { repo ->
                val e = assertThrows(RdfQueryException::class.java, { repo.select(selectAll) }, body)
                assertTrue(e.message!!.contains("RDF 1.2 triple term"), e.message)
                assertTrue(e.message!!.contains("not supported"), e.message)
            }
        }
        for (member in listOf("\"its:dir\":\"rtl\"", "\"direction\":\"ltr\"")) {
            val body = "{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[{\"x\":{\"type\":\"literal\",\"value\":\"abc\",\"xml:lang\":\"ar\",$member}}]}}"
            SparqlRepository(SparqlEndpointConfig(url), canned(body)).use { repo ->
                val e = assertThrows(RdfQueryException::class.java, { repo.select(selectAll) }, body)
                assertTrue(e.message!!.contains("base direction"), e.message)
                assertTrue(e.message!!.contains("RDF 1.2"), e.message)
                assertTrue(e.message!!.contains("not supported"), e.message)
            }
        }
        // An unknown binding type is named, but only as a short, printable excerpt.
        val odd = "x\u0000y".repeat(500)
        val unknown = "{\"results\":{\"bindings\":[{\"x\":{\"type\":\"${odd.replace("\u0000", "\\u0000")}\",\"value\":\"v\"}}]}}"
        SparqlRepository(SparqlEndpointConfig(url), canned(unknown)).use { repo ->
            val e = assertThrows(RdfQueryException::class.java) { repo.select(selectAll) }
            assertTrue(e.message!!.contains("Unsupported SPARQL result binding type"), e.message)
            assertTrue(e.message!!.length < 300, "message of ${e.message!!.length} characters")
            assertFalse(e.message!!.contains('\u0000'))
        }
    }
}
