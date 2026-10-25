package com.geoknoesis.kastor.rdf.sparql.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Query text given to [SparqlInitialBindings] is untrusted. The rewriter recurses into sub-selects and groups, so the
 * nesting it accepts is bounded: a deeper query is rejected with [IllegalArgumentException], never a
 * [StackOverflowError], and the work stays linear in the length of the query.
 */
class SparqlInitialBindingsDepthTest {
    private fun nestedSubSelects(depth: Int) =
        "SELECT ?v WHERE { " + "{ SELECT * WHERE ".repeat(depth) + "{ ?s ?p ?v }" + " }".repeat(depth) + " }"

    private fun nestedGroups(depth: Int) =
        "SELECT ?v WHERE { " + "{ ".repeat(depth) + "?s ?p ?v" + " }".repeat(depth) + " }"

    @Test
    fun `deeply nested sub-selects and groups are refused, not recursed into`() {
        for (query in listOf(nestedSubSelects(20_000), nestedGroups(20_000))) {
            assertTimeoutPreemptively(Duration.ofSeconds(20)) {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    SparqlInitialBindings.apply(query, mapOf("s" to "<urn:x>"))
                }
                assertTrue(error.message!!.contains("nest"), error.message)
                assertThrows(IllegalArgumentException::class.java) { SparqlInitialBindings.validate(query, setOf("s")) }
            }
        }
    }

    @Test
    fun `nesting within the limit is rewritten`() {
        val depth = SparqlInitialBindings.MAX_NESTING - 2
        for (query in listOf(nestedSubSelects(depth), nestedGroups(depth))) {
            val rewritten = SparqlInitialBindings.apply(query, mapOf("s" to "<urn:x>"))
            assertEquals(query.replace("?s", "<urn:x>"), rewritten)
        }
    }
}
