package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Codepoint escapes (`\uXXXX`) in dataset queries: an escape inside a string, an IRI or a comment that does not
 * change where that token ends is left exactly as written and the query runs in place; only a query that reads
 * differently depending on when the engine decodes escapes is refused.
 *
 * Every query is spelled with `$U` for the backslash-u of the SPARQL text, so this file contains no escape that the
 * Kotlin compiler would decode.
 */
class DatasetEscapeAnalysisTest {
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")
    private val g1 = Iri("urn:g1")

    /** A backslash followed by `u`: the start of a SPARQL codepoint escape. */
    private val U = "\\" + "u"
    private val BS = "\\"

    private val repo = Rdf.memory().apply {
        editDefaultGraph().addTriple(RdfTriple(s, p, string("default")))
        editGraph(g1).addTriple(RdfTriple(s, p, string("secret")))
    }

    private val storeDefaultOnly = (Dataset { defaultGraph(repo) } as DatasetImpl).apply {
        materializationRepositoryFactory = { error("the dataset must not be materialized") }
    }

    private val namedAsDefault = (Dataset { defaultGraph(repo.getGraph(g1).asGraphRef(repo, g1)) } as DatasetImpl).apply {
        materializationRepositoryFactory = { error("the dataset must not be materialized") }
    }

    @AfterEach
    fun close() {
        repo.close()
    }

    private fun values(dataset: Dataset, query: String): List<String?> =
        dataset.select(SparqlSelectQuery(query)).map { it.getString("o") }

    /** Queries without GRAPH whose escapes used to make them "unanalysable". */
    private val legitimate = listOf(
        "SELECT ?o { ?s ?p ?o FILTER(?o != \"\"\"a${U}000Ab\"\"\") }",
        "SELECT ?o { ?s ?p ?o FILTER(?o != '''a${U}000Db''') }",
        "SELECT ?o { ?s ?p ?o FILTER(?o != \"$BS${U}0041\") }",
        "SELECT ?o { ?s ?p ?o FILTER(?o != \"a${U}0022b\") }",
        "SELECT ?o { ?s ?p ?o FILTER(?o != 'a${U}0027b') }",
        "SELECT ?o { ?s ?p ?o FILTER(?o != \"a${U}005C${U}005Cb\") }",
        "SELECT ?o { ?s ?p ?o FILTER(?o != \"line1${U}000Aline2\") }",
        "SELECT ?o { ?s ?p ?o FILTER(?o != \"a$BS${BS}U00000022b\") }",
        "SELECT ?o { ?s <urn:${U}0070> ?o FILTER(?o != \"caf${U}00E9\") } # caf${U}00E9",
    )

    /** An even number of escaped quotes: decoded first it reads as several strings, but there is no GRAPH either way. */
    private val evenQuotes = "SELECT ?o { ?s ?p ?o FILTER(?o != \"say ${U}0022hi${U}0022\") }"

    @Test
    fun `a query without GRAPH under either reading runs in place even when the readings differ`() {
        assertNull(SparqlDatasetClauses.canonical(evenQuotes))
        assertFalse(SparqlDatasetClauses.usesGraphPattern(evenQuotes))
        assertFalse(SparqlDatasetClauses.declaresDataset(evenQuotes))
        assertEquals(listOf("default"), values(storeDefaultOnly, evenQuotes))
        assertEquals(listOf("secret"), values(namedAsDefault, evenQuotes))
        // A keyword that only one of the readings sees still counts.
        assertTrue(SparqlDatasetClauses.usesGraphPattern("SELECT ?o { ?s ?p \"a${U}0022 GRAPH ${U}0022\" }"))
        assertTrue(SparqlDatasetClauses.declaresDataset("SELECT ?o { ?s ?p \"a${U}0022 FROM ${U}0022\" }"))
    }

    @Test
    fun `escapes that stay inside their string run in place on a dataset of the store default graph`() {
        for (query in legitimate) {
            assertEquals(query, SparqlDatasetClauses.canonical(query), "the query is sent as written: $query")
            assertFalse(SparqlDatasetClauses.usesGraphPattern(query), query)
            assertFalse(SparqlDatasetClauses.declaresDataset(query), query)
            assertEquals(listOf("default"), values(storeDefaultOnly, query), query)
        }
    }

    @Test
    fun `escapes that stay inside their string run in place on a dataset of named graphs`() {
        for (query in legitimate) {
            // The predicate IRI of the last query is urn:p; every query sees the one triple of g1.
            assertEquals(listOf("secret"), values(namedAsDefault, query), query)
        }
    }

