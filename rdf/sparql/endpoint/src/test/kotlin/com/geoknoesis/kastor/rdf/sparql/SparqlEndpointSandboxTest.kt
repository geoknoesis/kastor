package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

/** A remote endpoint evaluates queries itself, so a sandboxed query is refused on every entry point and never sent. */
class SparqlEndpointSandboxTest {

    private val federated = SandboxedSelectQuery("SELECT * WHERE { SERVICE <http://169.254.169.254/> { ?s ?p ?o } }")

    private fun assertRefusedWithoutRequest(call: (SparqlRepository) -> Unit) {
        LocalEndpoint { exchange, _ -> exchange.respond(200, rowsJson(0)) }.use { endpoint ->
            SparqlRepository(SparqlEndpointConfig(endpoint.url)).use { repo ->
                assertThrows(UnsupportedOperationException::class.java) { call(repo) }
            }
            assertEquals(0, endpoint.requests.size, "a sandboxed query reached the endpoint")
        }
    }

    @Test
    fun `select refuses a sandboxed query without a request`() = assertRefusedWithoutRequest { it.select(federated) }

    @Test
    fun `withSelectRows refuses a sandboxed query without a request`() =
        assertRefusedWithoutRequest { repo -> repo.withSelectRows(federated) { it.toList() } }

    @Test
    fun `bound and timed withSelectRows refuses a sandboxed query without a request`() =
        assertRefusedWithoutRequest { repo -> repo.withSelectRows(federated, emptyMap(), Duration.ofSeconds(5)) { it.toList() } }

    @Test
    fun `an ordinary select is still sent`() {
        LocalEndpoint { exchange, _ -> exchange.respond(200, rowsJson(1)) }.use { endpoint ->
            SparqlRepository(SparqlEndpointConfig(endpoint.url)).use { repo ->
                assertEquals(1, repo.select(SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }")).count())
            }
            assertEquals(1, endpoint.requests.size)
        }
    }
}
