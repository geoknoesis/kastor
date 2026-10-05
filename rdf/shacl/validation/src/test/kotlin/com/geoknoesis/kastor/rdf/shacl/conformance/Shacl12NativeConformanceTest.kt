package com.geoknoesis.kastor.rdf.shacl.conformance

import java.nio.file.Files
import java.nio.file.Path
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeature
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
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
 * Non-approved manifest rows are skipped unless `-Dshacl.w3c.includeNonApproved=true`. Cases listed in
 * [W3cKnownDeviations] use features the native engine does not implement: they are **executed** and must fail with an
 * [com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeatureException] of exactly the listed category (never pass
 * silently, never be skipped). `sht:Failure` cases must fail with the category in [W3cExpectedFailures].
 *
 * A missing manifest, a manifest without `sht:Validate` entries, or one without a single approved case would make
 * this suite pass having run nothing: each is a **failing** test, unless the explicit opt-out
 * `-Dshacl.w3c.allowMissingData=true` (Gradle: `-PshaclW3cAllowMissingData=true`) turns it into a skip, as
 * `conformance.allowMissingData` does for the RDF 1.2 suites.
 *
 * Tagged `w3c`: `./gradlew :rdf:shacl-validation:w3cConformanceTest` runs only this suite and fails (instead of
 * falling back to the bundled fixture) when the upstream checkout is missing.
 */
@Tag("w3c")
class Shacl12NativeConformanceTest {

    internal companion object {
        /** System property that turns a missing or empty suite into a skip instead of a failure. */
        const val ALLOW_MISSING_DATA_PROPERTY = "shacl.w3c.allowMissingData"
    }

    @TestFactory
    fun `SHACL 1 2 core W3C manifests native`(): Stream<DynamicNode> = suite(manifestRoot())

    /** The tests of the suite at [manifest]; failing placeholders (or skipped ones, when opted out) for missing data. */
    internal fun suite(
        manifest: Path,
        allowMissing: Boolean = System.getProperty(ALLOW_MISSING_DATA_PROPERTY) == "true",
    ): Stream<DynamicNode> {
        fun absent(name: String, reason: String) = absentSuite(name, reason, allowMissing)
        if (!Files.isRegularFile(manifest)) {
            return absent("manifest missing", "SHACL W3C manifest not found at $manifest")
        }

        val cases = Shacl12ManifestParser.collect(manifest)
        if (System.getProperty("shacl.w3c.requireSuite") == "true") assertCompleteSuite(manifest, cases)
        if (cases.isEmpty()) {
            return absent("no sht Validate entries", "no sht:Validate tests under $manifest")
        }

        val includeNonApproved = System.getProperty("shacl.w3c.includeNonApproved") == "true"
        if (!includeNonApproved && cases.none { it.approved }) {
            return absent("no approved entries", "none of the ${cases.size} sht:Validate tests under $manifest is approved")
        }

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
                                    Shacl12W3cCaseRunner.run(case)
                                }
                            },
                    )
                }
        return containers.stream()
    }

    /** One failing test that explains the missing data, or one skipped test when [allowMissing] opts out. */
    private fun absentSuite(name: String, reason: String, allowMissing: Boolean): Stream<DynamicNode> =
        Stream.of(
            DynamicTest.dynamicTest(name) {
                if (allowMissing) {
                    Assumptions.assumeTrue(false, reason)
                } else {
                    fail<Unit>("$reason; the suite would pass without running a case. Set -D$ALLOW_MISSING_DATA_PROPERTY=true to skip instead.")
                }
            },
        )

    /**
     * With `shacl.w3c.requireSuite=true` the run must cover the full suite, including when `shacl.w3c.manifest`
     * overrides the location: the collected cases must come from the core, SPARQL and node-expression manifests.
     */
    private fun assertCompleteSuite(manifest: Path, cases: List<ShaclValidateCase>) {
        val files = cases.map { it.manifestPath.toString().replace(java.io.File.separatorChar, '/') }
        val missing = listOf("/core/", "/sparql/", "/node-expr/").filter { part -> files.none { it.contains(part) } }
        check(missing.isEmpty()) {
            "shacl.w3c.requireSuite=true but $manifest does not include the ${missing.joinToString()} manifests of the full W3C SHACL 1.2 suite"
        }
    }

    private fun manifestRoot(): Path {
        System.getProperty("shacl.w3c.manifest")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val p = Path.of(it).toAbsolutePath().normalize()
            // An explicit override never falls back silently to another suite.
            check(Files.isRegularFile(p)) { "shacl.w3c.manifest=$it is not a manifest file" }
            return p
        }

        val checkout =
            Path.of("test-data/w3c-shacl12/tests/manifest.ttl").toAbsolutePath().normalize()
        if (Files.isRegularFile(checkout)) return checkout
        check(System.getProperty("shacl.w3c.requireSuite") != "true") {
            "shacl.w3c.requireSuite=true but the W3C SHACL test suite is missing at $checkout (see test-data/README.md)"
        }

        val resource = Shacl12NativeConformanceTest::class.java.getResource("/w3c-shacl12-fixture/manifest.ttl")
            ?: error("bundled W3C fixture missing from test classpath")
        return Path.of(resource.toURI()).toAbsolutePath().normalize()
    }
}

