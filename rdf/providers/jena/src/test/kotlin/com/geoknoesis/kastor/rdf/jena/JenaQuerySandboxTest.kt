package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * A [SandboxedSelectQuery] is checked on Jena's own algebra before evaluation: SERVICE and `java:` functions or
 * property functions are refused with [IllegalArgumentException] wherever they occur, so no request is sent and no
 * class is loaded. The unreachable endpoint 127.0.0.1:1 would fail differently if a query were evaluated.
 */
class JenaQuerySandboxTest {

    private val repository: RdfRepository = JenaProvider().createRepository("memory", RdfConfig()).apply {
        editDefaultGraph().addTriples(Rdf.parse("<urn:a> <urn:p> 1 .", RdfFormat.TURTLE).getTriples())
    }

    @AfterEach fun close() = repository.close()

    private fun bound(sparql: String) =
        repository.withSelectRows(SandboxedSelectQuery(sparql), emptyMap(), Duration.ofSeconds(10)) { it.toList() }

    private fun plain(sparql: String) = repository.withSelectRows(SandboxedSelectQuery(sparql)) { it.toList() }

    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT * WHERE { SERVICE <http://127.0.0.1:1/s> { ?s ?p ?o } }",
        "SELECT * WHERE { SERVICE SILENT <http://127.0.0.1:1/s> { ?s ?p ?o } }",
        "SELECT * WHERE { ?s ?p ?o OPTIONAL { SERVICE <http://127.0.0.1:1/s> { ?s ?q ?r } } }",
        "SELECT * WHERE { ?s ?p ?o FILTER EXISTS { SERVICE <http://127.0.0.1:1/s> { ?s ?q ?r } } }",
        "SELECT * WHERE { ?s ?p ?o BIND (NOT EXISTS { SERVICE <http://127.0.0.1:1/s> { ?s ?q ?r } } AS ?x) }",
        "SELECT * WHERE { { SELECT ?s WHERE { SERVICE <http://127.0.0.1:1/s> { ?s ?p ?o } } } }",
        "SELECT * WHERE { BIND (<http://127.0.0.1:1/s> AS ?e) SERVICE ?e { ?s ?p ?o } }",
        "SELECT * WHERE { ?s ?p ?o FILTER (<java:org.example.F>(?o)) }",
        "SELECT * WHERE { ?s ?p ?o BIND (<java:org.example.F>(?o) AS ?x) }",
        "SELECT (<java:org.example.F>(?o) AS ?x) WHERE { ?s ?p ?o }",
        "SELECT * WHERE { ?s ?p ?o } ORDER BY <java:org.example.F>(?o)",
        "SELECT (SUM(<java:org.example.F>(?o)) AS ?x) WHERE { ?s ?p ?o }",
        "SELECT ?s WHERE { ?s ?p ?o } GROUP BY ?s HAVING (<java:org.example.F>(?s))",
        "SELECT * WHERE { ?s <java:org.example.P> ?o }",
        "SELECT ?g WHERE { ?s ?p ?o } GROUP BY (<java:org.example.F>(?s) AS ?g)",
        "SELECT (COUNT(DISTINCT <java:org.example.F>(?o)) AS ?x) WHERE { ?s ?p ?o }",
        "SELECT * WHERE { ?s ?p ?o OPTIONAL { ?s ?q ?r FILTER (<java:org.example.F>(?r)) } }",
        "SELECT * WHERE { { SELECT ?s WHERE { ?s ?p ?o } ORDER BY <java:org.example.F>(?o) LIMIT 1 } }",
    ])
    fun `service and java code are refused before evaluation`(sparql: String) {
        assertThrows(IllegalArgumentException::class.java) { bound(sparql) }
        assertThrows(IllegalArgumentException::class.java) { plain(sparql) }
    }

    @Test
    fun `a function IRI computed at run time cannot load a java class`() {
        val probe = "java:" + SandboxProbeFunction::class.java.name
        val (head, tail) = probe.substring(0, 5) to probe.substring(5)
        val sparql = "SELECT ?x WHERE { <urn:a> <urn:p> ?o BIND (CALL(IRI(CONCAT(\"$head\", \"$tail\")), ?o) AS ?x) }"
        val before = SandboxProbeFunction.instances.get()
        // Not refused statically (no constant java: IRI), but the function is not found, so ?x stays unbound.
        assertEquals(listOf(null), bound(sparql).map { it.get("x") })
        assertEquals(listOf(null), plain(sparql).map { it.get("x") })
        assertEquals(before, SandboxProbeFunction.instances.get())
        // The same query outside the sandbox does load the class: the probe would detect a regression.
        repository.withSelectRows(SparqlSelectQuery(sparql)) { it.toList() }
        assertTrue(SandboxProbeFunction.instances.get() > before)
    }

    @Test
    fun `an ordinary sandboxed query is evaluated`() {
        assertEquals(1, bound("SELECT ?o WHERE { <urn:a> <urn:p> ?o }").size)
        // A java: IRI as data, rather than as a function or property function, is harmless.
        assertEquals(1, plain("SELECT ?o WHERE { <urn:a> <urn:p> ?o BIND (<java:org.example.F> AS ?iri) }").size)
    }
}
