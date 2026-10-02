package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The GRAPH rewrite of a dataset without named graphs, for patterns with `FILTER`, `BIND`, `MINUS`, `VALUES` and
 * sub-selects: the pattern is not evaluated either, and the variables in scope stay exactly those of the original;
 * variable names are scanned by the SPARQL `VARNAME` production; and a repository without a SPARQL engine is
 * materialized instead of being queried in place.
 */
class DatasetGraphRewriteScopeTest {
    private fun rewritten(query: String): String = SparqlDatasetClauses.withoutNamedGraphs(query)!!

    /** The variables Jena projects for [query] (for `SELECT *`: the variables in scope). */
    private fun projected(query: String): Set<String> {
        val parsed = Class.forName("org.apache.jena.query.QueryFactory").getMethod("create", String::class.java).invoke(null, query)
        @Suppress("UNCHECKED_CAST")
        return (parsed.javaClass.getMethod("getResultVars").invoke(parsed) as List<String>).toSet()
    }

    /** Asserts the table the pattern of `SELECT * { GRAPH ?g { pattern } }` is replaced by, and its scope. */
    private fun assertTable(expectedVariables: String, pattern: String) {
        val query = "PREFIX ex: <urn:ex:> SELECT * { ?s0 <urn:p> ?o0 OPTIONAL { GRAPH ?g { $pattern } } }"
        val rewritten = rewritten(query)
        val expected = "PREFIX ex: <urn:ex:> SELECT * { ?s0 <urn:p> ?o0 OPTIONAL { " +
            "{ VALUES $expectedVariables { } FILTER EXISTS { $pattern } } } }"
        assertEquals(expected, rewritten, pattern)
        assertEquals(projected(query), projected(rewritten), "variables in scope of: $query\nrewritten: $rewritten")
    }

    @Test
    fun `FILTER, BIND, MINUS, VALUES and sub-selects are not evaluated and keep their variables in scope`() {
        // FILTER: its variables are not in scope.
        assertTable("(?g ?s ?x ?y)", "?s ?x ?y FILTER(?y != ?unbound)")
        assertTable("(?g ?s ?x ?y)", "?s ?x ?y FILTER regex(str(?y), \"a\", ?flags)")
        assertTable("(?g ?s ?x ?y)", "?s ?x ?y FILTER NOT EXISTS { ?y ?q ?r }")
        assertTable("(?g ?s ?x ?y)", "?s ?x ?y FILTER EXISTS { ?y ?q ?r FILTER(?r > 1) }")
        assertTable("(?g ?s ?x ?y)", "?s ?x ?y FILTER <urn:fn>(?y, ?other)")
        assertTable("(?g ?s ?x ?y)", "?s ?x ?y FILTER ex:fn(?y, ?other)")
        assertTable("(?g ?s ?x ?y)", "FILTER(?y > (1 + ?k)) ?s ?x ?y")
        // BIND: the variable it assigns is in scope, those of its expression are not.
        assertTable("(?g ?s ?x ?y ?b)", "?s ?x ?y BIND(1 AS ?b)")
        assertTable("(?g ?s ?x ?y ?b)", "?s ?x ?y BIND(concat(str(?y), ?free) AS ?b)")
        assertTable("(?g ?s ?x ?y ?b)", "?s ?x ?y bind ( (?y + 1) as ?b )")
        // MINUS: nothing of its group is in scope.
        assertTable("(?g ?s ?x ?y)", "?s ?x ?y MINUS { ?s ?m ?n }")
        assertTable("(?g ?s ?x ?y)", "?s ?x ?y MINUS { ?s ?m ?n FILTER(?n) { SELECT ?z { ?z ?z ?z } } }")
        // VALUES: its variables are in scope.
        assertTable("(?g ?s ?x ?y ?v)", "?s ?x ?y VALUES ?v { 1 2 }")
        assertTable("(?g ?s ?x ?y ?v ?w)", "?s ?x ?y VALUES (?v ?w) { (1 UNDEF) (\"a\" <urn:b>) }")
        // Sub-selects: the projected variables are in scope, and only they.
        assertTable("(?g ?y)", "SELECT ?y { ?s ?x ?y }")
        assertTable("(?g ?s ?x ?y)", "?s ?x ?y { SELECT ?y WHERE { ?a ?b ?y } }")
        assertTable("(?g ?n ?s)", "SELECT (COUNT(*) AS ?n) ?s { ?s ?x ?y } GROUP BY ?s HAVING (COUNT(?y) > 1)")
        assertTable("(?g ?a ?b ?c)", "{ SELECT * { ?a ?b ?c FILTER(?hidden) } LIMIT 3 }")
        assertTable("(?g ?a ?m)", "{ SELECT DISTINCT ?a (max(?c) + 1 AS ?m) WHERE { ?a ?b ?c } GROUP BY ?a ORDER BY ?a }")
        assertTable("(?g ?s ?x ?y ?b ?v)", "?s ?x ?y OPTIONAL { ?y ?x ?s FILTER(?s) BIND(?x AS ?b) } { VALUES ?v { 1 } } UNION { ?s ?x ?y }")
    }

