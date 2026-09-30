package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Direction
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.sparql.internal.SparqlLexical
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Both providers implement one initial-bindings contract (`SparqlInitialBindings`): for the same data, query and
 * bindings, `withSelectRows(query, bindings, timeout, consume)` returns the same rows, or fails with the same exception
 * type, on RDF4J (shared text substitution, native `setBinding` for terms SPARQL text cannot spell) and on Jena
 * (Jena's `substitution`, after the same validation). Every case runs against both providers.
 * Cases ported from `InitialBindingsJenaParityTest` (`:rdf:sparql`).
 */
class Rdf4jInitialBindingsParityTest {

    private val data = Rdf.parse(
        "<urn:a> <urn:p> 1 . <urn:a> <urn:p> 2 . <urn:b> <urn:p> 3 . <urn:a> <urn:l> \"x\"@en . <urn:a> <urn:l> \"y\"@fr . " +
            "<urn:b> <urn:l> \"z\"@en . <urn:b> <urn:q> 9 . <urn:b> <urn:t> \"\\\\u0022 ?s\" . <urn:c> <urn:f> true . " +
            "<urn:c> <urn:n> \"plain\" .",
        RdfFormat.TURTLE,
    ).getTriples()

    private val rdf4j = Rdf4jRepository.MemoryRepository().also { it.editDefaultGraph().addTriples(data) }
    private val jena = JenaRepository.MemoryRepository().also { it.editDefaultGraph().addTriples(data) }

    @AfterEach
    fun close() {
        rdf4j.close()
        jena.close()
    }

    private val a = Iri("urn:a")
    private val b = Iri("urn:b")

    private fun rows(repo: RdfRepository, query: String, bindings: Map<String, RdfTerm>): List<Map<String, RdfTerm>> =
        repo.withSelectRows(SparqlSelectQuery(query), bindings, Duration.ofSeconds(30)) { rows ->
            rows.map { row -> row.getVariableNames().filter { row.get(it) != null }.associateWith { row.get(it)!! }.toSortedMap() }.toList()
        }.sortedBy { it.toString() }

    private fun assertSameAsJena(name: String, query: String, bindings: Map<String, RdfTerm>, jenaQuery: String = query) {
        val expected = rows(jena, jenaQuery, bindings)
        assertEquals(expected, rows(rdf4j, query, bindings), name)
    }

    /** Rows with blank nodes replaced by a placeholder (their labels differ between stores), or the exception type. */
    private fun outcome(repo: RdfRepository, query: String, bindings: Map<String, RdfTerm>): Any = try {
        rows(repo, query, bindings).map { row -> row.mapValues { (_, term) -> if (term is BlankNode) "_:bnode" else term } }
    } catch (e: Throwable) {
        // Errors too: an engine assertion must show up as a mismatch, not abort the comparison.
        e.javaClass
    }

    /** Runs [query] on both providers, with bindings built per repository by [bindings], and asserts the same outcome. */
    private fun assertSameOutcome(name: String, query: String, bindings: (RdfRepository) -> Map<String, RdfTerm>): Any {
        val expected = outcome(jena, query, bindings(jena))
        assertEquals(expected, outcome(rdf4j, query, bindings(rdf4j)), name)
        return expected
    }

    private fun assertBothReject(name: String, query: String, bindings: (RdfRepository) -> Map<String, RdfTerm>) {
        assertEquals(IllegalArgumentException::class.java, assertSameOutcome(name, query, bindings), name)
    }

    @Test
    fun `nested aggregates keep sub-select scoping`() {
        assertSameAsJena(
            "COUNT(*) in a sub-select projecting the bound variable",
            "SELECT ?s ?c WHERE { ?s <urn:l> ?l { SELECT ?s (COUNT(*) AS ?c) WHERE { { SELECT ?s WHERE { ?s <urn:p> ?o } } } GROUP BY ?s } }",
            mapOf("s" to a),
        )
        assertBothReject(
            "bound variable local to a sub-select",
            "SELECT ?s ?c WHERE { ?s <urn:l> ?l { SELECT (COUNT(*) AS ?c) WHERE { { SELECT ?s WHERE { ?s <urn:p> ?o } } } } }",
        ) { mapOf("s" to a) }
    }

    @Test
    fun `projected bound variables are bound in every row even when a sub-select also projects them`() {
        assertSameAsJena(
            "sub-select in one UNION branch",
            "SELECT ?s ?o WHERE { { <urn:b> <urn:q> ?o } UNION { SELECT ?s ?o WHERE { ?s <urn:p> ?o } } }",
            mapOf("s" to a),
        )
        assertSameAsJena(
            "sub-select in an OPTIONAL",
            "SELECT ?s ?l WHERE { <urn:b> <urn:l> ?l OPTIONAL { { SELECT ?s WHERE { ?s <urn:q> ?z } } } }",
            mapOf("s" to a),
        )
        assertSameAsJena(
            "SELECT star over a sub-select projecting the bound variable",
            "SELECT * WHERE { { SELECT ?s ?o WHERE { ?s <urn:p> ?o } } }",
            mapOf("s" to a),
        )
        assertSameAsJena("sub-select joined directly", "SELECT ?s ?o WHERE { { SELECT ?s ?o WHERE { ?s <urn:p> ?o } } }", mapOf("s" to b))
        assertSameAsJena(
            "GROUP BY a variable projected by a sub-select",
            "SELECT ?s (COUNT(*) AS ?c) WHERE { { SELECT ?s ?o WHERE { ?s <urn:p> ?o } } } GROUP BY ?s",
            mapOf("s" to a),
        )
    }

    @Test
    fun `bindings restrict the query before aggregation LIMIT and FILTER`() {
        assertSameAsJena("aggregate", "SELECT ?s (COUNT(?o) AS ?n) WHERE { ?s <urn:p> ?o } GROUP BY ?s", mapOf("s" to a))
        assertSameAsJena("limit", "SELECT ?s ?o WHERE { ?s <urn:p> ?o } ORDER BY ?o LIMIT 1", mapOf("s" to b))
        assertSameAsJena("filter", "SELECT ?o WHERE { ?s <urn:p> ?o FILTER(?s = <urn:a>) }", mapOf("s" to b))
        assertSameAsJena("SELECT star omits bound variables", "SELECT * WHERE { ?s <urn:p> ?o }", mapOf("s" to a))
        assertSameAsJena("MINUS sharing only the bound variable", "SELECT ?o WHERE { ?s <urn:p> ?o MINUS { ?s <urn:q> ?z } }", mapOf("s" to b))
        assertSameAsJena(
            "variable spelled with a dollar sign, inside a string and a comment",
            "SELECT \$s ?o WHERE { \$s <urn:p> ?o . FILTER(BOUND(\$s) && STR(?o) != '\$s') # \$s\n }",
            mapOf("s" to a),
        )
    }

    @Test
    fun `every literal kind is rendered like the SPARQL endpoint adapter renders it`() {
        val cases = listOf(
            "typed literal" to ("p" to TypedLiteral("2", XSD.integer)),
            "language string" to ("l" to LangString("x", "en")),
            "boolean" to ("f" to TrueLiteral),
            "xsd:string" to ("n" to Literal("plain")),
            "string containing backslash-u text" to ("t" to Literal("\\u0022 ?s")),
        )
        for ((name, case) in cases) {
            val (predicate, value) = case
            val query = "SELECT ?s ?o WHERE { ?s <urn:$predicate> ?o }"
            assertSameAsJena(name, query, mapOf("o" to value))
            assertEquals(1, rows(rdf4j, query, mapOf("o" to value)).size, name)
        }
        assertSameAsJena("IRI in the projection and the pattern", "SELECT ?s ?p WHERE { ?s ?p 9 }", mapOf("s" to b))
        val text = "\\u0022 ?s"
        assertSameAsJena("literal constant in the query", "SELECT ?s WHERE { ?s <urn:t> ${SparqlLexical.quoted(text)} }", mapOf("s" to b))
    }

    @Test
    fun `escaped characters in prefixed local names do not start comments`() {
        assertSameAsJena(
            "PN_LOCAL_ESC",
            "PREFIX ex: <urn:> SELECT ?s ?o WHERE { OPTIONAL { ?s ex:x\\#y ?z } ?s <urn:p> ?o }",
            mapOf("s" to b),
        )
    }

    @Test
    fun `queries that assign or locally reuse a bound variable are rejected`() {
        val rejected = listOf(
            "SELECT ?o WHERE { BIND(<urn:a> AS ?s) ?s <urn:p> ?o }",
            "SELECT (<urn:a> AS ?s) WHERE { ?x <urn:p> ?o }",
            "SELECT ?o WHERE { VALUES ?s { <urn:a> } ?s <urn:p> ?o }",
            "SELECT ?o WHERE { ?x <urn:p> ?o { SELECT ?o WHERE { ?s <urn:p> ?o } } }",
        )
        for (query in rejected) {
            assertBothReject(query, query) { mapOf("s" to a) }
            // The same contract holds for terms RDF4J binds natively (blank nodes).
            assertBothReject("blank node: $query", query) { mapOf("s" to blankSubject(it)) }
        }
        assertBothReject("not a SELECT query", "ASK { ?s <urn:p> ?o }") { mapOf("s" to a) }
    }

    /** The blank node `[] <urn:bn> 7` of [repo] (blank node labels are store-specific). */
    private fun blankSubject(repo: RdfRepository): RdfTerm =
        repo.withSelectRows(SparqlSelectQuery("SELECT ?b WHERE { ?b <urn:bn> 7 }"), emptyMap(), Duration.ofSeconds(30)) { rows ->
            rows.single().get("b")!!
        }

    private val tripleTerm = TripleTerm(RdfTriple(a, Iri("urn:p"), TypedLiteral("1", XSD.integer)))
    private val directional = LangString("x", "ar", Direction.RTL)

    init {
        for (repo in listOf(rdf4j, jena)) {
            repo.editDefaultGraph().addTriples(
                listOf(
                    RdfTriple(BlankNode("n1"), Iri("urn:bn"), TypedLiteral("7", XSD.integer)),
                    RdfTriple(BlankNode("n1"), Iri("urn:bp"), TypedLiteral("5", XSD.integer)),
                    RdfTriple(Iri("urn:r"), Iri("urn:reif"), tripleTerm),
                    RdfTriple(Iri("urn:d"), Iri("urn:dl"), directional),
                ),
            )
        }
    }

    @Test
    fun `terms that SPARQL text cannot spell follow the same contract`() {
        val cases = listOf<Pair<String, (RdfRepository) -> Map<String, RdfTerm>>>(
            "blank node" to { repo -> mapOf("s" to blankSubject(repo)) },
            "triple term" to { _ -> mapOf("o" to tripleTerm) },
            "directional language string" to { _ -> mapOf("o" to directional) },
        )
        val queries = listOf(
            "SELECT ?s ?o WHERE { ?s ?p ?o }",
            "SELECT * WHERE { ?s ?p ?o }",
            "SELECT ?s (COUNT(*) AS ?n) WHERE { ?s ?p ?o } GROUP BY ?s",
            "SELECT ?s ?o WHERE { ?s ?p ?o FILTER(BOUND(?o) && BOUND(?s)) }",
            "SELECT ?s ?o WHERE { { SELECT ?s ?o WHERE { ?s ?p ?o } } }",
            "SELECT ?s ?o WHERE { ?s ?p ?o } ORDER BY ?o LIMIT 1",
            "SELECT ?x WHERE { ?x <urn:q> 9 OPTIONAL { ?s ?p ?o } }",
            "SELECT ?s ?x WHERE { ?s ?p ?x FILTER(?x = ?o || STR(?s) = \"urn:c\") }",
            "SELECT ?s ?x WHERE { ?s ?p ?x FILTER(sameTerm(?x, ?o)) }",
            "SELECT ?s ?x WHERE { { ?s <urn:q> ?x } UNION { ?s ?p ?x FILTER(sameTerm(?x, ?o)) } }",
            "SELECT ?o (COUNT(*) AS ?n) WHERE { ?s ?p ?o } GROUP BY ?o HAVING(COUNT(?o) > 0)",
            "SELECT ?s ?o WHERE { ?s ?p ?o FILTER EXISTS { ?s ?p ?o } }",
            "SELECT ?s ?o ?same WHERE { ?s ?p ?o BIND(sameTerm(?o, ?o) AS ?same) }",
        )
        val mismatches = ArrayList<String>()
        for ((kind, bindings) in cases) {
            for (query in queries) {
                val expected = outcome(jena, query, bindings(jena))
                val actual = outcome(rdf4j, query, bindings(rdf4j))
                if (expected != actual || expected !is List<*>) mismatches.add("$kind: $query\n  jena:  $expected\n  rdf4j: $actual")
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    /**
     * RDF4J's SPARQL parser decodes backslash-u / backslash-U codepoint escapes over the whole query text before
     * tokenizing (SPARQL 1.1 section 19.2), like the substitution does, so escaped queries give the rows of their
     * decoded spelling (evaluated here by the Jena provider).
     */
    @Test
    fun `codepoint escapes in the query text are decoded like RDF4J's parser does`() {
        assertEquals(3, rows(rdf4j, "SELECT ?\\u0073 ?o WHERE { ?\\u0073 <urn:p> ?o }", emptyMap()).size, "RDF4J applies the pre-pass")
        assertSameAsJena(
            "escaped variable name",
            "SELECT ?\\u0073 ?o WHERE { ?\\u0073 <urn:p> ?o }",
            mapOf("s" to a),
            jenaQuery = "SELECT ?s ?o WHERE { ?s <urn:p> ?o }",
        )
        assertSameAsJena(
            "u0075-encoded variable",
            "SELECT ?u ?o WHERE { ?\\u0075 <urn:p> ?o }",
            mapOf("u" to b),
            jenaQuery = "SELECT ?u ?o WHERE { ?u <urn:p> ?o }",
        )
        assertSameAsJena(
            "escaped keyword",
            "\\u0053ELECT ?s ?o WHERE { ?s <urn:p> ?o }",
            mapOf("s" to b),
            jenaQuery = "SELECT ?s ?o WHERE { ?s <urn:p> ?o }",
        )
        assertSameAsJena(
            "escaped quote ends a string",
            "SELECT ?o WHERE { ?s <urn:p> ?o FILTER(STR(?o) != \"x\\u0022 && ?s = <urn:a> && \\u0022\" = \"\") }",
            mapOf("s" to a),
            jenaQuery = "SELECT ?o WHERE { ?s <urn:p> ?o FILTER(STR(?o) != \"x\" && ?s = <urn:a> && \"\" = \"\") }",
        )
    }
}
