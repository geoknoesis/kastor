package com.geoknoesis.kastor.rdf.conformance

import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.RdfProvider
import com.geoknoesis.kastor.rdf.RdfRepository
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Adapter for a provider under test. Each adapter delegates to a specific
 * [RdfProvider] implementation, bypassing the global [com.geoknoesis.kastor.rdf.RdfProviderRegistry]
 * so the conformance harness can run the same test row against multiple
 * providers in the same JVM.
 *
 * The adapter exposes only what the W3C syntax suites need: parse a graph and parse a dataset
 * (TriG / N-Quads) through the provider, returning the statements it produced.
 */
class Conformer(
    /** Human-readable provider label - "Jena", "RDF4J", ... - used in test display names. */
    val label: String,
    private val provider: RdfProvider,
    private val newDatasetRepo: () -> RdfRepository,
    /**
     * Explicit, committed list of W3C test IRIs this provider is known not to pass, each with a reason and the
     * expected failure signature (see `conformance-allowlist.tsv`). Only listed tests whose failure matches the
     * listed signature may be skipped; a listed test that starts passing fails so the entry gets removed.
     */
    val allowlist: Map<String, AllowlistEntry> = emptyMap(),
) {
    /** Parses a graph and materialises every triple, so term-conversion failures surface here. */
    fun parseGraph(stream: InputStream, formatName: String, baseIri: String? = null): List<KastorQuad> =
        provider.parseGraph(stream, formatName, baseIri).getTriples().map { KastorQuad(null, it) }

    /** Parses a dataset (TriG or N-Quads) into a fresh repository and returns all of its statements. */
    fun parseDataset(stream: InputStream, formatName: String, baseIri: String? = null): List<KastorQuad> =
        newDatasetRepo().use { repo ->
            provider.parseDataset(repo, stream, formatName, baseIri)
            repo.defaultGraph.getTriples().map { KastorQuad(null, it) } +
                repo.listGraphs().flatMap { name -> repo.getGraph(name).getTriples().map { KastorQuad(name.value, it) } }
        }
}

/**
 * An allowlisted known failure.
 *
 * @property reason why the provider fails the test.
 * @property failure pattern the observed failure signature (see [Rdf12ConformanceRunner.failureSignature])
 *   must contain for the test to be skipped; [Rdf12ConformanceRunner.EVAL_MISMATCH] and
 *   [Rdf12ConformanceRunner.ACCEPTED_INVALID] name the non-exception failure categories.
 */
data class AllowlistEntry(val reason: String, val failure: Regex)

/** Loads the committed conformance allowlist (`provider<TAB>test IRI<TAB>reason<TAB>failure regex`, `#` comments). */
object ConformanceAllowlist {
    fun forProvider(label: String): Map<String, AllowlistEntry> {
        val stream = ConformanceAllowlist::class.java.classLoader.getResourceAsStream("conformance-allowlist.tsv")
            ?: return emptyMap()
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
                .map { it.split('\t') }
                .onEach {
                    require(it.size == 4 && it[2].isNotBlank() && it[3].isNotBlank()) {
                        "allowlist rows need provider, test IRI, reason and an expected-failure regex: $it"
                    }
                }
                .filter { it[0] == label }
                .associate { testKey(it[1]) to AllowlistEntry(it[2], Regex(it[3])) }
        }
    }

    /**
     * Checkout-independent key for a W3C test IRI. Manifests without an `@base` (the RDF 1.1 suites)
     * yield `file:` IRIs containing the absolute checkout path; those are reduced to the part starting
     * at `test-data/` so allowlist rows match in every checkout and on CI.
     */
    fun testKey(iri: String): String {
        if (!iri.startsWith("file:")) return iri
        val index = iri.indexOf("/test-data/")
        return if (index < 0) iri else iri.substring(index + 1)
    }
}

