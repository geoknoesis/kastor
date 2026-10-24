package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.SparqlPreBindingRestrictionException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `sh:prefix` label is written into the query prologue. A label that is not a SPARQL PN_PREFIX could open a
 * string the query body closes inside a comment, smuggling `SERVICE` (an SSRF) past the restriction check on the
 * body. Every SPARQL compile path must reject such a shapes graph before any query is sent.
 */
class SparqlPrefixInjectionTest {
    private val d = "$"

    private val header = """
        @prefix ex: <http://example.org/> .
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
    """.trimIndent()

    /** The prefix label opens `"""` after a SERVICE clause; the body closes it from inside a `#` comment. */
    private val maliciousPrefix =
        "'''ex: <http://a/>\\nSELECT ?x WHERE { SERVICE <http://127.0.0.1:1/sparql> { ?s ?p ?o } BIND (\"\"\"'''"
    private val maliciousBody = "'''SELECT ${d}this WHERE { } # \"\"\" AS ?z) }'''"
    private val declaration = "ex:P sh:declare [ sh:prefix $maliciousPrefix ; sh:namespace \"http://a/\" ] ."

    private fun compile(ttl: String) =
        ShapesCompiler.compile(Rdf.parse("$header\n$declaration\n$ttl", RdfFormat.TURTLE).getTriples(), ValidationConfig.default())

    @Test
    fun `sh sparql constraint with an injecting prefix fails to compile`() {
        assertThrows(ShapeCompileException::class.java) {
            compile("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:prefixes ex:P ; sh:select $maliciousBody ] .")
        }
    }

    @Test
    fun `component validator with an injecting prefix fails to compile`() {
        assertThrows(ShapeCompileException::class.java) {
            compile(
                """
                ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:flag ] ;
                  sh:nodeValidator [ a sh:SPARQLSelectValidator ; sh:prefixes ex:P ; sh:select $maliciousBody ] .
                ex:S a sh:NodeShape ; sh:targetNode ex:a ; ex:flag true .
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `select node expression with an injecting prefix fails to compile`() {
        assertThrows(ShapeCompileException::class.java) {
            compile("ex:S a sh:NodeShape ; sh:targetNode [ sh:prefixes ex:P ; sh:select $maliciousBody ] ; sh:class ex:C .")
        }
    }

    @Test
    fun `restrictions are checked on the full query as well as on the body`() {
        // Defence in depth: even a prologue that slipped past prefix validation cannot smuggle SERVICE.
        val prologue = "PREFIX ex: <http://a/>\nSELECT ?x WHERE { SERVICE <http://127.0.0.1:1/sparql> { ?s ?p ?o } BIND (\"\"\": <http://a/>\n"
        val body = "SELECT ${d}this WHERE { } # \"\"\" AS ?z) }"
        val e = assertThrows(SparqlPreBindingRestrictionException::class.java) {
            SparqlQueryTemplate.compileRestricted(prologue, body, setOf("this"), "Test query")
        }
        assertTrue(e.message!!.contains("SERVICE"), e.message)
        // A harmless prologue adds no false positive.
        SparqlQueryTemplate.compileRestricted("PREFIX values: <http://v/>\nPREFIX : <http://e/>\n", "SELECT ${d}this WHERE { ${d}this values:p :o }", setOf("this"), "Test query")
    }
}
