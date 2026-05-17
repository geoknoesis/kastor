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
 * W3C SHACL 1.2 Core manifest tests against the **native** validator ([NativeShaclValidatorProvider]).
 *
 * Test discovery:
 * 1. System property `shacl.w3c.manifest` — absolute path to a top manifest Turtle file (optional).
 * 2. Otherwise `test-data/w3c-shacl12/tests/core/manifest.ttl` relative to the `:rdf:shacl-validation`
 *    project dir (full upstream checkout — see `test-data/README.md`).
 * 3. Otherwise a small bundled fixture under `src/test/resources/w3c-shacl12-fixture/` so `./gradlew test`
 *    stays meaningful without cloning the suite.
 *
 * Non-approved manifest rows are skipped unless `-Dshacl.w3c.includeNonApproved=true`.
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
                        "${path.fileName} (${pathCases.size})",
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

    private fun manifestRoot(): Path {
        System.getProperty("shacl.w3c.manifest")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val p = Path.of(it).toAbsolutePath().normalize()
            if (Files.isRegularFile(p)) return p
        }

        val checkout =
            Path.of("test-data/w3c-shacl12/tests/core/manifest.ttl").toAbsolutePath().normalize()
        if (Files.isRegularFile(checkout)) return checkout

        val resource = Shacl12NativeConformanceTest::class.java.getResource("/w3c-shacl12-fixture/manifest.ttl")
            ?: error("bundled W3C fixture missing from test classpath")
        return Path.of(resource.toURI()).toAbsolutePath().normalize()
    }
}
