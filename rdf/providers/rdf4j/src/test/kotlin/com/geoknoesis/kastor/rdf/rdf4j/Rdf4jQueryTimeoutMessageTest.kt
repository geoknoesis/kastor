package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

/** A query stopped by its time limit says so, and names the limit (as on the Jena provider). */
class Rdf4jQueryTimeoutMessageTest {

    private val slow = SparqlSelectQuery("SELECT * WHERE { ?a ?b ?c . ?d ?e ?f . ?g ?h ?i . ?j ?k ?l }")

    private fun repository(): RdfRepository = Rdf4jRepository.MemoryRepository().also { repo ->
        repo.editDefaultGraph().addTriples((1..200).map { RdfTriple(Iri("http://example.org/s$it"), Iri("http://example.org/p"), Literal("$it")) })
    }

    private fun assertTimeLimitMessage(error: RdfQueryException) {
        val message = error.message!!
        assertFalse(message.contains("null"), message)
        assertTrue(message.contains("time limit") && message.contains("50 ms"), message)
    }

    @Test
    fun `a select stopped by its time limit names the limit`() {
        repository().use { repo ->
            assertTimeLimitMessage(assertThrows(RdfQueryException::class.java) {
                repo.withSelectRows(slow, emptyMap(), Duration.ofMillis(50)) { it.count() }
            })
        }
    }

    @Test
    fun `a sandboxed select stopped by its time limit names the limit`() {
        repository().use { repo ->
            assertTimeLimitMessage(assertThrows(RdfQueryException::class.java) {
                repo.withSelectRows(SandboxedSelectQuery(slow.sparql), emptyMap(), Duration.ofMillis(50)) { it.count() }
            })
        }
    }
}
