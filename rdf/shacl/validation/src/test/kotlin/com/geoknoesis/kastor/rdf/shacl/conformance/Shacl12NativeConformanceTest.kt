package com.geoknoesis.kastor.rdf.shacl.conformance

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * W3C SHACL 1.2 manifest tests against the **native** validator ([NativeShaclValidatorProvider]).
 *
 * Test discovery:
 * 1. System property `shacl.w3c.manifest` — absolute path to a top manifest Turtle file (optional).
 * 2. Otherwise `test-data/w3c-shacl12/tests/manifest.ttl` (core + node-expr + sparql) relative to the
 *    `:rdf:shacl-validation` project dir (full upstream checkout — see `test-data/README.md`).
 * 3. Otherwise a small bundled fixture under `src/test/resources/w3c-shacl12-fixture/` so `./gradlew test`
 *    stays meaningful without cloning the suite.
 *
 * Non-approved manifest rows are skipped unless `-Dshacl.w3c.includeNonApproved=true`. Known deviations are
 * skipped only through the explicit, justified list in [W3cKnownDeviations].
 */
class Shacl12NativeConformanceTest {

    @TestFactory
    fun `SHACL 1 2 core W3C manifests native`(): Stream<DynamicNode> {
        val manifest = manifestRoot()
        if (!Files.isRegularFile(manifest)) {
            return Stream.of(
                DynamicTest.dynamicTest("manifest missing") {
                    Assumptions.assumeTrue(false, "SHACL W3C manifest not found at $manifest")
                },
            )
        }

        val cases = Shacl12ManifestParser.collect(manifest)
        if (cases.isEmpty()) {
            return Stream.of(
                DynamicTest.dynamicTest("no sht Validate entries") {
                    Assumptions.assumeTrue(false, "no sht:Validate tests under $manifest")
                },
            )
        }

        val includeNonApproved = System.getProperty("shacl.w3c.includeNonApproved") == "true"

        val containers: List<DynamicNode> =
            cases
                .groupBy { it.manifestPath }
                .entries
                .sortedBy { it.key.toString() }
                .map { (path, pathCases) ->
                    DynamicContainer.dynamicContainer(
                        "${path.parent?.fileName}/${path.fileName} (${pathCases.size})",
                        pathCases
                            .sortedBy { it.displayName }
                            .map { case ->
                                DynamicTest.dynamicTest(case.displayName) {
                                    if (!case.approved && !includeNonApproved) {
                                        Assumptions.assumeTrue(false, "not approved: ${case.entryUri}")
                                    }
                                    W3cKnownDeviations.reasonFor(case.manifestPath)?.let { reason ->
                                        Assumptions.assumeTrue(false, "known deviation: $reason")
                                    }
                                    Shacl12W3cCaseRunner.run(case)
                                }
                            },
                    )
                }
        return containers.stream()
    }

    private fun manifestRoot(): Path {
        System.getProperty("shacl.w3c.manifest")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val p = Path.of(it).toAbsolutePath().normalize()
            if (Files.isRegularFile(p)) return p
        }

        val checkout =
            Path.of("test-data/w3c-shacl12/tests/manifest.ttl").toAbsolutePath().normalize()
        if (Files.isRegularFile(checkout)) return checkout

        val resource = Shacl12NativeConformanceTest::class.java.getResource("/w3c-shacl12-fixture/manifest.ttl")
            ?: error("bundled W3C fixture missing from test classpath")
        return Path.of(resource.toURI()).toAbsolutePath().normalize()
    }
}

/**
 * Explicit, reviewed list of W3C SHACL 1.2 cases the native engine does not (yet) pass. Every entry is matched
 * against the end of the path of the manifest file containing the case (`.../dir/file.ttl`) and must carry a
 * justification. Nothing else may be skipped silently.
 */
internal object W3cKnownDeviations {
    private const val SPARQL_COMPONENTS =
        "SHACL-SPARQL constraint components (sh:ConstraintComponent with sh:validator / sh:nodeValidator / " +
            "sh:propertyValidator / sh:SPARQLAskValidator) are not supported by the native engine " +
            "(ValidatorCapabilities.supportsCustomConstraints = false)"
    private const val NODE_EXPRESSIONS =
        "SHACL 1.2 node expressions (sh:expression, sh:nodeByExpression over computed nodes) are not " +
            "implemented by the native engine"
    private const val SPARQL_EXPRESSIONS =
        "SHACL 1.2 SPARQL node expressions (sh:select / sh:sparqlExpr as sh:targetNode or sh:property values) are " +
            "not implemented by the native engine"

    private val deviations: Map<String, String> = linkedMapOf(
        "sparql/component/optional-001.ttl" to SPARQL_COMPONENTS,
        "sparql/component/propertyValidator-select-001.ttl" to SPARQL_COMPONENTS,
        "sparql/component/validator-001.ttl" to SPARQL_COMPONENTS,
        // The expected sht:Failure stems from an sh:SPARQLAskValidator re-binding ?value inside a constraint component.
        "sparql/pre-binding/unsupported-sparql-006.ttl" to SPARQL_COMPONENTS,
        "node-expr/constraints/expression-001.ttl" to NODE_EXPRESSIONS,
        "node-expr/constraints/nodeByExpression-001.ttl" to NODE_EXPRESSIONS,
        "sparql/property/property-select-001.ttl" to SPARQL_EXPRESSIONS,
        "sparql/property/property-sparqlExpr-001.ttl" to SPARQL_EXPRESSIONS,
        "sparql/targets/targetNode-select-001.ttl" to SPARQL_EXPRESSIONS,
    )

    fun reasonFor(manifestPath: Path): String? {
        val file = manifestPath.toString().replace(java.io.File.separatorChar, '/')
        return deviations.entries.firstOrNull { (suffix, _) -> file.endsWith("/$suffix") }?.value
    }
}