    @Test
    fun `GRAPH is still found, and rewritten, next to such escapes`() {
        val query = "SELECT ?o { ?s ?p ?o FILTER(?o != \"\"\"a${U}000Ab\"\"\" && ?o != \"$BS${U}0041\") GRAPH ?g { ?s ?p ?o } }"
        assertTrue(SparqlDatasetClauses.usesGraphPattern(query))
        val rewritten = SparqlDatasetClauses.withoutNamedGraphs(query)
        assertNotNull(rewritten)
        assertTrue(rewritten!!.contains("\"\"\"a${U}000Ab\"\"\" && ?o != \"$BS${U}0041\""), "strings stay as written: $rewritten")
        assertEquals(emptyList<String?>(), values(storeDefaultOnly, query))
        // By the SPARQL grammar and for Jena alike, the escaped quote is inside the string and GRAPH follows it.
        val escapedBackslash = "SELECT ?o { ?s ?p \"$BS${U}0022 # \" GRAPH ?g { ?s ?p ?o } }"
        assertTrue(SparqlDatasetClauses.usesGraphPattern(escapedBackslash))
        assertEquals(emptyList<String?>(), values(storeDefaultOnly, escapedBackslash))
    }

    @Test
    fun `a query that needs no rewrite reaches the repository as the caller's own query object`() {
        var seen: SparqlSelect? = null
        val capturing = object : RdfRepository by repo {
            override fun select(query: SparqlSelect): SparqlQueryResult {
                seen = query
                return repo.select(query)
            }
        }
        val dataset = Dataset { defaultGraph(capturing) }
        // Not a value class: the instance that arrives can be told from an equal copy.
        class OwnQuery(override val sparql: String) : SparqlSelect
        for (text in legitimate) {
            val query = OwnQuery(text)
            dataset.select(query).toList()
            assertSame(query, seen, text)
        }
    }

    @Test
    fun `an escaped keyword is decoded, and only the keyword`() {
        val query = "SELECT ?o { ${U}0047RAPH ?g { ?s ?p ?o FILTER(?o != \"a${U}0022b\") } }"
        assertTrue(SparqlDatasetClauses.usesGraphPattern(query))
        assertEquals("SELECT ?o { GRAPH ?g { ?s ?p ?o FILTER(?o != \"a${U}0022b\") } }", SparqlDatasetClauses.canonical(query))
        assertEquals(emptyList<String?>(), values(storeDefaultOnly, query))
        assertTrue(SparqlDatasetClauses.declaresDataset("SELECT * ${U}0046ROM <urn:g1> { ?s ?p \"a${U}0022b\" }"))
        // An escape outside strings, IRIs and comments may only spell part of a name.
        assertNull(SparqlDatasetClauses.canonical("SELECT ?o { ?s ?p ${U}0022x\" }"))
        assertNull(SparqlDatasetClauses.canonical("SELECT ?o ${U}007B ?s ?p ?o }"))
    }

    @Test
    fun `queries that read differently depending on when escapes are decoded are still refused`() {
        val ambiguous = listOf(
            // Decoded first, the escape closes the string and the comment hides GRAPH; decoded late, GRAPH is a pattern.
            "SELECT ?o { ?s ?p ?o FILTER(?o != \"a${U}0022 # \") GRAPH ?g { ?s ?p ?o } }",
            "SELECT ?o { ?s ?p ?o FILTER(?o != 'a${U}0027 # ') GRAPH ?g { ?s ?p ?o } }",
            // Decoded first, the line break ends the comment.
            "SELECT ?o { ?s ?p ?o } # ${U}000A GRAPH ?g { ?s ?p ?o }",
            // Decoded first, the backslash escapes the closing quote and the string runs on to the next quote.
            "SELECT ?o { ?s ?p \"${U}005C\" } # \" GRAPH ?g { ?s ?p ?o }",
            // Decoded first, the space ends the IRI and the '#' starts a comment that hides GRAPH.
            "SELECT ?o { ?s <urn:a${U}0020#> ?o GRAPH ?g { ?s ?p ?o } }",
        )
        for (query in ambiguous) {
            assertNull(SparqlDatasetClauses.canonical(query), query)
            assertTrue(SparqlDatasetClauses.usesGraphPattern(query), query)
            assertThrows(IllegalArgumentException::class.java, { values(storeDefaultOnly, query) }, query)
        }
    }

    @Test
    fun `a dataset of named graphs materializes only the queries that are really ambiguous`() {
        var materialized = 0
        val named = (Dataset { defaultGraph(repo.getGraph(g1).asGraphRef(repo, g1)) } as DatasetImpl)
        named.materializationRepositoryFactory = { materialized++; Rdf.memory() }
        legitimate.forEach { named.select(SparqlSelectQuery(it)).toList() }
        assertEquals(0, materialized)
        // Text that differs only after the place where the dataset clauses go does not matter to them.
        named.select(SparqlSelectQuery("SELECT ?o { ?s ?p ?o } # ${U}000A")).toList()
        named.select(SparqlSelectQuery("SELECT ?o { ?s ?p ?o FILTER(?o != \"say ${U}0022hi${U}0022\") }")).toList()
        assertEquals(0, materialized)
        // Decoded first, the string ends early and the rest of the line is a comment: the projection is ambiguous.
        runCatching { named.select(SparqlSelectQuery("SELECT (\"a${U}0022 # \" AS ?x) ?o\n{ ?s ?p ?o }")).toList() }
        assertEquals(1, materialized)
    }
}
