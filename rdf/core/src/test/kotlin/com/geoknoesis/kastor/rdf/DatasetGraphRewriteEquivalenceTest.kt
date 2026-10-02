package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A dataset without named graphs replaces `GRAPH name { P }` by an empty table over the variables of `P`, with `P`
 * in a filter that is never asked, so the engine parses `P` but does not evaluate it against the default graph. The replacement must be invisible: the same solutions and
 * the same variables in scope as the original query has on a store that really has no named graphs.
 */
class DatasetGraphRewriteEquivalenceTest {
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")
    private val q = Iri("urn:q")
    private val g1 = Iri("urn:g1")

    private fun RdfRepository.addDefaultData() {
        editDefaultGraph().addTriple(RdfTriple(s, p, string("default")))
        editDefaultGraph().addTriple(RdfTriple(s, q, g1))
        editDefaultGraph().addTriple(RdfTriple(Iri("urn:t"), p, string("other")))
    }

    /** The store behind the dataset: it has a named graph the dataset does not include. */
    private val repo = Rdf.memory().apply {
        addDefaultData()
        editGraph(g1).addTriple(RdfTriple(s, p, string("secret")))
    }

    /** The reference: the same default graph in a store without any named graph, queried directly. */
    private val reference = Rdf.memory().apply { addDefaultData() }

    private val dataset = (Dataset { defaultGraph(repo) } as DatasetImpl).apply {
        materializationRepositoryFactory = { error("the dataset must not be materialized") }
    }

    @AfterEach
    fun close() {
        repo.close()
        reference.close()
    }

    private fun rows(result: SparqlQueryResult): List<Map<String, RdfTerm>> =
        result.map { row -> row.getVariableNames().mapNotNull { name -> row.get(name)?.let { name to it } }.toMap() }
            .sortedBy { it.toString() }

    /** The variables Jena projects for [query] (for `SELECT *`: the variables in scope). */
    private fun projected(query: String): Set<String> {
        val parsed = Class.forName("org.apache.jena.query.QueryFactory").getMethod("create", String::class.java).invoke(null, query)
        @Suppress("UNCHECKED_CAST")
        return (parsed.javaClass.getMethod("getResultVars").invoke(parsed) as List<String>).toSet()
    }

    private val names = listOf("<urn:g1>", "?g")

    private fun forms(name: String) = listOf(
        "SELECT * { GRAPH $name { ?s ?p ?o } }",
        "SELECT * { ?s <urn:p> ?o OPTIONAL { GRAPH $name { ?s ?x ?y } } }",
        "SELECT * { ?s <urn:p> ?o MINUS { GRAPH $name { ?s ?x ?y } } }",
        "SELECT * { { ?s <urn:p> ?o } UNION { GRAPH $name { ?s <urn:p> ?o2 } } }",
        "SELECT * { ?s <urn:p> ?o FILTER NOT EXISTS { GRAPH $name { ?s ?x ?y } } }",
        "SELECT * { ?s <urn:p> ?o FILTER EXISTS { GRAPH $name { ?s ?x ?y } } }",
        "SELECT * { ?s <urn:q> ?n GRAPH $name { ?s ?p ?o } }",
        "SELECT * { ?s <urn:p> ?o OPTIONAL { GRAPH $name { ?s ?x ?y OPTIONAL { ?y ?z ?w } { ?a ?b ?c } UNION { GRAPH ?h { ?d ?e ?f } } } } }",
        "SELECT * { ?s <urn:p> ?o OPTIONAL { GRAPH $name { ?s <urn:p>/<urn:q>* [ <urn:r> ?v ] ; a ?t . (?l 1 \"x\"@en true) <urn:p> \$z } } }",
        // Patterns whose variables in scope are not simply "every variable written".
        "SELECT * { ?s <urn:p> ?o OPTIONAL { GRAPH $name { ?s ?x ?y FILTER(?y != ?unbound) } } }",
        "SELECT * { ?s <urn:p> ?o OPTIONAL { GRAPH $name { ?s ?x ?y BIND(1 AS ?b) } } }",
        "SELECT * { ?s <urn:p> ?o OPTIONAL { GRAPH $name { ?s ?x ?y MINUS { ?s ?m ?n } } } }",
        "SELECT * { ?s <urn:p> ?o OPTIONAL { GRAPH $name { SELECT ?y { ?s ?x ?y } } } }",
        "SELECT * { ?s <urn:p> ?o OPTIONAL { GRAPH $name { ?s ?x ?y VALUES ?v { 1 } } } }",
    )

