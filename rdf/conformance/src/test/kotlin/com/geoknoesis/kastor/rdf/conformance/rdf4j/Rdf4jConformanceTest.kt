package com.geoknoesis.kastor.rdf.conformance.rdf4j

import com.geoknoesis.kastor.rdf.RdfFormatException
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
 * RDF4J 5.3.1 predates much of the RDF 1.2 syntax (the `VERSION` directive, the `~` reifier
 * shorthand, `<<( s p o )>>` triple-term syntax, annotations). Tests that fail for that reason are
 * listed **by IRI with a reason** in `conformance-allowlist.tsv`, and are only skipped when the failure
 * really is a Rio parse error ([rdf4jUpstreamFailure]). Anything else — an unlisted test, a
 * non-parser exception, or a listed test that starts passing — fails.
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
        allowlist = ConformanceAllowlist.forProvider("RDF4J"),
        expectedFailure = ::rdf4jUpstreamFailure,
    )

    @TestFactory
    fun `RDF 1 dot 2 syntax suite (RDF4J)`(): List<DynamicNode> =
        Rdf12ConformanceRunner.forRoot(conformer, TestData.rootDir)

    companion object {
        /**
         * True only for the documented upstream failure modes of an allowlisted test: a Rio
         * `RDFParseException` wrapped as [RdfFormatException], or RDF4J producing a triple term in
         * subject position (which Kastor's RDF 1.2 model rejects).
         */
        fun rdf4jUpstreamFailure(ex: Throwable): Boolean {
            val chain = generateSequence(ex) { it.cause }.toList()
            // Rio is an implementation dependency of :rdf:rdf4j, so match the class by name.
            if (ex is RdfFormatException && chain.any { it.javaClass.name == "org.eclipse.rdf4j.rio.RDFParseException" }) return true
            if (chain.any { "Unsupported RDF4J Resource type for RDF 1.2" in (it.message ?: "") }) return true
            // Rio 5.3.1 crashes (NullPointerException inside RDF4J, e.g. Values.triple) on RDF 1.2 annotation syntax.
            return chain.any { cause ->
                cause is NullPointerException && cause.stackTrace.firstOrNull { !it.className.startsWith("java.") }
                    ?.className?.startsWith("org.eclipse.rdf4j.") == true
            }
        }
    }
}
