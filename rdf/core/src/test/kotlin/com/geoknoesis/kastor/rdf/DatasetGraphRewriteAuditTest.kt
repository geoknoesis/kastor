package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * The GRAPH rewrite of a dataset without named graphs ([SparqlDatasetClauses.withoutNamedGraphs]) and the tokenizer
 * behind it: minified and adjacent patterns, SERVICE, codepoint escapes, numbers and booleans before a keyword,
 * and the query forms a GRAPH keyword can appear in. Rewritten queries are parsed and run with Jena ARQ (test
 * runtime classpath).
 */
class DatasetGraphRewriteAuditTest {
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")
    private val g1 = Iri("urn:g1")

    private val repo = Rdf.memory().apply {
        editDefaultGraph().addTriple(RdfTriple(s, p, string("default")))
        editGraph(g1).addTriple(RdfTriple(s, p, string("secret")))
    }

    private val dataset = (Dataset { defaultGraph(repo) } as DatasetImpl).apply {
        materializationRepositoryFactory = { error("the dataset must not be materialized") }
    }

    @AfterEach
    fun close() {
        repo.close()
    }

    private fun values(query: String): List<String?> = dataset.select(SparqlSelectQuery(query)).map { it.getString("o") }

    private fun assertParses(query: String) {
        val create = Class.forName("org.apache.jena.query.QueryFactory").getMethod("create", String::class.java)
        try {
            create.invoke(null, query)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            fail<Unit>("Rewritten query does not parse:\n$query\n${e.targetException.message}")
        }
    }

    private fun rewritten(query: String): String {
        val result = SparqlDatasetClauses.withoutNamedGraphs(query)
        assertNotNull(result, "query must be analysable: $query")
        assertParses(result!!)
        return result
    }

    // ---- finding 2: edit ordering ----

    @Test
    fun `adjacent GRAPH patterns in minified queries are rewritten to valid SPARQL`() {
        val queries = listOf(
            "SELECT ?o{{?s <urn:p> ?o}GRAPH ?g{?s ?x ?y}GRAPH ?h{?s ?x ?z}}",
            "SELECT ?o{GRAPH ?g{?s ?x ?o}GRAPH ?h{?s ?x ?z}}",
            "SELECT ?o{GRAPH ?g{?s ?x ?o}GRAPH<urn:g1>{?s ?x ?z}GRAPH ?h{?a ?b ?c}}",
            "SELECT ?o{GRAPH <urn:g1>{?s ?x ?o}GRAPH <urn:g1>{?s ?x ?z}}",
            "SELECT ?o{GRAPH ?a{GRAPH ?b{?s ?p ?o}GRAPH ?c{?s ?p ?o}}GRAPH ?d{GRAPH ?e{?s ?p ?o}}}",
            "ASK{GRAPH ?g{}GRAPH ?h{}}",
        )
        for (query in queries) {
            val text = rewritten(query)
            assertFalse(SparqlDatasetClauses.usesGraphPattern(text), text)
        }
        queries.filter { it.startsWith("SELECT") }.forEach { assertEquals(emptyList<String?>(), values(it), it) }
        assertFalse(dataset.ask(SparqlAskQuery("ASK{GRAPH ?g{}GRAPH ?h{}}")))
        assertEquals(
            listOf("default"),
            values("SELECT ?o{?s <urn:p> ?o OPTIONAL{GRAPH ?g{?s ?x ?y}GRAPH <urn:g1>{?s ?x ?z}}}"),
        )
        assertEquals(
            listOf("default"),
            values("SELECT ?o{{?s <urn:p> ?o}UNION{GRAPH ?g{?s <urn:p> ?o}GRAPH ?h{?s <urn:p> ?o}}}"),
        )
    }

    // ---- finding 3: SERVICE and the other places a GRAPH keyword can appear ----