    @Test
    fun `rewritten GRAPH patterns give the solutions and the variables of a store without named graphs`() {
        for (name in names) for (query in forms(name)) {
            val rewritten = SparqlDatasetClauses.withoutNamedGraphs(query)!!
            assertFalse(SparqlDatasetClauses.usesGraphPattern(rewritten), rewritten)
            assertEquals(projected(query), projected(rewritten), "variables in scope of: $query\nrewritten: $rewritten")
            assertEquals(rows(reference.select(SparqlSelectQuery(query))), rows(dataset.select(SparqlSelectQuery(query))), query)
        }
        // The data makes the outer patterns match, so the comparisons above are not between empty results only.
        assertEquals(2, dataset.select(SparqlSelectQuery(forms("?g")[1])).count())
    }

    @Test
    fun `a pattern of triples, OPTIONAL, UNION and nested groups becomes a table whose filter is never asked`() {
        fun rewritten(query: String) = SparqlDatasetClauses.withoutNamedGraphs(query)
        assertEquals("SELECT * { { VALUES (?s ?p ?o) { } FILTER EXISTS { ?s ?p ?o } } }", rewritten("SELECT * { GRAPH <urn:g1> { ?s ?p ?o } }"))
        assertEquals("SELECT * { { VALUES (?g ?s ?p ?o) { } FILTER EXISTS { ?s ?p ?o } } }", rewritten("SELECT * { GRAPH ?g { ?s ?p ?o } }"))
        assertEquals("SELECT * { { VALUES (?g ?p) { } FILTER EXISTS { ?g ?p \$g } } }", rewritten("SELECT * { GRAPH ?g { ?g ?p \$g } }"))
        assertEquals("SELECT * { { VALUES ?g { } FILTER EXISTS { } } }", rewritten("SELECT * { GRAPH ?g { } }"))
        assertEquals("PREFIX u: <urn:> SELECT * { { VALUES ?o { } FILTER EXISTS { u:s u:p ?o } } }", rewritten("PREFIX u: <urn:> SELECT * { GRAPH u:g1 { u:s u:p ?o } }"))
        assertEquals("ASK { { { <urn:s> <urn:p> 'GRAPH ?x { }' } FILTER(false) } }", rewritten("ASK { GRAPH <urn:g1> { <urn:s> <urn:p> 'GRAPH ?x { }' } }"))
        assertEquals(
            "SELECT * { ?s ?p ?o OPTIONAL { { VALUES (?g ?s ?x ?y ?h ?z) { } FILTER EXISTS { ?s ?x ?y OPTIONAL { " +
                "{ VALUES (?h ?y ?x ?z) { } FILTER EXISTS { ?y ?x ?z } } } } } } }",
            rewritten("SELECT * { ?s ?p ?o OPTIONAL { GRAPH ?g { ?s ?x ?y OPTIONAL { GRAPH ?h { ?y ?x ?z } } } } }"),
        )
        // SERVICE keeps the pattern (inside a group that yields nothing): its remote group is not analysed.
        val kept = rewritten("SELECT * { GRAPH <urn:g1> { ?s ?p ?o SERVICE <urn:ep> { ?o ?q ?r } GRAPH ?h { ?a ?b ?c } } }")!!
        assertTrue(kept.contains("?s ?p ?o SERVICE <urn:ep> { ?o ?q ?r }") && kept.contains("FILTER(false)"), kept)
        assertTrue(kept.contains("{ VALUES (?h ?a ?b ?c) { } FILTER EXISTS { ?a ?b ?c } }"), "a simple pattern nested in a kept one is replaced: $kept")
    }
}
