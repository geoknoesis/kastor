package com.geoknoesis.kastor.rdf.conformance.rdf4j

import com.geoknoesis.kastor.rdf.conformance.Conformer
import com.geoknoesis.kastor.rdf.conformance.Rdf12ConformanceRunner
import com.geoknoesis.kastor.rdf.conformance.TestData
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory

/**
 * Runs the W3C RDF 1.2 syntax test suites against Kastor's RDF4J provider.
 *
 * RDF4J 5.1.x predates the spec rename to `createTripleTerm`; the provider
 * bridge falls back to the legacy `createTriple` so triple terms still
 * round-trip. Tests that strictly require the renamed API will surface as
 * failures here, which is desired (the report tells us what to fix when we
 * eventually bump RDF4J).
 *
 * @see com.geoknoesis.kastor.rdf.conformance.jena.JenaConformanceTest
 *
 * Tagged **`w3c-rdf12-full`** — excluded from the **`conformanceSmokeTest`** task; requires W3C submodule.
 */
@Tag("w3c-rdf12-full")
class Rdf4jConformanceTest {

    private val provider = Rdf4jProvider()
    private val conformer = Conformer(
        label = "RDF4J",
        provider = provider,
        newDatasetRepo = { Rdf4jRepository.MemoryRepository() },
    )

    @TestFactory
    fun `RDF 1 dot 2 syntax suite (RDF4J)`(): List<DynamicNode> =
        Rdf12ConformanceRunner.forRoot(conformer, TestData.rootDir)
}