    @Test
    fun `GRAPH inside SERVICE is left to the remote endpoint`() {
        val text = rewritten(
            "SELECT * { ?s ?p ?o SERVICE <http://example.org/sparql> { GRAPH ?g { ?a ?b ?c } } GRAPH ?h { ?s ?p ?z } }"
        )
        assertTrue(text.contains("SERVICE <http://example.org/sparql> { GRAPH ?g { ?a ?b ?c } }"), text)
        assertFalse(text.contains("GRAPH ?h"), text)

        val silent = rewritten("SELECT * { SERVICE SILENT ?ep { graph <urn:g1> { ?a ?b ?c } } }")
        assertEquals("SELECT * { SERVICE SILENT ?ep { graph <urn:g1> { ?a ?b ?c } } }", silent)

        val nested = rewritten("SELECT * { GRAPH ?g { ?s ?p ?o SERVICE <http://example.org/sparql> { GRAPH ?x { ?a ?b ?c } } } }")
        assertTrue(nested.contains("SERVICE <http://example.org/sparql> { GRAPH ?x { ?a ?b ?c } }"), nested)
        assertFalse(nested.contains("GRAPH ?g"), nested)

        val minified = rewritten("SELECT*{GRAPH ?g{?s ?p ?o}SERVICE<http://example.org/sparql>{GRAPH ?x{?a ?b ?c}}GRAPH ?h{?s ?p ?o}}")
        assertTrue(minified.contains("SERVICE<http://example.org/sparql>{GRAPH ?x{?a ?b ?c}}"), minified)
        assertEquals(1, Regex("GRAPH").findAll(minified).count(), minified)

        // A SERVICE clause Kastor cannot delimit makes the query unanalysable.
        assertNull(SparqlDatasetClauses.withoutNamedGraphs("SELECT * { SERVICE { GRAPH ?g { ?a ?b ?c } } }"))
        assertNull(SparqlDatasetClauses.withoutNamedGraphs("SELECT * { SERVICE <http://example.org/sparql> { GRAPH ?g { ?a ?b ?c }"))
    }

    @Test
    fun `DESCRIBE, dollar variables and look-alikes of the GRAPH keyword`() {
        assertEquals(0, dataset.describe(SparqlDescribeQuery("DESCRIBE ?s WHERE { GRAPH ?g { ?s ?p ?o } }")).count())
        assertEquals(0, dataset.describe(SparqlDescribeQuery("DESCRIBE ?s { GRAPH <urn:g1> { ?s ?p ?o } }")).count())
        assertEquals(1, dataset.describe(SparqlDescribeQuery("DESCRIBE ?s { ?s ?p ?o }")).count())

        assertEquals(emptyList<String?>(), values("SELECT ?o { GRAPH \$g { ?s ?p ?o } }"))
        assertTrue(rewritten("SELECT * { GRAPH \$g { ?s ?p ?o } }").contains("VALUES \$g"))

        // Variables named ?graph, prefixed names like ex:GRAPH, comments and strings are not the keyword.
        val lookAlikes = "PREFIX ex: <urn:> # GRAPH ?g { }\nSELECT ?o ?graph { ?s ex:p ?o OPTIONAL { ?s ex:GRAPH ?graph } " +
            "FILTER(?o != \"GRAPH ?g { \" && ?o != 'GRAPH' && ?o != '''GRAPH ?g {''') }"
        assertFalse(SparqlDatasetClauses.usesGraphPattern(lookAlikes))
        assertEquals(listOf("default"), values(lookAlikes))
        assertFalse(SparqlDatasetClauses.usesGraphPattern("SELECT * { ?s <urn:GRAPH> \$graph . ?s :GRAPH ?GRAPH . ?s a:GRAPH.GRAPH ?o }"))
    }

