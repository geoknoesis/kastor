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

    @Test
    fun `expression variables stand in for the constant outside triple patterns`() {
        val t = "<< <urn:a> <urn:p> 1 >>"
        val rewritten = SparqlInitialBindings.apply(
            "SELECT ?o (COUNT(*) AS ?n) WHERE { ?s ?p ?o FILTER(sameTerm(?o, ?s)) FILTER EXISTS { ?x ?p ?o } " +
                "{ ?s ?q ?o } UNION { ?s ?q ?z FILTER(?z = ?o) } } GROUP BY ?o HAVING(COUNT(?o) > 0) ORDER BY ?o",
            mapOf("o" to t),
            mapOf("o" to "k"),
        )
        assertEquals(
            "SELECT ?o (COUNT(*) AS ?n) WHERE { BIND($t AS ?k) ?s ?p $t FILTER(sameTerm(?k, ?s)) FILTER EXISTS { ?x ?p $t } " +
                "{ ?s ?q $t } UNION { BIND($t AS ?k) ?s ?q ?z FILTER(?z = ?k) } } GROUP BY (?k AS ?o) HAVING(COUNT(?k) > 0) ORDER BY (?k)",
            rewritten,
        )
        assertThrows(IllegalArgumentException::class.java) {
            SparqlInitialBindings.apply("SELECT ?k WHERE { ?s ?p ?o }", mapOf("o" to t), mapOf("o" to "k"))
        }
    }

    @Test
    fun `validate rejects exactly what apply rejects`() {
        SparqlInitialBindings.validate("SELECT ?s WHERE { ?s ?p ?o }", setOf("s"))
        for (query in listOf(
            "SELECT ?o WHERE { BIND(<urn:a> AS ?s) ?s <urn:p> ?o }",
            "SELECT ?o WHERE { VALUES ?s { <urn:a> } ?s <urn:p> ?o }",
            "ASK { ?s ?p ?o }",
        )) {
            assertThrows(IllegalArgumentException::class.java, { SparqlInitialBindings.validate(query, setOf("s")) }, query)
        }
    }

    @Test
    fun `a number directly followed by a keyword is two tokens`() {
        for (query in listOf(
            "SELECT (1AS ?s) WHERE { ?x ?p ?o }",
            "SELECT (?o+1AS ?s) WHERE { ?x ?p ?o }",
            "SELECT (1.5e3AS ?s) WHERE { ?x ?p ?o }",
            "SELECT ?o WHERE { ?x ?p ?o BIND(.5AS ?s) }",
        )) {
            assertThrows(IllegalArgumentException::class.java, { apply(query) }, query)
        }
        assertEquals(
            "SELECT (<urn:a> AS ?s) (1AS ?one) (.5AS ?half) WHERE { <urn:a> ?p ?o FILTER(?o=1||<urn:a>=<urn:a>) }",
            apply("SELECT ?s (1AS ?one) (.5AS ?half) WHERE { ?s ?p ?o FILTER(?o=1||?s=?s) }"),
        )
        // Digits inside a name are part of the name.
        assertEquals(
            "PREFIX ex: <urn:> SELECT ?o WHERE { <urn:a> ex:p1AS ?o }",
            apply("PREFIX ex: <urn:> SELECT ?o WHERE { ?s ex:p1AS ?o }"),
        )
    }

    @Test
    fun `only an IRI can be bound to a variable used as a predicate or as a graph name`() {
        val literal = mapOf("v" to "\"x\"")
        for (query in listOf(
            "SELECT ?o WHERE { ?s ?v ?o }",
            "SELECT ?o WHERE { ?s <urn:p> ?o ; ?v ?z }",
            "SELECT ?o WHERE { ?s <urn:p> ?o . ?o \$v ?z }",
            "SELECT ?o WHERE { ?s <urn:p> ?o OPTIONAL { ?o ?v ?z } }",
            "SELECT ?o WHERE { FILTER(?o > 1) ?s ?v ?o }",
            "SELECT ?o WHERE { [ ?v ?o ] <urn:p> ?z }",
            "SELECT ?o WHERE { GRAPH ?v { ?s <urn:p> ?o } }",
            "SELECT ?o WHERE { SERVICE SILENT ?v { ?s <urn:p> ?o } }",
            "SELECT ?o WHERE { { SELECT ?o ?v WHERE { ?s ?v ?o } } }",
        )) {
            val e = assertThrows(IllegalArgumentException::class.java, { SparqlInitialBindings.apply(query, literal) }, query)
            assertEquals(true, e.message!!.contains("only an IRI"), e.message)
            // An IRI is fine in the same position.
            SparqlInitialBindings.apply(query, mapOf("v" to "<urn:a>"))
            assertThrows(IllegalArgumentException::class.java, { SparqlInitialBindings.validate(query, setOf("v"), setOf("v")) }, query)
            SparqlInitialBindings.validate(query, setOf("v"), emptySet())
            SparqlInitialBindings.validate(query, setOf("v"))
        }
        // Typed and language-tagged literals, numbers, booleans and triple terms are no IRIs either.
        for (constant in listOf("\"1\"^^<urn:dt>", "'x'@en", "1", "-1.5", "true", "<< <urn:a> <urn:p> 1 >>")) {
            assertThrows(IllegalArgumentException::class.java, { SparqlInitialBindings.apply("SELECT ?o WHERE { ?s ?v ?o }", mapOf("v" to constant)) }, constant)
        }
        // Subject, object and expression positions accept a literal.
        assertEquals(
            "SELECT ?o WHERE { \"x\" <urn:p> \"x\" , \"x\" ; <urn:p>/<urn:q>* \"x\" . ?o a (\"x\" 1) FILTER(\"x\" = ?o) }",
            SparqlInitialBindings.apply(
                "SELECT ?o WHERE { ?v <urn:p> ?v , ?v ; <urn:p>/<urn:q>* ?v . ?o a (?v 1) FILTER(?v = ?o) }",
                literal,
            ),
        )
    }
}
