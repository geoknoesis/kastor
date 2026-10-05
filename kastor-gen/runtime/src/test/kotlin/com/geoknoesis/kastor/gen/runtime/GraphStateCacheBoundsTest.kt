@file:OptIn(KastorGenInternalApi::class)

package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Read counts, replacement of the entry of a changing graph, expiry of entries that cannot be hit any more, the
 * saturation limit, the salted digest, and a randomised concurrent mutate + use run that must never see stale state.
 */
class GraphStateCacheBoundsTest {

    private fun ex(local: String) = Iri("http://example.org/$local")
    private fun triple(i: Int) = RdfTriple(ex("s$i"), ex("p"), Literal("v$i"))
    private fun graph(vararg ids: Int): MemoryGraph = MemoryGraph().apply { ids.forEach { addTriple(triple(it)) } }

    private class State(val triples: Set<RdfTriple>) {
        @Volatile var released = false
    }

    private class Fixture(max: Int, exclusive: Boolean = true, settings: GraphStateCache.Settings = GraphStateCache.Settings()) {
        val released: MutableList<State> = Collections.synchronizedList(ArrayList())
        val cache = GraphStateCache<State>(
            maxEntries = max,
            exclusive = exclusive,
            load = { triples, _ -> State(triples.toSet()) },
            release = { state ->
                state.released = true
                released += state
            },
            owner = "TestCache",
            settings = settings,
        )
    }

    /** A graph without a modification stamp and without handle equality; counts full reads in [reads]. */
    private class PlainGraph(private val inner: RdfGraph, private val reads: AtomicInteger = AtomicInteger()) : RdfGraph {
        override fun hasTriple(triple: RdfTriple) = inner.hasTriple(triple)
        override fun getTriples(): List<RdfTriple> {
            reads.incrementAndGet()
            return inner.getTriples()
        }
        override fun size() = inner.size()
    }

    /** A handle without a modification stamp that is equal to every handle with the same [key] (handle equality). */
    private class KeyedGraph(val key: String, private val inner: RdfGraph, private val reads: AtomicInteger = AtomicInteger()) :
        HandleEqualGraph {
        override fun hasTriple(triple: RdfTriple) = inner.hasTriple(triple)
        override fun getTriples(): List<RdfTriple> {
            reads.incrementAndGet()
            return inner.getTriples()
        }
        override fun size() = inner.size()
        override fun equals(other: Any?): Boolean = other is KeyedGraph && other.key == key
        override fun hashCode(): Int = key.hashCode()
    }

    private fun daemonPool() = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    /**
     * Records the reference the cache keeps to every handle, so that a test can clear it as the garbage collector
     * does when the handle is unreachable - without depending on when (or whether) a collection runs.
     */
    private class Handles(cache: GraphStateCache<*>) {
        private val references = java.util.IdentityHashMap<RdfGraph, ArrayList<java.lang.ref.Reference<RdfGraph>>>()

        init {
            cache.referenceFactory = { graph, _ ->
                WeakReference(graph).also { reference ->
                    synchronized(references) { references.getOrPut(graph) { ArrayList() } += reference }
                }
            }
        }

        fun collect(graph: RdfGraph) {
            synchronized(references) { references.remove(graph).orEmpty() }.forEach { it.clear() }
        }
    }

    @Test
    fun `a changing graph read through equal handles keeps one entry that is replaced in place`() {
        val f = Fixture(16)
        val inner = graph(0)
        repeat(6) { version ->
            if (version > 0) inner.addTriple(triple(version))
            // A fresh handle for every call, as repository.getGraph(name) returns.
            repeat(2) { f.cache.use(KeyedGraph("g", inner)) { assertEquals(inner.getTriples().toSet(), it.triples) } }
            assertEquals(1, f.cache.size, "one entry whatever the number of versions")
        }
        assertEquals(6, f.cache.loadCount)
        assertEquals(5, f.released.size, "every obsolete version was released when it was replaced")
        assertEquals(12, f.cache.digestCount, "a graph without a stamp is read and digested on every call")
    }

