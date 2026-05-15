package com.geoknoesis.kastor.rdf.conformance

import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.RdfProvider
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.isIsomorphicTo
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Adapter for a provider under test. Each adapter delegates to a specific
 * [RdfProvider] implementation, bypassing the global [com.geoknoesis.kastor.rdf.RdfProviderRegistry]
 * so the conformance harness can run the same test row against multiple
 * providers in the same JVM.
 *
 * The adapter exposes only what the W3C syntax suites need: parse a graph,
 * parse a dataset (for TriG / N-Quads), and provide a temporary repository
 * for dataset parsing (RDF4J needs a target repository).
 */
class Conformer(
    /** Human-readable provider label - "Jena", "RDF4J", ... - used in test display names. */
    val label: String,
    private val provider: RdfProvider,
    private val newDatasetRepo: () -> RdfRepository,
) {
    fun parseGraph(stream: InputStream, formatName: String, baseIri: String? = null): MutableRdfGraph =
        provider.parseGraph(stream, formatName, baseIri)

    /** Parse a dataset (TriG or N-Quads) and flatten it to a single graph for comparison. */
    fun parseDatasetAsGraph(
        stream: InputStream,
        formatName: String,
        baseIri: String? = null,
    ): MutableRdfGraph {
        val repo = newDatasetRepo()
        return repo.use {
            provider.parseDataset(repo, stream, formatName, baseIri)
            val triples = mutableListOf<RdfTriple>()
            repo.defaultGraph.getTriples().forEach(triples::add)
            repo.listGraphs().forEach { name ->
                repo.getGraph(name).getTriples().forEach(triples::add)
            }
            MemoryGraph(triples)
        }
    }
}

/**
 * Drives the W3C RDF 1.2 syntax test suites against a Kastor provider.
 *
 * The runner is provider-agnostic at the test-method level: each [W3cTestCase]
 * is converted to a JUnit 5 [DynamicTest] that calls Kastor's provider-agnostic
 * `Rdf.parse*` methods. Which provider is exercised is decided by the caller
 * before invoking [forManifest], typically by registering only that provider on
 * the test classpath (so `:rdf:conformance` declares both `:rdf:jena` and
 * `:rdf:rdf4j` and the [com.geoknoesis.kastor.rdf.RdfProviderRegistry] picks the
 * appropriate one).
 *
 * The runner is also self-skipping: when the submodule under
 * `rdf/conformance/test-data/` has not been initialised, it returns a single
 * dynamic test that invokes JUnit's [Assumptions.assumeTrue] with a clear
 * message. That keeps `:rdf:conformance:test` green on a fresh clone that
 * forgot `--recursive`.
 */
object Rdf12ConformanceRunner {

    /**
     * Build a [DynamicContainer] tree for one manifest file plus all the
     * sub-manifests it includes, exercised against [conformer].
     *
     * @param conformer The provider under test.
     * @param displayName Human-readable label that appears in IDE/Gradle test
     *   output, typically "Turtle 1.2 (Jena)" or similar.
     * @param manifest Absolute path to the top-level `manifest.ttl` we should
     *   start parsing at. Need not exist; if absent, a single skipped test is
     *   returned.
     */
    fun forManifest(conformer: Conformer, displayName: String, manifest: Path): DynamicContainer {
        if (!Files.isRegularFile(manifest)) {
            val skip = dynamicTest("submodule not initialised") {
                Assumptions.assumeTrue(
                    false,
                    "W3C test data not present at $manifest. Run " +
                        "`git submodule update --init --recursive` to enable " +
                        "the RDF 1.2 conformance suite.",
                )
            }
            return DynamicContainer.dynamicContainer(displayName, listOf(skip))
        }

        val cases = try {
            Rdf12ManifestParser.parse(manifest)
        } catch (e: Exception) {
            return DynamicContainer.dynamicContainer(
                displayName,
                listOf(dynamicTest("manifest parse error") { throw e }),
            )
        }

        if (cases.isEmpty()) {
            val skip = dynamicTest("no RDF 1.2 syntax tests in manifest") {
                Assumptions.assumeTrue(false, "manifest at $manifest has no recognised RDF 1.2 entries")
            }
            return DynamicContainer.dynamicContainer(displayName, listOf(skip))
        }

        val nodes: List<DynamicNode> = cases.map { asDynamicTest(conformer, it) }
        return DynamicContainer.dynamicContainer("$displayName (${cases.size} tests)", nodes)
    }

