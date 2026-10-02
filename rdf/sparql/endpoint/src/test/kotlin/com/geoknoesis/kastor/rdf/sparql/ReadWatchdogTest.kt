package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The thread that enforces read deadlines: it survives streams that fail, is replaced when it ends,
 * loses no deadline while it is being replaced, stops with its last user and starts again on demand.
 *
 * The tests create the watching threads themselves (the watchdog's thread factory), so they count
 * and observe them without any state in the watchdog that only tests would read.
 */
class ReadWatchdogTest {

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

    /** The threads a watchdog asked for, in order. */
    private class Threads : (Runnable) -> Thread {
        val created = CopyOnWriteArrayList<Thread>()

        override fun invoke(body: Runnable): Thread =
            Thread(body, "test-deadline-${created.size}").also {
                it.isDaemon = true
                created += it
            }
    }

    private fun assertExpires(stream: Due, what: String) =
        assertTrue(stream.expired.await(SLOW_HOST_SLACK_MILLIS, TimeUnit.MILLISECONDS), "$what: the deadline was not enforced")

    private fun assertEnds(thread: Thread, what: String) {
        thread.join(SLOW_HOST_SLACK_MILLIS)
        assertFalse(thread.isAlive, what)
    }

    /** Waits, without a time limit of its own, until [thread] is in one of [states]. */
    private fun awaitState(thread: Thread, vararg states: Thread.State) {
        while (thread.state !in states) Thread.onSpinWait()
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `the watchdog outlives streams that fail and is replaced when its thread ends`() {
        // Whatever a stream throws is reported, and the watch over the others goes on in the same thread.
        val reported = CopyOnWriteArrayList<Throwable>()
        val threads = Threads()
        val watchdog = ReadWatchdog({ reported.add(it) }, threads)
        watchdog.retain()
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
        // On a slow host both streams can come due in one scan, which visits them in no particular order.
        assertEquals(setOf("close failed", "simulated"), reported.map { it.message }.toSet())
        assertEquals(2, reported.size)
        assertEquals(1, threads.created.size, "the thread must survive a failing stream")
        watchdog.release()
        assertEnds(threads.created[0], "the thread must end with its last user")

        // A thread that ends all the same (here: reporting the failure fails too) is replaced.
        val replaced = Threads()
        val unreportable = ReadWatchdog({ throw IllegalStateException("the report failed") }, replaced)
        unreportable.retain()
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
        assertEquals(2, replaced.created.size, "one replacement for the one thread that ended")
        assertEnds(replaced.created[0], "the thread whose report failed")
        unreportable.release()
        assertEnds(replaced.created[1], "the replacement must end with its last user")
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a deadline published while the watching thread is being replaced is enforced`() {
        // The thread that ends starts its replacement; this factory holds it right there, with the
        // replacement already running, which is the moment a wake-up used to go to the wrong thread.
        val replacementStarted = CountDownLatch(1)
        val handOver = CountDownLatch(1)
        val created = CopyOnWriteArrayList<Thread>()
        val watchdog = ReadWatchdog({ throw IllegalStateException("the report failed") }, { body ->
            val replacement = created.isNotEmpty()
            object : Thread(body, "test-deadline-${created.size}") {
                override fun start() {
                    super.start()
                    if (replacement) {
                        replacementStarted.countDown()
                        handOver.await()
                    }
                }
            }.also {
                it.isDaemon = true
                created += it
            }
        })
        watchdog.retain()
        try {
            val first = Due { throw IllegalStateException("close failed") }
            watchdog.register(first)
            first.arm(watchdog, 1)
            assertExpires(first, "first")
            assertTrue(replacementStarted.await(SLOW_HOST_SLACK_MILLIS, TimeUnit.MILLISECONDS), "the thread that ended was not replaced")
            // The replacement found nothing to wait for and rests (or waits for the thread that started it).
            awaitState(created[1], Thread.State.WAITING, Thread.State.BLOCKED)

            val later = Due()
            watchdog.register(later)
            val publisher = Thread { later.arm(watchdog, 50) }.apply {
                isDaemon = true
                start()
            }
            // The deadline is published (or its publisher waits for the hand-over) before the hand-over ends.
            awaitState(publisher, Thread.State.TERMINATED, Thread.State.WAITING, Thread.State.BLOCKED)
            handOver.countDown()
            assertExpires(later, "a deadline published during the hand-over")
            assertEquals(2, created.size)
        } finally {
            handOver.countDown()
            watchdog.release()
        }
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `the watching thread stops with its last user and starts again on demand`() {
        val threads = Threads()
        val watchdog = ReadWatchdog({ throw AssertionError("nothing fails here", it) }, threads)
        assertEquals(0, threads.created.size, "no thread before the first deadline")
        watchdog.retain()
        watchdog.retain()
        val first = Due()
        watchdog.register(first)
        first.arm(watchdog, 5)
        assertExpires(first, "first")
        watchdog.unregister(first)

        watchdog.release()
        val second = Due()
        watchdog.register(second)
        second.arm(watchdog, 5)
        assertExpires(second, "with one user left")
        watchdog.unregister(second)
        assertEquals(1, threads.created.size, "one thread while there are users")

        watchdog.release()
        assertEnds(threads.created[0], "the thread must end when its last user is gone")

        // A stream that outlives its repository is still watched: the thread starts again, and ends when it has nothing left.
        val late = Due()
        watchdog.register(late)
        late.arm(watchdog, 5)
        assertExpires(late, "after the last user was gone")
        watchdog.unregister(late)
        assertEquals(2, threads.created.size)
        assertEnds(threads.created[1], "a thread without users must end once nothing is pending")
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `closing the last repository stops the watching thread`() {
        val threads = Threads()
        val watchdog = ReadWatchdog({ throw AssertionError("nothing fails here", it) }, threads)
        val transport = HttpTransport { request ->
            CompletableFuture.completedFuture(FakeResponse(request, 200, rowsJson(2).toByteArray().inputStream()))
        }
        val config = SparqlEndpointConfig("http://sparql.example/sparql")
        val select = SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")
        val one = SparqlRepository(config, transport, watchdog)
        val two = SparqlRepository(config, transport, watchdog)
        assertEquals(2, one.select(select).count())
        assertEquals(2, two.select(select).count())
        assertEquals(1, threads.created.size, "every read publishes a deadline, which starts the one thread")

        one.close()
        one.close()
        assertEquals(2, two.select(select).count())
        assertEquals(1, threads.created.size, "the thread keeps running while a repository is open")
        assertTrue(threads.created[0].isAlive)

        two.close()
        assertEnds(threads.created[0], "the thread must end when the last repository is closed")
    }
}