    @Test
    fun `a graph without a stamp is read once per use and not at all when found by handle under assumeImmutable`() {
        val reads = AtomicInteger()
        val inner = graph(1)

        val checking = Fixture(4)
        val handle = PlainGraph(inner, reads)
        repeat(5) { checking.cache.use(handle) { } }
        assertEquals(5, reads.get(), "by default every call reads the graph to detect changes")
        assertEquals(1, checking.cache.loadCount)

        reads.set(0)
        val trusting = Fixture(4, settings = GraphStateCache.Settings(assumeImmutable = true))
        repeat(5) { trusting.cache.use(handle) { assertEquals(setOf(triple(1)), it.triples) } }
        assertEquals(1, reads.get(), "the same instance is read once")
        // Equal handles are found without a read too.
        val other = graph(7)
        repeat(5) { trusting.cache.use(KeyedGraph("k", other, reads)) { assertEquals(setOf(triple(7)), it.triples) } }
        assertEquals(2, reads.get(), "equal handles are read once")
        assertEquals(2, trusting.cache.digestCount)
        // A fresh handle without handle equality cannot be recognised: it is read, and found by content.
        trusting.cache.use(PlainGraph(inner, reads)) { }
        assertEquals(3, reads.get())
        assertEquals(2, trusting.cache.loadCount, "it shares the state built for that content")

        // The promise is the caller's: a change of such a graph is not detected.
        inner.addTriple(triple(2))
        trusting.cache.use(handle) { assertEquals(setOf(triple(1)), it.triples) }
        // Graphs with a stamp are still checked.
        trusting.cache.use(inner) { assertEquals(setOf(triple(1), triple(2)), it.triples) }
    }

    @Test
    fun `obsolete versions left by fresh handles without equality expire and are evicted first`() {
        val f = Fixture(4, settings = GraphStateCache.Settings(orphanIdleMillis = 1_000))
        val handles = Handles(f.cache)
        val now = AtomicLong(0)
        f.cache.clock = { now.get() }
        val inner = graph(0)
        // Each version is read through a fresh handle that nothing links to the previous one: three entries.
        fun useFresh(): RdfGraph {
            val handle = PlainGraph(inner)
            f.cache.use(handle) { }
            return handle
        }
        val witnesses = ArrayList<RdfGraph>()
        repeat(3) { version ->
            if (version > 0) inner.addTriple(triple(version))
            witnesses += useFresh()
        }
        assertEquals(3, f.cache.loadCount)
        witnesses.forEach(handles::collect)
        assertEquals(3, f.cache.size, "content entries survive the collection of their handle")
        witnesses += useFresh()
        assertEquals(3, f.cache.loadCount, "a fresh handle with the same content still hits")

        // A live graph is used last; the orphaned versions are evicted before it although it is not the newest.
        val live = graph(100)
        val live2 = graph(101)
        f.cache.use(live) { }
        witnesses.forEach(handles::collect)
        now.set(TimeUnit.MILLISECONDS.toNanos(500))
        f.cache.use(live2) { }
        assertEquals(4, f.cache.size)
        assertEquals(1, f.released.size, "the cache was full: one orphaned version was evicted")
        assertFalse(f.released.single().triples.contains(triple(100)))

        // After the idle time the remaining orphans are released without waiting for a miss.
        now.set(TimeUnit.MILLISECONDS.toNanos(1_500))
        assertEquals(2, f.cache.size, "only the graphs with a live handle stay")
        assertEquals(3, f.released.size)
        f.cache.use(live) { }
        f.cache.use(live2) { }
        assertEquals(5, f.cache.loadCount, "the live graphs stayed cached")
    }

    @Test
    fun `an entry found by handle equality is released once its handle is collected`() {
        val f = Fixture(4)
        val handles = Handles(f.cache)
        val inner = graph(1)
        val witness = KeyedGraph("g", inner)
        f.cache.use(witness) { }
        val keep = KeyedGraph("kept", graph(2))
        f.cache.use(keep) { }
        assertEquals(2, f.cache.size)
        handles.collect(witness)
        assertEquals(1, f.cache.size, "an unstamped entry found by handle is dropped, not only stamped ones")
        assertEquals(listOf(setOf(triple(1))), f.released.map { it.triples })
        f.cache.use(keep) { }
        assertEquals(2, f.cache.loadCount)
    }