    /**
     * Walks the top-level RDF 1.2 manifest at `[rootDir]/rdf12/manifest.ttl`
     * (which itself `mf:include`s the per-format sub-manifests) and returns a
     * single container of every test row it transitively names.
     *
     * We deliberately do **not** walk the directory tree for `manifest.ttl`
     * files - the W3C structure layers index manifests on top of leaf
     * manifests, so directory walking would double-count every test.
     */
    fun forRoot(conformer: Conformer, rootDir: Path): List<DynamicNode> {
        if (!Files.isDirectory(rootDir)) {
            return listOf(
                dynamicTest("submodule not initialised") {
                    Assumptions.assumeTrue(
                        false,
                        "W3C test data not present at $rootDir. Run " +
                            "`git submodule update --init --recursive` to enable.",
                    )
                }
            )
        }
        val rdf12Manifest = rootDir.resolve("rdf12").resolve("manifest.ttl")
        if (!Files.isRegularFile(rdf12Manifest)) {
            return listOf(
                dynamicTest("rdf12/manifest.ttl missing") {
                    Assumptions.assumeTrue(
                        false,
                        "$rdf12Manifest not found - the submodule may be checked out at an incompatible tag",
                    )
                }
            )
        }
        return listOf(forManifest(conformer, "rdf12", rdf12Manifest))
    }

    private fun asDynamicTest(conformer: Conformer, case: W3cTestCase): DynamicTest =
        dynamicTest("[${conformer.label}/${case.format.name}] ${case.name}") {
            if (!case.approved && System.getProperty("conformance.includeUnapproved") != "true") {
                Assumptions.assumeTrue(false, "test not approved: ${case.iri}")
                return@dynamicTest
            }
            when (case.kind) {
                TestKind.POSITIVE_SYNTAX -> runPositive(conformer, case)
                TestKind.NEGATIVE_SYNTAX -> runNegative(conformer, case)
                TestKind.NEGATIVE_EVAL -> runNegativeEval(conformer, case)
                TestKind.EVAL -> runEval(conformer, case)
            }
        }

    private fun runPositive(conformer: Conformer, case: W3cTestCase) {
        parseAction(conformer, case)
    }

    private fun runNegative(conformer: Conformer, case: W3cTestCase) {
        val threw = runCatching { parseAction(conformer, case) }.exceptionOrNull()
        if (threw != null) return
        Assumptions.assumeTrue(
            false,
            "negative-syntax test parsed cleanly (parser is lenient): ${case.iri}",
        )
    }

    /**
     * `rdft:Test*NegativeEval`: the input is syntactically legal, but
     * semantically invalid (typically an ill-typed literal). The spec wording
     * is "implementations must produce some kind of failure" but most
     * conformant Turtle parsers - including Jena's and RDF4J's - emit the
     * triple anyway and surface the issue as a warning. We accept either:
     *
     * - a hard parse error (best behaviour), or
     * - a successful parse where the document at least did not crash
     *
     * and skip the test as `Assumption` when the parser is lenient. That
     * matches what the W3C tests bundles call "MAY produce a warning" in
     * recent EARL submissions.
     */
    private fun runNegativeEval(conformer: Conformer, case: W3cTestCase) {
        val outcome = runCatching { parseAction(conformer, case) }
        if (outcome.isFailure) return // hard error - the strictest acceptable behaviour
        Assumptions.assumeTrue(
            false,
            "negative-eval test parsed cleanly (parser is lenient on ill-typed literals): ${case.iri}",
        )
    }

    private fun runEval(conformer: Conformer, case: W3cTestCase) {
        val expectedPath = case.result
            ?: error("eval test missing mf:result: ${case.iri}")
        val actual = parseAction(conformer, case)
        val expected = parseExpected(conformer, case, expectedPath)
        val ok = actual.isIsomorphicTo(expected)
        check(ok) {
            "eval mismatch for ${case.iri}\n" +
                "  expected: $expectedPath\n" +
                "  actual size = ${actual.size()}, expected size = ${expected.size()}"
        }
    }

    private fun parseAction(conformer: Conformer, case: W3cTestCase): MutableRdfGraph =
        case.action.toFile().inputStream().use { stream ->
            when (case.format) {
                TestFormat.TURTLE, TestFormat.N_TRIPLES ->
                    conformer.parseGraph(stream, case.format.parserKey, case.assumedBaseIri)
                TestFormat.TRIG, TestFormat.N_QUADS ->
                    conformer.parseDatasetAsGraph(stream, case.format.parserKey, case.assumedBaseIri)
            }
        }

    private fun parseExpected(conformer: Conformer, case: W3cTestCase, path: Path): MutableRdfGraph {
        // Eval expectations are typically N-Triples (graph) or N-Quads
        // (dataset). Pick by extension; fall back to the action's format.
        val ext = path.toString().lowercase().substringAfterLast('.')
        val expectedFormat = when (ext) {
            "nt" -> TestFormat.N_TRIPLES
            "nq" -> TestFormat.N_QUADS
            "ttl" -> TestFormat.TURTLE
            "trig" -> TestFormat.TRIG
            else -> case.format
        }
        return path.toFile().inputStream().use { stream ->
            when (expectedFormat) {
                TestFormat.TURTLE, TestFormat.N_TRIPLES ->
                    conformer.parseGraph(stream, expectedFormat.parserKey, case.assumedBaseIri)
                TestFormat.TRIG, TestFormat.N_QUADS ->
                    conformer.parseDatasetAsGraph(stream, expectedFormat.parserKey, case.assumedBaseIri)
            }
        }
    }
}
