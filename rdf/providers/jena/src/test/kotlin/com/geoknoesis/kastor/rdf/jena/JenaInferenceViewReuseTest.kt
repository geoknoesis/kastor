package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.UpdateQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An in-memory inference view survives commits that did not write to a graph it has prepared, and never serves a
 * graph that was written after the view's snapshot.
 */
class JenaInferenceViewReuseTest {
    // TDB2 keeps its store files memory-mapped for the JVM's lifetime, so Windows cannot delete them after the test.
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tmp: Path

    private val ex = "http://example.org/"
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private fun cls(n: Int) = Iri(ex + "C$n")
    private fun instance(n: Int) = Iri(ex + "i$n")
    private val g1 = Iri(ex + "g1")
    private val g2 = Iri(ex + "g2")
    private val instances = 500

    private fun schema() = (0 until 5).map { RdfTriple(cls(it), subClassOf, cls(it + 1)) }

    private fun load(repo: JenaRepository) {
        repo.transaction {
            editDefaultGraph().addTriples(schema() + (0 until instances).map { RdfTriple(instance(it), type, cls(0)) })
            editGraph(g1).addTriples(schema() + RdfTriple(instance(1), type, cls(0)))
        }
    }

    private fun memory(): JenaRepository = JenaRepository.MemoryRepositoryWithInference(Duration.ofMinutes(10)).also(::load)

