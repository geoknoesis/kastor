package com.geoknoesis.kastor.rdf.conformance

import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import java.nio.file.Files
import java.nio.file.Path

/**
 * Serializer rows, driven by the same W3C manifests as [Rdf12ConformanceRunner]: for every eval test, the provider's
 * parse of the input is written back by the provider in the same format, the text is re-read by the independent
 * reference parser ([ReferenceRdf]), and the result must be isomorphic to the test's expected result (one blank-node
 * bijection across all graphs).
 *
 * - A test the parser allowlist lists for the provider has no serializer row: its parse is known to be wrong. A test
 *   whose parse is wrong although it is not listed fails here as in its parser row.
 * - A serialization that does not re-read, or re-reads as other data, fails, unless the test is listed for the
 *   provider in `serializer-roundtrip-allowlist.tsv` (`provider<TAB>test IRI<TAB>reason`). A listed row is an
 *   expected failure, named "known loss" in its display name: it passes while the loss persists and fails once the
 *   test round-trips, so the entry gets removed. (No row is skipped: skips would need entries in the CI skip
 *   allowlist as well.)
 */
object SerializerRoundTrip {

    /**
     * The serializer rows of every eval test under `[rootDir]/rdf12/manifest.ttl`. Empty when the corpus is missing:
     * the parser rows ([Rdf12ConformanceRunner.forRoot]) already fail for that.
     */
    fun forRoot(conformer: Conformer, rootDir: Path): List<DynamicNode> {
        val manifest = rootDir.resolve("rdf12").resolve("manifest.ttl")
        if (!Files.isRegularFile(manifest)) return emptyList()
        return listOf(forCases(conformer, "rdf12 serializer round trip", Rdf12ManifestParser.parse(manifest)))
    }

    fun forManifest(conformer: Conformer, displayName: String, manifest: Path): DynamicContainer =
        forCases(conformer, displayName, Rdf12ManifestParser.parse(manifest))

    private fun forCases(conformer: Conformer, displayName: String, cases: List<W3cTestCase>): DynamicContainer {
        val evals = cases.filter {
            it.kind == TestKind.EVAL && it.approved && conformer.allowlist[ConformanceAllowlist.testKey(it.iri)] == null
        }
        check(evals.isNotEmpty()) { "no eval tests for the serializer rows in $displayName" }
        return DynamicContainer.dynamicContainer("$displayName (${evals.size} tests)", evals.map { row(conformer, it) })
    }

    private fun row(conformer: Conformer, case: W3cTestCase): DynamicTest {
        val listed = conformer.roundTripAllowlist[ConformanceAllowlist.testKey(case.iri)]
        val name = "[${conformer.label}/${case.format.name} round trip] ${case.name}" + if (listed != null) " (known loss)" else ""
        return dynamicTest(name) {
            val expectedPath = case.result ?: error("eval test missing mf:result: ${case.iri}")
            val expected = ReferenceRdf.parse(expectedPath, Rdf12ConformanceRunner.expectedFormat(case, expectedPath), case.assumedBaseIri)
            val parsed = Rdf12ConformanceRunner.parseAction(conformer, case)
            if (!ReferenceRdf.isomorphic(expected, ReferenceRdf.dataset(parsed))) {
                throw AssertionError("FAILED ${case.iri}: the ${conformer.label} parse is wrong (see its parser row); nothing to write back")
            }
            val outcome = roundTrip(conformer, case, parsed, expected)
            when {
                outcome == null && listed != null -> throw AssertionError(
                    "ALLOWLIST-STALE ${case.iri}: now round-trips for ${conformer.label}; remove it from " +
                        "serializer-roundtrip-allowlist.tsv (reason was: $listed)",
                )
                outcome != null && listed == null -> throw AssertionError("FAILED ${case.iri}: the ${conformer.label} serialization $outcome")
                // Otherwise it round-trips, or it is a listed known loss that still fails as listed.
            }
        }
    }

    /** What went wrong writing [parsed] back and re-reading it, or `null` when it re-reads as [expected]. */
    private fun roundTrip(conformer: Conformer, case: W3cTestCase, parsed: List<KastorQuad>, expected: org.apache.jena.sparql.core.DatasetGraph): String? =
        runCatching {
            val text = conformer.serialize(parsed, case.format.parserKey, Rdf12ConformanceRunner.isDataset(case.format))
            val reread = ReferenceRdf.unskolemized(ReferenceRdf.parseText(text, case.format))
            if (ReferenceRdf.isomorphic(expected, reread)) {
                null
            } else {
                "re-reads as other data (expected ${ReferenceRdf.size(expected)} quads, got ${ReferenceRdf.size(reread)}):\n" +
                    "  written:\n${text.lines().take(20).joinToString("\n") { "    $it" }}\n  re-read quads:\n${ReferenceRdf.preview(reread)}"
            }
        }.getOrElse { "failed: ${it.javaClass.name}: ${it.message}" }

    /** The committed round-trip allowlist of [label] (`provider<TAB>test IRI<TAB>reason`, `#` comments). */
    fun allowlist(label: String): Map<String, String> {
        val stream = SerializerRoundTrip::class.java.classLoader.getResourceAsStream("serializer-roundtrip-allowlist.tsv")
            ?: return emptyMap()
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
                .map { it.split('\t') }
                .onEach { require(it.size == 3 && it[2].isNotBlank()) { "round-trip allowlist rows need provider, test IRI and reason: $it" } }
                .filter { it[0] == label }
                .associate { ConformanceAllowlist.testKey(it[1]) to it[2] }
        }
    }
}