    @Test
    fun `an entry in use is kept when the equal handle that replaced the in-use one is collected`() {
        val f = Fixture(4)
        val handles = Handles(f.cache)
        val inner = graph(1)
        val first = KeyedGraph("g", inner)
        val second = KeyedGraph("g", inner)
        f.cache.use(first) {
            f.cache.use(second) { } // the newest equal handle replaces the one this block is using
            handles.collect(second)
            assertEquals(1, f.cache.size, "an entry that is in use is not retired, whichever handles are alive")
            assertEquals(emptyList<Any>(), f.released, "its state is not released while the block runs")
        }
        assertEquals(1, f.cache.loadCount, "the state was built once")
    }

    @Test
    fun `a saturated cache fails with a clear exception after the wait`() {
        val f = Fixture(
            1,
            settings = GraphStateCache.Settings(maxTemporaryStates = 1, temporaryWaitMillis = 100, overflowWaitMillis = null),
        )
        val pool = daemonPool()
        val finish = CountDownLatch(1)
        val entered = List(2) { CountDownLatch(1) }
        try {
            val users = List(2) { i ->
                pool.submit { f.cache.use(graph(i)) { entered[i].countDown(); finish.await(30, TimeUnit.SECONDS) } }
                    .also { assertTrue(entered[i].await(10, TimeUnit.SECONDS)) }
            }
            val e = assertThrows(GraphStateCacheSaturatedException::class.java) { f.cache.use(graph(2)) { } }
            assertTrue(e.message!!.startsWith("TestCache is saturated: all 1 cached states and all 1 temporary states are in use"), e.message)
            assertEquals(2, f.cache.loadCount, "no third state was built")
            finish.countDown()
            users.forEach { it.get(10, TimeUnit.SECONDS) }
            f.cache.use(graph(2)) { } // free again
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }

        // Temporary states can be switched off: a full cache whose entries are in use then fails at once.
        val none = Fixture(
            1,
            settings = GraphStateCache.Settings(maxTemporaryStates = 0, temporaryWaitMillis = 0, overflowWaitMillis = null),
        )
        none.cache.use(graph(1)) {
            assertThrows(GraphStateCacheSaturatedException::class.java) { none.cache.use(graph(2)) { } }
        }
        assertThrows(IllegalArgumentException::class.java) { GraphStateCache.Settings(maxTemporaryStates = -1) }
    }

