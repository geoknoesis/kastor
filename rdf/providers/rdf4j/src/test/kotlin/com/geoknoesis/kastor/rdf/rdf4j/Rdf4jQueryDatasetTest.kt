package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.SparqlAskQuery
import com.geoknoesis.kastor.rdf.SparqlConstructQuery
import com.geoknoesis.kastor.rdf.SparqlDescribeQuery
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The dataset Kastor gives RDF4J queries: the default graph is the statements without a context (RDF4J's own default
 * is the union of all contexts), named graphs are the contexts. Covers what the cross-provider tests of
 * `:rdf:testkit` cannot: blank-node contexts and a wrapped, externally created store.
 */
class Rdf4jQueryDatasetTest {
    private val vf = SimpleValueFactory.getInstance()
    private val store = SailRepository(MemoryStore()).apply {
        init()
        connection.use { conn ->
            val s = vf.createIRI("urn:s")
            val p = vf.createIRI("urn:p")
            conn.add(s, p, vf.createLiteral("default"))
            conn.add(s, p, vf.createLiteral("named"), vf.createIRI("urn:g"))
            conn.add(s, p, vf.createLiteral("blank"), vf.createBNode("ctx"))
        }
    }
    private val repo = Rdf4jRepository(store)

    @AfterEach
    fun close() {
        repo.close()
    }

    private fun select(query: String): List<String> =
        repo.select(SparqlSelectQuery(query)).mapNotNull { (it.get("o") as? Literal)?.lexical }.sorted()

    @Test
    fun `patterns outside GRAPH read only statements without a context`() {
        assertEquals(listOf("default"), select("SELECT ?o { ?s ?p ?o }"))
        assertEquals(listOf("default"), select("SELECT ?o { <urn:s> <urn:p>* ?o FILTER(isLiteral(?o)) }"))
        assertEquals(listOf("default"), select("SELECT ?o { ?s ?p ?o FILTER NOT EXISTS { ?s ?p \"missing\" } }"))
        assertEquals(listOf("default"), select("SELECT ?o { { SELECT ?o { ?s ?p ?o } } }"))
        assertFalse(repo.ask(SparqlAskQuery("ASK { ?s ?p \"named\" }")))
        assertFalse(repo.ask(SparqlAskQuery("ASK { ?s ?p \"blank\" }")))
        assertEquals(1, repo.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")).count())
        assertEquals(1, repo.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).count())
        assertEquals(1, repo.describe(SparqlDescribeQuery("DESCRIBE ?s { GRAPH ?g { ?s ?p ?o } }")).count())
    }

    @Test
    fun `a query that only reads inside GRAPH sees every context, including blank-node contexts`() {
        assertEquals(listOf("blank", "named"), select("SELECT ?o { GRAPH ?g { ?s ?p ?o } }"))
        assertEquals(listOf("named"), select("SELECT ?o { GRAPH <urn:g> { ?s ?p ?o } }"))
        assertTrue(repo.ask(SparqlAskQuery("ASK { GRAPH ?g { ?s ?p \"blank\" } }")))
        assertFalse(repo.ask(SparqlAskQuery("ASK { GRAPH ?g { ?s ?p \"default\" } }")))
    }

    @Test
    fun `a query that reads inside and outside GRAPH sees the default graph and the IRI-named graphs`() {
        assertEquals(listOf("named"), select("SELECT ?o { ?s ?p \"default\" GRAPH ?g { ?s ?p ?o } }"))
        assertEquals(listOf("default", "named"), select("SELECT ?o { { ?s ?p ?o } UNION { GRAPH ?g { ?s ?p ?o } } }"))
        assertEquals(listOf("default"), select("SELECT ?o { ?s ?p ?o FILTER NOT EXISTS { GRAPH ?g { ?s ?p ?o } } }"))
        assertEquals(listOf("default"), select("SELECT ?o { ?s ?p ?o OPTIONAL { GRAPH <urn:absent> { ?s ?p ?x } } }"))
        assertEquals(listOf("urn:g"), repo.listGraphs().map { it.value })
    }

    @Test
    fun `a query's own dataset clauses are kept`() {
        assertEquals(listOf("named"), select("SELECT ?o FROM <urn:g> { ?s ?p ?o }"))
        assertEquals(listOf("named"), select("SELECT ?o FROM NAMED <urn:g> { GRAPH ?g { ?s ?p ?o } }"))
        assertEquals(emptyList<String>(), select("SELECT ?o FROM NAMED <urn:g> { ?s ?p ?o }"))
        // Codepoint escapes cannot hide a GRAPH pattern from the analysis (it reads RDF4J's parsed query).
        assertEquals(listOf("named"), select("SELECT ?o { ?s ?p \"default\" \\u0047RAPH <urn:g> { ?s ?p ?o } }"))
    }
}