    @Test
    fun `CONSTRUCT forms`() {
        // The template of a CONSTRUCT is output, not a pattern: a (Jena) quad template is left as written.
        val quads = SparqlDatasetClauses.withoutNamedGraphs(
            "CONSTRUCT { GRAPH ?g { ?s ?p ?o } } WHERE { ?s ?p ?o OPTIONAL { GRAPH ?g { ?s ?p ?x } } }"
        )!!
        assertTrue(quads.startsWith("CONSTRUCT { GRAPH ?g { ?s ?p ?o } } WHERE { ?s ?p ?o OPTIONAL { { VALUES ?g"), quads)
        assertEquals(1, Regex("GRAPH").findAll(quads).count(), quads)

        assertEquals(
            1,
            dataset.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o OPTIONAL { GRAPH ?g { ?s ?p ?x } } }")).count(),
        )
        assertEquals(1, dataset.construct(SparqlConstructQuery("CONSTRUCT WHERE { ?s ?p ?o }")).count())
        // The short form has no separate pattern to rewrite; GRAPH there (an extension) is rejected.
        assertNull(SparqlDatasetClauses.withoutNamedGraphs("CONSTRUCT WHERE { GRAPH ?g { ?s ?p ?o } }"))
        assertThrows(IllegalArgumentException::class.java) {
            dataset.construct(SparqlConstructQuery("CONSTRUCT WHERE { GRAPH ?g { ?s ?p ?o } }")).count()
        }
    }

    // ---- finding 4: tokenizer ----

    @Test
    fun `a keyword directly after a number, boolean, variable or prefixed name is still a keyword`() {
        for (before in listOf("1", "1.0", "1e3", "1.5e-3", ".5", "true", "false", "?o", "<urn:o>", "\"x\"", "\"x\"@en")) {
            val query = "SELECT * { ?s ?p $before.GRAPH ?g { ?a ?b ?c } }"
            assertTrue(SparqlDatasetClauses.usesGraphPattern(query), query)
            assertFalse(SparqlDatasetClauses.usesGraphPattern(rewritten(query)), query)
        }
        assertTrue(SparqlDatasetClauses.usesGraphPattern("PREFIX ex: <urn:> SELECT * { ?s ?p ex:o. GRAPH ?g { ?a ?b ?c } }"))
        // A dot inside a prefixed name belongs to the name ('ex:o.GRAPH' is one token), as in the SPARQL grammar.
        assertFalse(SparqlDatasetClauses.usesGraphPattern("PREFIX ex: <urn:> SELECT * { ?s ?p ex:o.GRAPH }"))
        assertTrue(SparqlDatasetClauses.usesGraphPattern("SELECT * { ?s ?p 1;?q 2.GRAPH ?g { ?a ?b ?c } }"))
        assertTrue(SparqlDatasetClauses.declaresDataset("SELECT (1 AS ?x)FROM <urn:g1> { }"))
        assertTrue(SparqlDatasetClauses.declaresDataset("SELECT * FROM<urn:g1>{ ?s ?p 1.}"))

        assertEquals(emptyList<String?>(), values("SELECT ?o { ?s <urn:p> ?o . ?s ?q 1.GRAPH ?g { ?a ?b ?c } }"))
        assertEquals(emptyList<String?>(), values("SELECT ?o { ?s <urn:p> ?o FILTER(true)GRAPH ?g { ?a ?b ?c } }"))
        assertEquals(listOf("default"), values("SELECT ?o { ?s <urn:p> ?o OPTIONAL { ?a ?b true.GRAPH ?g { ?a ?b ?c } } }"))
    }

    @Test
    fun `language tags and escaped local names do not hide or fake keywords`() {
        assertFalse(SparqlDatasetClauses.declaresDataset("SELECT * { ?s ?p \"x\"@from }"))
        assertFalse(SparqlDatasetClauses.usesGraphPattern("SELECT * { ?s ?p \"x\"@graph }"))
        // PN_LOCAL_ESC: the escaped '#' is part of the name, not the start of a comment that hides GRAPH.
        val escaped = "PREFIX ex: <urn:> SELECT * { ?s ex:a\\#b ?o GRAPH ?g { ?a ?b ?c } }"
        assertTrue(SparqlDatasetClauses.usesGraphPattern(escaped))
        assertFalse(SparqlDatasetClauses.usesGraphPattern(rewritten(escaped)))
        val quote = "PREFIX ex: <urn:> SELECT * { ?s ex:a\\'b ?o } # GRAPH ?g { } '"
        assertFalse(SparqlDatasetClauses.usesGraphPattern(quote))
    }

    @Test
    fun `codepoint escapes are decoded before the query is analysed`() {
        assertTrue(SparqlDatasetClauses.usesGraphPattern("SELECT * { \\u0047RAPH ?g { ?s ?p ?o } }"))
        assertTrue(SparqlDatasetClauses.usesGraphPattern("SELECT * { GR\\U00000041PH ?g { ?s ?p ?o } }"))
        assertTrue(SparqlDatasetClauses.declaresDataset("SELECT * \\u0046ROM <urn:g1> { ?s ?p ?o }"))
        assertTrue(SparqlDatasetClauses.declaresDataset("SELECT * FRO\\u004d NAMED <urn:g1> { ?s ?p ?o }"))
        assertFalse(SparqlDatasetClauses.usesGraphPattern("SELECT * { ?s ?p \"caf\\u00E9 GRAPH\" }"))

        assertEquals(emptyList<String?>(), values("SELECT ?o { \\u0047RAPH ?g { ?s ?p ?o } }"))
        assertThrows(IllegalArgumentException::class.java) { values("SELECT ?o \\u0046ROM <urn:g1> { ?s ?p ?o }") }
        // Escapes in strings and IRIs keep their meaning.
        assertEquals(listOf("default"), values("SELECT ?o { ?s <urn:\\u0070> ?o FILTER(?o != \"caf\\u00E9\") }"))
    }

    @Test
    fun `queries whose codepoint escapes cannot be analysed are rejected`() {
        // By the SPARQL grammar the escape closes the string (escapes are decoded first) and the comment hides GRAPH;
        // an engine that decodes escapes only inside strings would read a GRAPH pattern instead.
        val ambiguous = listOf(
            "SELECT ?o { ?s ?p ?o FILTER(?o != \"a\\u0022 # \") GRAPH ?g { ?s ?p ?o } }",
            "SELECT ?o { ?s ?p ?o FILTER(?o != 'a\\u0027 # ') GRAPH ?g { ?s ?p ?o } }",
            "SELECT ?o { ?s ?p ?o } # \\u000A GRAPH ?g { ?s ?p ?o }",
            "SELECT ?o { ?s ?p \"\\u005C\" } # \" GRAPH ?g { ?s ?p ?o }",
            "SELECT ?o { ?s ?p \"\\\\u0022 # \" GRAPH ?g { ?s ?p ?o } }",
            "SELECT ?o { ?s ?p \"\\U00110000\" }",
            "SELECT ?o { ?s ?p \"\\uD800\" }",
        )
        for (query in ambiguous) {
            assertNull(SparqlDatasetClauses.canonical(query), query)
            assertTrue(SparqlDatasetClauses.usesGraphPattern(query), query)
            assertThrows(IllegalArgumentException::class.java, { values(query) }, query)
        }
        // An unterminated string or a GRAPH that is not followed by a name and a group is rejected as well.
        assertThrows(IllegalArgumentException::class.java) { values("SELECT ?o { ?s ?p \"x } GRAPH ?g { ?s ?p ?o }") }
        assertThrows(IllegalArgumentException::class.java) { values("SELECT ?o { GRAPH 1 { ?s ?p ?o } }") }
    }

    @Test
    fun `a dataset of named graphs materializes queries it cannot analyse instead of running them in place`() {
        val named = (Dataset { defaultGraph(repo.getGraph(g1).asGraphRef(repo, g1)) } as DatasetImpl)
        val query = "SELECT ?o { ?s ?p ?o FILTER(?o != \"a\\u005Cu0022\") }"
        assertNull(SparqlDatasetClauses.canonical(query))
        var materialized = 0
        named.materializationRepositoryFactory = { materialized++; Rdf.memory() }
        runCatching { named.select(SparqlSelectQuery(query)).toList() }
        assertEquals(1, materialized)
        assertEquals(listOf("secret"), named.select(SparqlSelectQuery("SELECT ?o { ?s ?p ?o }")).map { it.getString("o") })
        assertEquals(1, materialized)
    }
}
