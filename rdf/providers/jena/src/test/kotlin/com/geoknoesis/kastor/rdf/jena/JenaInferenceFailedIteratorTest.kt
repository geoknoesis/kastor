package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** An inference iterator whose step failed stays failed: it never ends as if its results were complete. */
class JenaInferenceFailedIteratorTest {
    private val ex = "http://example.org/"
    private val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private fun cls(n: Int) = Iri(ex + "C$n")
    private fun instance(n: Int) = Iri(ex + "i$n")
    private val instances = 3_000

    private class StoreFailure : RuntimeException("the store failed under the reasoner")

    private fun repository(): JenaRepository = JenaRepository.MemoryRepositoryWithInference(Duration.ofMinutes(10)).also { repo ->
        repo.editDefaultGraph().addTriples(
            (0 until 5).map { RdfTriple(cls(it), subClassOf, cls(it + 1)) } + (0 until instances).map { RdfTriple(instance(it), type, cls(0)) },
        )
    }

    private fun find(repo: JenaRepository) = repo.readModel("", repo.getJenaDataset().defaultModel).graph
        .find(Node.ANY, NodeFactory.createURI(type.value), NodeFactory.createURI(cls(2).value))

    @Test
    @Timeout(120)
    fun `an iterator that failed after it delivered results fails again instead of ending`() {
        repository().use { repo ->
            repo.withRead {
                val iterator = find(repo)
                var delivered = 0
                assertTrue(iterator.hasNext())
                iterator.next()
                delivered++
                // From now on every store read of the reasoner fails.
                repo.hooks.onStoreRead = { throw StoreFailure() }
                val failure = assertFailsWith<StoreFailure> {
                    while (iterator.hasNext()) {
                        iterator.next()
                        delivered++
                    }
                }
                assertTrue(delivered < instances, "the failure must cut the result short ($delivered delivered)")
                repo.hooks.onStoreRead = {}
                // A caller that catches the failure and goes on must not see the truncated result as complete.
                repeat(3) {
                    assertSame(failure, assertFailsWith<StoreFailure> { iterator.hasNext() }, "hasNext after the failure")
                    assertSame(failure, assertFailsWith<StoreFailure> { iterator.next() }, "next after the failure")
                }
                iterator.close()
            }
            // The poisoned view was replaced: a new read is complete.
            assertEquals(instances, repo.defaultGraph.find(null, type, cls(2)).size)
        }
    }

    @Test
    @Timeout(120)
    fun `an iterator whose first step failed fails again instead of being empty`() {
        repository().use { repo ->
            // The graph is prepared: the find's own first step is the one that fails.
            assertTrue(repo.defaultGraph.hasTriple(RdfTriple(instance(0), type, cls(5))))
            repo.withRead {
                repo.hooks.onStoreRead = { throw StoreFailure() }
                val iterator = find(repo)
                val failure = assertFailsWith<StoreFailure> { iterator.hasNext() }
                repo.hooks.onStoreRead = {}
                assertSame(failure, assertFailsWith<StoreFailure> { iterator.hasNext() })
                assertSame(failure, assertFailsWith<StoreFailure> { iterator.next() })
                iterator.close()
            }
        }
    }
}
