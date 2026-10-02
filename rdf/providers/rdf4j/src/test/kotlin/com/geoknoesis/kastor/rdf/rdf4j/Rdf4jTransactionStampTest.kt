package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.UpdateQuery
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The modification stamp identifies the content that the **calling thread** would read right now: a thread with
 * uncommitted writes gets stamps no other thread ever receives, and every other thread keeps the stamp of the
 * committed content until the commit completes.
 *
 * Every interleaving is fixed by the test: the "other thread" is a single-thread executor, and the writing thread
 * waits for each of its steps (the timeouts only turn a deadlock into a failure).
 */
class Rdf4jTransactionStampTest {
    private val ex = "http://example.org/"
    private val g = Iri(ex + "g")
    private fun t(o: String) = RdfTriple(Iri(ex + "s"), Iri(ex + "p"), Literal(o))
    private val bad = t("bad")

    private val otherThread = Executors.newSingleThreadExecutor()

    @AfterEach
    fun stopOtherThread() {
        otherThread.shutdownNow()
    }

    /** Runs [step] on the other thread and waits for it. */
    private fun <T> onOtherThread(step: () -> T): T = otherThread.submit(Callable { step() }).get(60, TimeUnit.SECONDS)

    private fun stamp(graph: RdfGraph): Long = (graph as VersionedRdfGraph).modificationStamp

    /**
     * The protocol of a cache of graph states (kastor-gen's `GraphStateCache`): read the stamp, and serve the state
     * kept for an equal handle when it was built for that stamp; otherwise read the content and keep it under the
     * stamp read before it.
     */
    private class StampedCache {
        private class Entry(val stamp: Long, val content: Set<RdfTriple>)

        private val entries = java.util.concurrent.ConcurrentHashMap<RdfGraph, Entry>()
        val loads = java.util.concurrent.atomic.AtomicInteger()

        fun content(graph: RdfGraph): Set<RdfTriple> {
            val stamp = (graph as VersionedRdfGraph).modificationStamp
            entries[graph]?.let { if (it.stamp == stamp) return it.content }
            loads.incrementAndGet()
            val content = graph.getTriples().toSet()
            entries[graph] = Entry(stamp, content)
            return content
        }
    }

