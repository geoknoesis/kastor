package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/**
 * A [SandboxedSelectQuery] is parsed by RDF4J's own SPARQL parser before it is prepared, and refused with
 * [IllegalArgumentException] when it contains SERVICE anywhere, so no request is sent. The unreachable endpoint
 * 127.0.0.1:1 would fail differently if a query were evaluated.
 */
class Rdf4jQuerySandboxTest {

    private val repository: RdfRepository = Rdf4jProvider().createRepository("memory", RdfConfig()).apply {
        editDefaultGraph().addTriples(Rdf.parse("<urn:a> <urn:p> 1 .", RdfFormat.TURTLE).getTriples())
    }

    @AfterEach fun close() = repository.close()

    private fun bound(sparql: String) =
        repository.withSelectRows(SandboxedSelectQuery(sparql), emptyMap(), Duration.ofSeconds(10)) { it.toList() }

    private fun plain(sparql: String) = repository.withSelectRows(SandboxedSelectQuery(sparql)) { it.toList() }

    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT * WHERE { SERVICE <http://127.0.0.1:1/s> { ?a ?p ?o } }",
        "SELECT * WHERE { SERVICE SILENT <http://127.0.0.1:1/s> { ?a ?p ?o } }",
        "SELECT * WHERE { ?a ?p ?o OPTIONAL { SERVICE <http://127.0.0.1:1/s> { ?a ?q ?r } } }",
        "SELECT * WHERE { ?a ?p ?o FILTER EXISTS { SERVICE <http://127.0.0.1:1/s> { ?a ?q ?r } } }",
        "SELECT * WHERE { ?a ?p ?o BIND (NOT EXISTS { SERVICE <http://127.0.0.1:1/s> { ?a ?q ?r } } AS ?x) }",
        "SELECT * WHERE { { SELECT ?a WHERE { SERVICE <http://127.0.0.1:1/s> { ?a ?p ?o } } } }",
        // Escapes are decoded by RDF4J's parser itself, so the check sees what is prepared.
        "SELECT * WHERE { # x\\u000A SERVICE <http://127.0.0.1:1/s> { ?a ?p ?o } }",
    ])
    fun `service is refused before evaluation`(sparql: String) {
        assertThrows(IllegalArgumentException::class.java) { bound(sparql) }
        assertThrows(IllegalArgumentException::class.java) { plain(sparql) }
    }

    /**
     * RDF4J resolves a call of an IRI that its global `CustomAggregateFunctionRegistry` holds as an aggregate (filled
     * by `ServiceLoader`; RDF4J registers its own `stdev` and `variance` there) and runs whatever was registered. As on
     * Jena, a sandboxed query may use no custom aggregate: it is refused before evaluation, wherever it occurs.
     */
    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT (<http://rdf4j.org/aggregate#stdev>(?o) AS ?x) WHERE { ?a ?p ?o }",
        "SELECT ?a WHERE { ?a ?p ?o } GROUP BY ?a HAVING (<http://rdf4j.org/aggregate#stdev>(?o) >= 0)",
        "SELECT * WHERE { { SELECT (<http://rdf4j.org/aggregate#variance>(?o) AS ?x) WHERE { ?a ?p ?o } } }",
        "SELECT ?a WHERE { ?a ?p ?o FILTER EXISTS { SELECT (<http://rdf4j.org/aggregate#stdev>(?q) AS ?x) WHERE { ?b ?r ?q } } }",
    ])
    fun `custom aggregates are refused before evaluation`(sparql: String) {
        assertThrows(IllegalArgumentException::class.java) { bound(sparql) }
        assertThrows(IllegalArgumentException::class.java) { plain(sparql) }
        // Outside the sandbox the aggregate runs: the check is what refuses it.
        repository.withSelectRows(com.geoknoesis.kastor.rdf.SparqlSelectQuery(sparql)) { it.toList() }
    }

    @Test
    fun `an ordinary sandboxed query is evaluated`() {
        assertEquals(1, bound("SELECT ?o WHERE { <urn:a> <urn:p> ?o }").size)
        assertEquals(1, plain("SELECT ?o WHERE { <urn:a> <urn:p> ?o }").size)
    }
}
