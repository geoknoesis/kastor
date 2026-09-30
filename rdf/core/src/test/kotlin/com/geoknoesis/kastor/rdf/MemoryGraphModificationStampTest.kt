package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun `a rolled back transaction changes the stamp of the default graph`() {
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
        assertTrue(g.modificationStamp > before)
    }
}
