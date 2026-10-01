package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GraphStateCacheTest {

    private fun ex(local: String) = Iri("http://example.org/$local")
    private fun triple(i: Int) = RdfTriple(ex("s$i"), ex("p"), Literal("v$i"))
    private fun graph(vararg ids: Int): MemoryGraph = MemoryGraph().apply { ids.forEach { addTriple(triple(it)) } }

    /** A state that remembers its content and whether it was released. */
    private class State(val triples: Set<RdfTriple>) {
        @Volatile var released = false
    }

    private class Fixture(max: Int, exclusive: Boolean = true, val failRelease: (State) -> Boolean = { false }) {
        val released: MutableList<State> = Collections.synchronizedList(ArrayList())
        val cache = GraphStateCache<State>(
            maxEntries = max,
            exclusive = exclusive,
            load = { triples, _ -> State(triples.toSet()) },
            release = { state ->
                state.released = true
                released += state
                if (failRelease(state)) throw IllegalStateException("release failed")
            },
        )
    }

    /** A graph without a modification stamp (delegates to [inner]). */
    private class PlainGraph(private val inner: RdfGraph) : RdfGraph by inner

    private fun daemonPool() = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    @Test
    fun `a stamped graph is loaded once and reloaded after a change`() {
        val f = Fixture(4)
        val g = graph(1)
        repeat(5) { f.cache.use(g) { assertEquals(setOf(triple(1)), it.triples) } }
        assertEquals(1, f.cache.loadCount)
        assertEquals(0, f.cache.digestCount)
        g.addTriple(triple(2))
        f.cache.use(g) { assertEquals(setOf(triple(1), triple(2)), it.triples) }
        assertEquals(2, f.cache.loadCount)
        assertEquals(1, f.cache.size)
    }

    @Test
    fun `equal handles of one named graph share a state and data class graphs do not`() {
        val f = Fixture(4)
        val repo = MemoryRepository(RdfConfig(providerId = "memory"))
        repo.editGraph(ex("g")).addTriple(triple(1))
        repeat(5) { f.cache.use(repo.getGraph(ex("g"))) { } }
        assertEquals(1, f.cache.loadCount, "handles that are equal denote the same graph")
        // A different named graph and the same name in another repository are different graphs.
        f.cache.use(repo.getGraph(ex("other"))) { assertTrue(it.triples.isEmpty()) }
        val repo2 = MemoryRepository(RdfConfig(providerId = "memory"))
        f.cache.use(repo2.getGraph(ex("g"))) { assertTrue(it.triples.isEmpty()) }
        assertEquals(3, f.cache.loadCount)

        // Content equality does not identify a live graph: two equal data class graphs with the same stamp differ.
        data class Structural(val content: List<RdfTriple>, override val modificationStamp: Long) : VersionedRdfGraph {
            override fun hasTriple(triple: RdfTriple) = triple in content
            override fun getTriples() = content
            override fun size() = content.size
        }
        val f2 = Fixture(4)
        f2.cache.use(Structural(listOf(triple(1)), 7)) { }
        f2.cache.use(Structural(listOf(triple(1)), 7)) { }
        assertEquals(2, f2.cache.loadCount)
    }

    @Test
    fun `graphs without a stamp are identified by content whichever handle is used`() {
        val f = Fixture(4)
        val inner = graph(1)
        f.cache.use(PlainGraph(inner)) { }
        f.cache.use(PlainGraph(inner)) { }
        f.cache.use(PlainGraph(graph(1))) { }
        assertEquals(1, f.cache.loadCount, "same content: one state")
        assertEquals(3, f.cache.digestCount)

        val handle = PlainGraph(inner)
        f.cache.use(handle) { }
        inner.addTriple(triple(2))
        f.cache.use(handle) { assertEquals(setOf(triple(1), triple(2)), it.triples) }
        assertEquals(2, f.cache.loadCount, "a change is detected")
    }

    @Test
    fun `the least recently used idle entry is released when the cache is full`() {
        val f = Fixture(2)
        val g1 = graph(1); val g2 = graph(2); val g3 = graph(3)
        f.cache.use(g1) { }
        f.cache.use(g2) { }
        f.cache.use(g1) { }
        f.cache.use(g3) { }
        assertEquals(2, f.cache.size)
        assertEquals(listOf(setOf(triple(2))), f.released.map { it.triples }, "g2 was the least recently used")
        f.cache.use(g1) { }
        assertEquals(3, f.cache.loadCount, "g1 stayed cached")
    }

    @Test
    fun `a full cache whose entries are all in use serves a private temporary state without waiting`() {
        val f = Fixture(1)
        val g1 = graph(1); val g2 = graph(2)
        val inUse = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val pool = daemonPool()
        try {
            val first = pool.submit<Boolean> {
                f.cache.use(g1) { state -> inUse.countDown(); finish.await(30, TimeUnit.SECONDS); state.released }
            }
            assertTrue(inUse.await(10, TimeUnit.SECONDS))
            // g1's entry is in use: this call must neither wait for it nor take its state away.
            val second = pool.submit<State> { f.cache.use(g2) { it } }
            val temporary = second.get(10, TimeUnit.SECONDS)
            assertEquals(setOf(triple(2)), temporary.triples)
            assertTrue(temporary.released, "the temporary state is released when its use ends")
            assertEquals(1, f.cache.temporaryCount)
            finish.countDown()
            assertFalse(first.get(10, TimeUnit.SECONDS), "the state in use was not released under its user")
            assertEquals(1, f.cache.size)
            f.cache.use(g1) { }
            assertEquals(2, f.cache.loadCount, "g1 is still cached")
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `eviction during a use skips the entry in use`() {
        val f = Fixture(2)
        val g1 = graph(1); val g2 = graph(2); val g3 = graph(3)
        val inUse = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val pool = daemonPool()
        try {
            val first = pool.submit<Boolean> {
                f.cache.use(g1) { state -> inUse.countDown(); finish.await(30, TimeUnit.SECONDS); state.released }
            }
            assertTrue(inUse.await(10, TimeUnit.SECONDS))
            f.cache.use(g2) { }
            f.cache.use(g3) { } // g1 is the least recently used entry but is in use: g2 goes
            assertEquals(listOf(setOf(triple(2))), f.released.map { it.triples })
            assertEquals(0, f.cache.temporaryCount)
            finish.countDown()
            assertFalse(first.get(10, TimeUnit.SECONDS))
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `close releases idle states at once and states in use when their use ends`() {
        val f = Fixture(4)
        val g1 = graph(1); val g2 = graph(2)
        f.cache.use(g2) { }
        val inUse = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val pool = daemonPool()
        try {
            val first = pool.submit<Boolean> {
                f.cache.use(g1) { state -> inUse.countDown(); finish.await(30, TimeUnit.SECONDS); state.released }
            }
            assertTrue(inUse.await(10, TimeUnit.SECONDS))
            f.cache.close() // must not wait for the use of g1
            assertEquals(listOf(setOf(triple(2))), f.released.map { it.triples })
            finish.countDown()
            assertFalse(first.get(10, TimeUnit.SECONDS), "not released while in use")
            assertEquals(2, f.released.size, "released when its last use ended")
            val e = assertThrows(IllegalStateException::class.java) { f.cache.use(g1) { } }
            assertEquals("GraphStateCache has been closed", e.message)
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a failing release does not skip the remaining releases`() {
        val f = Fixture(4, failRelease = { triple(2) in it.triples || triple(3) in it.triples })
        listOf(graph(1), graph(2), graph(3), graph(4)).forEach { g -> f.cache.use(g) { } }
        val e = assertThrows(IllegalStateException::class.java) { f.cache.close() }
        assertEquals(4, f.released.size, "every state was released")
        assertEquals(1, e.suppressed.size, "the second failure is attached to the first")

        // A failure to release an evicted state does not fail the call that evicted it.
        val f2 = Fixture(1, failRelease = { true })
        f2.cache.use(graph(1)) { }
        f2.cache.use(graph(2)) { assertEquals(setOf(triple(2)), it.triples) }
        assertEquals(1, f2.released.size)
    }

    @Test
    fun `the state of a stamped graph that was garbage collected is released on the next call`() {
        val f = Fixture(4)
        f.cache.use(graph(1)) { } // the only reference to this graph is gone after the call
        val keep = graph(2)
        f.cache.use(keep) { }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (f.cache.size != 1 && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(20)
        }
        assertEquals(1, f.cache.size, "the dead entry is dropped without waiting for a cache miss")
        assertEquals(listOf(setOf(triple(1))), f.released.map { it.triples })
        f.cache.use(keep) { }
        assertEquals(2, f.cache.loadCount)
    }

    @Test
    fun `a failed load keeps the previous state`() {
        var fail = false
        val previousStates = ArrayList<State?>()
        val cache = GraphStateCache<State>(
            maxEntries = 2,
            exclusive = true,
            load = { triples, previous ->
                previousStates += previous
                if (fail) throw IllegalStateException("load failed") else State(triples.toSet())
            },
            release = { it.released = true },
        )
        val g = graph(1)
        val first = cache.use(g) { it }
        g.addTriple(triple(2))
        fail = true
        assertThrows(IllegalStateException::class.java) { cache.use(g) { } }
        assertFalse(first.released, "the previous state is kept when a load fails")
        fail = false
        // The stamp changed, so the state is rebuilt (never a stale hit); the kept state is offered for reuse.
        assertEquals(setOf(triple(1), triple(2)), cache.use(g) { it }.triples)
        assertEquals(listOf(null, first, first), previousStates)
        assertTrue(first.released, "replaced by the new state")
    }

    @Test
    fun `a non-exclusive cache lets uses of one graph run concurrently`() {
        val f = Fixture(2, exclusive = false)
        val g = graph(1)
        val inUse = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val pool = daemonPool()
        try {
            val first = pool.submit { f.cache.use(g) { inUse.countDown(); finish.await(30, TimeUnit.SECONDS) } }
            assertTrue(inUse.await(10, TimeUnit.SECONDS))
            val second = pool.submit<Set<RdfTriple>> { f.cache.use(g) { it.triples } }
            assertEquals(setOf(triple(1)), second.get(10, TimeUnit.SECONDS))
            finish.countDown()
            first.get(10, TimeUnit.SECONDS)
            assertEquals(1, f.cache.loadCount)
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `the graph is never read while a cache lock is held`() {
        // A changed graph whose triples can only be read by one thread once another thread lets it: if the reader
        // held the graph's entry lock while reading, the second caller of the same graph could not load its snapshot.
        val f = Fixture(2)
        val inner = graph(1)
        val reading = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val slow = object : VersionedRdfGraph, RdfGraph by inner {
            override val modificationStamp: Long get() = inner.modificationStamp
            override fun getTriples(): List<RdfTriple> {
                if (Thread.currentThread().name == "blocked-reader") { reading.countDown(); proceed.await(30, TimeUnit.SECONDS) }
                return inner.getTriples()
            }
        }
        f.cache.use(slow) { }
        inner.addTriple(triple(2))
        val pool = daemonPool()
        try {
            val blocked = pool.submit<Set<RdfTriple>> {
                Thread.currentThread().name = "blocked-reader"
                f.cache.use(slow) { it.triples }
            }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            val other = pool.submit<Set<RdfTriple>> { f.cache.use(slow) { it.triples } }
            assertEquals(setOf(triple(1), triple(2)), other.get(10, TimeUnit.SECONDS))
            assertEquals(2, f.cache.loadCount)
            proceed.countDown()
            assertEquals(setOf(triple(1), triple(2)), blocked.get(10, TimeUnit.SECONDS))
            assertEquals(2, f.cache.loadCount, "the state loaded meanwhile is current for the blocked reader's stamp")
        } finally {
            proceed.countDown()
            pool.shutdownNow()
        }
    }
}
