package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.sparql.internal.SparqlInitialBindings
import com.geoknoesis.kastor.rdf.sparql.internal.SparqlLexical
import org.apache.jena.query.Dataset
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryExecution
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.QuerySolutionMap
import org.apache.jena.query.Syntax
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Initial bindings substituted by [SparqlInitialBindings] must give the same rows as Jena's
 * `substitution()` (what the Jena provider uses), and the rewritten text must be a legal SPARQL 1.1
 * query (strict parse).
 */
class InitialBindingsJenaParityTest {

    private val dataset: Dataset = DatasetFactory.create().also {
        RDFParser.fromString(
            "<urn:a> <urn:p> 1 . <urn:a> <urn:p> 2 . <urn:b> <urn:p> 3 . <urn:a> <urn:l> \"x\"@en . <urn:a> <urn:l> \"y\"@fr . " +
                "<urn:b> <urn:l> \"z\"@en . <urn:b> <urn:q> 9 . <urn:b> <urn:t> \"\\\\u0022 ?s\" .",
            Lang.TURTLE,
        ).parse(it.asDatasetGraph())
    }

    @AfterEach
    fun close() = dataset.close()

    private val a = Iri("urn:a") to ResourceFactory.createResource("urn:a")
    private val b = Iri("urn:b") to ResourceFactory.createResource("urn:b")

    private fun rows(execution: QueryExecution): List<Map<String, String>> = execution.use { exec ->
        exec.execSelect().asSequence().map { row -> row.varNames().asSequence().associateWith { row.get(it).toString() }.toSortedMap() }.toList()
    }.sortedBy { it.toString() }

    private fun rewrite(query: String, bindings: Map<String, Pair<RdfTerm, RDFNode>>): String =
        SparqlInitialBindings.apply(query, bindings.mapValues { SparqlTermFormat.term(it.value.first) { error("no blank nodes") } })

    private fun assertSameAsJena(name: String, query: String, bindings: Map<String, Pair<RdfTerm, RDFNode>>) {
        val initial = QuerySolutionMap().apply { bindings.forEach { (variable, value) -> add(variable, value.second) } }
        // Jena's SPARQL 1.1 parser applies the codepoint-escape pre-pass (its SPARQL 1.2/ARQ parsers do not).
        val original = QueryFactory.create(query, Syntax.syntaxSPARQL_11)
        val expected = rows(QueryExecution.dataset(dataset).query(original).substitution(initial).build())
        val rewritten = rewrite(query, bindings)
        val parsed = try {
            QueryFactory.create(rewritten, Syntax.syntaxSPARQL_11)
        } catch (e: Exception) {
            throw AssertionError("$name: rewritten query is not legal SPARQL 1.1: ${e.message}\n$rewritten", e)
        }
        val actual = rows(QueryExecution.dataset(dataset).query(parsed).build())
        assertEquals(expected, actual, "$name:\n$rewritten")
    }