/**
 * Drives the W3C RDF 1.2 syntax test suites against a Kastor provider.
 *
 * Outcomes are strict:
 * - **positive syntax / eval**: any exception fails, unless the test is allowlisted for the provider and
 *   the failure signature matches the allowlisted pattern (then it is skipped).
 * - **negative syntax / negative eval**: the parser must reject the input with [RdfFormatException].
 *   Any other exception (NPE, ClassCastException, ...) fails, and so does a clean parse, unless the test
 *   is allowlisted with a matching signature (a clean parse has the signature [ACCEPTED_INVALID]).
 * - An allowlisted test that behaves correctly fails, so stale entries are removed.
 * - Eval expectations are parsed by an independent reference parser ([ReferenceRdf]) and compared with
 *   the provider's output as datasets (one isomorphism across all graphs).
 *
 * Set the system property `conformance.allowlistProposal` (Gradle property `conformanceAllowlistProposal`)
 * to a file path to append a proposed allowlist row for every failing or skipped test.
 *
 * The runner is self-skipping when the W3C test data under `rdf/conformance/test-data/` is absent.
 */
object Rdf12ConformanceRunner {

    /** Failure signature of an eval test whose output is not isomorphic to the expected result. */
    const val EVAL_MISMATCH = "EVAL-MISMATCH"

    /** Failure signature of a negative test whose invalid input was parsed without error. */
    const val ACCEPTED_INVALID = "ACCEPTED-INVALID-INPUT"

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

    /**
     * Stable description of a failure: one line per exception in the cause chain, as
     * `class: message @ first non-JDK frame`, with whitespace collapsed.
     */
    fun failureSignature(error: Throwable): String = generateSequence(error) { it.cause }.joinToString("\n") { t ->
        val frame = t.stackTrace.firstOrNull { !it.className.startsWith("java.") && !it.className.startsWith("kotlin.") }
        "${t.javaClass.name}: ${t.message?.replace(Regex("\\s+"), " ")} @ ${frame?.className}.${frame?.methodName}"
    }

    private fun allowlisted(conformer: Conformer, case: W3cTestCase): AllowlistEntry? =
        conformer.allowlist[ConformanceAllowlist.testKey(case.iri)]

    private fun staleAllowlistEntry(conformer: Conformer, case: W3cTestCase, entry: AllowlistEntry): Nothing =
        throw AssertionError(
            "ALLOWLIST-STALE ${case.iri}: test now behaves correctly for ${conformer.label}; " +
                "remove it from conformance-allowlist.tsv (reason was: ${entry.reason})",
        )

    /**
     * Skips the test when it is allowlisted and [signature] matches the allowlisted failure pattern;
     * otherwise runs [fail]. An allowlisted test failing in an unexpected way still fails.
     */
    private fun skipIfAllowlistedOr(conformer: Conformer, case: W3cTestCase, signature: String, fail: () -> Nothing): Nothing {
        val entry = allowlisted(conformer, case)
        proposeRow(conformer, case, entry, signature)
        if (entry != null) {
            if (entry.failure.containsMatchIn(signature)) {
                Assumptions.assumeTrue(false, "ALLOWLISTED ${conformer.label} ${case.iri}: ${entry.reason}")
            }
            throw AssertionError(
                "FAILED ${case.iri}: allowlisted for '${entry.reason}', but the failure does not match " +
                    "/${entry.failure.pattern}/:\n$signature",
            )
        }
        fail()
    }

    private fun runPositive(conformer: Conformer, case: W3cTestCase) {
        val error = runCatching { parseAction(conformer, case) }.exceptionOrNull()
        if (error != null) {
            skipIfAllowlistedOr(conformer, case, failureSignature(error)) {
                throw AssertionError("FAILED ${case.iri}: ${error.javaClass.name}: ${error.message}", error)
            }
        }
        allowlisted(conformer, case)?.let { staleAllowlistEntry(conformer, case, it) }
    }

    /**
     * Negative syntax and negative eval tests: the input must be rejected with [RdfFormatException].
     * Anything else is only tolerated (as a skip) for explicitly allowlisted cases with a matching signature.
     */
    private fun runNegative(conformer: Conformer, case: W3cTestCase) {
        val threw = runCatching { parseAction(conformer, case) }.exceptionOrNull()
        when (threw) {
            is RdfFormatException -> allowlisted(conformer, case)?.let { staleAllowlistEntry(conformer, case, it) }
            null -> skipIfAllowlistedOr(conformer, case, ACCEPTED_INVALID) {
                throw AssertionError("FAILED ${case.iri}: negative ${case.kind} test parsed without error")
            }
            else -> skipIfAllowlistedOr(conformer, case, failureSignature(threw)) {
                throw AssertionError(
                    "FAILED ${case.iri}: negative test must fail with RdfFormatException, got ${threw.javaClass.name}: ${threw.message}",
                    threw,
                )
            }
        }
    }

