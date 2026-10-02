package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.UpdateQuery
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import org.apache.jena.rdf.model.ModelFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Graph handles of a [JenaRepository] have value identity (repository instance + graph name) and a modification
 * stamp ([VersionedRdfGraph]) that changes with every write path and is never reused.
 */
class JenaGraphStampTest {
    // TDB2 keeps its store files memory-mapped for the JVM's lifetime, so Windows cannot delete them after the test.
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tmp: Path

    private val ex = "http://example.org/"
    private val p = Iri(ex + "p")
    private val g1 = Iri(ex + "g1")
    private val g2 = Iri(ex + "g2")
    private fun triple(n: Int) = RdfTriple(Iri(ex + "s$n"), p, Iri(ex + "o$n"))

    private fun repositories(): Map<String, () -> JenaRepository> = mapOf(
        "memory" to { JenaRepository.MemoryRepository() },
        "memory-inference" to { JenaRepository.MemoryRepositoryWithInference() },
        "tdb2" to { JenaRepository.Tdb2Repository(tmp.resolve("tdb2-" + System.nanoTime()).toString()) },
        "tdb2-inference" to { JenaRepository.Tdb2RepositoryWithInference(tmp.resolve("tdb2i-" + System.nanoTime()).toString()) },
    )

    private fun stamp(graph: RdfGraph): Long = (graph as VersionedRdfGraph).modificationStamp

    @Test
    fun `handles of the same graph of the same repository are equal`() {
        for ((variant, open) in repositories()) {
            open().use { repo ->
                open().use { other ->
                    assertEquals(repo.getGraph(g1), repo.getGraph(g1), variant)
                    assertEquals(repo.getGraph(g1).hashCode(), repo.getGraph(g1).hashCode(), variant)
                    assertEquals(repo.getGraph(g1), repo.editGraph(g1), variant)
                    assertEquals(repo.getGraph(g1), repo.createGraph(g1), variant)
                    assertEquals(repo.defaultGraph, repo.defaultGraph, variant)
                    assertEquals(repo.defaultGraph, repo.editDefaultGraph(), variant)
                    assertEquals(repo.defaultGraph.hashCode(), repo.editDefaultGraph().hashCode(), variant)
                    assertEquals(1, hashSetOf(repo.getGraph(g1), repo.getGraph(g1), repo.editGraph(g1)).size, variant)

                    assertNotEquals(repo.getGraph(g1), repo.getGraph(g2), variant)
                    assertNotEquals(repo.defaultGraph, repo.getGraph(g1), variant)
                    assertNotEquals(repo.getGraph(g1), other.getGraph(g1), "$variant: another repository instance")
                    assertNotEquals(repo.defaultGraph, other.defaultGraph, "$variant: another repository instance")
                }
            }
        }
    }

    @Test
    fun `every write path changes the stamp and no value is ever reused`() {
        for ((variant, open) in repositories()) {
            open().use { repo ->
                val graph = repo.getGraph(g1)
                val seen = LinkedHashSet<Long>()
                fun changed(step: String) {
                    assertEquals(stamp(graph), stamp(repo.getGraph(g1)), "$variant, $step: equal handles agree")
                    assertEquals(stamp(graph), stamp(graph), "$variant, $step: stable without writes")
                    assertTrue(seen.add(stamp(graph)), "$variant: stamp ${stamp(graph)} reused after $step (seen $seen)")
                }
                changed("start")
                repo.editGraph(g1).addTriple(triple(1)); changed("addTriple")
                repo.editGraph(g1).addTriples(listOf(triple(2), triple(3))); changed("addTriples")
                repo.editGraph(g1).addTriples(sequenceOf(triple(4))); changed("addTriples(Sequence)")
                repo.editGraph(g1).removeTriple(triple(1)); changed("removeTriple")
                repo.editGraph(g1).removeTriples(listOf(triple(2))); changed("removeTriples")
                repo.editGraph(g1).clear(); changed("graph clear")
                repo.editGraph(g1).addTriple(triple(1)); changed("add after clear")
                repo.update(UpdateQuery("INSERT DATA { GRAPH <${g1.value}> { <${ex}s9> <${p.value}> <${ex}o9> } }")); changed("update")
                JenaProvider().parseDataset(repo, "<${ex}s8> <${p.value}> <${ex}o8> <${g1.value}> .".byteInputStream(), "N-QUADS"); changed("load")
                repo.transaction { editGraph(g1).addTriple(triple(5)); editGraph(g1).addTriple(triple(6)) }; changed("transaction")
                assertFailsWith<IllegalStateException> {
                    repo.transaction { editGraph(g1).addTriple(triple(7)); error("roll back") }
                }
                changed("rollback")
                assertFalse(graph.hasTriple(triple(7)), variant)
                repo.removeGraph(g1); changed("removeGraph")
                repo.editGraph(g1).addTriple(triple(1)); changed("re-created graph")
                repo.clear(); changed("repository clear")
            }
        }
    }