    @Test
    fun `nested aggregates keep sub-select scoping`() {
        assertSameAsJena(
            "COUNT(*) in a sub-select projecting the bound variable",
            "SELECT ?s ?c WHERE { ?s <urn:l> ?l { SELECT ?s (COUNT(*) AS ?c) WHERE { { SELECT ?s WHERE { ?s <urn:p> ?o } } } GROUP BY ?s } }",
            mapOf("s" to a),
        )
        assertThrows(IllegalArgumentException::class.java) {
            rewrite(
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
        assertSameAsJena(
            "sub-select joined directly",
            "SELECT ?s ?o WHERE { { SELECT ?s ?o WHERE { ?s <urn:p> ?o } } }",
            mapOf("s" to b),
        )
        assertSameAsJena(
            "GROUP BY a variable projected by a sub-select",
            "SELECT ?s (COUNT(*) AS ?c) WHERE { { SELECT ?s ?o WHERE { ?s <urn:p> ?o } } } GROUP BY ?s",
            mapOf("s" to a),
        )
    }

    @Test
    fun `codepoint escapes in the query text are decoded like a SPARQL 1_1 parser does`() {
        assertSameAsJena("escaped variable name", "SELECT ?\\u0073 ?o WHERE { ?\\u0073 <urn:p> ?o }", mapOf("s" to a))
        assertSameAsJena("u0075-encoded variable", "SELECT ?u ?o WHERE { ?\\u0075 <urn:p> ?o }", mapOf("u" to b))
        assertSameAsJena("escaped keyword", "\\u0053ELECT ?s ?o WHERE { ?s <urn:p> ?o }", mapOf("s" to b))
        assertSameAsJena(
            "escaped quote ends a string",
            "SELECT ?o WHERE { ?s <urn:p> ?o FILTER(STR(?o) != \"x\\u0022 && ?s = <urn:a> && \\u0022\" = \"\") }",
            mapOf("s" to a),
        )
    }

    @Test
    fun `rendered literals with backslash-u text agree with the tokenizer`() {
        val text = "\\u0022 ?s"
        assertSameAsJena(
            "literal constant in the query",
            "SELECT ?s WHERE { ?s <urn:t> ${SparqlLexical.quoted(text)} }",
            mapOf("s" to b),
        )
        assertSameAsJena(
            "literal binding",
            "SELECT ?s ?o WHERE { ?s <urn:t> ?o }",
            mapOf("o" to (Literal(text) to ResourceFactory.createStringLiteral(text))),
        )
    }

    @Test
    fun `BOUND of a bound variable stays legal SPARQL and is true`() {
        assertSameAsJena("BOUND in a FILTER expression", "SELECT ?o WHERE { ?s <urn:p> ?o FILTER(BOUND(?s) && ?o != 2) }", mapOf("s" to a))
        assertSameAsJena("BOUND directly after FILTER", "SELECT ?o WHERE { ?s <urn:p> ?o FILTER bound( \$s ) }", mapOf("s" to a))
        assertSameAsJena("negated BOUND", "SELECT ?o WHERE { ?x <urn:p> ?o OPTIONAL { ?s <urn:q> ?o } FILTER(!BOUND(?s)) }", mapOf("s" to b))
        assertSameAsJena("BOUND in the projection", "SELECT ?o (BOUND(?s) AS ?has) WHERE { ?s <urn:p> ?o }", mapOf("s" to a))
    }

    @Test
    fun `a star written without surrounding spaces is still SELECT star`() {
        assertSameAsJena("SELECT*WHERE", "SELECT*WHERE{ ?s <urn:p> ?o }", mapOf("s" to a))
        assertSameAsJena(
            "DISTINCT star in a sub-select",
            "SELECT ?s ?o WHERE { { SELECT DISTINCT*{ ?s <urn:p> ?o } } }",
            mapOf("s" to b),
        )
        assertSameAsJena("REDUCED star with WHERE", "SELECT ?o WHERE { { SELECT REDUCED*WHERE{ ?s <urn:p> ?o } } }", mapOf("s" to a))
        assertSameAsJena(
            "property path star",
            "PREFIX ex: <urn:> SELECT ?s ?o WHERE { ?s ex:p* ?o . ?s <urn:p>+ ?o }",
            mapOf("s" to a),
        )
        assertSameAsJena("COUNT star", "SELECT ?s (COUNT(*)AS ?c) WHERE { ?s <urn:p> ?o } GROUP BY ?s", mapOf("s" to a))
    }

    @Test
    fun `escaped characters in prefixed local names do not start comments`() {
        assertSameAsJena(
            "PN_LOCAL_ESC",
            "PREFIX ex: <urn:> SELECT ?s ?o WHERE { OPTIONAL { ?s ex:x\\#y ?z } ?s <urn:p> ?o }",
            mapOf("s" to b),
        )
    }
}
