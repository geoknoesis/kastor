package com.geoknoesis.kastor.rdf.conformance

import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.Statement
import org.apache.jena.vocabulary.RDF
import java.net.URI
import java.nio.file.Path

/**
 * The kind of W3C RDF 1.2 syntax test we support.
 *
 * Test types come from the `rdft:` test vocabulary at
 * `http://www.w3.org/ns/rdftest#`. We map the spec's nine concrete classes into
 * three behavioural buckets because at the harness layer that's what matters.
 */
enum class TestKind {
    /** Parse `[action]`; pass if no syntax error is raised. */
    POSITIVE_SYNTAX,
    /** Parse `[action]`; pass if a syntax error is raised. */
    NEGATIVE_SYNTAX,
    /**
     * Parse `[action]` and compare it (graph- or dataset-isomorphic) to
     * `[result]`. Pass when both files parse and the resulting graphs are
     * equivalent.
     */
    EVAL,
    /**
     * `rdft:TestTurtleNegativeEval` / `rdft:TestTrigNegativeEval`: documents
     * that are *syntactically* valid but produce semantic warnings (typically
     * ill-typed literals that conformant parsers may either reject or accept
     * with a warning). The spec says implementations "must produce some kind
     * of failure" - we treat the test as passed if **either** the parse
     * throws **or** the parse succeeds but emits no triples that contain a
     * valid lexical form. This matches the latitude that Jena and RDF4J take
     * by default.
     */
    NEGATIVE_EVAL,
}

/** Top-level RDF format the test exercises. Drives parser choice. */
enum class TestFormat(val parserKey: String) {
    TURTLE("TURTLE"),
    TRIG("TRIG"),
    N_TRIPLES("N-TRIPLES"),
    N_QUADS("N-QUADS"),
}

/**
 * One W3C test row, after a manifest has been parsed.
 *
 * @property name The test's `mf:name` (used as the JUnit test display name).
 * @property action Absolute path to the input file (`mf:action`).
 * @property result Absolute path to the expected file (`mf:result`), only set
 *   for [TestKind.EVAL] tests.
 * @property approved True if the test carries `rdft:approval rdft:Approved`.
 *   Tests that are merely proposed / rejected are kept in the list so callers
 *   can choose to run them, but they're flagged here.
 * @property iri The full IRI that names the test in the manifest, used in the
 *   EARL/JUnit output for traceability.
 * @property assumedBaseIri The `mf:assumedTestBase` declared by the manifest
 *   that owns this test (or null if none). Parsers should resolve relative
 *   IRIs in [action] / [result] against this base, otherwise the W3C
 *   `IRI-resolution-*` tests can't pass.
 */
data class W3cTestCase(
    val iri: String,
    val name: String,
    val kind: TestKind,
    val format: TestFormat,
    val action: Path,
    val result: Path? = null,
    val approved: Boolean = true,
    val comment: String? = null,
    val assumedBaseIri: String? = null,
)

/**
 * Reads a W3C `mf:Manifest` (Turtle) plus any `mf:include`d sub-manifests and
 * yields a flat list of [W3cTestCase].
 *
 * The implementation deliberately uses Jena directly (not Kastor's parsers)
 * because Kastor's parsers are what we're conformance-testing - the test
 * harness must be independent of the system under test.
 */
object Rdf12ManifestParser {

    private const val MF = "http://www.w3.org/2001/sw/DataAccess/tests/test-manifest#"
    private const val RDFT = "http://www.w3.org/ns/rdftest#"

    private val mfManifest = MF + "Manifest"
    private val mfInclude = MF + "include"
    private val mfEntries = MF + "entries"
    private val mfName = MF + "name"
    private val mfAction = MF + "action"
    private val mfResult = MF + "result"
    private val mfAssumedTestBase = MF + "assumedTestBase"
    private val rdfsComment = "http://www.w3.org/2000/01/rdf-schema#comment"
    private val rdftApproval = RDFT + "approval"
    private val rdftApproved = RDFT + "Approved"

    private val typeToKindAndFormat: Map<String, Pair<TestKind, TestFormat>> = mapOf(
        // Turtle 1.2
        RDFT + "TestTurtlePositiveSyntax" to (TestKind.POSITIVE_SYNTAX to TestFormat.TURTLE),
        RDFT + "TestTurtleNegativeSyntax" to (TestKind.NEGATIVE_SYNTAX to TestFormat.TURTLE),
        RDFT + "TestTurtleEval" to (TestKind.EVAL to TestFormat.TURTLE),
        RDFT + "TestTurtleNegativeEval" to (TestKind.NEGATIVE_EVAL to TestFormat.TURTLE),
        // TriG 1.2
        RDFT + "TestTrigPositiveSyntax" to (TestKind.POSITIVE_SYNTAX to TestFormat.TRIG),
        RDFT + "TestTrigNegativeSyntax" to (TestKind.NEGATIVE_SYNTAX to TestFormat.TRIG),
        RDFT + "TestTrigEval" to (TestKind.EVAL to TestFormat.TRIG),
        RDFT + "TestTrigNegativeEval" to (TestKind.NEGATIVE_EVAL to TestFormat.TRIG),
        // N-Triples 1.2
        RDFT + "TestNTriplesPositiveSyntax" to (TestKind.POSITIVE_SYNTAX to TestFormat.N_TRIPLES),
        RDFT + "TestNTriplesNegativeSyntax" to (TestKind.NEGATIVE_SYNTAX to TestFormat.N_TRIPLES),
        // N-Quads 1.2
        RDFT + "TestNQuadsPositiveSyntax" to (TestKind.POSITIVE_SYNTAX to TestFormat.N_QUADS),
        RDFT + "TestNQuadsNegativeSyntax" to (TestKind.NEGATIVE_SYNTAX to TestFormat.N_QUADS),
    )

