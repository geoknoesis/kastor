package com.geoknoesis.kastor.rdf

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** URL loading releases its threads and sockets, and follows redirects within the overall deadline. */
class UrlLoadingResourceTest {
    private val cleanups = CopyOnWriteArrayList<() -> Unit>()

    @AfterEach
    fun cleanUp() {
        cleanups.reversed().forEach { runCatching(it) }
    }

    /**
     * A minimal HTTP/1.1 server on raw sockets that tracks how many client connections are still open: each
     * connection is counted until the client closes it. After [respond] writes (part of) a response the server sends
     * nothing more and never closes first, so a partial response stalls until the client gives up.
     */
    private inner class RawServer(private val respond: (Socket, head: String) -> Unit) {
        val open = AtomicInteger()
        val accepted = AtomicInteger()
        private val socket = ServerSocket(0, 200, InetAddress.getLoopbackAddress())
        private val workers: ExecutorService = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        val root = "http://127.0.0.1:${socket.localPort}"

        init {
            cleanups.add { socket.close(); workers.shutdownNow() }
            workers.execute {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: IOException) { break }
                    accepted.incrementAndGet()
                    open.incrementAndGet()
                    workers.execute { handle(client) }
                }
            }
        }

        private fun handle(client: Socket) {
            try {
                client.soTimeout = 30_000
                val input = client.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val b = input.read()
                    if (b < 0) return
                    head.append(b.toChar())
                }
                respond(client, head.toString())
                client.getOutputStream().flush()
                // Wait for the client to close the connection.
                while (input.read() >= 0) { /* discard */ }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally {
                runCatching { client.close() }
                open.decrementAndGet()
            }
        }

        fun awaitAllClosed(millis: Long): Boolean {
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
            while (open.get() > 0 && System.nanoTime() < end) Thread.sleep(20)
            return open.get() == 0
        }
    }

    private fun helperThreads(): Int = Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith(UrlLoadHelpers.THREAD_NAME_PREFIX) }

    /** Runs [loads] concurrent synchronous loads of [url] and returns the peak number of helper threads seen. */
    private fun burst(loads: Int, url: String, options: UrlLoadOptions, expect: Class<out Throwable>): Int {
        val peak = AtomicInteger()
        val sampling = AtomicBoolean(true)
        val sampler = Thread {
            while (sampling.get()) {
                peak.accumulateAndGet(helperThreads(), ::maxOf)
                Thread.sleep(5)
            }
        }.apply { isDaemon = true; start() }
        val callers = Executors.newFixedThreadPool(loads)
        cleanups.add { callers.shutdownNow() }
        val start = CountDownLatch(1)
        val failures = (1..loads).map {
            callers.submit<Throwable?> {
                start.await()
                runCatching { Rdf.parseFromUrl(url, RdfFormat.N_TRIPLES, options) }.exceptionOrNull()
            }
        }
        start.countDown()
        failures.forEach { future ->
            val failure = future.get(30, TimeUnit.SECONDS)
            assertTrue(expect.isInstance(failure), "expected ${expect.simpleName}, got $failure")
        }
        Thread.sleep(200)
        sampling.set(false)
        sampler.join()
        return peak.get()
    }

    /** Sends headers and the start of a 100000-byte body, then stalls; echoes `Connection: close` if [compliant]. */
    private fun stalledBody(compliant: Boolean) = RawServer { client, head ->
        val close = compliant && head.lineSequence().any { it.trim().equals("Connection: close", ignoreCase = true) }
        client.getOutputStream().write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/n-triples\r\nContent-Length: 100000\r\n" +
                (if (close) "Connection: close\r\n" else "") + "\r\n# ").toByteArray(),
        )
    }

    @Test
    fun `a burst of loads stalled on the response body stays within the helper bound and closes every socket`() {
        val server = stalledBody(compliant = true)
        val options = UrlLoadOptions(connectTimeoutMillis = 10_000, readTimeoutMillis = 10_000, totalTimeoutMillis = 700)
        val loads = UrlLoadHelpers.MAX_THREADS + 16

        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            val peak = burst(loads, "${server.root}/stall.nt", options, RdfLoadTimeoutException::class.java)
            assertTrue(peak <= UrlLoadHelpers.MAX_THREADS, "helper threads peaked at $peak")
            assertTrue(server.awaitAllClosed(3_000), "${server.open.get()} of ${server.accepted.get()} sockets still open")
        }
    }

    @Test
    fun `a server that ignores Connection close keeps at most the JDK keep-alive cleaner's sockets open`() {
        // The JDK drains an unfinished keep-alive body of up to 512 KiB in the background before closing its socket,
        // queueing at most 10 such sockets (http.KeepAlive.queuedConnections) and closing the rest at once.
        val server = stalledBody(compliant = false)
        val options = UrlLoadOptions(connectTimeoutMillis = 10_000, readTimeoutMillis = 10_000, totalTimeoutMillis = 700)
        val loads = UrlLoadHelpers.MAX_THREADS + 16

        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            val peak = burst(loads, "${server.root}/stall.nt", options, RdfLoadTimeoutException::class.java)
            assertTrue(peak <= UrlLoadHelpers.MAX_THREADS, "helper threads peaked at $peak")
            server.awaitAllClosed(1_500)
            assertTrue(server.open.get() <= 11, "${server.open.get()} of ${server.accepted.get()} sockets still open")
        }
    }

    @Test
    fun `a burst of loads stalled before the response headers stays within the helper bound and closes every socket`() {
        val server = RawServer { _, _ -> }
        val options = UrlLoadOptions(connectTimeoutMillis = 10_000, readTimeoutMillis = 10_000, totalTimeoutMillis = 700)
        val loads = UrlLoadHelpers.MAX_THREADS + 16

        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            val peak = burst(loads, "${server.root}/stall.nt", options, RdfLoadTimeoutException::class.java)
            assertTrue(peak <= UrlLoadHelpers.MAX_THREADS, "helper threads peaked at $peak")
            assertTrue(server.awaitAllClosed(3_000), "${server.open.get()} of ${server.accepted.get()} sockets still open")
        }
    }

    @Test
    fun `a completed synchronous load closes its connection instead of keeping it alive`() {
        val body = "<urn:s> <urn:p> <urn:o> .\n".toByteArray()
        val server = RawServer { client, _ ->
            client.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nContent-Type: application/n-triples\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body,
            )
        }
        val options = UrlLoadOptions(readTimeoutMillis = 3_000)
        repeat(3) { assertEquals(1, Rdf.parseFromUrl("${server.root}/ok.nt", RdfFormat.N_TRIPLES, options).size()) }
        assertTrue(server.awaitAllClosed(2_000), "${server.open.get()} of ${server.accepted.get()} sockets still open")
    }

    private fun httpServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): String {
        val threads = Executors.newFixedThreadPool(8)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                try { handler(exchange) } catch (_: IOException) { } catch (_: InterruptedException) { } finally { exchange.close() }
            }
            executor = threads
            start()
        }
        cleanups.add { server.stop(0); threads.shutdownNow() }
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun com.sun.net.httpserver.HttpExchange.redirect(location: String, status: Int = 302) {
        responseHeaders.add("Location", location)
        sendResponseHeaders(status, -1)
    }

    @Test
    fun `a redirect chain shares one overall deadline`() {
        // Each hop answers in 400 ms, well within the per-read timeout; five hops need 2 s against a 1 s budget.
        val root = httpServer { exchange ->
            val hop = exchange.requestURI.path.removePrefix("/r").toInt()
            Thread.sleep(400)
            if (hop < 5) exchange.redirect("/r${hop + 1}") else {
                val bytes = "<urn:s> <urn:p> <urn:o> .".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        val options = UrlLoadOptions(connectTimeoutMillis = 10_000, readTimeoutMillis = 10_000, totalTimeoutMillis = 1_000)

        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            val started = System.nanoTime()
            assertThrows(RdfLoadTimeoutException::class.java) { Rdf.parseFromUrl("$root/r0", RdfFormat.N_TRIPLES, options) }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(elapsed < 1_500, "redirect chain overran the 1 s deadline: $elapsed ms")
        }
    }

    @Test
    fun `redirects are followed manually, resolve relative locations and set the base IRI to the final URL`() {
        val root = httpServer { exchange ->
            when (exchange.requestURI.path) {
                "/a" -> exchange.redirect("/b", 301)
                "/b" -> exchange.redirect("c/doc.ttl", 307)
                "/c/doc.ttl" -> {
                    val bytes = "<#s> <urn:p> <> .".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/turtle")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
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
