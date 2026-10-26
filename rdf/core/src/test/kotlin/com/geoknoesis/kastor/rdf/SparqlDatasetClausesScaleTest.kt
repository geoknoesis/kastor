package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The dataset-clause scanner reads untrusted query text, so its work must grow linearly with the query. Each
 * codepoint escape is placed among the strings, IRIs and comments of the query: a query with many of both used to
 * cost their product. The bound is generous (a linear scan takes well under a second); quadratic work takes minutes.
 */
class SparqlDatasetClausesScaleTest {
    /** A backslash followed by `u`: the start of a SPARQL codepoint escape, spelled so Kotlin does not decode it. */
    private val u = "\\" + "u"

    @Test
    fun `a query with many escaped strings is analysed in linear time`() {
        val count = 100_000
        val strings = (1..count).joinToString(", ") { "\"a${u}0041\"" }
        val query = "SELECT ?o WHERE { ?s ?p ?o FILTER(?o IN ($strings)) }"
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            assertFalse(SparqlDatasetClauses.declaresDataset(query))
            assertEquals(query, SparqlDatasetClauses.canonical(query))
        }
    }

    @Test
    fun `escapes outside strings among many strings are decoded in linear time`() {
        val count = 100_000
        val strings = (1..count).joinToString(", ") { "\"a\"" }
        val query = "SELECT ?o WHERE { ?s ?p ?o FILTER(?o IN ($strings)) } ${u}0046ROM"
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            assertEquals(true, SparqlDatasetClauses.declaresDataset(query))
        }
    }

    @Test
    fun `many GRAPH patterns are rewritten in linear time`() {
        val count = 20_000
        val flat = (1..count).joinToString(" ") { "GRAPH ?g$it { ?s ?p ?o$it }" }
        val sub = (1..count).joinToString(" ") { "{ SELECT ?x$it WHERE { GRAPH ?h$it { ?x$it ?p ?o } } }" }
        // Each enclosing group reads its content once more: the cost is the length times the depth, which is bounded.
        val nestedWithinLimit = "GRAPH ?g { ".repeat(SparqlDatasetClauses.MAX_GROUP_DEPTH - 2) + flat.take(flat.length / 4).substringBeforeLast(" GRAPH") +
            " }".repeat(SparqlDatasetClauses.MAX_GROUP_DEPTH - 2)
        for (body in listOf(flat, sub, nestedWithinLimit)) {
            val query = "SELECT * WHERE { $body }"
            assertTimeoutPreemptively(Duration.ofSeconds(20)) {
                assertFalse(SparqlDatasetClauses.withoutNamedGraphs(query) == query)
                SparqlDatasetClauses.insert(query, "FROM <urn:g>")
            }
        }
    }

    @Test
    fun `groups nested deeper than the limit are refused, not recursed into`() {
        val depth = 20_000
        val nested = "SELECT * WHERE { " + "GRAPH ?g { ".repeat(depth) + "?s ?p ?o" + " }".repeat(depth) + " }"
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            // Before the limit this overflowed the stack (StackOverflowError) in the scope analysis.
            assertEquals(null, SparqlDatasetClauses.withoutNamedGraphs(nested))
        }
        val atLimit = "SELECT * WHERE { " + "GRAPH ?g { ".repeat(SparqlDatasetClauses.MAX_GROUP_DEPTH - 1) + "?s ?p ?o" +
            " }".repeat(SparqlDatasetClauses.MAX_GROUP_DEPTH - 1) + " }"
        assertFalse(SparqlDatasetClauses.withoutNamedGraphs(atLimit) == null)
        val error = Rdf.memory().use { repository ->
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                Dataset { defaultGraph(repository) }.select(SparqlSelectQuery(nested))
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(error.message!!.contains("nest"), error.message)
    }
    /** Round thirteen, core P2-1: leading unbalanced `}` drove the count negative, so 5,000 groups overflowed the stack. */
    @Test
    fun `unbalanced closing braces cannot hide deeper nesting`() {
        for (n in listOf(1000, 5000, 20_000)) {
            val query = "}".repeat(n) + " SELECT * WHERE { GRAPH ?g " + "{ ?s ?p ?o ".repeat(n) + "}".repeat(n) + " }"
            assertTimeoutPreemptively(Duration.ofSeconds(20)) {
                assertEquals(null, SparqlDatasetClauses.withoutNamedGraphs(query))
            }
        }
    }
}
