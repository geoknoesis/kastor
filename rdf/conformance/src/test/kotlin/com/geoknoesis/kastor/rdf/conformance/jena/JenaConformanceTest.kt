package com.geoknoesis.kastor.rdf.conformance.jena

import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.conformance.ConformanceAllowlist
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
 * The test factory walks `test-data/rdf12/` manifests, turning each test row into a dynamic test.
 * When the W3C data has not been initialised, the factory yields a single skipped test.
 * Known deviations must be listed by IRI with a reason in `conformance-allowlist.tsv`.
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
        allowlist = ConformanceAllowlist.forProvider("Jena"),
        expectedFailure = { it is RdfFormatException },
    )

    @TestFactory
    fun `RDF 1 dot 2 syntax suite (Jena)`(): List<DynamicNode> =
        Rdf12ConformanceRunner.forRoot(conformer, TestData.rootDir)
}
