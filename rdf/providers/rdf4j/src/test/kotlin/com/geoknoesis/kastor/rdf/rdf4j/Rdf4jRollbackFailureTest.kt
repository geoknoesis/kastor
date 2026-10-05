package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.SailConnection
import org.eclipse.rdf4j.sail.helpers.SailConnectionWrapper
import org.eclipse.rdf4j.sail.helpers.SailWrapper
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Rdf4jRollbackFailureTest {
    /** A store whose connections roll back and then fail. */
    private class FailingRollbackSail : SailWrapper(MemoryStore()) {
        override fun getConnection(): SailConnection = object : SailConnectionWrapper(super.getConnection()) {
            override fun rollback() {
                super.rollback()
                throw IllegalStateException("rollback failed")
            }
        }
    }

    @Test
    fun `a failing rollback does not replace the exception of the transaction`() {
        val sail = SailRepository(FailingRollbackSail()).also { it.init() }
        Rdf4jRepository(sail).use { repo ->
            val original = IllegalArgumentException("operations failed")
            val thrown = assertFailsWith<IllegalArgumentException> {
                repo.transaction {
                    editGraph(Iri("http://example.org/g")).addTriple(
                        RdfTriple(Iri("http://example.org/s"), Iri("http://example.org/p"), Literal("o"))
                    )
                    throw original
                }
            }
            assertSame(original, thrown)
            assertEquals(listOf("rollback failed"), thrown.suppressed.map { it.message })
            assertTrue(thrown.suppressed.single() is IllegalStateException)
        }
    }
}
