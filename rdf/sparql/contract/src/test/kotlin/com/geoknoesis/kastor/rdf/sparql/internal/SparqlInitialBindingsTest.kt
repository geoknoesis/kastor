package com.geoknoesis.kastor.rdf.sparql.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Text-level checks of [SparqlInitialBindings]. Result parity with Jena's `substitution()` is tested
 * in `:rdf:sparql` (which has Jena on its test classpath).
 */
class SparqlInitialBindingsTest {

    private val s = mapOf("s" to "<urn:a>")

    private fun apply(query: String) = SparqlInitialBindings.apply(query, s)

    @Test
    fun `bound variables are substituted in patterns and aliased in the projection`() {
        assertEquals(
            "SELECT (<urn:a> AS ?s) ?o WHERE { <urn:a> <urn:p> ?o }",
            apply("SELECT ?s ?o WHERE { ?s <urn:p> ?o }"),
        )
    }

    @Test
    fun `codepoint escapes are decoded before tokenizing while other text is kept verbatim`() {
        // ?\u0073 is ?s; escapes elsewhere stay as written.
        assertEquals(
            "SELECT (<urn:a> AS ?s) WHERE { <urn:a> <urn:\\u0070> \"\\u0041\" }",
            apply("SELECT ?\\u0073 WHERE { ?s <urn:\\u0070> \"\\u0041\" }"),
        )
        assertEquals(
            "\\u0053ELECT ?o WHERE { <urn:a> ?p ?o }",
            apply("\\u0053ELECT ?o WHERE { ?\\U00000073 ?p ?o }"),
        )
        // An escaped backslash does not start a codepoint escape: the string still contains "?s".
        assertEquals(
            "SELECT ?o WHERE { <urn:a> ?p \"\\\\u0022 ?s\" }",
            apply("SELECT ?o WHERE { ?s ?p \"\\\\u0022 ?s\" }"),
        )
        // A decoded quote ends the string, so the ?s after it is query syntax.
        assertEquals(
            "SELECT ?o WHERE { ?x ?p ?o FILTER(STR(?o) != \"x\\u0022 && <urn:a> = <urn:a> && \\u0022\" = \"\") }",
            apply("SELECT ?o WHERE { ?x ?p ?o FILTER(STR(?o) != \"x\\u0022 && ?s = ?s && \\u0022\" = \"\") }"),
        )
    }

    @Test
    fun `rendered literals containing backslash-u text are one token under the codepoint pre-pass`() {
        val literal = SparqlLexical.quoted("\\u0022 ?s")
        assertEquals(
            "SELECT ?o WHERE { <urn:a> ?p $literal }",
            apply("SELECT ?o WHERE { ?s ?p $literal }"),
        )
    }

    @Test
    fun `escaped characters in prefixed local names do not start comments`() {
        assertEquals(
            "PREFIX ex: <urn:> SELECT ?o WHERE { <urn:a> ex:a\\#b ?o . FILTER(<urn:a> != ex:c\\?d) }",
            apply("PREFIX ex: <urn:> SELECT ?o WHERE { ?s ex:a\\#b ?o . FILTER(?s != ex:c\\?d) }"),
        )
    }

    @Test
    fun `COUNT(*) is not a star projection`() {
        // The middle sub-select does not project ?s, so ?s is local to it and cannot be bound.
        assertThrows(IllegalArgumentException::class.java) {
            apply("SELECT ?c WHERE { ?s ?p ?x { SELECT (COUNT(*) AS ?c) WHERE { { SELECT ?s WHERE { ?s ?p ?o } } } } }")
        }
    }

    @Test
    fun `a bound variable projected at several levels is aliased only once in scope`() {
        assertEquals(
            "SELECT (<urn:a> AS ?s) ?o WHERE { { ?x ?q ?o } UNION { SELECT (<urn:a> AS ?s_bound) ?o WHERE { <urn:a> ?p ?o } } }",
            apply("SELECT ?s ?o WHERE { { ?x ?q ?o } UNION { SELECT ?s ?o WHERE { ?s ?p ?o } } }"),
        )
        // The fresh name never collides with a variable of the query.
        assertEquals(
            "SELECT (<urn:a> AS ?s) (<urn:a> AS ?s_bound) WHERE { { SELECT (<urn:a> AS ?s_bound1) WHERE { <urn:a> ?p ?o } } }",
            SparqlInitialBindings.apply(
                "SELECT ?s ?s_bound WHERE { { SELECT ?s WHERE { ?s ?p ?o } } }",
                mapOf("s" to "<urn:a>", "s_bound" to "<urn:a>"),
            ),
        )
    }
}
