package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlAskQuery
import com.geoknoesis.kastor.rdf.VersionedRdfGraph
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Jena's special graph names: `urn:x-arq:DefaultGraph` is the default graph, `urn:x-arq:UnionGraph` is a read-only
 * view of every named graph and therefore changes with a write to any of them.
 */
class JenaSpecialGraphNamesTest {
    // TDB2 keeps its store files memory-mapped for the JVM's lifetime, so Windows cannot delete them after the test.
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tmp: Path

    private val ex = "http://example.org/"
    private val g1 = Iri(ex + "g1")
    private val g2 = Iri(ex + "g2")
    private val union = Iri("urn:x-arq:UnionGraph")
    private val defaultAliases = listOf(Iri("urn:x-arq:DefaultGraph"), Iri("urn:x-arq:DefaultGraphNode"))
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private fun cls(n: Int) = Iri(ex + "C$n")
    private val x = Iri(ex + "x")

    private fun repositories(): Map<String, () -> JenaRepository> = mapOf(
        "memory" to { JenaRepository.MemoryRepository() },
        "memory-inference" to { JenaRepository.MemoryRepositoryWithInference(Duration.ofMinutes(10)) },
        "tdb2" to { JenaRepository.Tdb2Repository(tmp.resolve("tdb2-" + System.nanoTime()).toString()) },
        "tdb2-inference" to { JenaRepository.Tdb2RepositoryWithInference(tmp.resolve("tdb2i-" + System.nanoTime()).toString(), Duration.ofMinutes(10)) },
    )

    private fun stamp(graph: RdfGraph): Long = (graph as VersionedRdfGraph).modificationStamp

    @Test
    @Timeout(120)
    fun `the default graph alias is the default graph`() {
        for ((variant, open) in repositories()) {
            for (alias in defaultAliases) {
                open().use { repo ->
                    val what = "$variant, $alias"
                    assertEquals(repo.defaultGraph, repo.getGraph(alias), what)
                    assertEquals(repo.defaultGraph.hashCode(), repo.getGraph(alias).hashCode(), what)
                    assertEquals(repo.defaultGraph, repo.editGraph(alias), what)
                    repo.editGraph(alias).addTriple(RdfTriple(x, type, cls(0)))
                    assertTrue(repo.defaultGraph.hasTriple(RdfTriple(x, type, cls(0))), what)
                    assertEquals(emptyList(), repo.listGraphs(), "$what: no named graph was created")
                    assertFailsWith<IllegalArgumentException>(what) { repo.removeGraph(alias) }
                    assertTrue(repo.defaultGraph.hasTriple(RdfTriple(x, type, cls(0))), what)
                }
            }
        }
    }

    @Test
    @Timeout(120)
    fun `a write through the default graph alias is seen by the kept inference view of the default graph`() {
        for (alias in defaultAliases) {
            JenaRepository.MemoryRepositoryWithInference(Duration.ofMinutes(10)).use { repo ->
                repo.editDefaultGraph().addTriples(listOf(RdfTriple(x, type, cls(0)), RdfTriple(cls(0), subClassOf, cls(1))))
                assertTrue(repo.defaultGraph.hasTriple(RdfTriple(x, type, cls(1))))
                assertNotNull(repo.currentInferenceView())
                repo.editGraph(alias).addTriple(RdfTriple(cls(1), subClassOf, cls(2)))
                assertTrue(repo.defaultGraph.hasTriple(RdfTriple(x, type, cls(2))), "$alias: the default graph was written")
                assertTrue(repo.getGraph(alias).hasTriple(RdfTriple(x, type, cls(2))), "$alias")
            }
        }
    }

