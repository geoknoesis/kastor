package com.geoknoesis.kastor.rdf.conformance.rdf4j

import com.geoknoesis.kastor.rdf.conformance.ConformanceAllowlist
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
 * RDF4J 5.3.2 predates much of the RDF 1.2 syntax (the `VERSION` directive, the `~` reifier
 * shorthand, `<<( s p o )>>` triple-term syntax, annotations). Tests that fail for that reason are
 * listed **by IRI with a reason and the expected failure signature** in `conformance-allowlist.tsv`,
 * and are only skipped when the observed failure matches that signature. Anything else (an unlisted
 * test, a failure of a different kind, or a listed test that starts passing) fails.
 *
 * @see com.geoknoesis.kastor.rdf.conformance.jena.JenaConformanceTest
 *
 * Tagged **`w3c-rdf12-full`** — excluded from the **`conformanceSmokeTest`** task; requires W3C submodule.
 */
@Tag("w3c-rdf12-full")
class Rdf4jConformanceTest {

    private val conformer = Conformer(
        label = "RDF4J",
        provider = Rdf4jProvider(),
        newDatasetRepo = { Rdf4jRepository.MemoryRepository() },
        allowlist = ConformanceAllowlist.forProvider("RDF4J"),
    )

    @TestFactory
    fun `RDF 1 dot 2 syntax suite (RDF4J)`(): List<DynamicNode> =
        Rdf12ConformanceRunner.forRoot(conformer, TestData.rootDir)
}
