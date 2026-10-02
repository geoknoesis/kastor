@file:OptIn(KastorGenInternalApi::class)

package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Loads are single-flight per graph: callers that find a stale state wait for the caller that is already reading the
 * graph instead of reading it too, and a reload that finds the content unchanged keeps the state.
 */
class GraphStateCacheSingleFlightTest {

    private fun ex(local: String) = Iri("http://example.org/$local")
    private fun triple(i: Int) = RdfTriple(ex("s$i"), ex("p"), Literal("v$i"))

    private class State(val triples: Set<RdfTriple>) {
        @Volatile var released = false
    }

    private class Fixture(
        exclusive: Boolean = false,
        loadWaitMillis: Long = 30_000,
        val onEvent: (GraphStateCache.Event) -> Unit = { },
    ) {
        val released: MutableList<State> = Collections.synchronizedList(ArrayList())
        val cache = GraphStateCache<State>(
            maxEntries = 4,
            exclusive = exclusive,
            load = { triples, _ -> State(triples.toSet()) },
            release = { state ->
                state.released = true
                released += state
            },
            owner = "TestCache",
            settings = GraphStateCache.Settings(loadWaitMillis = loadWaitMillis, probe = { event -> onEvent(event) }),
        )
    }

    /**
     * A stamped graph whose stamp is set by the test (so that it can move without a change of content, as the
     * repository-wide stamp of a store does when another graph is written), whose reads are counted and can be held
     * back, and whose stamp can differ per thread.
     */
    private class ControlledGraph(val content: MemoryGraph = MemoryGraph()) : VersionedRdfGraph {
        val stamp = AtomicLong(1)
        val threadStamp = ThreadLocal<Long?>()
        val reads = AtomicInteger()
        @Volatile var gate: CountDownLatch? = null
        @Volatile var reading: CountDownLatch? = null
        @Volatile var failNextRead = false

        override val modificationStamp: Long get() = threadStamp.get() ?: stamp.get()

        override fun getTriples(): List<RdfTriple> {
            reads.incrementAndGet()
            val snapshot = content.getTriples()
            val held = gate
            reading?.countDown()
            held?.let { check(it.await(30, TimeUnit.SECONDS)) { "the read was never let through" } }
            if (failNextRead) {
                failNextRead = false
                throw IllegalStateException("read failed")
            }
            return snapshot
        }

        override fun hasTriple(triple: RdfTriple): Boolean = content.hasTriple(triple)
        override fun size(): Int = content.size()

        fun change(i: Int) {
            content.addTriple(RdfTriple(Iri("http://example.org/s$i"), Iri("http://example.org/p"), Literal("v$i")))
            stamp.incrementAndGet()
        }
    }

    private fun daemonPool() = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    @Test
    fun `callers that find a stale state wait for the one load in flight and reuse its result`() {
        for (exclusive in listOf(false, true)) {
            val waiters = 5
            val waiting = CountDownLatch(waiters)
            val f = Fixture(exclusive) { event -> if (event == GraphStateCache.Event.LOAD_WAIT) waiting.countDown() }
            val g = ControlledGraph()
            g.change(1)
            f.cache.use(g) { }
            assertEquals(1, g.reads.get())

            g.change(2)
            val gate = CountDownLatch(1)
            val reading = CountDownLatch(1)
            g.gate = gate
            g.reading = reading
            val pool = daemonPool()
            try {
                val leader = pool.submit<Set<RdfTriple>> { f.cache.use(g) { it.triples } }
                assertTrue(reading.await(10, TimeUnit.SECONDS), "the first caller reads the graph")
                val others = List(waiters) { pool.submit<Set<RdfTriple>> { f.cache.use(g) { it.triples } } }
                assertTrue(waiting.await(10, TimeUnit.SECONDS), "the other callers wait for the load in flight (exclusive=$exclusive)")
                assertEquals(2, g.reads.get(), "nobody else reads the graph while the load is in flight")
                gate.countDown()
                val expected = setOf(triple(1), triple(2))
                assertEquals(expected, leader.get(10, TimeUnit.SECONDS))
                others.forEach { assertEquals(expected, it.get(10, TimeUnit.SECONDS)) }
                assertEquals(2, g.reads.get(), "one read for all the callers, not one each (exclusive=$exclusive)")
                assertEquals(2, f.cache.loadCount)
            } finally {
                gate.countDown()
                pool.shutdownNow()
            }
            f.cache.close()
        }
    }

    @Test
    fun `a reload that finds the content unchanged keeps the state and only records the new stamp`() {
        val f = Fixture(exclusive = true)
        val g = ControlledGraph()
        g.change(1)
        val state = f.cache.use(g) { it }
        assertEquals(1, f.cache.digestCount, "the digest is computed when the state is built")
        repeat(3) { f.cache.use(g) { } }
        assertEquals(1, f.cache.digestCount, "and never on a hit")
        assertEquals(1, g.reads.get())

        // Another graph of the same repository was written: the stamp moved, this graph did not change.
        g.stamp.incrementAndGet()
        assertTrue(f.cache.use(g) { it } === state, "the loaded state is kept")
        assertEquals(1, f.cache.loadCount, "nothing is rebuilt")
        assertEquals(1, f.cache.count(GraphStateCache.Event.STAMP_REFRESH))
        assertEquals(2, g.reads.get(), "the reload reads the graph once to compare the content")
        assertEquals(2, f.cache.digestCount)
        assertFalse(state.released)

        repeat(3) { assertTrue(f.cache.use(g) { it } === state) }
        assertEquals(2, g.reads.get(), "the new stamp hits without a read")
        assertEquals(2, f.cache.digestCount)

        // A real change is still loaded.
        g.change(2)
        assertEquals(setOf(triple(1), triple(2)), f.cache.use(g) { it.triples })
        assertEquals(2, f.cache.loadCount)
        assertTrue(state.released, "the replaced state is released")
        f.cache.close()
    }