    /** Prepares the default graph in the shared view of the current snapshot and returns the view's identity. */
    private fun warmUp(repo: JenaRepository): Any {
        assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(5))))
        return assertNotNull(repo.currentInferenceView())
    }

    @Test
    @Timeout(120)
    fun `a commit to a graph the view has not prepared keeps the view and its prepared graphs`() {
        memory().use { repo ->
            val view = warmUp(repo)
            val prepared = assertNotNull(repo.cachedInferenceGraph())
            repeat(3) { n ->
                repo.editGraph(g2).addTriple(RdfTriple(instance(100 + n), type, cls(0)))
                assertSame(view, repo.currentInferenceView(), "commit $n did not touch the default graph")
                assertEquals(instances, repo.defaultGraph.find(null, type, cls(4)).size)
                assertSame(view, repo.currentInferenceView())
                assertSame(prepared, repo.cachedInferenceGraph(), "the default graph was not prepared again")
            }
            // A transaction that writes nothing, and one that is rolled back, do not cost the view either.
            repo.transaction { defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(0))) }
            runCatching { repo.transaction { editGraph(g2).addTriple(RdfTriple(instance(200), type, cls(0))); error("roll back") } }
            assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(5))))
            assertSame(view, repo.currentInferenceView())
            assertSame(prepared, repo.cachedInferenceGraph())
        }
    }

    @Test
    @Timeout(120)
    fun `a graph written after the view's snapshot is never read from that view`() {
        memory().use { repo ->
            val view = warmUp(repo)
            // g2 is not prepared in the view: the commit keeps the view, but g2 must be read from the new snapshot.
            repo.editGraph(g2).addTriples(schema() + RdfTriple(instance(7), type, cls(0)))
            assertSame(view, repo.currentInferenceView())
            assertTrue(repo.getGraph(g2).hasTriple(RdfTriple(instance(7), type, cls(5))), "g2 as committed, with its entailments")
            assertNotSame(view, repo.currentInferenceView(), "g2 needs a view of the snapshot that contains it")

            // The same for a graph that existed before but was not prepared: g1 changes, then is read.
            val second = assertNotNull(repo.currentInferenceView())
            assertFalse(repo.getGraph(g2).hasTriple(RdfTriple(instance(8), type, cls(5))))
            repo.editGraph(g1).addTriple(RdfTriple(instance(8), type, cls(0)))
            assertTrue(repo.getGraph(g1).hasTriple(RdfTriple(instance(8), type, cls(5))))
            assertTrue(repo.getGraph(g1).hasTriple(RdfTriple(instance(1), type, cls(5))))
            assertNotSame(second, repo.currentInferenceView())
        }
    }

    @Test
    @Timeout(120)
    fun `a commit to a prepared graph replaces the view`() {
        memory().use { repo ->
            val view = warmUp(repo)
            repo.editDefaultGraph().addTriple(RdfTriple(cls(5), subClassOf, cls(6)))
            assertNotSame(view, repo.currentInferenceView())
            assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(6))))
            val second = assertNotNull(repo.currentInferenceView())
            repo.editDefaultGraph().removeTriple(RdfTriple(cls(5), subClassOf, cls(6)))
            assertFalse(repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(6))))
            assertNotSame(second, repo.currentInferenceView())
        }
    }

    @Test
    @Timeout(120)
    fun `writes whose target graphs are not tracked replace the view`() {
        val top = Iri(ex + "Top")
        val writes: Map<String, (JenaRepository) -> Unit> = mapOf(
            "update" to { repo -> repo.update(UpdateQuery("INSERT DATA { <${cls(5).value}> <${subClassOf.value}> <${top.value}> }")) },
            "load" to { repo -> JenaProvider().parseDataset(repo, "<${cls(5).value}> <${subClassOf.value}> <${top.value}> .".byteInputStream(), "N-QUADS") },
            "load in a transaction" to { repo ->
                repo.transaction { JenaProvider().parseDataset(this, "<${cls(5).value}> <${subClassOf.value}> <${top.value}> .".byteInputStream(), "N-QUADS") }
            },
        )
        for ((name, write) in writes) {
            memory().use { repo ->
                val view = warmUp(repo)
                write(repo)
                assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, top)), "$name: the default graph changed")
                assertNotSame(view, repo.currentInferenceView(), name)
            }
        }
        memory().use { repo ->
            val view = warmUp(repo)
            repo.clear()
            assertFalse(repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(5))), "clear")
            assertNotSame(view, repo.currentInferenceView())
        }
        memory().use { repo ->
            assertTrue(repo.getGraph(g1).hasTriple(RdfTriple(instance(1), type, cls(5))))
            val view = assertNotNull(repo.currentInferenceView())
            repo.removeGraph(g1)
            assertFalse(repo.getGraph(g1).hasTriple(RdfTriple(instance(1), type, cls(5))), "removeGraph")
            assertNotSame(view, repo.currentInferenceView())
        }
    }

    @Test
    @Timeout(120)
    fun `one transaction reads an unchanged graph from the kept view and a changed graph from a new one`() {
        memory().use { repo ->
            val view = warmUp(repo)
            repo.editGraph(g2).addTriples(schema() + RdfTriple(instance(7), type, cls(0)))
            assertSame(view, repo.currentInferenceView())
            val rows = repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s a <${cls(4).value}> }")) { rows ->
                val iterator = rows.iterator()
                iterator.next() // served by the kept view
                assertSame(view, repo.currentInferenceView())
                assertTrue(repo.getGraph(g2).hasTriple(RdfTriple(instance(7), type, cls(5)))) // needs a newer view
                assertNotSame(view, repo.currentInferenceView())
                // The iterator opened on the kept view goes on: the transaction holds both views until it ends.
                var n = 1
                while (iterator.hasNext()) { iterator.next(); n++ }
                n
            }
            assertEquals(instances, rows)
        }
    }

    /**
     * The kept-view path, step by step: commits alternate between two graphs, and after each one the graph that was
     * **not** written is read from the view kept across the commit (counted by
     * [JenaRepository.keptInferenceViewReads]) while the written graph is read from a fresh view. Neither read may
     * miss a committed link.
     */
    @Test
    @Timeout(120)
    fun `after each alternating commit the unchanged graph is read from the kept view and the written graph from a fresh one`() {
        JenaRepository.MemoryRepositoryWithInference(Duration.ofMinutes(10)).use { repo ->
            val x = Iri(ex + "x")
            repo.transaction { listOf(g1, g2).forEach { editGraph(it).addTriple(RdfTriple(x, type, cls(0))) } }
            val links = mutableMapOf(g1 to 0, g2 to 0)
            fun chain(g: Iri): Set<Iri> =
                repo.getGraph(g).find(x, type, null).map { it.obj }.filterIsInstance<Iri>().filter { it.value.startsWith(ex + "C") }.toSet()
            fun committed(g: Iri): Set<Iri> = (0..links.getValue(g)).map(::cls).toSet()

            // The current view has prepared g1 only.
            assertEquals(committed(g1), chain(g1))
            var view = assertNotNull(repo.currentInferenceView())
            assertEquals(setOf(g1.value), repo.preparedInferenceGraphs())
            assertEquals(0, repo.keptInferenceViewReads(), "no commit yet: every read was of the view's own snapshot")

            var unchanged = g1
            var written = g2
            for (round in 1..20) {
                repo.editGraph(written).addTriple(RdfTriple(cls(links.getValue(written)), subClassOf, cls(links.getValue(written) + 1)))
                links[written] = links.getValue(written) + 1
                assertSame(view, repo.currentInferenceView(), "round $round: the commit to $written wrote no graph the view has prepared")

                val keptBefore = repo.keptInferenceViewReads()
                assertEquals(committed(unchanged), chain(unchanged), "round $round: $unchanged from the kept view")
                assertEquals(keptBefore + 1, repo.keptInferenceViewReads(), "round $round: the read of $unchanged must be served by the kept view")
                assertSame(view, repo.currentInferenceView(), "round $round")

                assertEquals(committed(written), chain(written), "round $round: $written must be read as committed")
                assertEquals(keptBefore + 1, repo.keptInferenceViewReads(), "round $round: the written graph is not served by the kept view")
                val fresh = assertNotNull(repo.currentInferenceView())
                assertNotSame(view, fresh, "round $round: $written needs a view of the snapshot that contains the commit")
                assertEquals(setOf(written.value), repo.preparedInferenceGraphs(), "round $round")

                // The fresh view has prepared the graph just read: the roles swap for the next commit.
                view = fresh
                unchanged = written.also { written = unchanged }
            }
            assertEquals(20, repo.keptInferenceViewReads())
        }
    }

    /**
     * Commits alternate between two graphs while readers keep views of both alive: whatever view a reader is handed
     * (kept or fresh), a graph is never read as it was before its latest commit. (Which view serves a read depends on
     * the interleaving here; the kept-view path is pinned down by the test above.)
     */
    @Test
    @Timeout(180)
    fun `kept views never serve a stale graph while commits alternate between graphs`() {
        JenaRepository.MemoryRepositoryWithInference(Duration.ofMinutes(10)).use { repo ->
            val x = Iri(ex + "x")
            val graphs = listOf(g1, g2)
            repo.transaction { graphs.forEach { editGraph(it).addTriple(RdfTriple(x, type, cls(0))) } }
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            // What is committed for sure: links[g] = n means cls(0) .. cls(n) are chained in g.
            val links = graphs.associateWith { java.util.concurrent.atomic.AtomicInteger(0) }
            val readers = (0 until 4).map { r ->
                kotlin.concurrent.thread {
                    try {
                        while (!done.get()) {
                            // Read before the transaction begins: its snapshot contains at least these commits.
                            val committed = graphs.associateWith { links.getValue(it).get() }
                            repo.readTransaction {
                                for (g in if (r % 2 == 0) graphs else graphs.reversed()) {
                                    val before = committed.getValue(g)
                                    val types = getGraph(g).find(x, type, null).map { it.obj }.toSet()
                                    // Everything committed before this transaction began is entailed ...
                                    assertTrue(types.containsAll((0..before).map(::cls)), "$g: missing a committed link <= $before in $types")
                                    // ... and the chain has no gap: a snapshot is read as a whole.
                                    val chained = types.count { it is Iri && it.value.startsWith(ex + "C") }
                                    assertTrue((0 until chained).all { cls(it) in types }, "$g: gap in $types")
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        failures.add(t)
                    }
                }
            }
            try {
                for (i in 0 until 30) {
                    for (g in graphs) {
                        repo.editGraph(g).addTriple(RdfTriple(cls(i), subClassOf, cls(i + 1)))
                        links.getValue(g).set(i + 1)
                        assertTrue(repo.getGraph(g).hasTriple(RdfTriple(x, type, cls(i + 1))), "$g: stale inference after commit $i")
                        val other = graphs.first { it != g }
                        assertTrue(repo.getGraph(other).hasTriple(RdfTriple(x, type, cls(links.getValue(other).get()))), "$other after a commit to $g")
                    }
                }
            } finally {
                done.set(true)
                readers.forEach { it.join(60_000) }
            }
            assertTrue(failures.isEmpty(), "$failures")
        }
    }

    @Test
    @Timeout(120)
    fun `a tdb2 view is replaced by every commit`() {
        JenaRepository.Tdb2RepositoryWithInference(tmp.resolve("reuse").toString(), Duration.ofMinutes(10)).use { repo ->
            load(repo)
            val view = warmUp(repo)
            // Other clients of the location may write any graph: only the data version tells snapshots apart.
            repo.editGraph(g2).addTriple(RdfTriple(instance(100), type, cls(0)))
            assertNotSame(view, repo.currentInferenceView())
            assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(5))))
            assertNotSame(view, repo.currentInferenceView())
        }
    }
}
