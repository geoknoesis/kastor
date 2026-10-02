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

    @Test
    fun `a number directly followed by a keyword is read like a SPARQL parser reads it`() {
        assertSameAsJena("integer before AS", "SELECT ?s (1AS ?one) WHERE { ?s <urn:p> ?o }", mapOf("s" to a))
        assertSameAsJena(
            "decimal, exponent and signed forms",
            "SELECT ?s (1.5e0AS ?x) (.5AS ?y) (?o+1AS ?z) (-2AS ?w) WHERE { ?s <urn:p> ?o }",
            mapOf("s" to a),
        )
        assertSameAsJena("numbers glued to operators", "SELECT ?o WHERE { ?s <urn:p> ?o FILTER(?o=1||?o=3&&?s=<urn:b>) }", mapOf("s" to b))
        assertSameAsJena("number before a dot", "SELECT ?s WHERE { ?s <urn:p> 1. ?s <urn:p> ?o }", mapOf("s" to a))
        for (query in listOf("SELECT (1AS ?s) WHERE { ?x <urn:p> ?o }", "SELECT ?o WHERE { ?x <urn:p> ?o BIND(1AS ?s) }")) {
            assertThrows(IllegalArgumentException::class.java, { rewrite(query, mapOf("s" to a)) }, query)
        }
    }

    /**
     * Queries using `?v`, and whether `?v` stands where only an IRI is legal (a predicate, or the name of a GRAPH
     * or SERVICE).
     */
    private val positions: List<Pair<String, Boolean>> = listOf(
        "SELECT * WHERE { ?s ?v ?o }" to true,
        "SELECT ?o WHERE { ?v <urn:p> ?o }" to false,
        "SELECT ?s WHERE { ?s <urn:p> ?v }" to false,
        "SELECT ?o WHERE { ?s <urn:p> ?o ; ?v ?z }" to true,
        "SELECT ?o WHERE { ?s <urn:p> ?o , ?v }" to false,
        "SELECT ?o WHERE { ?s <urn:p> ?o ; <urn:l> ?v . }" to false,
        "SELECT ?o WHERE { ?s <urn:p> ?o . ?o ?v ?z }" to true,
        "SELECT ?o WHERE { ?s <urn:p> ?o. ?v <urn:l> ?z }" to false,
        "SELECT ?o WHERE { ?s <urn:p> ?o.?z ?v ?o }" to true,
        "SELECT ?o WHERE { ?s <urn:p> ?o ; . ?v <urn:l> ?z }" to false,
        "SELECT ?o WHERE { ?s <urn:p> ?o ; ; ?v ?z }" to true,
        "SELECT ?o WHERE { ?s <urn:p> ?o ; ?v ?z ; <urn:l> ?y }" to true,
        "SELECT ?o WHERE { \$s <urn:p> ?o . ?o \$v ?z }" to true,
        "PREFIX ex: <urn:> SELECT ?z WHERE { ?s ex:p ex:o. ?v ex:l ?z }" to false,
        "PREFIX ex: <urn:> SELECT ?z WHERE { ?s ex:p ex:o. ?z ?v ex:o }" to true,
        "PREFIX ex: <urn:> SELECT ?z WHERE { ex:s ?v ?z }" to true,
        "PREFIX ex: <urn:> SELECT ?z WHERE { ex:s ex:p ?z ; ex:q ?v }" to false,
        "PREFIX ex: <urn:> SELECT ?z WHERE { ?s ex:a.b ?v . ex:a.b ?v ?z }" to true,
        "SELECT ?z WHERE { ?s <urn:p> 1. ?v <urn:l> ?z }" to false,
        "SELECT ?z WHERE { ?s <urn:p> 1.5 ; ?v ?z }" to true,
        "SELECT ?z WHERE { ?s <urn:p> 1.5e3 , ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p> -1 ; ?v ?z }" to true,
        "SELECT ?z WHERE { ?s <urn:p> +1 . ?v <urn:l> ?z }" to false,
        "SELECT ?z WHERE { ?s <urn:p> +1 . ?z ?v 2 }" to true,
        "SELECT ?z WHERE { ?s <urn:p> true ; ?v ?z }" to true,
        "SELECT ?z WHERE { ?s <urn:p> false . ?v <urn:l> ?z }" to false,
        "SELECT ?z WHERE { ?s <urn:p> \"x\"@en ; ?v ?z }" to true,
        "SELECT ?z WHERE { ?s <urn:p> \"x\"@en-GB . ?v <urn:l> ?z }" to false,
        "SELECT ?z WHERE { ?s <urn:p> \"1\"^^<urn:dt> ; ?v ?z }" to true,
        "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#> SELECT ?z WHERE { ?s <urn:p> \"1\"^^xsd:int . ?v <urn:l> ?z }" to false,
        "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#> SELECT ?z WHERE { ?s <urn:p> \"1\"^^xsd:int. ?z ?v 1 }" to true,
        "SELECT ?z WHERE { ?s <urn:p> 'x' , ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p> '''x ?s ?v ?o''' ; <urn:q> ?v }" to false,
        "SELECT ?z WHERE { \"a\" ?v ?z }" to true,
        "SELECT ?z WHERE { \"a\"@en ?v ?z }" to true,
        "SELECT ?z WHERE { \"a\"^^<urn:dt> ?v ?z }" to true,
        "SELECT ?z WHERE { \"a\"^^<urn:dt> <urn:p> ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p>/<urn:q> ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p> / <urn:q> ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p>|<urn:q> ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p> | ^ <urn:q> ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p>* ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p>+ ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p>? ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p>?/<urn:q> ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p>+/<urn:q>* ?v ; ?v ?z }" to true,
        "SELECT ?z WHERE { ?s ^<urn:p> ?v }" to false,
        "SELECT ?z WHERE { ?s (<urn:p>|<urn:q>)+ ?v }" to false,
        "SELECT ?z WHERE { ?s (<urn:p>/(<urn:q>|^<urn:r>))* ?v . ?v <urn:l> ?z }" to false,
        "SELECT ?z WHERE { ?s !(<urn:p>|^<urn:q>) ?v }" to false,
        "SELECT ?z WHERE { ?s !<urn:p> ?v }" to false,
        "SELECT ?z WHERE { ?s !a ?v }" to false,
        "SELECT ?z WHERE { ?s a ?v }" to false,
        "SELECT ?z WHERE { ?s a <urn:C> ; ?v ?z }" to true,
        "PREFIX ex: <urn:> SELECT ?z WHERE { ?s ex:p/ex:q|^ex:r ?v }" to false,
        "PREFIX ex: <urn:> SELECT ?z WHERE { ?s ex:p*/ex:q+ ?v }" to false,
        "PREFIX ex: <urn:> SELECT ?z WHERE { ?s ^ex:p ?v . ?z ?v ?s }" to true,
        "PREFIX ex: <urn:> SELECT ?z WHERE { ?s !(ex:p|^ex:q)/ex:r ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p>+ 1 . ?z ?v ?o }" to true,
        "SELECT ?z WHERE { [ ?v ?o ] <urn:p> ?z }" to true,
        "SELECT ?z WHERE { [ <urn:p> ?v ] <urn:q> ?z }" to false,
        "SELECT ?z WHERE { [ <urn:p> ?o ; ?v ?z ] }" to true,
        "SELECT ?z WHERE { [ <urn:p> ?o ] ?v ?z }" to true,
        "SELECT ?z WHERE { [] ?v ?z }" to true,
        "SELECT ?z WHERE { [ ] <urn:p> ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p> [ <urn:q> ?z ] ; ?v ?o }" to true,
        "SELECT ?z WHERE { ?s <urn:p> [ <urn:q> ?z ] , ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p> [ <urn:q> [ ?v ?z ] ] }" to true,
        "SELECT ?z WHERE { _:b ?v ?z }" to true,
        "SELECT ?z WHERE { _:b <urn:p> ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p> (?v 1) }" to false,
        "SELECT ?z WHERE { (?v) <urn:p> ?z }" to false,
        "SELECT ?z WHERE { (1 2) ?v ?z }" to true,
        "SELECT ?z WHERE { ?s <urn:p> (1 [ ?v 2 ] (?z)) }" to true,
        "SELECT ?z WHERE { ?s <urn:p> (1 [ <urn:q> ?v ] (?v)) ; <urn:r> ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p> () ; ?v ?z }" to true,
        "SELECT ?z WHERE { ?s <urn:p> ?o OPTIONAL { ?o ?v ?z } }" to true,
        "SELECT ?z WHERE { ?s <urn:p> ?o OPTIONAL { ?v <urn:l> ?z } }" to false,
        "SELECT ?z WHERE { ?s <urn:p> ?o OPTIONAL { ?s <urn:q> ?y } ?z ?v ?y }" to true,
        "SELECT ?z WHERE { { ?s ?v ?z } UNION { ?s <urn:p> ?z } }" to true,
        "SELECT ?z WHERE { { ?v <urn:p> ?z } UNION { ?s <urn:p> ?v } }" to false,
        "SELECT ?z WHERE { { ?s <urn:p> ?o } ?z ?v ?y }" to true,
        "SELECT ?z WHERE { ?s <urn:p> ?z MINUS { ?s ?v ?z } }" to true,
        "SELECT ?z WHERE { ?s <urn:p> ?z FILTER NOT EXISTS { ?s ?v ?y } }" to true,
        "SELECT ?z WHERE { ?s <urn:p> ?z FILTER EXISTS { ?v <urn:q> ?y } }" to false,
        "SELECT ?z WHERE { ?s <urn:p> ?z FILTER NOT EXISTS { ?s <urn:q> ?y } ?z ?v ?y }" to true,
        "SELECT ?z WHERE { ?s <urn:p> ?z FILTER(NOT EXISTS { ?s ?v ?y } && ?z > 0) }" to true,
        "SELECT ?z WHERE { FILTER(?z > 1) ?s ?v ?z }" to true,
        "SELECT ?z WHERE { FILTER(?z > 1) ?v <urn:p> ?z }" to false,
        "SELECT ?z WHERE { FILTER(?z > 1 && ?v = 2) . ?s <urn:p> ?z }" to false,
        "SELECT ?z WHERE { FILTER regex(str(?z), \"1\") ?s ?v ?z }" to true,
        "SELECT ?z WHERE { FILTER regex(str(?z), ?v) ?v <urn:p> ?z }" to false,
        "SELECT ?z WHERE { FILTER <urn:fn>(?z) ?s ?v ?z }" to true,
        "PREFIX ex: <urn:> SELECT ?z WHERE { FILTER ex:fn(?z, ?v) ?s ex:p ?z }" to false,
        "SELECT ?z WHERE { ?s <urn:p> ?z FILTER(?z IN (?v, 1)) }" to false,
        "SELECT ?z WHERE { BIND(?v AS ?w) ?s <urn:p> ?z }" to false,
        "SELECT ?z WHERE { BIND(1 AS ?w) ?s ?v ?z }" to true,
        "SELECT ?z WHERE { ?s <urn:p> ?z BIND(str(?z) AS ?w) ?w ?v ?y }" to true,
        "SELECT ?z WHERE { VALUES ?z { 1 2 } ?s ?v ?z }" to true,
        "SELECT ?z WHERE { VALUES (?z ?y) { (1 2) (UNDEF 3) } ?v <urn:p> ?z }" to false,
        "SELECT ?z WHERE { ?s <urn:p> ?z VALUES ?z { 1 <urn:x> \"s\" } }" to false,
        "SELECT ?z WHERE { GRAPH ?v { ?s <urn:p> ?z } }" to true,
        "SELECT ?z WHERE { GRAPH <urn:g> { ?s ?v ?z } }" to true,
        "SELECT ?z WHERE { GRAPH <urn:g> { ?v <urn:p> ?z } }" to false,
        "SELECT ?z WHERE { GRAPH ?g { ?v <urn:p> ?z } ?g <urn:q> ?v }" to false,
        "SELECT ?z WHERE { ?s <urn:p> ?z . GRAPH ?v { ?s <urn:p> ?z } }" to true,
        "SELECT ?z WHERE { SERVICE ?v { ?s <urn:p> ?z } }" to true,
        "SELECT ?z WHERE { SERVICE SILENT ?v { ?s <urn:p> ?z } }" to true,
        "SELECT ?z WHERE { SERVICE <urn:x> { ?v <urn:p> ?z } }" to false,
        "SELECT ?z WHERE { SERVICE SILENT <urn:x> { ?s ?v ?z } }" to true,
        "SELECT ?z WHERE { { SELECT ?z ?v WHERE { ?s ?v ?z } } }" to true,
        "SELECT ?z WHERE { { SELECT ?v WHERE { ?v <urn:p> ?o } ORDER BY ?v LIMIT 3 } ?v <urn:l> ?z }" to false,
        "SELECT ?z WHERE { { SELECT ?v (COUNT(*) AS ?z) WHERE { ?v <urn:p> ?o } GROUP BY ?v HAVING(COUNT(*) > 0) } }" to false,
        "SELECT ?z WHERE { { SELECT ?v ?z WHERE { ?v <urn:p> ?z } } ?z ?v ?y }" to true,
        "SELECT ?v (COUNT(*) AS ?n) WHERE { ?v <urn:p> ?o } GROUP BY ?v ORDER BY ?v" to false,
        "SELECT ?z (COUNT(?v) AS ?n) WHERE { ?s ?v ?z } GROUP BY ?z" to true,
        "SELECT ?z FROM <urn:d> WHERE { ?s ?v ?z }" to true,
        "SELECT ?z { ?v <urn:p> ?z } VALUES ?z { 1 }" to false,
        "SELECT ?z WHERE { ?s ?v ?z } VALUES ?z { 1 }" to true,
        "SELECT ?z WHERE { ?s <urn:p> ?z # ?s ?v ?z\n ; ?v ?y }" to true,
        "SELECT ?z WHERE { ?s <urn:p> ?z # ; ?v ?y\n . ?v <urn:l> ?y }" to false,
        "SELECT ?z WHERE { ?s <urn:p> \"; ?v ?o\" . ?v <urn:l> ?z }" to false,
        "SELECT ?z WHERE{?s<urn:p>?z;?v?y}" to true,
        "SELECT ?z WHERE{?v<urn:p>?z;<urn:q>?v.}" to false,
    )

    /**
     * The same for RDF 1.2 syntax (SPARQL 1.2): reified triples, triple terms and annotation blocks have a verb
     * of their own, and a reifier is an IRI, a blank node or a variable, never a literal.
     */
    private val positions12: List<Pair<String, Boolean>> = listOf(
        "SELECT * WHERE { << ?s ?v ?o >> <urn:q> ?z }" to true,
        "SELECT * WHERE { <<?s ?v ?o>> <urn:q> ?z }" to true,
        "SELECT * WHERE { << ?v <urn:p> ?o >> <urn:q> ?z }" to false,
        "SELECT * WHERE { << ?s <urn:p> ?v >> <urn:q> ?z }" to false,
        "SELECT * WHERE { << ?s <urn:p> ?o >> ?v ?z }" to true,
        "SELECT * WHERE { << ?s <urn:p> ?o >> <urn:q> ?v }" to false,
        "SELECT * WHERE { ?x <urn:q> << ?s ?v ?o >> }" to true,
        "SELECT * WHERE { ?x <urn:q> << ?s <urn:p> ?o >> ; ?v ?z }" to true,
        "SELECT * WHERE { ?x <urn:q> << ?s <urn:p> ?o >> , ?v }" to false,
        "SELECT * WHERE { ?x <urn:q> << ?s <urn:p> ?o >> . ?v <urn:l> ?z }" to false,
        "SELECT * WHERE { ?x <urn:q> << ?s <urn:p> ?o >>. ?z ?v ?y }" to true,
        "SELECT * WHERE { << ?s <urn:p> ?o >> <urn:q> ?z . ?z ?v ?y }" to true,
        "SELECT * WHERE { << << ?a ?v ?b >> <urn:p> ?o >> <urn:q> ?z }" to true,
        "SELECT * WHERE { << << ?a <urn:p> ?b >> ?v ?o >> <urn:q> ?z }" to true,
        "SELECT * WHERE { << ?a <urn:p> << ?b ?v ?c >> >> <urn:q> ?z }" to true,
        "SELECT * WHERE { << ?a <urn:p> << ?b <urn:p> ?v >> >> <urn:q> ?z }" to false,
        "SELECT * WHERE { << ?a <urn:p> <<( ?b <urn:p> ?v )>> >> <urn:q> ?z }" to false,
        "SELECT * WHERE { << ?a <urn:p> <<( ?b ?v ?c )>> >> <urn:q> ?z }" to true,
        "SELECT * WHERE { << ?s <urn:p> \"x\"@en>> ?v ?z }" to true,
        "SELECT * WHERE { << ?s <urn:p> \"x\"@en >> <urn:q> ?v }" to false,
        "SELECT * WHERE { << ?s <urn:p> \"1\"^^<urn:dt>>> ?v ?z }" to true,
        "SELECT * WHERE { << ?s <urn:p> 1>> ?v ?z }" to true,
        "SELECT * WHERE { << ?s <urn:p> true>> <urn:q> ?v }" to false,
        "PREFIX ex: <urn:> SELECT * WHERE { <<ex:s ?v ex:o>> ex:q ?z }" to true,
        "PREFIX ex: <urn:> SELECT * WHERE { <<ex:s ex:p ex:o>> ?v ?z }" to true,
        "PREFIX ex: <urn:> SELECT * WHERE { <<ex:s ex:p ex:o>> ex:q ?v }" to false,
        "PREFIX ex: <urn:> SELECT * WHERE { ?x ex:q <<ex:s ex:p 1>>. ?v ex:l ?z }" to false,
        "PREFIX ex: <urn:> SELECT * WHERE { ?x ex:q <<ex:s ex:p 1>>; ?v ?z }" to true,
        "SELECT * WHERE { [ <urn:p> << ?a ?v ?b >> ] <urn:q> ?z }" to true,
        "SELECT * WHERE { [ <urn:p> << ?a <urn:p> ?b >> ; ?v ?z ] }" to true,
        "SELECT * WHERE { ?s <urn:p> (1 << ?a ?v ?b >>) }" to true,
        "SELECT * WHERE { ?s <urn:p> (1 << ?a <urn:p> ?v >> ?v) }" to false,
        "SELECT * WHERE { << ?s ?v ?o ~ <urn:r> >> <urn:q> ?z }" to true,
        "SELECT * WHERE { << ?s <urn:p> ?o ~ ?v >> <urn:q> ?z }" to true,
        "SELECT * WHERE { << ?s <urn:p> ?o ~ <urn:r> >> <urn:q> ?v }" to false,
        "SELECT * WHERE { ?s <urn:p> ?o ~ ?v }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o ~?v }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o ~ <urn:r> ; ?v ?z }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o ~ <urn:r> , ?v }" to false,
        "SELECT * WHERE { ?s <urn:p> ?o ~ ; ?v ?z }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o ~ . ?v <urn:l> ?z }" to false,
        "SELECT * WHERE { ?s <urn:p> ?o ~ ?r ~ ?v }" to true,
        "PREFIX ex: <urn:> SELECT * WHERE { ?s ex:p ?o ~ex:r ; ?v ?z }" to true,
        "PREFIX ex: <urn:> SELECT * WHERE { ?s ex:p ?o ~ex:r , ?v }" to false,
        "PREFIX ex: <urn:> SELECT * WHERE { ?s ex:p ?o ~ _:r ; ex:q ?v }" to false,
        "SELECT * WHERE { ?x <urn:q> <<( ?s ?v ?o )>> }" to true,
        "SELECT * WHERE { ?x <urn:q> <<(?s ?v ?o)>>. }" to true,
        "SELECT * WHERE { ?x <urn:q> <<( ?v <urn:p> ?o )>> }" to false,
        "SELECT * WHERE { ?x <urn:q> <<( ?s <urn:p> ?v )>> }" to false,
        "SELECT * WHERE { ?x <urn:q> <<( ?s <urn:p> ?o )>> ; ?v ?z }" to true,
        "SELECT * WHERE { ?x <urn:q> <<( ?s <urn:p> ?o )>> , ?v }" to false,
        "SELECT * WHERE { ?x <urn:q> <<( ?s <urn:p> <<( ?a ?v ?b )>> )>> }" to true,
        "SELECT * WHERE { ?x <urn:q> <<( ?s <urn:p> <<( ?a <urn:p> ?v )>> )>> . ?v <urn:l> ?z }" to false,
        "SELECT * WHERE { ?x <urn:q> ?t FILTER(?t = <<( ?s ?v ?o )>>) }" to true,
        "SELECT * WHERE { ?x <urn:q> ?t FILTER(?t = <<( ?v <urn:p> ?o )>>) }" to true,
        "SELECT * WHERE { ?x <urn:q> ?t FILTER(?t = <<( ?s <urn:p> ?v )>>) }" to false,
        "SELECT * WHERE { ?x <urn:q> ?t FILTER(?t = <<( ?s <urn:p> <<( ?v <urn:p> ?o )>> )>>) }" to true,
        "SELECT * WHERE { ?x <urn:q> ?t FILTER(?t = <<( ?s <urn:p> <<( ?a <urn:p> ?v )>> )>>) }" to false,
        "SELECT * WHERE { ?x <urn:q> ?t FILTER(isTRIPLE(<<( ?s ?v ?o )>>) && ?t = 1) }" to true,
        "SELECT * WHERE { ?x <urn:q> ?t FILTER(isTRIPLE(<<( ?s <urn:p> ?v )>>)) ?v <urn:l> ?z }" to false,
        "SELECT * WHERE { ?x <urn:q> ?t FILTER(?t = <<( ?s <urn:p> ?o )>>) ?z ?v ?y }" to true,
        "SELECT * WHERE { BIND(<<( ?s ?v ?o )>> AS ?t) ?s <urn:p> ?o }" to true,
        "SELECT * WHERE { BIND(<<( ?s <urn:p> ?v )>> AS ?t) ?v <urn:p> ?o }" to false,
        "SELECT (<<( ?s ?v ?o )>> AS ?t) WHERE { ?s <urn:p> ?o }" to true,
        "SELECT (<<( ?s <urn:p> ?v )>> AS ?t) WHERE { ?s <urn:p> ?o }" to false,
        "SELECT ?o WHERE { ?s <urn:p> ?o } ORDER BY (<<( ?s ?v ?o )>>)" to true,
        "SELECT ?o WHERE { ?s <urn:p> ?o } ORDER BY (<<( ?v <urn:p> ?o )>>)" to true,
        "SELECT ?o WHERE { ?s <urn:p> ?o } ORDER BY (<<( ?s <urn:p> ?v )>>)" to false,
        "SELECT ?o (COUNT(*) AS ?n) WHERE { ?s <urn:p> ?o } GROUP BY ?o HAVING(?o != <<( ?v <urn:p> 1 )>>)" to true,
        "SELECT * WHERE { ?s <urn:p> ?o {| ?v ?z |} }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o {|?v ?z|} }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o {| <urn:q> ?v |} }" to false,
        "SELECT * WHERE { ?s <urn:p> ?o {| <urn:q> ?z ; ?v ?y |} }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o {| <urn:q> ?z , ?v |} }" to false,
        "SELECT * WHERE { ?s <urn:p> ?o {| <urn:q> ?z |} ; ?v ?y }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o {| <urn:q> ?z |} , ?v }" to false,
        "SELECT * WHERE { ?s <urn:p> ?o {| <urn:q> ?z |} . ?v <urn:l> ?y }" to false,
        "SELECT * WHERE { ?s <urn:p> ?o {| <urn:q> ?z |} . ?y ?v ?z }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o {| <urn:q> ?z {| ?v ?y |} |} }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o {| <urn:q> ?z {| <urn:r> ?v |} ; ?v ?y |} }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o {| <urn:q> ?z {| <urn:r> ?v |} |} , ?v }" to false,
        "SELECT * WHERE { ?s <urn:p> ?o ~ <urn:r> {| ?v ?z |} }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o ~ ?r {| <urn:q> ?v |} }" to false,
        "SELECT * WHERE { ?s <urn:p> ?o {| <urn:q> ?z |} {| ?v ?y |} }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o {| <urn:q> ?z |} ~ ?v }" to true,
        "SELECT * WHERE { ?s <urn:p> ?o {| <urn:q> << ?a ?v ?b >> |} }" to true,
        "SELECT * WHERE { ?s <urn:p> << ?a <urn:p> ?b >> {| ?v ?z |} }" to true,
        "SELECT * WHERE { ?s <urn:p> [ <urn:q> ?z {| ?v ?y |} ] }" to true,
        "PREFIX ex: <urn:> SELECT * WHERE { ?s ex:p ex:o {| ex:q ex:r|} ; ?v ?z }" to true,
        "PREFIX ex: <urn:> SELECT * WHERE { ?s ex:p ex:o{|ex:q ?v|} }" to false,
        "PREFIX ex: <urn:> SELECT * WHERE { ?s ex:p ex:o{|ex:q 1|}. ?z ?v ?y }" to true,
        "PREFIX ex: <urn:> SELECT * WHERE { ?s ex:p ex:o{|ex:q \"x\"@en|}. ?v ex:l ?y }" to false,
        "SELECT * WHERE { ?s <urn:p> ?o FILTER(?o < <urn:x> || ?o < ?v || ?v > ?o) ?v <urn:l> ?z }" to false,
    )

    @Test
    fun `only variables in IRI-only positions reject a literal and a literal is legal everywhere else`() {
        assertPositions(positions, Syntax.syntaxSPARQL_11)
        assertEquals(true, positions.count { it.second } > 40 && positions.count { !it.second } > 40)
    }

    @Test
    fun `RDF 1_2 triple terms, reified triples and annotation blocks have IRI-only positions too`() {
        assertPositions(positions12, Syntax.syntaxSPARQL_12)
        assertEquals(true, positions12.count { it.second } > 40 && positions12.count { !it.second } > 25)
    }

    @Test
    fun `an IRI substituted next to a comparison operator does not form an RDF 1_2 token`() {
        // Glued as written, `?s<<urn:b>` and `<urn:b>>?s` would start and end a reified triple for a SPARQL 1.2 parser.
        val query = "SELECT ?s ?o WHERE { ?s <urn:p> ?o FILTER(?s<?x || ?x>?s || ?s=?x) }"
        val rewritten = rewrite(query, mapOf("x" to b))
        assertEquals("SELECT ?s ?o WHERE { ?s <urn:p> ?o FILTER(?s< <urn:b> || <urn:b> >?s || ?s=<urn:b>) }", rewritten)
        for (syntax in listOf(Syntax.syntaxSPARQL_11, Syntax.syntaxSPARQL_12, Syntax.syntaxARQ)) {
            QueryFactory.create(rewritten, syntax)
        }
        assertSameAsJena("IRI next to comparison operators", query, mapOf("x" to b))
    }

    /** Every query of [positions] against Jena's parser for [syntax], which says where a literal is legal. */
    private fun assertPositions(positions: List<Pair<String, Boolean>>, syntax: Syntax) {
        val probe = "<urn:kastor:position-probe>"
        val literal = "\"lit\""
        fun parses(text: String) = try {
            QueryFactory.create(text, syntax)
            true
        } catch (e: Exception) {
            false
        }
        for ((query, iriOnly) in positions) {
            assertEquals(true, parses(query), "test query is not legal SPARQL: $query")
            // The same rewrite with an IRI is always legal; swapping the IRI for the literal in its output is
            // what a rewrite without the position check would send.
            val withIri = SparqlInitialBindings.apply(query, mapOf("v" to probe))
            assertEquals(true, parses(withIri), "IRI binding: $withIri")
            val unchecked = withIri.replace(probe, literal)
            assertEquals(!iriOnly, parses(unchecked), "expectation for: $query\n$unchecked")

            val outcome = try {
                SparqlInitialBindings.apply(query, mapOf("v" to literal))
            } catch (e: IllegalArgumentException) {
                assertEquals(true, e.message!!.contains("only an IRI"), e.message)
                null
            }
            if (iriOnly) {
                assertEquals(null, outcome, "a literal was accepted where only an IRI is legal: $query")
                assertThrows(IllegalArgumentException::class.java, { SparqlInitialBindings.validate(query, setOf("v"), setOf("v")) }, query)
            } else {
                assertEquals(unchecked, outcome, "a literal was rejected in a legal position: $query")
                SparqlInitialBindings.validate(query, setOf("v"), setOf("v"))
            }
            SparqlInitialBindings.validate(query, setOf("v"), emptySet())
        }
    }
}