    /**
     * Parse the manifest file at [manifestPath] and return all tests it
     * declares (recursing through `mf:include`).
     *
     * @param manifestPath Absolute path to a `manifest.ttl` file.
     * @return A list of test cases. Empty if the manifest declares no tests
     *   whose type we recognise.
     * @throws java.io.FileNotFoundException if the manifest does not exist.
     */
    fun parse(manifestPath: Path): List<W3cTestCase> {
        if (!manifestPath.toFile().isFile) {
            throw java.io.FileNotFoundException("manifest not found: $manifestPath")
        }
        val visited = mutableSetOf<Path>()
        val out = mutableListOf<W3cTestCase>()
        parseInto(manifestPath.toAbsolutePath().normalize(), visited, out)
        return out
    }

    private fun parseInto(manifest: Path, visited: MutableSet<Path>, out: MutableList<W3cTestCase>) {
        if (!visited.add(manifest)) return
        val baseUri = manifest.toUri().toString()
        val model: Model = ModelFactory.createDefaultModel().also { m ->
            manifest.toFile().inputStream().use { stream ->
                m.read(stream, baseUri, "TURTLE")
            }
        }

        val manifestType = model.createResource(mfManifest)
        val includesProp = model.createProperty(mfInclude)
        val entriesProp = model.createProperty(mfEntries)

        val manifestRoots = model.listResourcesWithProperty(RDF.type, manifestType).toList()
        for (root in manifestRoots) {
            // mf:assumedTestBase applies to every entry in this manifest.
            val assumedBase = uriValue(root, mfAssumedTestBase, model)

            // mf:include - other manifests we should also parse.
            for (incl in root.listProperties(includesProp).toList().flatMap { stmt ->
                rdfListToResources(model, stmt.`object`)
            }) {
                if (incl.isURIResource) {
                    val included = uriToPath(incl.uri, manifest) ?: continue
                    parseInto(included, visited, out)
                }
            }

            // mf:entries - the test list itself.
            for (stmt in root.listProperties(entriesProp).toList()) {
                rdfListToResources(model, stmt.`object`).forEach { entry ->
                    parseEntry(model, entry, manifest, assumedBase)?.let { out.add(it) }
                }
            }
        }
    }

    private fun parseEntry(
        model: Model,
        entry: Resource,
        manifest: Path,
        assumedBase: String?,
    ): W3cTestCase? {
        val typeStmt = entry.listProperties(RDF.type).toList().firstOrNull { it.`object`.isURIResource }
            ?: return null
        val typeIri = typeStmt.`object`.asResource().uri
        val (kind, format) = typeToKindAndFormat[typeIri] ?: return null

        val name = stringValue(entry, mfName, model) ?: entry.uri ?: return null
        val actionUri = uriValue(entry, mfAction, model) ?: return null
        val action = uriToPath(actionUri, manifest) ?: return null

        val resultUri = uriValue(entry, mfResult, model)
        val result = resultUri?.let { uriToPath(it, manifest) }

        val approval = uriValue(entry, rdftApproval, model)
        val approved = approval == null || approval == rdftApproved

        val comment = stringValue(entry, rdfsComment, model)

        return W3cTestCase(
            iri = entry.uri ?: name,
            name = name,
            kind = kind,
            format = format,
            action = action,
            result = result,
            approved = approved,
            comment = comment,
            assumedBaseIri = assumedBase,
        )
    }

    private fun rdfListToResources(model: Model, head: RDFNode): List<Resource> {
        if (!head.isResource) return emptyList()
        val out = mutableListOf<Resource>()
        var node: RDFNode = head
        val first = model.createProperty(RDF.first.uri)
        val rest = model.createProperty(RDF.rest.uri)
        while (node.isResource && node.asResource().uri != RDF.nil.uri && node != RDF.nil) {
            val firstStmt: Statement = node.asResource().getProperty(first) ?: break
            if (firstStmt.`object`.isResource) {
                out.add(firstStmt.`object`.asResource())
            }
            val restStmt: Statement = node.asResource().getProperty(rest) ?: break
            node = restStmt.`object`
        }
        return out
    }

    private fun stringValue(r: Resource, propIri: String, model: Model): String? {
        val prop: Property = model.createProperty(propIri)
        val stmt = r.getProperty(prop) ?: return null
        val obj = stmt.`object`
        return if (obj.isLiteral) obj.asLiteral().lexicalForm else obj.toString()
    }

    private fun uriValue(r: Resource, propIri: String, model: Model): String? {
        val prop: Property = model.createProperty(propIri)
        val stmt = r.getProperty(prop) ?: return null
        val obj = stmt.`object`
        return if (obj.isURIResource) obj.asResource().uri else null
    }

    /**
     * Resolve a manifest-relative `file:` or absolute IRI to an on-disk Path.
     * Returns null if the IRI is not a file IRI we can resolve - we silently
     * skip such tests (they typically reference HTTP-only fragments).
     */
    private fun uriToPath(iri: String, manifest: Path): Path? {
        return try {
            val uri = URI.create(iri)
            val resolved = if (uri.isAbsolute) uri else manifest.toUri().resolve(uri)
            if (resolved.scheme == "file") {
                Path.of(resolved)
            } else {
                null
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
