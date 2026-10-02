package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Getting a graph handle and reading its modification stamp are cheap: an in-memory store begins no transaction for
 * them (counted by [JenaRepository.storeTransactionsBegun]), and the stamp still names what the caller reads.
 */
class JenaCheapReadsTest {
    // TDB2 keeps its store files memory-mapped for the JVM's lifetime, so Windows cannot delete them after the test.
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tmp: Path

    private val ex = "http://example.org/"
    private val g1 = Iri(ex + "g1")
    private fun triple(n: Int) = RdfTriple(Iri(ex + "s$n"), Iri(ex + "p"), Iri(ex + "o$n"))
    private fun stamp(graph: RdfGraph): Long = (graph as VersionedRdfGraph).modificationStamp

    private fun memory(): Map<String, () -> JenaRepository> = mapOf(
        "memory" to { JenaRepository.MemoryRepository() },
        "memory-inference" to { JenaRepository.MemoryRepositoryWithInference() },
    )

    @Test
    fun `an in-memory store begins no transaction for a graph handle or a stamp`() {
        for ((variant, open) in memory()) {
            open().use { repo ->
                repo.editGraph(g1).addTriple(triple(1))
                repo.defaultGraph // the handle of the default graph is created once
                val before = repo.storeTransactionsBegun()
                val stamps = HashSet<Long>()
                repeat(100) {
                    stamps.add(stamp(repo.defaultGraph))
                    stamps.add(stamp(repo.getGraph(g1)))
                    repo.editDefaultGraph()
                    repo.editGraph(g1)
                }
                assertEquals(before, repo.storeTransactionsBegun(), "$variant: handles and stamps must not begin transactions")
                assertEquals(1, stamps.size, "$variant: one committed state, one stamp")

                repo.editGraph(g1).addTriple(triple(2))
                assertEquals(before + 1, repo.storeTransactionsBegun(), "$variant: the write is one transaction")
                assertTrue(stamps.add(stamp(repo.getGraph(g1))), "$variant: the commit moved the stamp")
                assertEquals(before + 1, repo.storeTransactionsBegun(), variant)
            }
        }
    }

    @Test
    fun `a tdb2 store reads its stamp from the storage and still sees commits of another connection`() {
        val location = tmp.resolve("shared").toString()
        JenaRepository.Tdb2Repository(location).use { repo ->
            JenaRepository.Tdb2Repository(location).use { other ->
                val graph = repo.getGraph(g1)
                val before = stamp(graph)
                assertEquals(before, stamp(graph))
                other.editGraph(g1).addTriple(triple(1))
                assertNotEquals(before, stamp(graph), "a commit through another connection of the location")
                assertTrue(graph.hasTriple(triple(1)))
            }
        }
    }

    @Test
    @Timeout(120)
    fun `the stamp read without a transaction follows commits, rollbacks and the caller's own transaction`() {
        for ((variant, open) in memory()) {
            open().use { repo ->
                val graph = repo.getGraph(g1)
                val seen = LinkedHashSet<Long>()
                assertTrue(seen.add(stamp(graph)), variant)

                // Another thread's uncommitted write: this thread still reads (and names) the committed state.
                val writing = CountDownLatch(1)
                val finish = CountDownLatch(1)
                val writer = thread {
                    runCatching {
                        repo.transaction {
                            editGraph(g1).addTriple(triple(1))
                            writing.countDown()
                            finish.await(60, TimeUnit.SECONDS)
                            error("roll back")
                        }
                    }
                }
                assertTrue(writing.await(60, TimeUnit.SECONDS), variant)
                assertEquals(seen.last(), stamp(graph), "$variant: an uncommitted write of another thread is not read")
                assertFalse(graph.hasTriple(triple(1)), variant)
                finish.countDown()
                writer.join(60_000)
                assertTrue(seen.add(stamp(graph)), "$variant: a rollback moves the stamp")

                repo.editGraph(g1).addTriple(triple(2))
                assertTrue(seen.add(stamp(graph)), "$variant: a commit moves the stamp")

                // Inside the caller's own transactions the stamp is that transaction's.
                val committed = stamp(graph)
                repo.readTransaction { assertEquals(committed, stamp(graph), "$variant: a read transaction of the same state") }
                assertFailsWith<IllegalStateException>(variant) {
                    repo.transaction {
                        val inside = stamp(graph)
                        assertNotEquals(committed, inside, "$variant: a write transaction has a private stamp")
                        editGraph(g1).addTriple(triple(3))
                        assertNotEquals(inside, stamp(graph), "$variant: each write moves it")
                        error("roll back")
                    }
                }
                assertNotEquals(committed, stamp(graph), variant)
            }
        }
    }

    @Test
    fun `a closed repository hands out neither handles nor stamps`() {
        for ((variant, open) in memory()) {
            val repo = open()
            val graph = repo.getGraph(g1)
            repo.close()
            assertFailsWith<IllegalStateException>(variant) { repo.defaultGraph }
            assertFailsWith<IllegalStateException>(variant) { repo.getGraph(g1) }
            assertFailsWith<IllegalStateException>(variant) { stamp(graph) }
        }
    }
}