    @Test
    fun `a caller with another stamp does not wait for a load it could not use`() {
        val f = Fixture()
        val g = ControlledGraph()
        g.change(1)
        f.cache.use(g) { }
        g.change(2)
        val gate = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val pool = daemonPool()
        try {
            val leader = pool.submit<Set<RdfTriple>> {
                // Only this thread's read is held back: it keeps its load in flight.
                g.gate = gate
                g.reading = reading
                f.cache.use(g) { it.triples }
            }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            g.gate = null
            // This thread reads another version of the graph (its own transaction's): the load in flight is of no
            // use to it, and its owner may be waiting for a lock this thread holds.
            g.threadStamp.set(1_000)
            assertEquals(setOf(triple(1), triple(2)), f.cache.use(g) { it.triples })
            assertEquals(0, f.cache.count(GraphStateCache.Event.LOAD_WAIT), "it did not wait")
            assertFalse(leader.isDone, "the first load is still in flight")
            gate.countDown()
            assertEquals(setOf(triple(1), triple(2)), leader.get(10, TimeUnit.SECONDS))
        } finally {
            g.threadStamp.remove()
            gate.countDown()
            pool.shutdownNow()
        }
        f.cache.close()
    }

    @Test
    fun `a caller whose wait for the load in flight times out reads the graph itself`() {
        val f = Fixture(loadWaitMillis = 1)
        val g = ControlledGraph()
        g.change(1)
        f.cache.use(g) { }
        g.change(2)
        val gate = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val pool = daemonPool()
        try {
            val leader = pool.submit<Set<RdfTriple>> {
                g.gate = gate
                g.reading = reading
                f.cache.use(g) { it.triples }
            }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            g.gate = null
            // Same stamp: this caller waits for the load in flight, which never ends while this thread is here (as
            // when its owner waits for a repository lock that this thread holds).
            assertEquals(setOf(triple(1), triple(2)), f.cache.use(g) { it.triples })
            assertEquals(1, f.cache.count(GraphStateCache.Event.LOAD_WAIT))
            assertFalse(leader.isDone)
            assertEquals(2, f.cache.loadCount)
            gate.countDown()
            assertEquals(setOf(triple(1), triple(2)), leader.get(10, TimeUnit.SECONDS))
            assertEquals(2, f.cache.loadCount, "the state built meanwhile is current for the first caller too")
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
        f.cache.close()
    }

    @Test
    fun `a failed load fails its caller only and the waiting callers load in turn`() {
        val waiting = CountDownLatch(2)
        val f = Fixture { event -> if (event == GraphStateCache.Event.LOAD_WAIT) waiting.countDown() }
        val g = ControlledGraph()
        g.change(1)
        f.cache.use(g) { }
        g.change(2)
        val gate = CountDownLatch(1)
        val reading = CountDownLatch(1)
        g.gate = gate
        g.reading = reading
        g.failNextRead = true
        val pool = daemonPool()
        try {
            val leader = pool.submit<Set<RdfTriple>> { f.cache.use(g) { it.triples } }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            val others = List(2) { pool.submit<Set<RdfTriple>> { f.cache.use(g) { it.triples } } }
            assertTrue(waiting.await(10, TimeUnit.SECONDS))
            gate.countDown()
            val failure = assertThrows(ExecutionException::class.java) { leader.get(10, TimeUnit.SECONDS) }
            assertEquals("read failed", failure.cause!!.message)
            others.forEach { assertEquals(setOf(triple(1), triple(2)), it.get(10, TimeUnit.SECONDS)) }
            assertEquals(2, f.cache.loadCount)
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
        f.cache.close()
    }

    @Test
    fun `an interrupted wait for the load in flight restores the interrupt and fails clearly`() {
        val f = Fixture()
        val g = ControlledGraph()
        g.change(1)
        f.cache.use(g) { }
        g.change(2)
        val gate = CountDownLatch(1)
        val reading = CountDownLatch(1)
        g.gate = gate
        g.reading = reading
        val pool = daemonPool()
        try {
            val leader = pool.submit<Set<RdfTriple>> { f.cache.use(g) { it.triples } }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            // An interrupt that is already pending makes the wait fail at once: no timing is involved.
            Thread.currentThread().interrupt()
            val failure = assertThrows(GraphStateCacheInterruptedException::class.java) { f.cache.use(g) { } }
            assertTrue(Thread.interrupted(), "the interrupt status is restored for the caller")
            assertTrue(failure.message!!.startsWith("TestCache: interrupted while waiting"), failure.message)
            gate.countDown()
            assertEquals(setOf(triple(1), triple(2)), leader.get(10, TimeUnit.SECONDS))
        } finally {
            Thread.interrupted()
            gate.countDown()
            pool.shutdownNow()
        }
        f.cache.close()
    }
}
