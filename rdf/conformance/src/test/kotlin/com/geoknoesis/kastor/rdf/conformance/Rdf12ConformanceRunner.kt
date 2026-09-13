package com.geoknoesis.kastor.rdf.conformance

import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfProvider
import com.geoknoesis.kastor.rdf.RdfRepository
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
 * for dataset parsing.
 */
class Conformer(
    /** Human-readable provider label - "Jena", "RDF4J", ... - used in test display names. */
    val label: String,
    private val provider: RdfProvider,
    private val newDatasetRepo: () -> RdfRepository,
    /**
     * Explicit, committed list of W3C test IRIs this provider is known not to pass, each with a reason
     * (see `conformance-allowlist.tsv`). Only listed tests may be skipped; a listed test that starts
     * passing fails so the entry gets removed.
     */
    val allowlist: Map<String, String> = emptyMap(),
    /**
     * For allowlisted positive/eval tests: returns true when the thrown exception is the documented
     * upstream failure (e.g. a Rio parse error). Any other exception still fails the test.
     */
    val expectedFailure: (Throwable) -> Boolean = { it is RdfFormatException },
) {
    /** Parses a graph and materialises every triple, so term-conversion failures surface here. */
    fun parseGraph(stream: InputStream, formatName: String, baseIri: String? = null): RdfGraph =
        provider.parseGraph(stream, formatName, baseIri).let { MemoryGraph(it.getTriples()) }

    /**
     * Parses a dataset (TriG or N-Quads) into graph name → graph (`null` = default graph), so named
     * graphs are compared graph by graph instead of being flattened into one graph.
     */
    fun parseDataset(stream: InputStream, formatName: String, baseIri: String? = null): Map<String?, RdfGraph> {
        val repo = newDatasetRepo()
        return repo.use {
            provider.parseDataset(repo, stream, formatName, baseIri)
            val graphs = linkedMapOf<String?, RdfGraph>(null to MemoryGraph(repo.defaultGraph.getTriples()))
            repo.listGraphs().forEach { name -> graphs[name.value] = MemoryGraph(repo.getGraph(name).getTriples()) }
            graphs.filter { (name, graph) -> name == null || graph.size() > 0 }
        }
    }
}

/** Loads the committed conformance allowlist (`provider<TAB>test IRI<TAB>reason`, `#` comments). */
object ConformanceAllowlist {
    fun forProvider(label: String): Map<String, String> {
        val stream = ConformanceAllowlist::class.java.classLoader.getResourceAsStream("conformance-allowlist.tsv")
            ?: return emptyMap()
        return stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.split('\t') }
                .onEach { require(it.size == 3 && it[2].isNotBlank()) { "allowlist rows need provider, test IRI and a reason: $it" } }
                .filter { it[0] == label }
                .associate { it[1] to it[2] }
        }
    }
}

/**
 * Drives the W3C RDF 1.2 syntax test suites against a Kastor provider.
 *
 * Outcomes are strict:
 * - **positive syntax / eval**: any exception fails, unless the test is allowlisted for the provider and
 *   the exception is the documented upstream failure (then it is skipped).
 * - **negative syntax / negative eval**: the parser must reject the input with [RdfFormatException].
 *   Any other exception (NPE, ClassCastException, ...) fails, and so does a clean parse — unless the test
 *   is allowlisted as a known lenient parser (then it is skipped).
 * - An allowlisted test that behaves correctly fails, so stale entries are removed.
 * - TriG / N-Quads eval results are compared as datasets, graph by graph.
 *
 * The runner is self-skipping when the W3C test data under `rdf/conformance/test-data/` is absent.
 */
object Rdf12ConformanceRunner {

    /**
     * Build a [DynamicContainer] tree for one manifest file plus all the
     * sub-manifests it includes, exercised against [conformer].
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
                TestKind.NEGATIVE_SYNTAX, TestKind.NEGATIVE_EVAL -> runNegative(conformer, case)
                TestKind.EVAL -> runEval(conformer, case)
            }
        }

    private fun allowlisted(conformer: Conformer, case: W3cTestCase): String? = conformer.allowlist[case.iri]

    private fun staleAllowlistEntry(conformer: Conformer, case: W3cTestCase, reason: String): Nothing =
        throw AssertionError(
            "ALLOWLIST-STALE ${case.iri}: test now behaves correctly for ${conformer.label}; " +
                "remove it from conformance-allowlist.tsv (reason was: $reason)",
        )

    /** Rethrows [ex] as a failure unless the test is allowlisted and [ex] is the documented upstream failure. */
    private fun failOrSkip(conformer: Conformer, case: W3cTestCase, ex: Throwable): Nothing {
        val reason = allowlisted(conformer, case)
        if (reason != null && conformer.expectedFailure(ex)) {
            Assumptions.assumeTrue(false, "ALLOWLISTED ${conformer.label} ${case.iri}: $reason")
        }
        throw AssertionError("FAILED ${case.iri}: ${ex.javaClass.name}: ${ex.message}", ex)
    }