    @Test
    fun `inside a write transaction the stamp follows the uncommitted writes and is never seen again after a rollback`() {
        for ((variant, open) in repositories()) {
            open().use { repo ->
                val graph = repo.getGraph(g1)
                val before = stamp(graph)
                val inside = ArrayList<Long>()
                assertFailsWith<IllegalStateException> {
                    repo.transaction {
                        inside.add(stamp(graph))
                        editGraph(g1).addTriple(triple(1))
                        inside.add(stamp(graph))
                        assertEquals(inside.last(), stamp(graph), "$variant: stable between writes")
                        update(UpdateQuery("INSERT DATA { GRAPH <${g1.value}> { <${ex}s9> <${p.value}> <${ex}o9> } }"))
                        inside.add(stamp(graph))
                        error("roll back")
                    }
                }
                assertEquals(3, inside.toSet().size, "$variant: every uncommitted write changes the stamp: $inside")
                val after = stamp(graph)
                assertFalse(after in inside.drop(1), "$variant: a stamp of rolled-back content must not come back ($after in $inside)")
                assertNotEquals(before, after, "$variant: a rollback changes the stamp")
            }
        }
    }

    /** A reader inside an older read transaction must not get the stamp that readers of the newer content get. */
    @Test
    @Timeout(120)
    fun `a read transaction older than a commit does not share the stamp of the committed content`() {
        for ((variant, open) in repositories()) {
            open().use { repo ->
                val graph = repo.getGraph(g1)
                repo.editGraph(g1).addTriple(triple(1))
                val inTransaction = CountDownLatch(1)
                val committed = CountDownLatch(1)
                val oldStamp = AtomicReference<Long>()
                val oldSeesNew = AtomicReference<Boolean>()
                val failure = AtomicReference<Throwable>()
                val reader = thread {
                    try {
                        repo.readTransaction {
                            graph.hasTriple(triple(1)) // the transaction has read the store before the commit
                            inTransaction.countDown()
                            assertTrue(committed.await(60, TimeUnit.SECONDS))
                            oldStamp.set(stamp(graph))
                            oldSeesNew.set(graph.hasTriple(triple(2)))
                        }
                    } catch (t: Throwable) {
                        failure.set(t)
                    }
                }
                assertTrue(inTransaction.await(60, TimeUnit.SECONDS), variant)
                repo.editGraph(g1).addTriple(triple(2))
                val newStamp = stamp(graph)
                committed.countDown()
                reader.join(60_000)
                assertNull(failure.get(), "$variant: ${failure.get()}")
                assertEquals(false, oldSeesNew.get(), "$variant: the older transaction still reads the older content")
                assertNotEquals(newStamp, oldStamp.get(), "$variant: older content must not be stamped like the newer content")
                assertNotEquals(oldStamp.get(), stamp(graph), variant)
            }
        }
    }

    @Test
    fun `a tdb2 stamp changes when another repository instance on the same location writes`() {
        for (inference in listOf(false, true)) {
            val location = tmp.resolve("shared-$inference").toString()
            fun open() = if (inference) JenaRepository.Tdb2RepositoryWithInference(location) else JenaRepository.Tdb2Repository(location)
            val first = open()
            val second = open()
            try {
                val graph = first.getGraph(g1)
                val defaultGraph = first.defaultGraph
                val s0 = stamp(graph)
                assertEquals(s0, stamp(graph))
                second.editGraph(g1).addTriple(triple(1))
                val s1 = stamp(graph)
                assertNotEquals(s0, s1, "a write through another instance must change the stamp")
                assertTrue(graph.hasTriple(triple(1)))
                assertEquals(s1, stamp(graph))
                second.editDefaultGraph().addTriple(triple(2))
                assertNotEquals(s1, stamp(defaultGraph))
                val s2 = stamp(graph)
                second.update(UpdateQuery("DELETE WHERE { GRAPH <${g1.value}> { ?s ?p ?o } }"))
                val s3 = stamp(graph)
                assertEquals(4, setOf(s0, s1, s2, s3).size, "no value is reused: $s0 $s1 $s2 $s3")
            } finally {
                first.close()
                second.close()
            }
        }
    }

    @Test
    fun `reading the stamp of a closed repository fails like a read`() {
        val repo = JenaRepository.MemoryRepository()
        val graph = repo.getGraph(g1)
        repo.close()
        assertFailsWith<IllegalStateException> { stamp(graph) }
    }

    @Test
    fun `views over caller-supplied Jena models and standalone graphs claim no stamp`() {
        val model = ModelFactory.createDefaultModel()
        val standalone = listOf(
            JenaBridge.fromJenaModel(model),
            JenaBridge.fromJenaModel(model, strictRead = false),
            JenaBridge.fromJenaGraph(model.graph),
            JenaBridge.createEmptyModel(),
            JenaBridge.createInferenceModel(),
            JenaProvider().parseGraph("<${ex}s> <${ex}p> <${ex}o> .".byteInputStream(), "TURTLE"),
        )
        for (graph in standalone) {
            // The model can be changed behind Kastor's back (it is handed out by JenaBridge.toJenaModel), so no
            // stamp could be trusted.
            assertFalse(graph is VersionedRdfGraph, "$graph")
        }
    }
}
