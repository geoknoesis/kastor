package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * URL loading releases its threads and connections, and follows redirects within the overall deadline.
 *
 * What a burst of loads does to the helper threads and to the connections is observed on the loader under test: a
 * helper pool of its own, connections that are counted when the load releases them. Nothing is sampled across the
 * JVM, and the bursts open no sockets at all.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UrlLoadingResourceTest {
    private val http = LoopbackHttp()
    private val cleanups = CopyOnWriteArrayList<() -> Unit>()

    @AfterEach
    fun cleanUp() {
        cleanups.reversed().forEach { runCatching(it) }
        cleanups.clear()
    }

    @AfterAll
    fun stopServer() {
        http.close()
    }

    private val helperBound = 4

    /** More loads than helpers: what the assertions need, and no more. */
    private val burstLoads = helperBound + 3

    /** A stalled socket operation of the JDK: it ends with its timeout, whatever interrupts it. */
    private fun stall(timeoutMillis: Int, until: CountDownLatch): Nothing {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
        while (true) {
            try {
                if (until.await(end - System.nanoTime(), TimeUnit.NANOSECONDS)) throw IOException("connection reset")
                throw SocketTimeoutException("Read timed out")
            } catch (_: InterruptedException) {
            }
        }
    }

    /**
     * Runs [burstLoads] concurrent loads against connections made by [connect], each expected to time out, and
     * checks that the helper threads stay within the bound and that every connection is released exactly once.
     */
    private fun burst(connect: (java.net.URL, CountDownLatch) -> FakeHttpConnection) {
        val pool = UrlLoadHelperPool(helperBound).also { created -> cleanups.add { created.close() } }
        val neverAnswers = CountDownLatch(1)
        cleanups.add { neverAnswers.countDown() }
        val opened = CopyOnWriteArrayList<FakeHttpConnection>()
        val released = CountDownLatch(burstLoads)
        val runtime = UrlLoadRuntime(
            helpers = pool,
            open = { uri -> connect(uri.toURL(), neverAnswers).also(opened::add) },
            onRelease = { released.countDown() },
        )
        val options = UrlLoadOptions(connectTimeoutMillis = 10_000, readTimeoutMillis = 10_000, totalTimeoutMillis = 700)
        val callers = Executors.newFixedThreadPool(burstLoads)
        cleanups.add { callers.shutdownNow() }
        val start = CountDownLatch(1)
        val failures = (1..burstLoads).map { n ->
            callers.submit<Throwable?> {
                start.await()
                runCatching {
                    openRdfUrlStream("http://stalled.example/$n.nt", RdfFormat.N_TRIPLES, options, runtime).read { it.readBytes() }
                }.exceptionOrNull()
            }
        }
        start.countDown()
        failures.forEach { future ->
            val failure = future.get(60, TimeUnit.SECONDS)
            assertTrue(failure is RdfLoadTimeoutException, "expected a timeout, got $failure")
        }
        // An abandoned request or read keeps its helper until its (capped) socket timeout; then it lets go.
        assertTrue(released.await(60, TimeUnit.SECONDS), "${released.count} of $burstLoads connections were never released")
        assertTrue(pool.awaitIdle(60_000), "${pool.busy} helpers still busy")
        assertEquals(burstLoads, opened.size)
        opened.forEach { assertEquals(1, it.disconnects.get(), "disconnects of ${it.url}") }
        assertTrue(pool.largestThreadCount <= helperBound, "helper threads peaked at ${pool.largestThreadCount}")
        assertEquals(0, pool.helpersReplaced.get())
    }

    @Test
    fun `a burst of loads stalled on the response body stays within the helper bound and releases every connection`() {
        burst { url, neverAnswers ->
            FakeHttpConnection(url, body = { connection ->
                object : InputStream() {
                    override fun read(): Int = stall(connection.readTimeout, neverAnswers)
                    override fun read(b: ByteArray, off: Int, len: Int): Int = stall(connection.readTimeout, neverAnswers)
                }
            })
        }
    }

    @Test
    fun `a burst of loads stalled before the response headers stays within the helper bound and releases every connection`() {
        burst { url, neverAnswers -> FakeHttpConnection(url, status = { connection -> stall(connection.readTimeout, neverAnswers) }) }
    }

    /**
     * A minimal HTTP/1.1 server on raw sockets that tells when a client connection is closed: each connection
     * releases a permit of [closed] when the client closes it. After [respond] writes (part of) a response the
     * server sends nothing more and never closes first, so a partial response stalls until the client gives up.
     */
    private inner class RawServer(private val respond: (Socket, head: String) -> Unit) {
        val accepted = AtomicInteger()
        val closed = Semaphore(0)
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val workers: ExecutorService = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        val root = "http://127.0.0.1:${socket.localPort}"

        private val clients = CopyOnWriteArrayList<Socket>()

        init {
            cleanups.add {
                socket.close()
                clients.forEach { runCatching { it.close() } }
                workers.shutdownNow()
            }
            workers.execute {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: IOException) { break }
                    accepted.incrementAndGet()
                    clients.add(client)
                    workers.execute { handle(client) }
                }
            }
        }

        private fun handle(client: Socket) {
            var closedByClient = false
            try {
                client.soTimeout = 120_000
                val input = client.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val b = input.read()
                    if (b < 0) { closedByClient = true; return }
                    head.append(b.toChar())
                }
                respond(client, head.toString())
                client.getOutputStream().flush()
                // Wait for the client to close the connection.
                while (input.read() >= 0) { /* discard */ }
                closedByClient = true
            } catch (_: SocketTimeoutException) {
                // The client kept the connection open.
            } catch (_: IOException) {
                // A reset is the client closing as well (unless the test is over and closed the socket itself).
                closedByClient = !socket.isClosed
            } finally {
                runCatching { client.close() }
                if (closedByClient) closed.release()
            }
        }
    }

    /** Sends headers and the start of a 100000-byte body, then stalls; echoes `Connection: close` if [compliant]. */
    private fun stalledBody(compliant: Boolean) = RawServer { client, head ->
        val close = compliant && head.lineSequence().any { it.trim().equals("Connection: close", ignoreCase = true) }
        client.getOutputStream().write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/n-triples\r\nContent-Length: 100000\r\n" +
                (if (close) "Connection: close\r\n" else "") + "\r\n# ").toByteArray(),
        )
    }

    /** Loads [url] [loads] times, one after the other, through a loader that counts the connections it releases. */
    private fun timedOutLoads(loads: Int, url: String): AtomicInteger {
        val released = AtomicInteger()
        val pool = UrlLoadHelperPool(2).also { created -> cleanups.add { created.close() } }
        val runtime = UrlLoadRuntime(helpers = pool, onRelease = { released.incrementAndGet() })
        val options = UrlLoadOptions(connectTimeoutMillis = 10_000, readTimeoutMillis = 10_000, totalTimeoutMillis = 500)
        repeat(loads) {
            assertThrows(RdfLoadTimeoutException::class.java) {
                openRdfUrlStream(url, RdfFormat.N_TRIPLES, options, runtime).read { it.readBytes() }
            }
        }
        // The abandoned reads have returned (with their capped socket timeout) when no helper is busy any more.
        assertTrue(pool.awaitIdle(60_000), "${pool.busy} helpers still busy")
        return released
    }

    @Test
    fun `loads stalled on the response body of a real server close their sockets`() {
        val server = stalledBody(compliant = true)
        val released = timedOutLoads(3, "${server.root}/stall.nt")
        assertEquals(3, released.get(), "every connection the loader opened was released")
        assertTrue(server.closed.tryAcquire(3, 60, TimeUnit.SECONDS), "sockets closed: ${server.closed.availablePermits()} of ${server.accepted.get()}")
        assertEquals(3, server.accepted.get())
    }

    @Test
    fun `a server that ignores Connection close has every connection disconnected all the same`() {
        // What the JDK then does with the socket of an unfinished keep-alive body (it drains it in the background
        // before closing) is the JDK's business; the loader disconnects every connection it opened.
        val server = stalledBody(compliant = false)
        val released = timedOutLoads(3, "${server.root}/stall.nt")
        assertEquals(3, released.get(), "every connection the loader opened was released")
        assertEquals(3, server.accepted.get())
    }

    @Test
    fun `a completed synchronous load closes its connection instead of keeping it alive`() {
        val body = "<urn:s> <urn:p> <urn:o> .\n".toByteArray()
        val server = RawServer { client, _ ->
            client.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nContent-Type: application/n-triples\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body,
            )
        }
        val options = UrlLoadOptions(readTimeoutMillis = 30_000)
        repeat(2) { assertEquals(1, Rdf.parseFromUrl("${server.root}/ok.nt", RdfFormat.N_TRIPLES, options).size()) }
        assertTrue(server.closed.tryAcquire(2, 60, TimeUnit.SECONDS), "sockets closed: ${server.closed.availablePermits()} of ${server.accepted.get()}")
    }

    @Test
    fun `a redirect chain shares one overall deadline`() {
        // The clock of the load is the test's: every hop takes 40 s of it, well within the per-read timeout; five
        // hops need 200 s against a budget of 100 s. No hop waits for anything real.
        val clock = AtomicLong(0)
        val root = http.serve { exchange ->
            val hop = exchange.requestURI.path.removePrefix("/r").toInt()
            clock.addAndGet(TimeUnit.SECONDS.toNanos(40))
            if (hop < 5) exchange.redirect("/r${hop + 1}") else exchange.reply(200, "<urn:s> <urn:p> <urn:o> .", "application/n-triples")
        }
        val pool = UrlLoadHelperPool(2).also { created -> cleanups.add { created.close() } }
        val runtime = UrlLoadRuntime(helpers = pool, nanoTime = clock::get)
        val options = UrlLoadOptions(connectTimeoutMillis = 60_000, readTimeoutMillis = 60_000, totalTimeoutMillis = 100_000)

        val error = assertThrows(RdfLoadTimeoutException::class.java) { openRdfUrlStream("$root/r0", RdfFormat.N_TRIPLES, options, runtime) }
        assertEquals(100_000, error.timeoutMillis)
        // The third hop answered after 120 s of the 100 s budget: the load ends there, the fourth is never asked.
        assertEquals(listOf("/r0", "/r1", "/r2"), http.requests.toList())

        // With a budget for all six requests the chain is followed to its end.
        clock.set(0)
        val enough = options.copy(totalTimeoutMillis = 600_000)
        http.serve { exchange ->
            val hop = exchange.requestURI.path.removePrefix("/r").toInt()
            clock.addAndGet(TimeUnit.SECONDS.toNanos(40))
            if (hop < 5) exchange.redirect("/r${hop + 1}") else exchange.reply(200, "<urn:s> <urn:p> <urn:o> .", "application/n-triples")
        }
        assertTrue(openRdfUrlStream("$root/r0", RdfFormat.N_TRIPLES, enough, runtime).read { it.readBytes() }.isNotEmpty())
        assertEquals((0..5).map { "/r$it" }, http.requests.toList())
    }

    @Test
    fun `redirects are followed manually, resolve relative locations and set the base IRI to the final URL`() {
        val root = http.serve { exchange ->
            when (exchange.requestURI.path) {
                "/a" -> exchange.redirect("/b", 301)
                "/b" -> exchange.redirect("c/doc.ttl", 307)
                "/c/doc.ttl" -> exchange.reply(200, "<#s> <urn:p> <> .")
                "/loop" -> exchange.redirect("/loop")
                "/file" -> exchange.redirect("file:///etc/passwd")
                "/nowhere" -> exchange.sendResponseHeaders(302, -1)
            }
        }

        val final = "$root/c/doc.ttl"
        assertEquals(listOf(RdfTriple(Iri("$final#s"), Iri("urn:p"), Iri(final))), Rdf.parseFromUrl("$root/a").getTriples())

        val loop = assertThrows(RdfHttpStatusException::class.java) { Rdf.parseFromUrl("$root/loop") }
        assertEquals(302, loop.statusCode)
        assertTrue(loop.message!!.contains("redirect"), loop.message)

        val file = assertThrows(RdfHttpStatusException::class.java) { Rdf.parseFromUrl("$root/file") }
        assertEquals(302, file.statusCode)
        assertTrue(file.message!!.contains("file:///etc/passwd"), file.message)

        assertEquals(302, assertThrows(RdfHttpStatusException::class.java) { Rdf.parseFromUrl("$root/nowhere") }.statusCode)
    }
}
