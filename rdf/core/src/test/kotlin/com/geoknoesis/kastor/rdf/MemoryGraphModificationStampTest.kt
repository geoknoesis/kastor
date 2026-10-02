package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MemoryGraphModificationStampTest {
    private val t1 = RdfTriple(Iri("urn:s"), Iri("urn:p"), Literal("a"))
    private val t2 = RdfTriple(Iri("urn:s"), Iri("urn:p"), Literal("b"))

    @Test
    fun `stamp changes on every content change and only then`() {
        val g = MemoryGraph()
        val s0 = g.modificationStamp
        g.addTriple(t1)
        val s1 = g.modificationStamp
        assertNotEquals(s0, s1)
        g.addTriple(t1) // no-op
        g.removeTriple(t2) // no-op
        g.getTriples(); g.find(); g.size()
        assertEquals(s1, g.modificationStamp)
        g.addTriples(listOf(t2))
        val s2 = g.modificationStamp
        assertNotEquals(s1, s2)
        g.removeTriple(t1)
        val s3 = g.modificationStamp
        assertNotEquals(s2, s3)
        g.clear()
        assertTrue(g.modificationStamp > s3)
    }

    @Test
    fun `a rolled back transaction leaves the committed stamp of the default graph as it was`() {
        val repo = MemoryRepository(RdfConfig(providerId = "memory"))
        val g = repo.defaultGraph as VersionedRdfGraph
        val before = g.modificationStamp
        assertThrows(IllegalStateException::class.java) {
            repo.transaction {
                editDefaultGraph().addTriple(t1)
                error("rollback")
            }
        }
        assertEquals(0, g.size())
        // The content is what it was, and so is the stamp that stands for it.
        assertEquals(before, g.modificationStamp)
        repo.editDefaultGraph().addTriple(t1)
        assertTrue(g.modificationStamp > before)
    }

    @Test
    fun `named graph views of the memory repository carry a stamp that never repeats a value`() {
        val repo = MemoryRepository(RdfConfig(providerId = "memory"))
        val name = Iri("urn:g")
        val view = repo.getGraph(name) as VersionedRdfGraph
        val seen = mutableListOf(view.modificationStamp)
        fun changed(what: String) {
            val stamp = view.modificationStamp
            assertTrue(stamp !in seen, "$what must yield a new stamp, got $stamp after $seen")
            seen += stamp
        }
        fun unchanged(what: String) = assertEquals(seen.last(), view.modificationStamp, what)

        repo.editGraph(name).addTriple(t1); changed("add through another view")
        view.getTriples(); view.size(); unchanged("reads")
        repo.editGraph(Iri("urn:other")).addTriple(t2); unchanged("a change of another graph")
        repo.editDefaultGraph().addTriple(t2); unchanged("a change of the default graph")
        repo.removeGraph(name); changed("removing the graph")
        repo.createGraph(name); changed("re-creating the graph")
        assertThrows(IllegalStateException::class.java) {
            repo.transaction { editGraph(name).addTriple(t2); removeGraph(name); error("rollback") }
        }
        assertEquals(0, view.size())
        unchanged("a rolled back transaction")
        repo.editGraph(name).addTriple(t1); changed("add after rollback")
        repo.clear(); changed("clearing the repository")
        assertTrue(repo.editGraph(name) is VersionedRdfGraph)
    }

    @Test
    fun `the stamp of the default graph and of a named graph view fail alike once the repository is closed`() {
        val repo = MemoryRepository(RdfConfig(providerId = "memory"))
        val default = repo.defaultGraph as VersionedRdfGraph
        val named = repo.getGraph(Iri("urn:g")) as VersionedRdfGraph
        repo.editDefaultGraph().addTriple(t1)
        repo.editGraph(Iri("urn:g")).addTriple(t1)
        default.modificationStamp
        named.modificationStamp
        repo.close()
        assertThrows(IllegalStateException::class.java) { default.getTriples() }
        assertThrows(IllegalStateException::class.java) { named.getTriples() }
        assertThrows(IllegalStateException::class.java) { default.modificationStamp }
        assertThrows(IllegalStateException::class.java) { named.modificationStamp }
        // A stand-alone graph has no repository to close.
        assertEquals(0L, MemoryGraph().modificationStamp)
    }

    @Test
    fun `the stamp is read without waiting for a write transaction of another thread`() {
        val repo = MemoryRepository(RdfConfig(providerId = "memory"))
        val name = Iri("urn:g")
        val untouchedName = Iri("urn:untouched")
        repo.editGraph(untouchedName).addTriple(t1)
        val changed = listOf(repo.defaultGraph as VersionedRdfGraph, repo.getGraph(name) as VersionedRdfGraph)
        val untouched = repo.getGraph(untouchedName) as VersionedRdfGraph
        val before = changed.map { it.modificationStamp }
        val untouchedBefore = untouched.modificationStamp
        val pool = Executors.newCachedThreadPool()
        try {
            val inTransaction = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val writer = pool.submit {
                repo.transaction {
                    editDefaultGraph().addTriple(t1)
                    editGraph(name).addTriple(t1)
                    inTransaction.countDown()
                    finish.await(20, TimeUnit.SECONDS)
                    editDefaultGraph().addTriple(t2)
                    editGraph(name).addTriple(t2)
                    // The writing thread itself can read the stamp inside its transaction.
                    changed.forEach { it.modificationStamp }
                }
            }
            assertTrue(inTransaction.await(10, TimeUnit.SECONDS))
            // The transaction is still open: these reads return only because the stamp does not take the lock.
            val during = changed.map { graph -> pool.submit<Long> { graph.modificationStamp }.get(10, TimeUnit.SECONDS) }
            val untouchedDuring = pool.submit<Long> { untouched.modificationStamp }.get(10, TimeUnit.SECONDS)
            assertEquals(untouchedBefore, untouchedDuring, "a graph the transaction did not change keeps its stamp: a cache hit")
            assertEquals(before, during, "another thread keeps reading the committed stamps while the transaction is open")
            // A content read still waits for the transaction.
            val content = pool.submit<Int> { changed[0].size() }
            assertThrows(java.util.concurrent.TimeoutException::class.java) { content.get(200, TimeUnit.MILLISECONDS) }
            finish.countDown()
            writer.get(10, TimeUnit.SECONDS)
            assertEquals(2, content.get(10, TimeUnit.SECONDS))
            changed.zip(during).forEach { (graph, mid) ->
                assertNotEquals(mid, graph.modificationStamp, "the commit gives a changed graph a new committed stamp")
            }
            assertEquals(untouchedBefore, untouched.modificationStamp)
            // Inside a read transaction the stamp can be read as well.
            repo.readTransaction { changed.forEach { it.modificationStamp } }
        } finally {
            pool.shutdownNow()
            repo.close()
        }
    }

    /**
     * The protocol documented on [VersionedRdfGraph]: read the stamp, then the content, and key the cached work by
     * that stamp. While writers change the graph, a cache entry whose stamp equals the current stamp of the graph
     * must hold its current content (no stale hit). The current (stamp, content) pair is read atomically inside a
     * read transaction.
     */
    @Test
    fun `stamp-then-content readers never get a stale cache hit while writers modify the graph`() {
        val repo = MemoryRepository(RdfConfig(providerId = "memory"))
        val name = Iri("urn:g")
        val pool = Executors.newCachedThreadPool()
        val stop = AtomicBoolean(false)
        val stopReaders = AtomicBoolean(false)
        val enoughChecks = CountDownLatch(400)
        val staleHits = ConcurrentLinkedQueue<String>()
        val hits = AtomicInteger()
        val checks = AtomicInteger()
        try {
            val triples = List(8) { RdfTriple(Iri("urn:s$it"), Iri("urn:p"), Literal("v$it")) }
            val writers = List(2) { w ->
                pool.submit {
                    val random = java.util.Random(42L + w)
                    while (!stop.get()) {
                        val triple = triples[random.nextInt(triples.size)]
                        when (random.nextInt(8)) {
                            0 -> repo.editDefaultGraph().addTriple(triple)
                            1 -> repo.editDefaultGraph().removeTriple(triple)
                            2 -> repo.editGraph(name).addTriple(triple)
                            3 -> repo.editGraph(name).removeTriple(triple)
                            4 -> repo.transaction {
                                editDefaultGraph().addTriples(triples)
                                editGraph(name).removeTriples(triples)
                            }
                            5 -> runCatching {
                                repo.transaction {
                                    editDefaultGraph().clear()
                                    editGraph(name).addTriples(triples)
                                    error("rollback")
                                }
                            }
                            6 -> repo.removeGraph(name)
                            else -> repo.editGraph(name).addTriples(triples.shuffled(random).take(3))
                        }
                        Thread.yield()
                    }
                }
            }
            val readers = List(4) { r ->
                pool.submit {
                    val graph = (if (r % 2 == 0) repo.defaultGraph else repo.getGraph(name)) as VersionedRdfGraph
                    var cachedStamp = Long.MIN_VALUE
                    var cachedContent = emptySet<RdfTriple>()
                    var quiet = 0
                    // Until told to stop, and then twice more: with the writers gone the second round is a cache hit.
                    while (!stopReaders.get() || quiet++ < 2) {
                        // The consumer protocol: stamp first, then content.
                        val stamp = graph.modificationStamp
                        if (stamp != cachedStamp) {
                            cachedContent = graph.getTriples().toSet()
                            cachedStamp = stamp
                        }
                        // Ground truth: an atomic (stamp, content) pair.
                        var currentStamp = 0L
                        var current = emptySet<RdfTriple>()
                        repo.readTransaction {
                            currentStamp = graph.modificationStamp
                            current = graph.getTriples().toSet()
                        }
                        checks.incrementAndGet()
                        enoughChecks.countDown()
                        if (currentStamp == cachedStamp) {
                            hits.incrementAndGet()
                            if (current != cachedContent) staleHits.add("stamp $cachedStamp: cached $cachedContent, current $current")
                        }
                    }
                }
            }
            // No clock decides how long the test runs: 400 checks against running writers, however long they take.
            assertTrue(enoughChecks.await(120, TimeUnit.SECONDS), "only ${checks.get()} checks in two minutes")
            stop.set(true)
            writers.forEach { it.get(60, TimeUnit.SECONDS) }
            stopReaders.set(true)
            readers.forEach { it.get(60, TimeUnit.SECONDS) }
            assertTrue(staleHits.isEmpty(), "stale cache hits: ${staleHits.take(3)}")
            assertTrue(checks.get() >= 400, "too few checks: ${checks.get()}")
            assertTrue(hits.get() >= readers.size, "every reader ends with a cache hit on the quiet graph: ${hits.get()}")
        } finally {
            stop.set(true)
            stopReaders.set(true)
            pool.shutdownNow()
            repo.close()
        }
    }
}
