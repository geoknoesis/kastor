package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Behaviour of the graph-only in-core memory store (providerId = "memory"), not Jena. */
class MemoryTransactionReauditTest {
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")
    private val t1 = RdfTriple(s, p, string("1"))
    private val t2 = RdfTriple(s, p, string("2"))
    private val t3 = RdfTriple(s, p, string("3"))
    private val g1 = Iri("urn:g1")
    private val g2 = Iri("urn:g2")
    private val g3 = Iri("urn:g3")

    @Test
    fun `a failing undo action does not stop the rollback or hide the original error`() {
        val repo = MemoryRepository(RdfConfig(providerId = "memory", variantId = "memory"))
        repo.editDefaultGraph().addTriple(t1)
        val original = IllegalStateException("original failure")

        val thrown = assertThrows(IllegalStateException::class.java) {
            repo.transaction {
                editDefaultGraph().addTriple(t2)
                repo.recordUndo { throw IllegalArgumentException("undo 1 failed") }
                editGraph(g1).addTriple(t3)
                repo.recordUndo { throw IllegalArgumentException("undo 2 failed") }
                throw original
            }
        }

        assertSame(original, thrown)
        assertEquals(listOf("undo 2 failed", "undo 1 failed"), thrown.suppressed.map { it.message })
        assertEquals(listOf(t1), repo.defaultGraph.getTriples())
        assertFalse(repo.hasGraph(g1))
        repo.transaction { editDefaultGraph().addTriple(t2) } // lock released, repository usable
        assertEquals(listOf(t1, t2), repo.defaultGraph.getTriples())
        repo.close()
    }

    @Test
    fun `created graphs are listed while empty and follow remove, clear and rollback`() {
        val repo = RdfProviderRegistry.create(RdfConfig(providerId = "memory", variantId = "memory"))

        val created = repo.createGraph(g1)
        assertEquals(0, created.size())
        assertTrue(repo.hasGraph(g1))
        assertEquals(listOf(g1), repo.listGraphs())

        repo.editGraph(g1).addTriple(t1)
        repo.editGraph(g1).removeTriple(t1)
        assertTrue(repo.hasGraph(g1), "an explicitly created graph stays when emptied")

        assertTrue(repo.removeGraph(g1))
        assertFalse(repo.hasGraph(g1))
        assertTrue(repo.listGraphs().isEmpty())

        repo.editGraph(g2).addTriple(t1)
        repo.editGraph(g2).removeTriple(t1)
        assertFalse(repo.hasGraph(g2), "a graph created implicitly by a write disappears when emptied")

        assertThrows(IllegalStateException::class.java) { repo.transaction { createGraph(g3); error("rollback") } }
        assertFalse(repo.hasGraph(g3))

        repo.createGraph(g1)
        assertThrows(IllegalStateException::class.java) { repo.transaction { removeGraph(g1); error("rollback") } }
        assertTrue(repo.hasGraph(g1))
        assertThrows(IllegalStateException::class.java) { repo.transaction { clear(); error("rollback") } }
        assertTrue(repo.hasGraph(g1))

        repo.clear()
        assertFalse(repo.hasGraph(g1))
        repo.close()
    }
}
