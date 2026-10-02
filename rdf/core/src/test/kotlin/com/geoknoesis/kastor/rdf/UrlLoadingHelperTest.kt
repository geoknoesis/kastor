package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The helper threads of URL loading: a stream uses one long-lived helper instead of one task per read, a load that
 * finds every helper busy waits for one within its deadline instead of running unbounded on the calling thread, an
 * abandoned read still releases its connection once it returns, abandoned work is interrupted, and a helper whose
 * work never returns is replaced.
 *
 * Every test has a helper pool of its own ([pool]): what another test (or another load of the JVM) does with the
 * shared pool cannot change what is counted here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UrlLoadingHelperTest {
    private val http = LoopbackHttp(threads = 4)
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

    /** A helper pool for one test, closed when the test ends. */
    private fun pool(maxThreads: Int = 3, graceMillis: Long = 60_000, clock: () -> Long = System::nanoTime): UrlLoadHelperPool =
        UrlLoadHelperPool(maxThreads, graceMillis, clock).also { created -> cleanups.add { created.close() } }

    /** Serves `/start` as a redirect to `/doc.ttl` and everything else as one triple. */
    private fun serve(): String = http.serve { exchange ->
        if (exchange.requestURI.path == "/start") exchange.redirect("/doc.ttl") else exchange.turtle("<urn:s> <urn:p> <urn:o> .")
    }

    /** Occupies every helper thread of [pool] until the returned latch is counted down. */
    private fun occupy(pool: UrlLoadHelperPool): CountDownLatch {
        val release = CountDownLatch(1)
        cleanups.add { release.countDown() }
        val running = CountDownLatch(pool.maxThreads)
        repeat(pool.maxThreads) {
            assertNotNull(pool.execute({ running.countDown(); release.await(120, TimeUnit.SECONDS) }, TimeUnit.SECONDS.toNanos(30)))
        }
        assertTrue(running.await(30, TimeUnit.SECONDS), "every helper thread is busy")
        assertNull(pool.tryExecute { }, "no helper thread is free")
        assertEquals(pool.maxThreads, pool.busy)
        return release
    }

    @Test
    fun `a load that finds every helper busy times out at its deadline without sending a request`() {
        val root = serve()
        val pool = pool()
        occupy(pool)
        val options = UrlLoadOptions(totalTimeoutMillis = 400)
        val error = assertThrows(RdfLoadTimeoutException::class.java) {
            openRdfUrlStream("$root/doc.ttl", RdfFormat.TURTLE, options, UrlLoadRuntime(helpers = pool))
        }
        assertEquals(400, error.timeoutMillis)
        assertEquals(emptyList<String>(), http.requests.toList(), "the request must not run on the calling thread")
    }

    @Test
    fun `a load that finds every helper busy proceeds on a helper once one is free`() {
        val root = serve()
        val pool = pool()
        val release = occupy(pool)
        val policyThreads = CopyOnWriteArrayList<String>()
        val options = UrlLoadOptions(
            totalTimeoutMillis = 120_000,
            redirectPolicy = { _, _ -> policyThreads.add(Thread.currentThread().name); true },
        )
        val caller = Executors.newSingleThreadExecutor()
        cleanups.add { caller.shutdownNow() }
        val load = caller.submit<Int> {
            openRdfUrlStream("$root/start", RdfFormat.TURTLE, options, UrlLoadRuntime(helpers = pool)).read { it.readBytes().size }
        }
        // Every helper is busy and the deadline is far away: the load waits. (It can only fail by returning.)
        assertThrows(TimeoutException::class.java) { load.get(300, TimeUnit.MILLISECONDS) }
        assertEquals(emptyList<String>(), http.requests.toList())
        release.countDown()
        assertTrue(load.get(60, TimeUnit.SECONDS) > 0)
        assertEquals(listOf("/start", "/doc.ttl"), http.requests.toList())
        assertEquals(1, policyThreads.size)
        assertTrue(policyThreads[0].startsWith(UrlLoadHelpers.THREAD_NAME_PREFIX), "the policy ran on ${policyThreads[0]}")
    }

    @Test
    fun `the bound of the shared pool scales with the processors and can be configured`() {
        assertEquals(32, UrlLoadHelpers.maxThreads(null, processors = 1))
        assertEquals(32, UrlLoadHelpers.maxThreads(null, processors = 8))
        assertEquals(64, UrlLoadHelpers.maxThreads(null, processors = 16))
        assertEquals(256, UrlLoadHelpers.maxThreads(null, processors = 64))
        assertEquals(100, UrlLoadHelpers.maxThreads("100", processors = 4))
        assertEquals(4, UrlLoadHelpers.maxThreads(" 4 ", processors = 64))
        // A value that is not a positive number is ignored.
        for (unusable in listOf("0", "-3", "many", "")) assertEquals(32, UrlLoadHelpers.maxThreads(unusable, processors = 4), unusable)
        assertEquals(60_000, UrlLoadHelpers.abandonGraceMillis(null))
        assertEquals(5, UrlLoadHelpers.abandonGraceMillis("5"))
        assertEquals(60_000, UrlLoadHelpers.abandonGraceMillis("-1"))
        assertEquals("kastor.url.helperThreads", UrlLoadHelpers.MAX_THREADS_PROPERTY)
        assertTrue(UrlLoadHelpers.shared.maxThreads >= 32 || System.getProperty(UrlLoadHelpers.MAX_THREADS_PROPERTY) != null)
        assertThrows(IllegalArgumentException::class.java) { UrlLoadHelperPool(0) }
    }

    @Test
    fun `more loads than the default bound run at once in a pool that was given more threads`() {
        // 33 and more concurrent loads from slow hosts used to fail: the bound of 32 was fixed.
        val pool = pool(maxThreads = 40)
        val release = occupy(pool)
        assertEquals(40, pool.busy)
        release.countDown()
        assertTrue(pool.awaitIdle(30_000))
        assertTrue(pool.largestThreadCount <= 40, "threads: ${pool.largestThreadCount}")
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
        val pool = pool()
        // A blocking-read limit longer than the whole deadline: no read may run on the calling thread.
        val stream = DeadlineInputStream(source, System.nanoTime(), 600_000, blockingReadMillis = 1_200_000, helpers = pool)
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
        assertEquals(1, pool.tasksStarted.get(), "helper tasks started for 201 reads")
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
        val pool = pool()
        val stream = DeadlineInputStream(source, System.nanoTime(), 600_000, blockingReadMillis = 1_200_000, helpers = pool)
        assertArrayEquals(data, stream.readBytes())
        stream.close()
        // Only the read that finds nothing available (the end of the stream) may block, so only it needs the helper.
        assertEquals(1, pool.tasksStarted.get())
        assertTrue(Thread.currentThread().name in threads, threads.toString())
    }

    @Test
    fun `a read abandoned at the deadline releases the connection when it returns, not before`() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val interrupts = AtomicInteger()
        val source = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                entered.countDown()
                // Like a socket read of the JDK: an interrupt does not end it.
                interrupts.addAndGet(awaitIgnoringInterrupts(unblock))
                events.add("read returned")
                return -1
            }
            override fun close() { events.add("closed") }
        }
        cleanups.add { unblock.countDown() }
        val released = CountDownLatch(1)
        val stream = DeadlineInputStream(source, System.nanoTime(), 300, blockingReadMillis = 60_000, helpers = pool()) {
            events.add("released")
            released.countDown()
        }
        assertThrows(RdfLoadTimeoutException::class.java) { stream.read(ByteArray(8), 0, 8) }
        assertTrue(entered.await(30, TimeUnit.SECONDS))
        // The stream is unusable from now on, and closing it does not wait for the blocked read.
        assertThrows(RdfLoadTimeoutException::class.java) { stream.read(ByteArray(8), 0, 8) }
        stream.close()
        assertEquals(emptyList<String>(), events.toList(), "nothing is released while the read is blocked")
        unblock.countDown()
        assertTrue(released.await(30, TimeUnit.SECONDS))
        assertEquals("read returned", events[0])
        assertEquals("released", events[1])
        assertEquals(1, interrupts.get(), "the abandoned read was interrupted, once")
    }

    @Test
    fun `closing a stream sends its idle helper home`() {
        val source = Chunked(3)
        val pool = pool()
        val stream = DeadlineInputStream(source, System.nanoTime(), 600_000, blockingReadMillis = 1_200_000, helpers = pool)
        assertEquals(4, stream.read(ByteArray(8), 0, 8))
        assertEquals(1, pool.busy, "the helper waits for the next read")
        stream.close()
        assertTrue(pool.awaitIdle(30_000), "the helper of a closed stream must leave its loop")
    }

    @Test
    fun `a reader interrupted while it waits for a read is told so, by that read and by every later one`() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        cleanups.add { unblock.countDown() }
        val source = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                entered.countDown()
                unblock.await(120, TimeUnit.SECONDS)
                return -1
            }
        }
        val stream = DeadlineInputStream(source, System.nanoTime(), 600_000, blockingReadMillis = 1_200_000, helpers = pool())
        val failures = CopyOnWriteArrayList<Throwable>()
        val flagKept = CopyOnWriteArrayList<Boolean>()
        val done = CountDownLatch(1)
        val reader = Thread {
            failures.add(runCatching { stream.read(ByteArray(8), 0, 8) }.exceptionOrNull() ?: IllegalStateException("the read returned"))
            flagKept.add(Thread.interrupted())
            // The read that was given up may still be running: the stream is over, and says why.
            failures.add(runCatching { stream.read(ByteArray(8), 0, 8) }.exceptionOrNull() ?: IllegalStateException("the read returned"))
            failures.add(runCatching { stream.read() }.exceptionOrNull() ?: IllegalStateException("the read returned"))
            done.countDown()
        }
        reader.start()
        assertTrue(entered.await(30, TimeUnit.SECONDS), "the helper is in the read")
        reader.interrupt()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertEquals(3, failures.size)
        for (failure in failures) {
            assertTrue(failure is InterruptedIOException, "expected an interruption, got $failure")
            assertFalse(failure is RdfLoadTimeoutException, "an interrupt is not a timeout: $failure")
        }
        assertEquals(listOf(true), flagKept.toList(), "the interrupt flag of the reader stays set")
        assertNull(stream.failure, "no limit was exceeded")
        stream.close()
    }

    @Test
    fun `abandoned work is interrupted, and its helper is free as soon as it returns`() {
        val pool = pool(maxThreads = 1)
        val interrupted = CountDownLatch(1)
        val never = CountDownLatch(1)
        val call = HelperCall(pool) {
            try {
                never.await(120, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                interrupted.countDown()
                throw e
            }
        }
        assertTrue(call.start())
        assertNull(pool.tryExecute { }, "the only helper is busy")
        val cleanedUp = CountDownLatch(1)
        call.abandon { cleanedUp.countDown() }
        assertTrue(interrupted.await(30, TimeUnit.SECONDS), "the abandoned call must be interrupted")
        assertTrue(cleanedUp.await(30, TimeUnit.SECONDS), "the clean-up runs when the call has returned")
        assertTrue(pool.awaitIdle(30_000))
        // The interrupt was for that call: the next task of the thread does not see it.
        val flag = CopyOnWriteArrayList<Boolean>()
        val ran = CountDownLatch(1)
        assertNotNull(pool.execute({ flag.add(Thread.currentThread().isInterrupted); ran.countDown() }, TimeUnit.SECONDS.toNanos(30)))
        assertTrue(ran.await(30, TimeUnit.SECONDS))
        assertEquals(listOf(false), flag.toList())
        assertEquals(0, pool.helpersReplaced.get())
    }

    @Test
    fun `a helper whose abandoned work never returns is replaced after the grace period`() {
        val clock = AtomicLong(1_000)
        val pool = pool(maxThreads = 1, graceMillis = 10_000, clock = clock::get)
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        cleanups.add { unblock.countDown() }
        val interrupts = AtomicInteger()
        // A policy that hangs and does not react to an interrupt.
        val hung = pool.tryExecute {
            entered.countDown()
            interrupts.addAndGet(awaitIgnoringInterrupts(unblock))
        }
        assertNotNull(hung)
        assertTrue(entered.await(30, TimeUnit.SECONDS))
        hung!!.abandon()
        // Within the grace period the helper still counts: a hung lookup must not turn into a thread per load.
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(9_999))
        assertNull(pool.tryExecute { }, "the helper is still busy within the grace period")
        assertEquals(1, pool.busy)
        assertEquals(1, pool.threadLimit)
        // After it, the hung helper no longer counts and another thread takes its place.
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1))
        val ran = CountDownLatch(1)
        assertNotNull(pool.tryExecute { ran.countDown() }, "one hung policy must not hold the pool forever")
        assertTrue(ran.await(30, TimeUnit.SECONDS))
        assertEquals(1, pool.helpersReplaced.get())
        assertEquals(2, pool.threadLimit, "one thread more, for as long as the hung one is still there")
        assertTrue(pool.awaitIdle(30_000), "the hung helper does not count as busy")
        // When the hung work finally returns, the pool is back at its bound.
        unblock.countDown()
        assertTrue(hung.awaitDone(30_000))
        assertEquals(1, pool.threadLimit)
        assertEquals(0, pool.busy)
        assertEquals(1, interrupts.get(), "it was interrupted when it was abandoned, once")
        assertEquals(2, pool.largestThreadCount)
    }

    @Test
    fun `a load waiting for a helper gets the place of a hung one when its grace period ends`() {
        val clock = AtomicLong(0)
        val pool = pool(maxThreads = 1, graceMillis = 10_000, clock = clock::get)
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        cleanups.add { unblock.countDown() }
        val hung = pool.tryExecute {
            entered.countDown()
            awaitIgnoringInterrupts(unblock)
        }
        assertNotNull(hung)
        assertTrue(entered.await(30, TimeUnit.SECONDS))
        hung!!.abandon()
        val waiter = Executors.newSingleThreadExecutor()
        cleanups.add { waiter.shutdownNow() }
        val ran = CountDownLatch(1)
        val waiting = waiter.submit<Boolean> { pool.execute({ ran.countDown() }, TimeUnit.SECONDS.toNanos(120)) != null }
        // It can only fail by returning: the helper is busy and the grace period has not passed.
        assertThrows(TimeoutException::class.java) { waiting.get(300, TimeUnit.MILLISECONDS) }
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(10_000))
        assertTrue(waiting.get(60, TimeUnit.SECONDS), "the waiting task got the place of the hung helper")
        assertTrue(ran.await(30, TimeUnit.SECONDS))
    }

    @Test
    fun `an abandoned address policy is interrupted and no request is sent`() {
        val root = serve()
        val pool = pool()
        val interrupted = CountDownLatch(1)
        val never = CountDownLatch(1)
        val options = UrlLoadOptions(
            totalTimeoutMillis = 3_000,
            addressPolicy = {
                try {
                    never.await(120, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    interrupted.countDown()
                    throw e
                }
                true
            },
        )
        assertThrows(RdfLoadTimeoutException::class.java) {
            openRdfUrlStream("$root/doc.ttl", RdfFormat.TURTLE, options, UrlLoadRuntime(helpers = pool))
        }
        assertTrue(interrupted.await(30, TimeUnit.SECONDS), "the policy call that was cut off must be interrupted")
        assertTrue(pool.awaitIdle(30_000), "its helper is free again")
        assertEquals(emptyList<String>(), http.requests.toList())
    }

    // ---- schemes other than HTTP are bound by the deadline as well ----

    // Generous: the steps before the one under test (helper start, opening the connection) must fit in it on a slow host.
    private val ftpOptions = UrlLoadOptions(allowedSchemes = setOf("ftp"), totalTimeoutMillis = 3_000)
    private val ftpUrl = "ftp://files.example/data.nt"

    @Test
    fun `connecting to a scheme other than HTTP is cut off at the deadline`() {
        val unblock = CountDownLatch(1)
        cleanups.add { unblock.countDown() }
        val returned = CountDownLatch(1)
        val releases = CopyOnWriteArrayList<String>()
        val runtime = UrlLoadRuntime(
            helpers = pool(),
            open = { uri ->
                FakeUrlConnection(
                    uri.toURL(),
                    onConnect = {
                        // Like an FTP server that accepts the connection and then says nothing.
                        awaitIgnoringInterrupts(unblock)
                        returned.countDown()
                    },
                    body = { InputStream.nullInputStream() },
                )
            },
            onRelease = { releases.add(it.url.toString()) },
        )
        val error = assertThrows(RdfLoadTimeoutException::class.java) { openRdfUrlStream(ftpUrl, RdfFormat.N_TRIPLES, ftpOptions, runtime) }
        assertEquals(3_000, error.timeoutMillis)
        assertEquals(1, returned.count, "the load must fail at its deadline, while the connect is still blocked")
        assertEquals(emptyList<String>(), releases.toList(), "the connection is released when the connect returns")
        unblock.countDown()
        assertTrue(runtime.helpers.awaitIdle(30_000))
        assertEquals(listOf(ftpUrl), releases.toList())
    }

    @Test
    fun `a body read of a scheme other than HTTP is cut off at the deadline`() {
        val unblock = CountDownLatch(1)
        cleanups.add { unblock.countDown() }
        val readReturned = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val body = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                awaitIgnoringInterrupts(unblock)
                readReturned.countDown()
                return -1
            }
            override fun close() { closed.countDown() }
        }
        val runtime = UrlLoadRuntime(helpers = pool(), open = { uri -> FakeUrlConnection(uri.toURL(), body = { body }) })
        val opened = openRdfUrlStream(ftpUrl, RdfFormat.N_TRIPLES, ftpOptions, runtime)
        assertEquals(ftpUrl, opened.baseIri)
        assertThrows(RdfLoadTimeoutException::class.java) { opened.read { it.readBytes() } }
        assertEquals(1, readReturned.count, "the read must be given up at the deadline, while it is still blocked")
        unblock.countDown()
        assertTrue(closed.await(30, TimeUnit.SECONDS), "the body is closed once the abandoned read has returned")
    }

    @Test
    fun `a scheme other than HTTP loads on a helper, and without a deadline on the calling thread`() {
        val threads = CopyOnWriteArrayList<String>()
        val bytes = "<urn:s> <urn:p> <urn:o> .\n".toByteArray()
        fun runtime() = UrlLoadRuntime(
            helpers = pool(),
            open = { uri ->
                FakeUrlConnection(uri.toURL(), onConnect = { threads.add(Thread.currentThread().name) }, body = { bytes.inputStream() })
            },
        )
        val options = UrlLoadOptions(allowedSchemes = setOf("ftp"))
        assertArrayEquals(bytes, openRdfUrlStream(ftpUrl, RdfFormat.N_TRIPLES, options, runtime()).read { it.readBytes() })
        assertTrue(threads.single().startsWith(UrlLoadHelpers.THREAD_NAME_PREFIX), threads.toString())
        threads.clear()
        val unbounded = options.copy(totalTimeoutMillis = 0)
        assertArrayEquals(bytes, openRdfUrlStream(ftpUrl, RdfFormat.N_TRIPLES, unbounded, runtime()).read { it.readBytes() })
        assertEquals(listOf(Thread.currentThread().name), threads.toList())
    }

    @Test
    fun `a load whose thread is interrupted fails as interrupted, not as timed out`() {
        val unblock = CountDownLatch(1)
        cleanups.add { unblock.countDown() }
        val entered = CountDownLatch(1)
        val runtime = UrlLoadRuntime(
            helpers = pool(),
            open = { uri ->
                FakeHttpConnection(
                    URI(uri.toString()).toURL(),
                    status = {
                        entered.countDown()
                        unblock.await(120, TimeUnit.SECONDS)
                        200
                    },
                )
            },
        )
        val failure = CopyOnWriteArrayList<Throwable>()
        val done = CountDownLatch(1)
        val loader = Thread {
            failure.add(
                runCatching { openRdfUrlStream("http://files.example/x.nt", RdfFormat.N_TRIPLES, UrlLoadOptions(totalTimeoutMillis = 600_000), runtime) }
                    .exceptionOrNull() ?: IllegalStateException("the load returned"),
            )
            done.countDown()
        }
        loader.start()
        assertTrue(entered.await(30, TimeUnit.SECONDS))
        loader.interrupt()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertTrue(failure.single() is InterruptedIOException, failure.toString())
        assertFalse(failure.single() is RdfLoadTimeoutException, failure.toString())
    }
}
