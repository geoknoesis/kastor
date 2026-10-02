package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A dataset whose only graph is the repository's default graph has an empty named-graph set: GRAPH patterns
 * must match nothing, and the store must be queried in place (never copied into a temporary repository).
 */
class DatasetEmptyNamedGraphsTest {
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")
    private val q = Iri("urn:q")
    private val g1 = Iri("urn:g1")

    private val repo = Rdf.memory().apply {
        editDefaultGraph().addTriple(RdfTriple(s, p, string("default")))
        editDefaultGraph().addTriple(RdfTriple(s, q, g1))
        editGraph(g1).addTriple(RdfTriple(s, p, string("secret")))
    }

    private val dataset = (Dataset { defaultGraph(repo) } as DatasetImpl).apply {
        materializationRepositoryFactory = { error("the dataset must not be materialized") }
    }

    @AfterEach
    fun close() = repo.close()

    private fun values(query: String) = dataset.select(SparqlSelectQuery(query)).map { it.getString("o") }

    @Test
    fun `GRAPH patterns see no named graphs and the store is not copied`() {
        assertEquals(emptyList<String?>(), values("SELECT ?o { GRAPH ?g { ?s ?p ?o } }"))
        assertEquals(emptyList<String?>(), values("SELECT ?o WHERE { GRAPH <urn:g1> { ?s ?p ?o } }"))
        assertEquals(emptyList<String?>(), values("PREFIX u: <urn:> SELECT ?o { graph u:g1 { ?s ?p ?o } }"))
        assertFalse(dataset.ask(SparqlAskQuery("ASK { GRAPH ?g { ?s ?p ?o } }")))
        assertFalse(dataset.ask(SparqlAskQuery("ask{graph<urn:g1>{?s ?p ?o}}")))
        assertEquals(0, dataset.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?g { ?s ?p ?o } }")).count())
    }

    @Test
    fun `GRAPH patterns combine with the default graph as with an empty named-graph set`() {
        assertEquals(listOf("default"), values("SELECT ?o { ?s <urn:p> ?o OPTIONAL { GRAPH ?g { ?s ?x ?y } } }"))
        assertEquals(listOf("default"), values("SELECT ?o { { ?s <urn:p> ?o } UNION { GRAPH ?g { ?s <urn:p> ?o } } }"))
        assertEquals(listOf("default"), values("SELECT ?o { ?s <urn:p> ?o MINUS { GRAPH ?g { ?s ?x ?y } } }"))
        assertEquals(listOf("default"), values("SELECT ?o { ?s <urn:p> ?o FILTER NOT EXISTS { GRAPH ?g { ?s ?x ?y } } }"))
        assertEquals(emptyList<String?>(), values("SELECT ?o { ?s <urn:p> ?o FILTER EXISTS { GRAPH ?g { ?s ?x ?y } } }"))
        // A graph name taken from the default graph still finds no named graph.
        assertEquals(emptyList<String?>(), values("SELECT ?o { ?s <urn:q> ?g GRAPH ?g { ?s ?p ?o } }"))
        // Nested GRAPH patterns and sub-selects inside GRAPH.
        assertEquals(emptyList<String?>(), values("SELECT ?o { GRAPH ?a { GRAPH ?b { ?s ?p ?o } } }"))
        assertEquals(emptyList<String?>(), values("SELECT ?o { GRAPH ?g { SELECT ?o { ?s ?p ?o } } }"))
        assertEquals(listOf("default"), values("SELECT ?o { ?s <urn:p> ?o OPTIONAL { GRAPH ?g { SELECT ?y { ?s ?x ?y } } } }"))
        // GRAPH in strings, IRIs and comments is left alone.
        assertEquals(
            listOf("default"),
            values("# GRAPH ?g { }\nSELECT ?o { ?s <urn:p> ?o FILTER(?o != \"GRAPH ?g {\") OPTIONAL { GRAPH ?g { ?s <urn:GRAPH> ?z } } }"),
        )
    }

    @Test
    fun `the named-graph rewrite keeps the GRAPH variable in scope and leaves the rest of the query unchanged`() {
        val rewritten = SparqlDatasetClauses.withoutNamedGraphs("SELECT * { ?s ?p ?o OPTIONAL { GRAPH ?g { ?s ?p ?x } } }")!!
        assertFalse(SparqlDatasetClauses.usesGraphPattern(rewritten), rewritten)
        assertTrue(rewritten.contains("VALUES (?g ?s ?p ?x) { }"), rewritten)
        assertTrue(rewritten.startsWith("SELECT * { ?s ?p ?o OPTIONAL { "), rewritten)
        assertEquals(
            listOf("default"),
            values("SELECT * { ?s <urn:p> ?o OPTIONAL { GRAPH ?g { ?s ?p ?x } } }"),
        )
        // Unanalysable GRAPH usage is rejected rather than run against the store's named graphs.
        assertNull(SparqlDatasetClauses.withoutNamedGraphs("SELECT ?o { GRAPH { ?s ?p ?o } }"))
        assertNull(SparqlDatasetClauses.withoutNamedGraphs("SELECT ?o { GRAPH ?g { ?s ?p ?o"))
        assertThrows(IllegalArgumentException::class.java) { values("SELECT ?o { GRAPH { ?s ?p ?o } }") }
    }
}
