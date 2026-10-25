package com.geoknoesis.kastor.rdf.shacl.conformance

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpressions
import com.geoknoesis.kastor.rdf.shacl.native.ShapeGraphIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Every `sht:nodeExpr` of the W3C SHACL 1.2 Node Expressions suite parses ([NodeExpressions.compile]). The node-expr
 * files are read next to the root manifest of [Shacl12NativeConformanceTest.manifestRoot]: the upstream checkout
 * (`shnex/` and `shnex-sparql/`, 143 cases), or the bundled fixture (`node-expr/`, 4 cases) when there is none.
 * Tagged `w3c` like the other suite tests, so that `w3cConformanceTest` runs it against the full suite.
 */
@Tag("w3c")
class Shacl12NodeExprParseTest {

    @Test
    fun `every W3C nodeExpr parses`() {
        val root = Shacl12NativeConformanceTest.manifestRoot().parent.resolve("node-expr").toFile()
        check(root.isDirectory) { "no node-expr directory next to the root manifest: $root" }
        val upstream = root.resolve("shnex").isDirectory
        val nodeExpr = Iri("http://www.w3.org/ns/shacl-test#nodeExpr")
        val failures = mutableListOf<String>()
        var cases = 0
        // sparql:hasLang ( "hello"@en "en" ): the §5 table and SPARQL 1.2 HASLANG take one argument.
        val illFormed = setOf("shnex-sparql/hasLang.ttl")
        for (dir in if (upstream) listOf("shnex", "shnex-sparql") else listOf("")) {
            val files = root.resolve(dir).listFiles { f -> f.name.endsWith(".ttl") && f.name != "manifest.ttl" }.orEmpty().sortedBy { it.name }
            for (file in files) {
                val key = if (dir.isEmpty()) file.name else "$dir/${file.name}"
                val triples = Rdf.parse(file.readText(), RdfFormat.TURTLE, "http://example.com/test/$key").getTriples()
                val index = ShapeGraphIndex(triples)
                for (t in triples.filter { it.predicate == nodeExpr }) {
                    cases++
                    val outcome = runCatching { NodeExpressions.compile(t.obj, index) }
                    if (key in illFormed) {
                        if (outcome.exceptionOrNull() !is ShapeCompileException) failures += "$key: expected ill-formed, got $outcome"
                    } else if (outcome.getOrNull() == null) {
                        failures += "$key: ${outcome.exceptionOrNull() ?: "null"}"
                    }
                }
            }
        }
        assertEquals(if (upstream) 143 else 4, cases, "number of W3C sht:nodeExpr cases under $root")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