/**
 * Explicit, reviewed list of W3C SHACL 1.2 cases that use features the native engine does not implement. Every entry
 * is matched against the end of the path of the manifest file containing the case (`.../dir/file.ttl`) and carries the
 * expected [UnsupportedShaclFeature] category and a justification. The harness runs these cases and asserts that
 * validation fails with an [com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeatureException] whose categories are
 * exactly that category ([com.geoknoesis.kastor.rdf.shacl.ValidationConfig.unsupportedFeatures]).
 */
internal object W3cKnownDeviations {
    class Deviation(val category: UnsupportedShaclFeature, val reason: String)

    private val SPARQL_COMPONENTS = Deviation(
        UnsupportedShaclFeature.SPARQL_CONSTRAINT_COMPONENT,
        "SHACL-SPARQL constraint components (sh:ConstraintComponent with sh:validator / sh:nodeValidator / " +
            "sh:propertyValidator / sh:SPARQLAskValidator) are not supported by the native engine " +
            "(ValidatorCapabilities.supportsCustomConstraints = false)",
    )
    private val NODE_EXPRESSIONS = Deviation(
        UnsupportedShaclFeature.NODE_EXPRESSION,
        "SHACL 1.2 node expression constraints (sh:expression) are not implemented by the native engine",
    )
    private val SPARQL_EXPRESSIONS = Deviation(
        UnsupportedShaclFeature.SPARQL_NODE_EXPRESSION,
        "SHACL 1.2 SPARQL node expressions (sh:select / sh:sparqlExpr as sh:targetNode or sh:property values) are " +
            "not implemented by the native engine",
    )
    private val SPARQL_FUNCTIONS = Deviation(
        UnsupportedShaclFeature.SHACL_FUNCTION,
        "SHACL 1.2 expression functions (sh:ListParameterExpressionFunction with sh:bodyExpression) called from " +
            "SPARQL are not implemented by the native engine",
    )

    private val deviations: Map<String, Deviation> = linkedMapOf(
        "sparql/component/optional-001.ttl" to SPARQL_COMPONENTS,
        "sparql/component/propertyValidator-select-001.ttl" to SPARQL_COMPONENTS,
        "sparql/component/validator-001.ttl" to SPARQL_COMPONENTS,
        // The expected sht:Failure stems from an sh:SPARQLAskValidator re-binding ?value inside a constraint component.
        "sparql/pre-binding/unsupported-sparql-006.ttl" to SPARQL_COMPONENTS,
        "node-expr/constraints/expression-001.ttl" to NODE_EXPRESSIONS,
        "sparql/property/property-select-001.ttl" to SPARQL_EXPRESSIONS,
        "sparql/property/property-sparqlExpr-001.ttl" to SPARQL_EXPRESSIONS,
        "sparql/targets/targetNode-select-001.ttl" to SPARQL_EXPRESSIONS,
        "sparql/functions/instanceCount-example.ttl" to SPARQL_FUNCTIONS,
        "sparql/functions/langLabelCount-example.ttl" to SPARQL_FUNCTIONS,
        "sparql/functions/spacedConcat-example.ttl" to SPARQL_FUNCTIONS,
    )

    fun deviationFor(manifestPath: Path): Deviation? {
        val file = manifestPath.toString().replace(java.io.File.separatorChar, '/')
        return deviations.entries.firstOrNull { (suffix, _) -> file.endsWith("/$suffix") }?.value
    }

    /**
     * Cases (same path matching as [deviations]) in which the engine may report **undecided** results
     * (`ksh:resultStatus`: undefined recursion, pattern timeout, pattern too complex) where the expected report has
     * definite ones, mapped to the reason. Every other case fails when the engine emits an undecided result (see
     * [assertNoUndecidedResults]). Empty: the engine decides every constraint of the approved W3C SHACL 1.2 suite.
     */
    private val undecidedResults: Map<String, String> = linkedMapOf()

    fun undecidedResultsAllowed(manifestPath: Path): String? {
        val file = manifestPath.toString().replace(java.io.File.separatorChar, '/')
        return undecidedResults.entries.firstOrNull { (suffix, _) -> file.endsWith("/$suffix") }?.value
    }
}