    private fun runEval(conformer: Conformer, case: W3cTestCase) {
        val expectedPath = case.result ?: error("eval test missing mf:result: ${case.iri}")
        val actual = runCatching { parseAction(conformer, case) }.getOrElse { error ->
            skipIfAllowlistedOr(conformer, case, failureSignature(error)) {
                throw AssertionError("FAILED ${case.iri}: ${error.javaClass.name}: ${error.message}", error)
            }
        }
        // The reference parse is not the system under test: its failure is a harness error, never a skip.
        val expected = ReferenceRdf.parse(expectedPath, expectedFormat(case, expectedPath), case.assumedBaseIri)
        val actualDataset = ReferenceRdf.dataset(actual)
        if (!ReferenceRdf.isomorphic(expected, actualDataset)) {
            val details = "expected ${ReferenceRdf.size(expected)} quads, actual ${ReferenceRdf.size(actualDataset)}"
            skipIfAllowlistedOr(conformer, case, "$EVAL_MISMATCH: $details") {
                throw AssertionError(
                    "FAILED ${case.iri}: eval mismatch ($details)\n  expected: $expectedPath\n" +
                        "  expected quads:\n${ReferenceRdf.preview(expected)}\n  actual quads:\n${ReferenceRdf.preview(actualDataset)}",
                )
            }
        }
        allowlisted(conformer, case)?.let { staleAllowlistEntry(conformer, case, it) }
    }

    private fun expectedFormat(case: W3cTestCase, path: Path): TestFormat =
        when (path.toString().lowercase().substringAfterLast('.')) {
            "nt" -> TestFormat.N_TRIPLES
            "nq" -> TestFormat.N_QUADS
            "ttl" -> TestFormat.TURTLE
            "trig" -> TestFormat.TRIG
            else -> case.format
        }

    private fun isDataset(format: TestFormat) = format == TestFormat.TRIG || format == TestFormat.N_QUADS

    private fun parseAction(conformer: Conformer, case: W3cTestCase): List<KastorQuad> =
        case.action.toFile().inputStream().use { stream ->
            if (isDataset(case.format)) conformer.parseDataset(stream, case.format.parserKey, case.assumedBaseIri)
            else conformer.parseGraph(stream, case.format.parserKey, case.assumedBaseIri)
        }

    /** Appends a proposed allowlist row (for maintainers regenerating the allowlist) when requested. */
    private fun proposeRow(conformer: Conformer, case: W3cTestCase, entry: AllowlistEntry?, signature: String) {
        val target = System.getProperty("conformance.allowlistProposal") ?: return
        val row = listOf(
            conformer.label,
            ConformanceAllowlist.testKey(case.iri),
            entry?.reason ?: "TODO: reason",
            proposedPattern(signature),
        ).joinToString("\t") + System.lineSeparator()
        synchronized(this) {
            Files.writeString(Path.of(target), row, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    /** Regex matching the deepest cause of [signature], without source positions. */
    fun proposedPattern(signature: String): String {
        if (signature.startsWith(EVAL_MISMATCH)) return EVAL_MISMATCH
        if (signature == ACCEPTED_INVALID) return ACCEPTED_INVALID
        val deepest = signature.lines().last()
        val (head, frame) = deepest.substringBefore(" @ ") to deepest.substringAfter(" @ ", "")
        val withoutPosition = head.replace(Regex("\\s*\\[line [0-9]+(, column [0-9]+)?]"), "")
        val message = withoutPosition.substringAfter(": ", "")
        val stable = if (message.isEmpty() || message == "null") "$withoutPosition @ $frame" else withoutPosition
        return Regex.escape(stable)
    }
}