    private fun runPositive(conformer: Conformer, case: W3cTestCase) {
        runCatching { parseAction(conformer, case) }.exceptionOrNull()?.let { failOrSkip(conformer, case, it) }
        allowlisted(conformer, case)?.let { staleAllowlistEntry(conformer, case, it) }
    }

    /**
     * Negative syntax and negative eval tests: the input must be rejected with [RdfFormatException].
     * A clean parse is only tolerated (as a skip) for explicitly allowlisted lenient-parser cases.
     */
    private fun runNegative(conformer: Conformer, case: W3cTestCase) {
        val threw = runCatching { parseAction(conformer, case) }.exceptionOrNull()
        val reason = allowlisted(conformer, case)
        when {
            threw is RdfFormatException -> if (reason != null) staleAllowlistEntry(conformer, case, reason)
            threw != null && reason != null && conformer.expectedFailure(threw) ->
                Assumptions.assumeTrue(false, "ALLOWLISTED ${conformer.label} ${case.iri}: $reason")
            threw != null -> throw AssertionError(
                "FAILED ${case.iri}: negative test must fail with RdfFormatException, got ${threw.javaClass.name}: ${threw.message}",
                threw,
            )
            reason != null -> Assumptions.assumeTrue(false, "ALLOWLISTED ${conformer.label} ${case.iri}: $reason")
            else -> throw AssertionError("FAILED ${case.iri}: negative ${case.kind} test parsed without error")
        }
    }

    private fun runEval(conformer: Conformer, case: W3cTestCase) {
        val expectedPath = case.result
            ?: error("eval test missing mf:result: ${case.iri}")
        val (actual, expected) = runCatching {
            parseActionAsDataset(conformer, case) to parseExpected(conformer, case, expectedPath)
        }.getOrElse { failOrSkip(conformer, case, it) }
        val mismatch = datasetMismatch(expected, actual)
        if (mismatch != null) {
            val reason = allowlisted(conformer, case)
            if (reason != null) Assumptions.assumeTrue(false, "ALLOWLISTED ${conformer.label} ${case.iri}: $reason")
            throw AssertionError("FAILED ${case.iri}: eval mismatch ($mismatch)\n  expected: $expectedPath")
        }
        allowlisted(conformer, case)?.let { staleAllowlistEntry(conformer, case, it) }
    }

    /** Null when both datasets have the same graph names and every graph is isomorphic. */
    private fun datasetMismatch(expected: Map<String?, RdfGraph>, actual: Map<String?, RdfGraph>): String? {
        if (expected.keys != actual.keys) return "graph names differ: expected ${expected.keys}, actual ${actual.keys}"
        for ((name, graph) in expected) {
            val other = actual.getValue(name)
            if (!other.isIsomorphicTo(graph)) {
                return "graph ${name ?: "(default)"} not isomorphic: expected ${graph.size()} triples, actual ${other.size()}"
            }
        }
        return null
    }

    private fun isDataset(format: TestFormat) = format == TestFormat.TRIG || format == TestFormat.N_QUADS

    private fun parseAction(conformer: Conformer, case: W3cTestCase): Any = parseActionAsDataset(conformer, case)

    private fun parseActionAsDataset(conformer: Conformer, case: W3cTestCase): Map<String?, RdfGraph> =
        case.action.toFile().inputStream().use { stream -> parse(conformer, stream, case.format, case.assumedBaseIri) }

    private fun parse(conformer: Conformer, stream: InputStream, format: TestFormat, base: String?): Map<String?, RdfGraph> =
        if (isDataset(format)) conformer.parseDataset(stream, format.parserKey, base)
        else mapOf(null to conformer.parseGraph(stream, format.parserKey, base))

    private fun parseExpected(conformer: Conformer, case: W3cTestCase, path: Path): Map<String?, RdfGraph> {
        // Eval expectations are typically N-Triples (graph) or N-Quads (dataset). Pick by extension;
        // fall back to the action's format.
        val ext = path.toString().lowercase().substringAfterLast('.')
        val expectedFormat = when (ext) {
            "nt" -> TestFormat.N_TRIPLES
            "nq" -> TestFormat.N_QUADS
            "ttl" -> TestFormat.TURTLE
            "trig" -> TestFormat.TRIG
            else -> case.format
        }
        return path.toFile().inputStream().use { stream -> parse(conformer, stream, expectedFormat, case.assumedBaseIri) }
    }
}
