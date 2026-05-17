package com.geoknoesis.kastor.rdf.conformance.jena

import com.geoknoesis.kastor.rdf.conformance.Conformer
import com.geoknoesis.kastor.rdf.conformance.Rdf12ConformanceRunner
import com.geoknoesis.kastor.rdf.conformance.TestData
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory

/**
 * Runs the W3C RDF 1.2 syntax test suites against Kastor's Jena provider.
 *
 * The test factory walks `test-data/rdf12/` for `manifest.ttl` files, turning
 * each manifest into a [org.junit.jupiter.api.DynamicContainer] of dynamic
 * tests. When the submodule has not been initialised, the factory yields a
 * single skipped test with a friendly message, so the build stays green on
 * fresh clones that did not pass `--recursive`.
 *
 * The system property `conformance.includeUnapproved=true` opts in to running
 * tests whose `rdft:approval` is not `rdft:Approved`.
 *
 * Tagged **`w3c-rdf12-full`** — excluded from the **`conformanceSmokeTest`** task; requires W3C submodule.
 */
@Tag("w3c-rdf12-full")
class JenaConformanceTest {

    private val provider = JenaProvider()
    private val conformer = Conformer(
        label = "Jena",
        provider = provider,
        newDatasetRepo = { JenaRepository.MemoryRepository() },
    )

    @TestFactory
    fun `RDF 1 dot 2 syntax suite (Jena)`(): List<DynamicNode> =
        Rdf12ConformanceRunner.forRoot(conformer, TestData.rootDir)
}
