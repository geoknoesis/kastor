package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

/** A remote endpoint evaluates queries itself, so a sandboxed query is refused and never sent. */
class SparqlEndpointSandboxTest {

    @Test
    fun `sandboxed queries are refused without a request`() {
        LocalEndpoint { _, _ -> }.use { endpoint ->
            SparqlRepository(SparqlEndpointConfig(endpoint.url)).use { repo ->
                val query = SandboxedSelectQuery("SELECT * WHERE { ?s ?p ?o }")
                assertThrows(UnsupportedOperationException::class.java) { repo.withSelectRows(query) { it.toList() } }
                assertThrows(UnsupportedOperationException::class.java) {
                    repo.withSelectRows(query, emptyMap(), Duration.ofSeconds(5)) { it.toList() }
                }
            }
            assertEquals(0, endpoint.requests.size)
        }
    }
}
