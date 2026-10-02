package com.geoknoesis.kastor.rdf

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The helper threads of URL loading: a stream uses one long-lived helper instead of one task per read, a load that
 * finds every helper busy waits for one within its deadline instead of running unbounded on the calling thread, and
 * an abandoned read still releases its connection once it returns.
 */
class UrlLoadingHelperTest {
    private val cleanups = CopyOnWriteArrayList<() -> Unit>()

    @AfterEach
    fun cleanUp() {
        cleanups.reversed().forEach { runCatching(it) }
    }

    /** Serves `/start` as a redirect to `/doc.ttl` and everything else as one triple; records the request paths. */
    private fun serve(requests: MutableList<String>): String {
        val threads = Executors.newFixedThreadPool(4)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                requests.add(exchange.requestURI.path)
                try {
                    if (exchange.requestURI.path == "/start") {
                        exchange.responseHeaders.add("Location", "/doc.ttl")
                        exchange.sendResponseHeaders(302, -1)
                    } else {
                        val bytes = "<urn:s> <urn:p> <urn:o> .".toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                } catch (_: IOException) {
                } finally {
                    exchange.close()
                }
            }
            executor = threads
            start()
        }
        cleanups.add { server.stop(0); threads.shutdownNow() }
        return "http://127.0.0.1:${server.address.port}"
    }

    /** Occupies every helper thread until the returned latch is counted down. */
    private fun occupyHelpers(): CountDownLatch {
        val release = CountDownLatch(1)
        cleanups.add { release.countDown() }
        val running = CountDownLatch(UrlLoadHelpers.MAX_THREADS)
        repeat(UrlLoadHelpers.MAX_THREADS) {
            assertTrue(UrlLoadHelpers.tryExecute { running.countDown(); release.await(60, TimeUnit.SECONDS) })
        }
        assertTrue(running.await(20, TimeUnit.SECONDS), "every helper thread is busy")
        assertFalse(UrlLoadHelpers.tryExecute { }, "no helper thread is free")
        return release
    }

    @Test
    fun `a load that finds every helper busy times out at its deadline without sending a request`() {
        val requests = CopyOnWriteArrayList<String>()
        val root = serve(requests)
        occupyHelpers()
        val options = UrlLoadOptions(totalTimeoutMillis = 400)
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            val error = assertThrows(RdfLoadTimeoutException::class.java) { Rdf.parseFromUrl("$root/doc.ttl", RdfFormat.TURTLE, options) }
            assertEquals(400, error.timeoutMillis)
        }
        assertEquals(emptyList<String>(), requests.toList(), "the request must not run on the calling thread")
    }

    @Test
    fun `a load that finds every helper busy proceeds on a helper once one is free`() {
        val requests = CopyOnWriteArrayList<String>()
        val root = serve(requests)
        val release = occupyHelpers()
        val policyThreads = CopyOnWriteArrayList<String>()
        val options = UrlLoadOptions(
            totalTimeoutMillis = 60_000,
            redirectPolicy = { _, _ -> policyThreads.add(Thread.currentThread().name); true },
        )
        val caller = Executors.newSingleThreadExecutor()
        cleanups.add { caller.shutdownNow() }
        val load = caller.submit<Int> { Rdf.parseFromUrl("$root/start", RdfFormat.TURTLE, options).size() }
        // Every helper is busy and the deadline is far away: the load waits.
        assertThrows(TimeoutException::class.java) { load.get(300, TimeUnit.MILLISECONDS) }
        assertEquals(emptyList<String>(), requests.toList())
        release.countDown()
        assertEquals(1, load.get(30, TimeUnit.SECONDS))
        assertEquals(listOf("/start", "/doc.ttl"), requests.toList())
        assertEquals(1, policyThreads.size)
        assertTrue(policyThreads[0].startsWith(UrlLoadHelpers.THREAD_NAME_PREFIX), "the policy ran on ${policyThreads[0]}")
    }

    /** A source of [chunks] chunks of 4 bytes whose `available()` is 0, as for a socket without buffered data. */
    private class Chunked(private val chunks: Int) : InputStream() {
        var reads = 0
        val threads = HashSet<String>()
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            threads.add(Thread.currentThread().name)
            if (reads == chunks) return -1
            val n = minOf(len, 4)
            for (k in 0 until n) b[off + k] = (reads + k).toByte()
            reads++
            return n
        }
    }

    @Test
    fun `reads that may outlast the deadline share one helper task for the whole stream`() {
        val source = Chunked(200)
        val before = UrlLoadHelpers.tasksStarted.get()
        // A blocking-read limit longer than the whole deadline: no read may run on the calling thread.
        val stream = DeadlineInputStream(source, System.nanoTime(), 60_000, blockingReadMillis = 120_000)
        val buffer = ByteArray(16)
        var total = 0
        while (true) {
            val n = stream.read(buffer, 0, buffer.size)
            if (n < 0) break
            total += n
        }
        stream.close()
        assertEquals(800, total)
        assertEquals(200, source.reads)
        assertEquals(1, UrlLoadHelpers.tasksStarted.get() - before, "helper tasks started for 201 reads")
        assertEquals(1, source.threads.size, source.threads.toString())
        assertTrue(source.threads.single().startsWith(UrlLoadHelpers.THREAD_NAME_PREFIX), source.threads.toString())
    }

    @Test
    fun `data that is already available is read on the calling thread`() {
        val data = ByteArray(64) { it.toByte() }
        val threads = HashSet<String>()
        val source = object : java.io.ByteArrayInputStream(data) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                threads.add(Thread.currentThread().name)
                return super.read(b, off, minOf(len, 8))
            }
        }
        val before = UrlLoadHelpers.tasksStarted.get()
        val stream = DeadlineInputStream(source, System.nanoTime(), 60_000, blockingReadMillis = 120_000)
        assertArrayEquals(data, stream.readBytes())
        stream.close()
        // Only the read that finds nothing available (the end of the stream) may block, so only it needs the helper.
        assertEquals(1, UrlLoadHelpers.tasksStarted.get() - before)
        assertTrue(Thread.currentThread().name in threads, threads.toString())
    }

    @Test
    fun `a read abandoned at the deadline releases the connection when it returns, not before`() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val source = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                entered.countDown()
                unblock.await(30, TimeUnit.SECONDS)
                events.add("read returned")
                return -1
            }
            override fun close() { events.add("closed") }
        }
        cleanups.add { unblock.countDown() }
        val released = CountDownLatch(1)
        val stream = DeadlineInputStream(source, System.nanoTime(), 300, blockingReadMillis = 60_000) {
            events.add("released")
            released.countDown()
        }
        assertThrows(RdfLoadTimeoutException::class.java) { stream.read(ByteArray(8), 0, 8) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        // The stream is unusable from now on, and closing it does not wait for the blocked read.
        assertThrows(RdfLoadTimeoutException::class.java) { stream.read(ByteArray(8), 0, 8) }
        stream.close()
        assertEquals(emptyList<String>(), events.toList(), "nothing is released while the read is blocked")
        unblock.countDown()
        assertTrue(released.await(10, TimeUnit.SECONDS))
        assertEquals("read returned", events[0])
        assertEquals("released", events[1])
    }

    @Test
    fun `closing a stream sends its idle helper home`() {
        val source = Chunked(3)
        val stream = DeadlineInputStream(source, System.nanoTime(), 60_000, blockingReadMillis = 120_000)
        assertEquals(4, stream.read(ByteArray(8), 0, 8))
        val helper = source.threads.single()
        stream.close()
        fun inLoop() = Thread.getAllStackTraces().any { (thread, stack) ->
            thread.name == helper && stack.any { it.className.contains("StreamHelper") }
        }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (inLoop() && System.nanoTime() < end) Thread.sleep(10)
        assertFalse(inLoop(), "the helper of a closed stream must leave its loop")
    }
}
