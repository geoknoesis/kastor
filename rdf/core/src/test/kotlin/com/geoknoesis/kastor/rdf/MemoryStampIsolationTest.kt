package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The contract of [VersionedRdfGraph.modificationStamp] across threads: the stamp identifies the content that the
 * calling thread would read right now. A thread inside its own write transaction reads transaction-private stamps,
 * every other thread the last committed one, until the commit completes.
 *
 * Thread A is the single thread of [threadA]; thread B is the single thread of [threadB]. Every step is ordered with
 * latches: nothing depends on how fast a thread runs.
 */
class MemoryStampIsolationTest {
    private val t1 = RdfTriple(Iri("urn:s"), Iri("urn:p"), Literal("a"))
    private val t2 = RdfTriple(Iri("urn:s"), Iri("urn:p"), Literal("b"))
    private val t3 = RdfTriple(Iri("urn:s"), Iri("urn:p"), Literal("c"))
    private val name = Iri("urn:g")
    private val untouchedName = Iri("urn:untouched")

    private val repo = MemoryRepository(RdfConfig(providerId = "memory"))
    private val threadA: ExecutorService = Executors.newSingleThreadExecutor()
    private val threadB: ExecutorService = Executors.newSingleThreadExecutor()

    @AfterEach
    fun cleanUp() {
        threadA.shutdownNow()
        threadB.shutdownNow()
        repo.close()
    }

    private class Rollback : RuntimeException("rollback")

    /** The graphs a transaction of these tests changes: the default graph and a named graph. */
    private fun changedGraphs(): List<VersionedRdfGraph> =
        listOf(repo.defaultGraph as VersionedRdfGraph, repo.getGraph(name) as VersionedRdfGraph)

    private fun <T> onB(action: () -> T): T = threadB.submit<T> { action() }.get(30, TimeUnit.SECONDS)

    /** What thread A saw inside its transaction. */
    private class Seen {
        @Volatile var afterFirstWrite: List<Long> = emptyList()
        @Volatile var afterSecondWrite: List<Long> = emptyList()
        @Volatile var untouched: Long = -1
        @Volatile var afterTransaction: List<Long> = emptyList()
    }

    /**
     * Thread A writes to both graphs in a transaction, twice, reading its stamps after each write, then waits for
     * [finish] with the transaction open, and commits or (with [rollback]) rolls back. [inTransaction] opens once
     * both writes are done.
     */
    private fun transactionOnA(seen: Seen, inTransaction: CountDownLatch, finish: CountDownLatch, rollback: Boolean): Future<*> =
        threadA.submit {
            try {
                repo.transaction {
                    editDefaultGraph().addTriple(t2)
                    editGraph(name).addTriple(t2)
                    seen.afterFirstWrite = changedGraphs().map { it.modificationStamp }
                    editDefaultGraph().addTriple(t3)
                    editGraph(name).addTriple(t3)
                    seen.afterSecondWrite = changedGraphs().map { it.modificationStamp }
                    seen.untouched = (getGraph(untouchedName) as VersionedRdfGraph).modificationStamp
                    inTransaction.countDown()
                    check(finish.await(60, TimeUnit.SECONDS)) { "the test never let the transaction finish" }
                    if (rollback) throw Rollback()
                }
            } catch (_: Rollback) {
            }
            seen.afterTransaction = changedGraphs().map { it.modificationStamp }
        }

    private fun seed() {
        repo.editDefaultGraph().addTriple(t1)
        repo.editGraph(name).addTriple(t1)
        repo.editGraph(untouchedName).addTriple(t1)
    }

    @Test
    fun `another thread reads the committed stamp while a transaction is open, and a new one after the commit`() {
        seed()
        val committed = onB { changedGraphs().map { it.modificationStamp } }
        val untouched = onB { (repo.getGraph(untouchedName) as VersionedRdfGraph).modificationStamp }
        val seen = Seen()
        val inTransaction = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val a = transactionOnA(seen, inTransaction, finish, rollback = false)
        try {
            assertTrue(inTransaction.await(30, TimeUnit.SECONDS))
            // B, with A's transaction open: the pre-transaction stamps, at once (a stamp read takes no lock).
            val during = onB { changedGraphs().map { it.modificationStamp } }
            assertEquals(committed, during, "B must keep reading the committed stamps")
            for (k in committed.indices) {
                // A: private stamps, a new one after each of its writes, none of them the committed one.
                assertNotEquals(committed[k], seen.afterFirstWrite[k], "A must not read the committed stamp after its write")
                assertNotEquals(committed[k], seen.afterSecondWrite[k])
                assertNotEquals(seen.afterFirstWrite[k], seen.afterSecondWrite[k], "each write of A renews its stamp")
                assertNotEquals(seen.afterSecondWrite[k], during[k], "B must not read A's private stamp")
            }
            assertEquals(untouched, seen.untouched, "A reads the committed stamp of a graph it has not changed")
        } finally {
            finish.countDown()
        }
        a.get(30, TimeUnit.SECONDS)
        val after = onB { changedGraphs().map { it.modificationStamp } }
        for (k in committed.indices) {
            assertFalse(
                after[k] in setOf(committed[k], seen.afterFirstWrite[k], seen.afterSecondWrite[k]),
                "the commit publishes a stamp nobody has read: ${after[k]}",
            )
        }
        assertEquals(after, seen.afterTransaction, "after the commit A reads the committed stamps like everybody else")
        assertEquals(untouched, onB { (repo.getGraph(untouchedName) as VersionedRdfGraph).modificationStamp })
        assertEquals(3, repo.defaultGraph.size())
    }