    @Test
    fun `an uncommitted write changes the stamp of the writing thread only`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editGraph(g).addTriple(t("1"))
            val committed = stamp(repo.getGraph(g))
            assertEquals(committed, onOtherThread { stamp(repo.getGraph(g)) })
            val private = LinkedHashSet<Long>()
            repo.transaction {
                assertEquals(committed, stamp(getGraph(g)), "a transaction that has not written reads the committed content")
                editGraph(g).addTriple(bad)
                assertTrue(private.add(stamp(getGraph(g))), "the writer gets a new stamp after its write")
                assertEquals(private.last(), stamp(getGraph(g)), "and keeps it while it does not write")
                assertEquals(committed, onOtherThread { stamp(repo.getGraph(g)) }, "other threads still read the committed content")
                editGraph(g).addTriple(t("2"))
                assertTrue(private.add(stamp(getGraph(g))), "renewed after each write")
                update(UpdateQuery("INSERT DATA { GRAPH <${g.value}> { <${ex}s> <${ex}p> \"3\" } }"))
                assertTrue(private.add(stamp(getGraph(g))), "renewed after an update")
                assertEquals(committed, onOtherThread { stamp(repo.getGraph(g)) })
            }
            assertFalse(committed in private, "a private stamp is never the committed one")
            val after = stamp(repo.getGraph(g))
            assertNotEquals(committed, after, "the commit changes the stamp")
            assertFalse(after in private, "a private stamp is never handed out again")
            assertEquals(after, onOtherThread { stamp(repo.getGraph(g)) }, "everyone reads the new committed stamp")
        }
    }

    @Test
    fun `the private stamps of a rolled back transaction are never seen again`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editGraph(g).addTriple(t("1"))
            val committed = stamp(repo.getGraph(g))
            var private = 0L
            assertFailsWith<IllegalStateException> {
                repo.transaction {
                    editGraph(g).addTriple(bad)
                    private = stamp(getGraph(g))
                    assertNotEquals(private, onOtherThread { stamp(repo.getGraph(g)) })
                    error("roll back")
                }
            }
            assertNotEquals(committed, private)
            assertNotEquals(private, stamp(repo.getGraph(g)))
            assertNotEquals(private, onOtherThread { stamp(repo.getGraph(g)) })
            assertEquals(stamp(repo.getGraph(g)), onOtherThread { stamp(repo.getGraph(g)) })
            // A later transaction of the same thread does not get the stamps of the rolled back one either.
            repo.transaction {
                editGraph(g).addTriple(t("2"))
                assertNotEquals(private, stamp(getGraph(g)))
            }
        }
    }

    @Test
    fun `two writing transactions never share a stamp`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            val h = Iri(ex + "h")
            repo.transaction {
                editGraph(g).addTriple(t("1"))
                val mine = stamp(getGraph(g))
                val theirs = onOtherThread {
                    var seen = 0L
                    repo.transaction {
                        editGraph(h).addTriple(t("2"))
                        seen = stamp(getGraph(g))
                    }
                    seen
                }
                assertNotEquals(mine, theirs)
                // The other transaction committed: this thread now reads its own writes and that commit.
                assertNotEquals(mine, stamp(getGraph(g)), "a commit of another thread changes what this transaction reads")
                assertEquals(setOf(t("2")), getGraph(h).getTriples().toSet())
            }
        }
    }

    @Test
    fun `a writer never validates content that another thread cached without its write`() {
        // Scenario A: between the writer's add and its read, another thread reads the committed content and caches it.
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editGraph(g).addTriple(t("1"))
            val cache = StampedCache()
            var seenByWriter: Set<RdfTriple> = emptySet()
            repo.transaction {
                editGraph(g).addTriple(bad)
                val seenByOther = onOtherThread { cache.content(repo.getGraph(g)) }
                assertEquals(setOf(t("1")), seenByOther, "the other thread reads the committed content")
                seenByWriter = cache.content(getGraph(g))
            }
            assertEquals(setOf(t("1"), bad), seenByWriter, "the writer must see its own write")
            assertEquals(2, cache.loads.get())
            assertEquals(setOf(t("1"), bad), onOtherThread { cache.content(repo.getGraph(g)) }, "after the commit")
        }
    }

    @Test
    fun `no thread is served the uncommitted content that a writer cached`() {
        // Scenario B: the writer caches its uncommitted content; another thread reads; the writer rolls back.
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editGraph(g).addTriple(t("1"))
            val cache = StampedCache()
            var seenByOther: Set<RdfTriple> = emptySet()
            assertFailsWith<IllegalStateException> {
                repo.transaction {
                    editGraph(g).addTriple(bad)
                    assertEquals(setOf(t("1"), bad), cache.content(getGraph(g)))
                    seenByOther = onOtherThread { cache.content(repo.getGraph(g)) }
                    error("roll back")
                }
            }
            assertEquals(setOf(t("1")), seenByOther, "a dirty read")
            assertEquals(setOf(t("1")), cache.content(repo.getGraph(g)), "after the rollback, on the writing thread")
            assertEquals(setOf(t("1")), onOtherThread { cache.content(repo.getGraph(g)) }, "after the rollback, on another thread")
        }
    }

    @Test
    fun `a stamp read during a commit returns a value of its own`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editGraph(g).addTriple(t("1"))
            val before = stamp(repo.getGraph(g))
            val during = ArrayList<Long>()
            var private = 0L
            repo.beforeCommit = {
                // On the committing thread, after the commit was announced and before it happens.
                repeat(2) { during.add(onOtherThread { stamp(repo.getGraph(g)) }) }
            }
            repo.transaction {
                editGraph(g).addTriple(t("2"))
                private = stamp(getGraph(g))
            }
            repo.beforeCommit = null
            val after = stamp(repo.getGraph(g))
            assertEquals(2, during.size)
            assertEquals(5, setOf(before, private, after, during[0], during[1]).size, "every one of these stamps is its own value")
            assertEquals(after, onOtherThread { stamp(repo.getGraph(g)) })
        }
    }

    @Test
    fun `the committed stamp is stable for readers while another thread holds uncommitted writes`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editGraph(g).addTriple(t("1"))
            val cache = StampedCache()
            assertEquals(setOf(t("1")), onOtherThread { cache.content(repo.getGraph(g)) })
            repo.transaction {
                repeat(3) { editGraph(g).addTriple(t("w$it")) }
                assertEquals(setOf(t("1")), onOtherThread { cache.content(repo.getGraph(g)) })
            }
            assertEquals(1, cache.loads.get(), "an uncommitted write of another thread must not invalidate the cached state")
        }
    }
}