    @Test
    @Timeout(120)
    fun `the union graph is a read-only view of every named graph`() {
        for ((variant, open) in repositories()) {
            open().use { repo ->
                repo.transaction {
                    editDefaultGraph().addTriple(RdfTriple(x, type, cls(9)))
                    editGraph(g1).addTriple(RdfTriple(x, type, cls(1)))
                    editGraph(g2).addTriple(RdfTriple(x, type, cls(2)))
                }
                val view = repo.getGraph(union)
                assertTrue(view.hasTriple(RdfTriple(x, type, cls(1))), variant)
                assertTrue(view.hasTriple(RdfTriple(x, type, cls(2))), variant)
                assertFalse(view.hasTriple(RdfTriple(x, type, cls(9))), "$variant: the default graph is not part of the union")
                assertEquals(listOf(g1, g2), repo.listGraphs().sortedBy { it.value }, "$variant: the union graph is not listed")

                val mutable = view as com.geoknoesis.kastor.rdf.MutableRdfGraph
                assertFailsWith<UnsupportedOperationException>(variant) { mutable.addTriple(RdfTriple(x, type, cls(3))) }
                assertFailsWith<UnsupportedOperationException>(variant) { mutable.removeTriple(RdfTriple(x, type, cls(1))) }
                assertFailsWith<UnsupportedOperationException>(variant) { mutable.clear() }
                assertFailsWith<RuntimeException>(variant) {
                    JenaBridge.getJenaGraph(view)!!.add(JenaTerms.toJenaTriple(RdfTriple(x, type, cls(3))))
                }
                assertFailsWith<IllegalArgumentException>(variant) { repo.removeGraph(union) }
                assertTrue(repo.getGraph(g1).hasTriple(RdfTriple(x, type, cls(1))), "$variant: nothing was removed")
                assertTrue(repo.getGraph(g2).hasTriple(RdfTriple(x, type, cls(2))), "$variant: nothing was removed")
                assertEquals(listOf(g1, g2), repo.listGraphs().sortedBy { it.value }, variant)

                val before = stamp(view)
                repo.editGraph(g2).addTriple(RdfTriple(x, type, cls(4)))
                assertNotEquals(before, stamp(view), "$variant: a write to a member graph moves the union graph's stamp")
                assertTrue(view.hasTriple(RdfTriple(x, type, cls(4))), variant)
            }
        }
    }

    @Test
    @Timeout(120)
    fun `the inference view of the union graph follows commits to its member graphs`() {
        for ((variant, open) in repositories().filterKeys { it.endsWith("-inference") }) {
            open().use { repo ->
                repo.transaction {
                    editGraph(g1).addTriples(listOf(RdfTriple(x, type, cls(0)), RdfTriple(cls(0), subClassOf, cls(1))))
                    editGraph(g2).addTriple(RdfTriple(cls(1), subClassOf, cls(2)))
                }
                val view = repo.getGraph(union)
                fun askUnion(n: Int) = repo.ask(SparqlAskQuery("ASK { GRAPH <${union.value}> { <${x.value}> a <${cls(n).value}> } }"))
                // The union is prepared in the shared view: entailments span the member graphs.
                assertTrue(view.hasTriple(RdfTriple(x, type, cls(2))), variant)
                assertTrue(askUnion(2), variant)
                val prepared = assertNotNull(repo.currentInferenceView())

                // A commit to a member graph that the view has not prepared by itself.
                repo.editGraph(g2).addTriple(RdfTriple(cls(2), subClassOf, cls(3)))
                assertNotSame(prepared, repo.currentInferenceView(), "$variant: a view that has prepared the union graph is retired by any commit")
                assertTrue(view.hasTriple(RdfTriple(x, type, cls(3))), "$variant: stale union graph after a commit to a member")
                assertTrue(askUnion(3), "$variant: stale union graph in a query after a commit to a member")

                // A new named graph is a member too.
                repo.editGraph(Iri(ex + "g3")).addTriple(RdfTriple(cls(3), subClassOf, cls(4)))
                assertTrue(askUnion(4), "$variant: stale union graph in a query after a commit to a new member")
                assertTrue(view.hasTriple(RdfTriple(x, type, cls(4))), "$variant: stale union graph after a commit to a new member")

                repo.removeGraph(g2)
                assertFalse(view.hasTriple(RdfTriple(x, type, cls(2))), "$variant: stale union graph after a member was removed")
                assertFalse(askUnion(2), variant)
            }
        }
    }

    @Test
    @Timeout(120)
    fun `a kept inference view never serves the union graph as it was before a commit`() {
        JenaRepository.MemoryRepositoryWithInference(Duration.ofMinutes(10)).use { repo ->
            repo.transaction {
                editDefaultGraph().addTriple(RdfTriple(x, type, cls(9)))
                editGraph(g1).addTriples(listOf(RdfTriple(x, type, cls(0)), RdfTriple(cls(0), subClassOf, cls(1))))
            }
            // The shared view has prepared the default graph only, so a commit to a named graph keeps it ...
            assertTrue(repo.defaultGraph.hasTriple(RdfTriple(x, type, cls(9))))
            val kept = assertNotNull(repo.currentInferenceView())
            repo.editGraph(g2).addTriple(RdfTriple(cls(1), subClassOf, cls(2)))
            assertSame(kept, repo.currentInferenceView(), "the commit wrote no graph the view has prepared")
            // ... but the union graph, first read now, must not be prepared on the kept view's older snapshot.
            assertTrue(repo.getGraph(union).hasTriple(RdfTriple(x, type, cls(2))), "the union graph of an older snapshot was served")
            assertNotSame(kept, repo.currentInferenceView(), "the union graph needs a view of the snapshot that contains the commit")
        }
    }
}
