package com.geoknoesis.kastor.rdf.shacl.conformance

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.booleanValue
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.shacl.providers.NodeExprOutcome
import com.geoknoesis.kastor.rdf.shacl.providers.evaluateNodeExpression
import com.geoknoesis.kastor.rdf.vocab.RDF
import java.nio.file.Path
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory

/**
 * The `sht:EvalNodeExpr` cases of the W3C SHACL 1.2 Node Expressions suite (`node-expr/README.md`) against the native
 * evaluator. Cases are collected from the same root manifest as [Shacl12NativeConformanceTest] (the upstream
 * checkout, or the bundled fixture). Each case evaluates `sht:nodeExpr` with `sht:focusNode` as the focus node and
 * every `sht:scope-<name>` value as scope variable `name`; the test file is the focus graph and the shapes graph. The
 * output nodes must equal the `mf:result` list under RDF term equality, as a multiset when `sht:ignoreOrder true` is
 * given (on the action or the entry).
 *
 * Every case must pass, except the cases in [W3cNodeExprIllFormed], whose expression the native engine rejects as
 * ill-formed. Tagged `w3c` like [Shacl12NativeConformanceTest]; with `shacl.w3c.requireSuite=true` the run must cover the
 * `shnex/` and `shnex-sparql/` manifests of the full suite.
 */
@Tag("w3c")
class Shacl12NodeExprConformanceTest {

    @TestFactory
    fun `SHACL 1 2 node expression W3C manifests native`(): Stream<DynamicNode> {
        val manifest = Shacl12NativeConformanceTest.manifestRoot()
        val cases = Shacl12ManifestParser.collect(manifest, Shacl12ManifestParser.EVAL_NODE_EXPR)
        val files = cases.map { slashed(it.manifestPath) }
        if (System.getProperty("shacl.w3c.requireSuite") == "true") {
            val missing = listOf("/node-expr/shnex/", "/node-expr/shnex-sparql/").filter { part -> files.none { it.contains(part) } }
            check(missing.isEmpty()) { "shacl.w3c.requireSuite=true but $manifest has no sht:EvalNodeExpr cases under ${missing.joinToString()}" }
        }
        if (cases.isEmpty()) {
            return Stream.of(DynamicTest.dynamicTest("no sht EvalNodeExpr entries") { fail<Unit>("no sht:EvalNodeExpr tests under $manifest") })
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
                        pathCases.sortedBy { it.displayName }.map { case ->
                            DynamicTest.dynamicTest(case.displayName) {
                                if (!case.approved && !includeNonApproved) Assumptions.assumeTrue(false, "not approved: ${case.entryUri}")
                                runCase(case)
                            }
                        },
                    )
                }
        // Against the upstream suite, every ill-formed entry must still name cases (a stale entry hides nothing, but
        // misleads). The bundled fixture has none of the upstream files, and gets no such test (it would only skip).
        if (files.none { it.contains("/node-expr/shnex") }) return containers.stream()
        val stale = DynamicTest.dynamicTest("ill-formed entries name existing cases") {
            val unusedIllFormed = W3cNodeExprIllFormed.entries.filter { key -> files.none { it.endsWith("/$key") } }
            assertTrue(unusedIllFormed.isEmpty(), "W3cNodeExprIllFormed entries without cases: $unusedIllFormed")
        }
        return (containers + stale).stream()
    }

    private fun runCase(case: ShaclValidateCase) {
        val graph = loadW3cGraph(case.manifestPath)
        val entry = Iri(case.entryUri)
        val action = single(graph, entry, MF_ACTION) as? RdfResource ?: error("mf:action missing for ${case.entryUri}")
        val expr = single(graph, action, SHT_NODE_EXPR) ?: error("sht:nodeExpr missing for ${case.entryUri}")
        val focus = single(graph, action, SHT_FOCUS_NODE)
        val variables = graph.find(action, null, null)
            .filter { it.predicate.value.startsWith(SHT_SCOPE) }
            .associate { it.predicate.value.removePrefix(SHT_SCOPE) to it.obj }
        val ignoreOrder = listOf(action, entry).any { (single(graph, it, SHT_IGNORE_ORDER) as? Literal)?.booleanValue() == true }
        val expected = rdfList(graph, single(graph, entry, MF_RESULT) ?: error("mf:result missing for ${case.entryUri}"))

        val label = case.displayName
        W3cNodeExprIllFormed.reasonFor(case.manifestPath)?.let { why ->
            assertThrows(ShapeCompileException::class.java, {
                NativeShaclValidator(ValidationConfig.default()).evaluateNodeExpression(graph, expr, focus, variables)
            }, "$label: the expression is ill-formed ($why)")
            return
        }
        val outcome = NativeShaclValidator(ValidationConfig.default()).evaluateNodeExpression(graph, expr, focus, variables)
        assertTrue(outcome is NodeExprOutcome.Nodes, "$label: expected output nodes $expected, got $outcome")
        val actual = (outcome as NodeExprOutcome.Nodes).nodes
        if (ignoreOrder) {
            assertEquals(expected.groupingBy { it }.eachCount(), actual.groupingBy { it }.eachCount(), "$label: output nodes (ignoring order)")
        } else {
            assertEquals(expected, actual, "$label: output nodes")
        }
    }

    private fun single(graph: RdfGraph, subject: RdfResource, predicate: Iri): RdfTerm? {
        val values = graph.find(subject, predicate, null)
        check(values.size <= 1) { "$subject has more than one ${predicate.value}" }
        return values.singleOrNull()?.obj
    }

    private fun rdfList(graph: RdfGraph, head: RdfTerm): List<RdfTerm> {
        val out = ArrayList<RdfTerm>()
        var node = head
        while (node != RDF.nil) {
            val cell = node as? RdfResource ?: error("mf:result is not an RDF list: $head")
            out.add(single(graph, cell, RDF.first) ?: error("mf:result list cell without rdf:first: $cell"))
            node = single(graph, cell, RDF.rest) ?: error("mf:result list cell without rdf:rest: $cell")
        }
        return out
    }

    private companion object {
        const val MF = "http://www.w3.org/2001/sw/DataAccess/tests/test-manifest#"
        const val SHT = "http://www.w3.org/ns/shacl-test#"
        val MF_ACTION = Iri(MF + "action")
        val MF_RESULT = Iri(MF + "result")
        val SHT_NODE_EXPR = Iri(SHT + "nodeExpr")
        val SHT_FOCUS_NODE = Iri(SHT + "focusNode")
        val SHT_IGNORE_ORDER = Iri(SHT + "ignoreOrder")
        const val SHT_SCOPE = SHT + "scope-"
    }
}

private fun slashed(path: Path): String = path.toString().replace(java.io.File.separatorChar, '/')

/**
 * W3C `sht:EvalNodeExpr` cases whose expression is ill-formed under Node Expressions §5, by case file, with the reason.
 * They must be rejected with [ShapeCompileException], so a fix of the test (or of the engine) does not go unnoticed.
 */
internal object W3cNodeExprIllFormed {
    private val illFormed: Map<String, String> = linkedMapOf(
        // The case passes two arguments ( "hello"@en "en" ); the §5 table example and SPARQL 1.2 HASLANG take one.
        "node-expr/shnex-sparql/hasLang.ttl" to "sparql:hasLang takes one argument (§5 table, SPARQL 1.2 HASLANG)",
    )

    val entries: Set<String> get() = illFormed.keys

    fun reasonFor(manifestPath: Path): String? {
        val file = slashed(manifestPath)
        return illFormed.entries.firstOrNull { (key, _) -> file.endsWith("/$key") }?.value
    }
}
