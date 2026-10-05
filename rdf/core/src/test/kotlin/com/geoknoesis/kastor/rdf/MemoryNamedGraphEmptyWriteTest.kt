package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** A write that adds nothing to a named graph neither creates its backing graph nor moves its stamp. */
class MemoryNamedGraphEmptyWriteTest {
    private val repo = MemoryRepository(RdfConfig(providerId = "memory"))
    private val name = Iri("urn:g")

    private fun stamp(): Long = (repo.getGraph(name) as VersionedRdfGraph).modificationStamp

    @Test
    fun `adding no triples leaves the stamp and the graph list alone`() {
        val before = stamp()
        repo.getGraph(name).let { it as MutableRdfGraph }.addTriples(emptyList())
        assertEquals(before, stamp())
        assertFalse(repo.hasGraph(name))
    }

    @Test
    fun `adding no triples to a closed repository still fails`() {
        val graph = repo.getGraph(name) as MutableRdfGraph
        repo.close()
        assertThrows(IllegalStateException::class.java) { graph.addTriples(emptyList()) }
    }

    @Test
    fun `a rolled back first write leaves the stamp as it was`() {
        val before = stamp()
        assertThrows(IllegalStateException::class.java) {
            repo.transaction {
                (getGraph(name) as MutableRdfGraph).addTriple(RdfTriple(Iri("urn:s"), Iri("urn:p"), Literal("a")))
                error("boom")
            }
        }
        assertEquals(before, stamp())
        assertFalse(repo.hasGraph(name))
    }
}
