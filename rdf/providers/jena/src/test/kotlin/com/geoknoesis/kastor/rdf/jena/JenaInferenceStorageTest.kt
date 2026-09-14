package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Inference views stay lazy (no heap copy of the closure) and follow commits made through any connection to a TDB2 location. */
class JenaInferenceStorageTest {
    // TDB2 keeps its store files memory-mapped for the JVM's lifetime, so Windows cannot delete them after the test.
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tmp: Path

    private val ex = "http://example.org/"
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private fun cls(n: Int) = Iri(ex + "C$n")
    private fun instance(n: Int) = Iri(ex + "i$n")

    private fun load(repo: JenaRepository, instances: Int) {
        repo.editDefaultGraph().addTriples(
            (0 until 5).map { RdfTriple(cls(it), subClassOf, cls(it + 1)) } +
                (0 until instances).map { RdfTriple(instance(it), type, cls(0)) },
        )
    }

    /**
     * The cached view is Jena's lazy inference graph: its forward deductions hold schema-level entailments only,
     * never a copy of the instance closure (which would be at least 5x the asserted instance triples here).
     */
    private fun assertLazyInference(repo: JenaRepository, instances: Int) {
        repo.readTransaction {
            assertTrue(defaultGraph.hasTriple(RdfTriple(instance(instances - 1), type, cls(5))))
            val rows = repo.withSelectRows(SparqlSelectQuery("SELECT ?c WHERE { <${instance(7).value}> a ?c }")) { it.count() }
            assertTrue(rows >= 6, "rows=$rows")
        }
        val inf = assertNotNull(repo.cachedInferenceGraph(), "the prepared inference graph must be cached")
        val deductions = inf.deductionsGraph.size()
        assertTrue(deductions < 1_000, "forward deductions must not materialise the instance closure: $deductions triples")
        assertFalse(inf.rawGraph is org.apache.jena.mem.GraphMem, "the inference graph must read the store, not a copy")
    }

    @Test
    @Timeout(300)
    fun `memory inference view over 100k statements is not materialised`() {
        JenaRepository.MemoryRepositoryWithInference().use { repo ->
            load(repo, 100_000)
            assertLazyInference(repo, 100_000)
            assertEquals(100_005, repo.withRead { repo.getJenaDataset().defaultModel.size() }.toInt())
        }
    }

    @Test
    @Timeout(300)
    fun `tdb2 inference view over 100k statements is not materialised`() {
        JenaRepository.Tdb2RepositoryWithInference(tmp.resolve("tdb2-large").toString()).use { repo ->
            load(repo, 100_000)
            assertLazyInference(repo, 100_000)
        }
    }

    @Test
    @Timeout(120)
    fun `two repositories on one TDB2 location see each other's commits in inference views`() {
        val location = tmp.resolve("shared").toString()
        val a = JenaRepository.Tdb2RepositoryWithInference(location)
        val b = JenaRepository.Tdb2RepositoryWithInference(location)
        val plain = JenaRepository.Tdb2Repository(location)
        try {
            a.editDefaultGraph().addTriples(listOf(RdfTriple(instance(0), type, cls(0)), RdfTriple(cls(0), subClassOf, cls(1))))
            assertTrue(a.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(1))), "A caches the first snapshot")
            assertTrue(b.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(1))), "B sees A's commit")

            b.editDefaultGraph().addTriple(RdfTriple(cls(1), subClassOf, cls(2)))
            assertTrue(a.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(2))), "A must not serve its stale cached view after B commits")

            plain.editDefaultGraph().addTriple(RdfTriple(cls(2), subClassOf, cls(3)))
            assertTrue(a.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(3))), "commits from a non-inference repository count too")
            assertTrue(b.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(3))))

            plain.editDefaultGraph().removeTriple(RdfTriple(cls(0), subClassOf, cls(1)))
            assertFalse(a.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(3))), "removals invalidate other instances' views")
        } finally {
            plain.close()
            b.close()
            a.close()
        }
    }
}
