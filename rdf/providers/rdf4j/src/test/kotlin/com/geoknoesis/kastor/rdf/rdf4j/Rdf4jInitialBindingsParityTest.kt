package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.sparql.internal.SparqlLexical
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * `Rdf4jRepository.withSelectRows(query, bindings, timeout, consume)` applies IRI and literal bindings with the
 * shared `SparqlInitialBindings` substitution, so it returns the same rows as the Jena provider (Jena's
 * `substitution`) for the same data and bindings. Cases ported from `InitialBindingsJenaParityTest` (`:rdf:sparql`).
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

    @Test
    fun `nested aggregates keep sub-select scoping`() {
        assertSameAsJena(
            "COUNT(*) in a sub-select projecting the bound variable",
            "SELECT ?s ?c WHERE { ?s <urn:l> ?l { SELECT ?s (COUNT(*) AS ?c) WHERE { { SELECT ?s WHERE { ?s <urn:p> ?o } } } GROUP BY ?s } }",
            mapOf("s" to a),
        )
        assertThrows(IllegalArgumentException::class.java) {
            rows(
                rdf4j,
                "SELECT ?s ?c WHERE { ?s <urn:l> ?l { SELECT (COUNT(*) AS ?c) WHERE { { SELECT ?s WHERE { ?s <urn:p> ?o } } } } }",
                mapOf("s" to a),
            )
        }
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
            assertThrows(IllegalArgumentException::class.java, { rows(rdf4j, query, mapOf("s" to a)) }, query)
        }
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