    @Test
    fun `patterns whose scope cannot be told keep the form that evaluates them`() {
        // SERVICE: the remote group is not analysed.
        val service = rewritten("SELECT * { GRAPH ?g { ?s ?p ?o SERVICE <urn:ep> { ?s ?q ?r } } }")
        assertEquals("SELECT * { { VALUES ?g { } { ?s ?p ?o SERVICE <urn:ep> { ?s ?q ?r } } } }", service)
        // A sub-select with a trailing VALUES clause.
        val values = rewritten("SELECT * { GRAPH <urn:g1> { SELECT ?s { ?s ?p ?o } VALUES ?s { <urn:a> } } }")
        assertEquals("SELECT * { { { SELECT ?s { ?s ?p ?o } VALUES ?s { <urn:a> } } FILTER(false) } }", values)
        // Something that is not a pattern keyword.
        val odd = rewritten("SELECT * { GRAPH ?g { ?s ?p ?o LATERAL { ?o ?q ?r } } }")
        assertTrue(odd.startsWith("SELECT * { { VALUES ?g { } { ?s ?p ?o LATERAL"), odd)
        // A simple pattern nested in a kept one is still replaced.
        val nested = rewritten("SELECT * { GRAPH <urn:g1> { ?s ?p ?o SERVICE ?ep { ?s ?q ?r } GRAPH ?h { ?a ?b ?c } } }")
        assertTrue(nested.contains("FILTER(false)"), nested)
        assertTrue(nested.contains("{ VALUES (?h ?a ?b ?c) { } FILTER EXISTS { ?a ?b ?c } }"), nested)
    }

    @Test
    fun `variable names follow the SPARQL VARNAME production`() {
        val combining = "o" + 0x0301.toChar() + "x" // a combining acute accent inside the name
        val tie = "a" + 0x203F.toChar() + "b" + 0x2040.toChar() // undertie and character tie
        val middleDot = "m" + 0xB7.toChar() + "n"
        val supplementary = String(Character.toChars(0x1D49C)) + "1" // a letter outside the BMP
        val digitFirst = "1st"
        for (name in listOf(combining, tie, middleDot, supplementary, digitFirst)) {
            assertEquals(
                "SELECT * { { VALUES (?g ?s ?$name) { } FILTER EXISTS { ?s <urn:p> ?$name } } }",
                rewritten("SELECT * { GRAPH ?g { ?s <urn:p> ?$name } }"),
                "variable name with code points ${name.map { Integer.toHexString(it.code) }}",
            )
        }
        // A name ends where VARNAME ends: a combining mark cannot start one, and '-' or '.' never belongs to one.
        assertEquals(
            "SELECT * { { VALUES (?g ?s ?o) { } FILTER EXISTS { ?s <urn:p> ?o. ?s <urn:q> ?o } } }",
            rewritten("SELECT * { GRAPH ?g { ?s <urn:p> ?o. ?s <urn:q> ?o } }"),
        )
        // A character that is a letter for Java but not for SPARQL (U+00AA) does not continue a name.
        val ordinal = 0xAA.toChar()
        val stray = rewritten("SELECT * { GRAPH ?g { ?s <urn:p> ?x$ordinal } }")
        assertTrue(stray.contains("VALUES (?g ?s ?x) { }"), stray)
        assertFalse(stray.contains("?x$ordinal)"), stray)
    }

    private val closeables = ArrayList<AutoCloseable>()

    @AfterEach
    fun close() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    @Test
    fun `a single-repository dataset over a repository without SPARQL is materialized, like a two-repository one`() {
        val s = Iri("urn:s")
        val p = Iri("urn:p")
        val g = Iri("urn:g")
        val memory = Rdf.repository { providerId = "memory" }.also(closeables::add)
        memory.editDefaultGraph().addTriple(RdfTriple(s, p, string("default")))
        memory.editGraph(g).addTriple(RdfTriple(s, p, string("named")))

        fun values(dataset: Dataset, query: String) = dataset.select(SparqlSelectQuery(query)).mapNotNull { it.getString("o") }.sorted()

        // The store's default graph only.
        val defaultOnly = Dataset { defaultGraph(memory) }
        assertEquals(listOf("default"), values(defaultOnly, "SELECT ?o { ?s ?p ?o }"))
        assertEquals(emptyList<String>(), values(defaultOnly, "SELECT ?o { GRAPH ?g { ?s ?p ?o } }"))
        assertTrue(defaultOnly.ask(SparqlAskQuery("ASK { <urn:s> <urn:p> 'default' }")))
        assertEquals(1, defaultOnly.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")).count())
        assertEquals(1, defaultOnly.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).count())

        // Named graphs of the one repository.
        val named = Dataset {
            defaultGraph(memory.getGraph(g).asGraphRef(memory, g))
            namedGraph(g, memory, g)
        }
        assertEquals(listOf("named"), values(named, "SELECT ?o { ?s ?p ?o }"))
        assertEquals(listOf("named"), values(named, "SELECT ?o { GRAPH <urn:g> { ?s ?p ?o } }"))
    }
}