    @Test
    fun `close wakes a call that waits for a free state`() {
        val waiting = CountDownLatch(1)
        val f = Fixture(
            1,
            settings = GraphStateCache.Settings(
                maxTemporaryStates = 0,
                temporaryWaitMillis = 30_000,
                overflowWaitMillis = null,
                probe = { event -> if (event == GraphStateCache.Event.SLOT_WAIT) waiting.countDown() },
            ),
        )
        val pool = daemonPool()
        val finish = CountDownLatch(1)
        val entered = CountDownLatch(1)
        try {
            val user = pool.submit { f.cache.use(graph(1)) { entered.countDown(); finish.await(30, TimeUnit.SECONDS) } }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val waiter = pool.submit { f.cache.use(graph(2)) { } }
            assertTrue(waiting.await(10, TimeUnit.SECONDS), "the second call waits for a free state")
            f.cache.close()
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { waiter.get(10, TimeUnit.SECONDS) }
            assertEquals("TestCache has been closed", failure.cause!!.message)
            finish.countDown()
            user.get(10, TimeUnit.SECONDS)
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a saturated cache validates in a private state beyond the cap after a short wait`() {
        val f = Fixture(1, settings = GraphStateCache.Settings(maxTemporaryStates = 1, overflowWaitMillis = 1))
        val pool = daemonPool()
        val finish = CountDownLatch(1)
        val entered = List(2) { CountDownLatch(1) }
        try {
            val users = List(2) { i ->
                pool.submit { f.cache.use(graph(i)) { entered[i].countDown(); finish.await(30, TimeUnit.SECONDS) } }
                    .also { assertTrue(entered[i].await(10, TimeUnit.SECONDS)) }
            }
            // The cached state and the temporary state stay in use (their users may be waiting for a lock this
            // caller holds): the call must neither stall nor fail.
            val state = f.cache.use(graph(2)) { it }
            assertEquals(setOf(triple(2)), state.triples)
            assertTrue(state.released, "the private state is released when its use ends")
            assertEquals(1, f.cache.count(GraphStateCache.Event.OVERFLOW))
            assertEquals(1, f.cache.temporaryCount, "the cap on temporary states is not raised")
            assertEquals(1, f.cache.size)
            finish.countDown()
            users.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
        f.cache.close()
    }

    /** Counts the states that exist (loaded and not yet released) and the most that existed at once. */
    private class LiveStates(max: Int, settings: GraphStateCache.Settings, val onRelease: (State) -> Unit = {}) {
        val live = AtomicInteger()
        val peak = AtomicInteger()
        val cache = GraphStateCache<State>(
            maxEntries = max,
            exclusive = true,
            load = { triples, _ ->
                val now = live.incrementAndGet()
                peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                State(triples.toSet())
            },
            release = { state ->
                onRelease(state)
                state.released = true
                live.decrementAndGet()
            },
            owner = "TestCache",
            settings = settings,
        )
    }

    @Test
    fun `a strict limit holds while a temporary state is released and its slot is awaited`() {
        // Every entry and every temporary state is in use; a third graph waits for a slot. The slot is freed only
        // after the state of its previous user is released, so no more than maxEntries + maxTemporaryStates states
        // ever exist, however the release (blocked here) and the waiter interleave.
        val gate = CountDownLatch(1)
        val releasing = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val f = LiveStates(
            1,
            GraphStateCache.Settings(
                maxTemporaryStates = 1,
                overflowWaitMillis = null,
                temporaryWaitMillis = 60_000,
                probe = { if (it == GraphStateCache.Event.SLOT_WAIT) waiting.countDown() },
            ),
            onRelease = { state ->
                if (state.triples == setOf(triple(1))) {
                    releasing.countDown()
                    assertTrue(gate.await(30, TimeUnit.SECONDS))
                }
            },
        )
        val pool = daemonPool()
        val finishTemporary = CountDownLatch(1)
        val finishCached = CountDownLatch(1)
        val entered = List(2) { CountDownLatch(1) }
        try {
            val cached = pool.submit { f.cache.use(graph(0)) { entered[0].countDown(); finishCached.await(30, TimeUnit.SECONDS) } }
            assertTrue(entered[0].await(10, TimeUnit.SECONDS))
            val temporary = pool.submit { f.cache.use(graph(1)) { entered[1].countDown(); finishTemporary.await(30, TimeUnit.SECONDS) } }
            assertTrue(entered[1].await(10, TimeUnit.SECONDS))
            assertEquals(2, f.live.get())

            val waiter = pool.submit<Set<RdfTriple>> { f.cache.use(graph(2)) { it.triples } }
            assertTrue(waiting.await(10, TimeUnit.SECONDS), "the third graph waits for a slot")
            finishTemporary.countDown()
            assertTrue(releasing.await(10, TimeUnit.SECONDS), "the temporary state is being released")
            // Its release is blocked: the slot is not free, so the waiter has not built its state.
            assertEquals(2, f.live.get())
            gate.countDown()
            assertEquals(setOf(triple(2)), waiter.get(10, TimeUnit.SECONDS))
            finishCached.countDown()
            cached.get(10, TimeUnit.SECONDS)
            temporary.get(10, TimeUnit.SECONDS)
            assertEquals(2, f.peak.get(), "never more than maxEntries + maxTemporaryStates states")
        } finally {
            gate.countDown()
            finishTemporary.countDown()
            finishCached.countDown()
            pool.shutdownNow()
        }
        f.cache.close()
        assertEquals(0, f.live.get())
    }

    @Test
    fun `evicting an idle entry is best-effort - the replacement may be built while the victim is being released`() {
        // Known, documented race: the victim of an eviction is released outside the lock (a release can be slow), so
        // for that time a caller that finds the replacement entry builds its state while the victim still exists.
        // The overshoot is bounded by the number of evictions in flight and ends when the release returns.
        val gate = CountDownLatch(1)
        val releasing = CountDownLatch(1)
        val f = LiveStates(1, GraphStateCache.Settings(), onRelease = { state ->
            if (state.triples == setOf(triple(0))) {
                releasing.countDown()
                assertTrue(gate.await(30, TimeUnit.SECONDS))
            }
        })
        val first = graph(0)
        val second = graph(1)
        f.cache.use(first) { }
        val pool = daemonPool()
        try {
            // Evicts the idle entry of `first`; this thread is held in the release of its state.
            val evicting = pool.submit { f.cache.use(second) { } }
            assertTrue(releasing.await(10, TimeUnit.SECONDS))
            assertEquals(setOf(triple(1)), f.cache.use(second) { it.triples })
            assertEquals(2, f.live.get(), "the victim and its replacement coexist while the release is in progress")
            gate.countDown()
            evicting.get(10, TimeUnit.SECONDS)
            assertEquals(1, f.live.get(), "the overshoot ends with the release")
            assertEquals(2, f.peak.get())
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
        f.cache.close()
    }

    @Test
    fun `an interrupted wait for a free state restores the interrupt and fails clearly`() {
        val f = Fixture(
            1,
            settings = GraphStateCache.Settings(maxTemporaryStates = 0, temporaryWaitMillis = 30_000, overflowWaitMillis = null),
        )
        try {
            f.cache.use(graph(1)) {
                // An interrupt that is already pending makes the wait fail at once: no timing is involved.
                Thread.currentThread().interrupt()
                val failure = assertThrows(GraphStateCacheInterruptedException::class.java) { f.cache.use(graph(2)) { } }
                assertTrue(Thread.interrupted(), "the interrupt status is restored for the caller")
                assertTrue(failure.message!!.startsWith("TestCache: interrupted while waiting"), failure.message)
            }
        } finally {
            Thread.interrupted()
        }
        assertEquals(1, f.cache.loadCount)
        f.cache.close()
    }

    @Test
    fun `under assumeImmutable a graph with the content of another entry is found by its handle afterwards`() {
        val f = Fixture(4, settings = GraphStateCache.Settings(assumeImmutable = true))
        val readsA = AtomicInteger()
        val readsB = AtomicInteger()
        // Two distinct graphs with equal content (two empty graphs are the common case).
        val a = PlainGraph(graph(), readsA)
        val b = PlainGraph(graph(), readsB)
        f.cache.use(a) { }
        repeat(4) { f.cache.use(b) { assertTrue(it.triples.isEmpty()) } }
        assertEquals(1, readsB.get(), "found by content once, then by its handle: not read on every call")
        repeat(4) { f.cache.use(a) { } }
        assertEquals(1, readsA.get(), "the first graph is still found by its handle")
        assertEquals(1, f.cache.loadCount)
        assertEquals(1, f.cache.size)
        f.cache.close()
    }

    @Test
    fun `equal handles used at the same time get one entry`() {
        val f = Fixture(64, exclusive = false)
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val pool = daemonPool()
        try {
            repeat(20) { round ->
                val inner = graph(round)
                (0 until threads).map {
                    pool.submit {
                        val handle = KeyedGraph("g$round", inner)
                        barrier.await(10, TimeUnit.SECONDS)
                        f.cache.use(handle) { assertEquals(setOf(triple(round)), it.triples) }
                    }
                }.forEach { it.get(30, TimeUnit.SECONDS) }
                // An entry is not found by content before its state is built, so only the comparison of the handles
                // keeps a second thread from creating (and loading) a second entry for the same graph.
                assertEquals(round + 1, f.cache.loadCount, "round $round")
                assertEquals(round + 1, f.cache.size, "round $round")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the digest is salted per digester, order independent and separates near contents`() {
        val a = GraphDigester()
        val b = GraphDigester()
        val triples = (1..50).map(::triple)
        assertEquals(a.digest(triples), a.digest(triples.shuffled(Random(7))), "order independent")
        assertEquals(a.digest(triples), a.digest(triples.toList()))
        assertNotEquals(a.digest(triples), b.digest(triples), "another digester has another salt")
        assertEquals(
            GraphDigester(ByteArray(32) { 1 }).digest(triples),
            GraphDigester(ByteArray(32) { 1 }).digest(triples),
            "the digest is a function of the salt and the content",
        )
        assertEquals(50, a.digest(triples).count)

        fun one(obj: com.geoknoesis.kastor.rdf.RdfTerm) = a.digest(listOf(RdfTriple(ex("s"), ex("p"), obj)))
        // "Aa" and "BB" share a String hash code; a value and its split into two fields must differ too.
        assertNotEquals(one(Literal("Aa")), one(Literal("BB")))
        assertNotEquals(one(LangString("chat", "en")), one(LangString("chat", "fr")))
        assertNotEquals(one(Literal("x")), one(ex("x")))
        assertNotEquals(a.digest(listOf(triple(1), triple(1))), a.digest(listOf(triple(1))), "a multiset, not a set")
        // Values longer than the internal buffer and non-ASCII characters.
        val long = "\u00e9".repeat(5_000)
        assertNotEquals(one(Literal(long)), one(Literal(long + "\u00e9")))
        assertNotEquals(one(Literal(long)), one(Literal("e".repeat(5_000))))
        assertEquals(one(Literal(long)), one(Literal("\u00e9".repeat(5_000))))
    }

    /**
     * Seeded random mix of mutations and uses from several threads over more graphs than the cache holds, of every
     * kind (stamped, stamped behind equal handles, unstamped by instance, unstamped behind equal handles, unstamped
     * behind fresh handles). A use holds the graph's read lock, so the content cannot change during it: the state
     * must be exactly the content at that moment, never an older one.
     */
    private fun stress(exclusive: Boolean, seed: Long) {
        val f = Fixture(3, exclusive = exclusive)
        class Subject(val inner: MemoryGraph, val handle: () -> RdfGraph) {
            val lock = ReentrantReadWriteLock()
        }
        class StampedHandle(val key: String, val source: MemoryGraph) : VersionedRdfGraph, RdfGraph by source {
            override val modificationStamp: Long get() = source.modificationStamp
            override fun equals(other: Any?): Boolean = other is StampedHandle && other.key == key
            override fun hashCode(): Int = key.hashCode()
        }
        val subjects = List(7) { i ->
            val inner = graph(i * 1000)
            when (i % 5) {
                0 -> Subject(inner) { inner }
                1 -> Subject(inner) { StampedHandle("s$i", inner) }
                2 -> PlainGraph(inner).let { same -> Subject(inner) { same } }
                3 -> Subject(inner) { KeyedGraph("k$i", inner) }
                else -> Subject(inner) { PlainGraph(inner) }
            }
        }
        val threads = 6
        val stale = Collections.synchronizedList(ArrayList<String>())
        val start = CountDownLatch(1)
        val pool = daemonPool()
        try {
            val jobs = (0 until threads).map { t ->
                pool.submit {
                    val random = Random(seed + t)
                    start.await()
                    repeat(400) { step ->
                        val index = random.nextInt(subjects.size)
                        val subject = subjects[index]
                        if (random.nextInt(4) == 0) {
                            subject.lock.write {
                                val id = index * 1000 + random.nextInt(20)
                                if (random.nextBoolean()) subject.inner.addTriple(triple(id)) else subject.inner.removeTriple(triple(id))
                            }
                        } else {
                            subject.lock.read {
                                val expected = subject.inner.getTriples().toSet()
                                f.cache.use(subject.handle()) { state ->
                                    if (state.triples != expected) stale += "thread $t step $step graph $index: ${state.triples.size} triples, expected ${expected.size}"
                                    if (state.released && exclusive) stale += "thread $t step $step graph $index: released state"
                                }
                            }
                        }
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(emptyList<String>(), stale.take(5), "seed $seed")
        assertTrue(f.cache.size <= 3)
        f.cache.close()
        assertTrue(f.released.all { it.released })
    }

    @Test
    fun `randomised concurrent mutation and use never sees stale state - exclusive`() {
        listOf(1L, 20260917L, 987654321L).forEach { stress(exclusive = true, seed = it) }
    }

    @Test
    fun `randomised concurrent mutation and use never sees stale state - shared`() {
        listOf(2L, 20261001L, 123456789L).forEach { stress(exclusive = false, seed = it) }
    }
}
