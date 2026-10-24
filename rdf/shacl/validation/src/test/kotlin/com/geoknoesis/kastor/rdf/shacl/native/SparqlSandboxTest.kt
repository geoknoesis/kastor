package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/**
 * SHACL-SPARQL queries reach the provider as sandboxed queries, so SERVICE is refused by the provider's own parser
 * even when a query got past the engine's lexical restriction check (which this test bypasses by calling the session
 * directly). The unreachable endpoint 127.0.0.1:1 would fail differently if the query were evaluated.
 */
class SparqlSandboxTest {

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `the provider refuses SERVICE in a SHACL query`(provider: String) {
        val data = Rdf.parse("<urn:a> <urn:p> 1 .", RdfFormat.TURTLE)
        val factory = {
            if (provider == "jena") JenaProvider().createRepository("memory", RdfConfig())
            else Rdf4jProvider().createRepository("memory", RdfConfig())
        }
        SparqlConstraintEvaluator.Session(data, repositoryFactory = factory).use { session ->
            val error = assertThrows(ShaclValidationException::class.java) {
                session.select("SELECT * WHERE { SERVICE <http://127.0.0.1:1/s> { ?s ?p ?o } }", emptyMap(), Duration.ofSeconds(10)) { it.toList() }
            }
            val causes = generateSequence<Throwable>(error) { it.cause }.toList()
            assertTrue(causes.any { it is IllegalArgumentException && "sandboxed" in it.message.orEmpty() }, causes.toString())
        }
    }
}