    @Test
    fun `another thread still reads the old stamp after a rollback, and so does the thread that rolled back`() {
        seed()
        val committed = onB { changedGraphs().map { it.modificationStamp } }
        val seen = Seen()
        val inTransaction = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val a = transactionOnA(seen, inTransaction, finish, rollback = true)
        try {
            assertTrue(inTransaction.await(30, TimeUnit.SECONDS))
            assertEquals(committed, onB { changedGraphs().map { it.modificationStamp } })
            for (k in committed.indices) assertNotEquals(committed[k], seen.afterSecondWrite[k])
        } finally {
            finish.countDown()
        }
        a.get(30, TimeUnit.SECONDS)
        assertEquals(committed, onB { changedGraphs().map { it.modificationStamp } }, "the content is what it was")
        assertEquals(committed, seen.afterTransaction, "A's private stamps are gone with its transaction")
        assertEquals(listOf(t1), repo.defaultGraph.getTriples())
        // The private stamps are never handed out again: the next change gets values nobody has seen.
        repo.editDefaultGraph().addTriple(t2)
        repo.editGraph(name).addTriple(t2)
        val next = changedGraphs().map { it.modificationStamp }
        for (k in committed.indices) {
            assertFalse(next[k] in setOf(committed[k], seen.afterFirstWrite[k], seen.afterSecondWrite[k]), "reused stamp ${next[k]}")
        }
    }

    @Test
    fun `a graph created and removed inside a transaction has private stamps only`() {
        val created = Iri("urn:created")
        val view = repo.getGraph(created) as VersionedRdfGraph
        val before = view.modificationStamp
        var inside = before
        runCatching {
            repo.transaction {
                createGraph(created)
                editGraph(created).addTriple(t1)
                inside = view.modificationStamp
                throw Rollback()
            }
        }
        assertNotEquals(before, inside)
        assertEquals(before, view.modificationStamp, "a rolled back creation leaves no trace in the stamp")
        repo.transaction {
            editGraph(created).addTriple(t1)
            removeGraph(created)
        }
        assertFalse(view.modificationStamp in setOf(before, inside), "a committed transaction publishes a new stamp")
    }

    /**
     * A cache shared between threads, as a consumer of the stamp would write one: keyed by the stamp, and a hit is
     * served **without reading the graph**. [loads] counts the graph reads.
     */
    private class StampCache(private val graph: VersionedRdfGraph) {
        private val states = ConcurrentHashMap<Long, Set<RdfTriple>>()
        val loads = AtomicInteger()

        fun get(): Set<RdfTriple> {
            val stamp = graph.modificationStamp // the stamp first, then the content
            states[stamp]?.let { return it }
            loads.incrementAndGet()
            return graph.getTriples().toSet().also { states[stamp] = it }
        }
    }

    private fun cacheProtocol(rollback: Boolean) {
        seed()
        val caches = changedGraphs().map(::StampCache)
        // B fills the cache with the committed content.
        assertEquals(List(2) { setOf(t1) }, onB { caches.map { it.get() } })
        val loadsBefore = caches.map { it.loads.get() }
        val inTransaction = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val ownWrites = ArrayList<List<Set<RdfTriple>>>()
        val a = threadA.submit {
            try {
                repo.transaction {
                    ownWrites.add(caches.map { it.get() }) // nothing written yet: the committed state
                    editDefaultGraph().addTriple(t2)
                    editGraph(name).addTriple(t2)
                    ownWrites.add(caches.map { it.get() })
                    editDefaultGraph().addTriple(t3)
                    editGraph(name).addTriple(t3)
                    ownWrites.add(caches.map { it.get() })
                    inTransaction.countDown()
                    check(finish.await(60, TimeUnit.SECONDS)) { "the test never let the transaction finish" }
                    if (rollback) throw Rollback()
                }
            } catch (_: Rollback) {
            }
            ownWrites.add(caches.map { it.get() })
        }
        try {
            assertTrue(inTransaction.await(30, TimeUnit.SECONDS))
            val loadsOfA = caches.map { it.loads.get() }
            // B, while A's transaction is open and A has cached its uncommitted content: the committed state, from
            // the cache (a graph read would wait for the transaction, so a miss would time out here).
            val seenByB = onB { caches.map { it.get() } }
            assertEquals(List(2) { setOf(t1) }, seenByB, "dirty read: B was served a state built from uncommitted content")
            assertEquals(loadsOfA, caches.map { it.loads.get() }, "B must be served from the cache")
        } finally {
            finish.countDown()
        }
        a.get(30, TimeUnit.SECONDS)
        // A never missed one of its own writes, and before its first write it was served the committed state.
        assertEquals(List(2) { setOf(t1) }, ownWrites[0])
        assertEquals(List(2) { setOf(t1, t2) }, ownWrites[1], "missed own write")
        assertEquals(List(2) { setOf(t1, t2, t3) }, ownWrites[2], "missed own write")
        val final = if (rollback) setOf(t1) else setOf(t1, t2, t3)
        assertEquals(List(2) { final }, ownWrites[3], "A after its transaction")
        assertEquals(List(2) { final }, onB { caches.map { it.get() } }, "B after A's transaction")
        assertEquals(final, repo.defaultGraph.getTriples().toSet())
        assertTrue(caches.map { it.loads.get() }.zip(loadsBefore).all { (now, before) -> now > before })
    }

    @Test
    fun `a cache keyed by the stamp serves neither a dirty read nor a missed own write - commit`() {
        cacheProtocol(rollback = false)
    }

    @Test
    fun `a cache keyed by the stamp serves neither a dirty read nor a missed own write - rollback`() {
        cacheProtocol(rollback = true)
    }
}
