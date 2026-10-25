package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.EmptySparqlQueryResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A [SandboxedSelectQuery] keeps its sandbox and its function library on every path of a [Dataset]: a rewrite with
 * dataset clauses, a rewrite of `GRAPH` patterns, an unchanged query and the materialized copy. Losing the type would
 * hand untrusted query text (with `SERVICE`) to the provider as a trusted query.
 */
class DatasetSandboxedQueryTest {

    private val g1 = Iri("http://example.org/g1")
    private val n1 = Iri("http://example.org/n1")
    private val library = SparqlFunctionLibrary.of(listOf(SparqlFunction("urn:f", emptyList()) { null }))

    private fun assertSandboxed(sent: SparqlSelect?, expectedText: (String) -> Boolean) {
        val query = sent as? SandboxedSelectQuery ?: error("the query lost its sandbox: $sent")
        assertSame(library, query.functions, "the function library must travel with the query")
        assertTrue(expectedText(query.sparql), query.sparql)
    }

    @Test
    fun `a rewrite with dataset clauses keeps the sandbox and the functions`() {
        val repo = CapturingRepository()
        val dataset = Dataset {
            defaultGraph(repo.getGraph(g1).asGraphRef(repo, g1))
            namedGraph(n1, repo, n1)
        }
        dataset.select(SandboxedSelectQuery("SELECT * WHERE { ?s ?p ?o }", library))
        assertSandboxed(repo.lastSelect) { it.contains("FROM <${g1.value}>") && it.contains("FROM NAMED <${n1.value}>") }
    }

    @Test
    fun `a GRAPH rewrite on the store's default graph keeps the sandbox and the functions`() {
        val repo = CapturingRepository()
        val dataset = Dataset { defaultGraph(repo) }
        dataset.select(SandboxedSelectQuery("SELECT * WHERE { GRAPH ?g { ?s ?p ?o } }", library))
        assertSandboxed(repo.lastSelect) { !it.contains("GRAPH ?g {") }
    }

    @Test
    fun `an unchanged query is passed on as it is`() {
        val repo = CapturingRepository()
        val query = SandboxedSelectQuery("SELECT * WHERE { ?s ?p ?o }", library)
        Dataset { defaultGraph(repo) }.select(query)
        assertSame(query, repo.lastSelect)
    }

    @Test
    fun `the materialized path passes the sandboxed query to the temporary repository`() {
        val first = Rdf.memory()
        val second = Rdf.memory()
        val target = CapturingRepository()
        val dataset = (Dataset {
            defaultGraph(first)
            namedGraph(n1, second, n1)
        } as DatasetImpl).apply { materializationRepositoryFactory = { target } }
        val query = SandboxedSelectQuery("SELECT * WHERE { ?s ?p ?o }", library)
        dataset.select(query).toList()
        assertSame(query, target.lastSelect)
    }

    @Test
    fun `retargeting keeps every query type`() {
        val sandboxed = SandboxedSelectQuery("SELECT * {}", library)
        val moved = SparqlQueries.retarget(sandboxed, "SELECT * FROM <urn:g> {}")
        assertEquals(SandboxedSelectQuery("SELECT * FROM <urn:g> {}", library), moved)
        assertEquals(SparqlSelectQuery("SELECT 1 {}"), SparqlQueries.retarget(SparqlSelectQuery("x"), "SELECT 1 {}"))
        assertSame(sandboxed, SparqlQueries.retarget(sandboxed, sandboxed.sparql))
    }

    private class CapturingRepository(
        private val delegate: RdfRepository = Rdf.memory()
    ) : RdfRepository by delegate {
        var lastSelect: SparqlSelect? = null

        override fun select(query: SparqlSelect): SparqlQueryResult {
            lastSelect = query
            return EmptySparqlQueryResult
        }
    }
}
