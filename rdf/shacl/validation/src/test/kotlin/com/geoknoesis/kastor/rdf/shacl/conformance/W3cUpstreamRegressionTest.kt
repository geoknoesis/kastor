package com.geoknoesis.kastor.rdf.shacl.conformance

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.io.path.extension
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * W3C SHACL 1.2 cases added upstream after the pinned test-suite checkout, bundled under
 * `src/test/resources/w3c-shacl12-regressions/` and run through the same manifest parser and case runner as the full
 * suite, so they are checked on every `test` run.
 */
class W3cUpstreamRegressionTest {

    @TestFactory
    fun `bundled upstream W3C SHACL 1 2 cases pass on the native engine`(): Stream<DynamicTest> {
        val root = Path.of(
            W3cUpstreamRegressionTest::class.java.getResource("/w3c-shacl12-regressions")?.toURI()
                ?: error("w3c-shacl12-regressions resources missing from the test classpath"),
        )
        val manifests = Files.walk(root).use { paths -> paths.filter { it.extension == "ttl" }.sorted().toList() }
        val cases = manifests.flatMap { Shacl12ManifestParser.collect(it) }
        assertTrue(cases.any { it.entryUri.endsWith("uniqueValuesFor-005") }, "uniqueValuesFor-005 must be bundled: $cases")
        return cases.stream().map { case -> DynamicTest.dynamicTest(case.displayName) { Shacl12W3cCaseRunner.run(case) } }
    }
}
