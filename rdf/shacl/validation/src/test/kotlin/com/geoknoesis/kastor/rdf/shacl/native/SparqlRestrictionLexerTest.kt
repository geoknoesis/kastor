package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.SparqlPreBindingRestrictionException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.string
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The restriction check (no MINUS / SERVICE / VALUES, no AS on a pre-bound variable) must read a query the way
 * the SPARQL parsers of Jena and RDF4J do. Every query below makes at least one of them run SERVICE (checked
 * against Jena 6.2.0 and RDF4J 5.3.2), so each must be rejected when the shapes are compiled.
 */
class SparqlRestrictionLexerTest {
    private val d = "$"
    private val bs = "\\"
    private val service = "SERVICE <http://127.0.0.1:1/s> { ?s ?p ?o }"

    private fun restricted(body: String, prologue: String = "") =
        SparqlQueryTemplate.compileRestricted(prologue, body, setOf("this"), "Test query")

    private fun assertRejected(body: String, prologue: String = "") {
        assertThrows(SparqlPreBindingRestrictionException::class.java, { restricted(body, prologue) }, body)
    }

    // --- the reported bypasses, on all three compile paths -------------------------------------------------------

    private val header = """
        @prefix ex: <http://example.org/> .
        @prefix sh: <http://www.w3.org/ns/shacl#> .
    """.trimIndent()

    private fun compile(ttl: String) =
        ShapesCompiler.compile(Rdf.parse("$header\n$ttl", RdfFormat.TURTLE).getTriples(), ValidationConfig.default())

    /** The three ways a shapes graph runs a SPARQL query, each with [query] as a Turtle `'''` string body. */
    private fun allPaths(query: String) = listOf(
        "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select '''$query''' ] .",
        """
        ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:flag ] ;
          sh:nodeValidator [ a sh:SPARQLSelectValidator ; sh:select '''$query''' ] .
        ex:S a sh:NodeShape ; sh:targetNode ex:a ; ex:flag true .
        """.trimIndent(),
        "ex:S a sh:NodeShape ; sh:targetNode [ sh:select '''$query''' ] ; sh:class ex:C .",
    )

    @Test
    fun `a comment ends at a carriage return`() {
        assertRejected("SELECT ${d}this WHERE { # x\r $service }")
        // In Turtle, \r is an escape, so the literal (the query) holds a real CR character.
        for (shapes in allPaths("SELECT ${d}this WHERE { # x\\r $service }")) {
            assertThrows(SparqlPreBindingRestrictionException::class.java, { compile(shapes) }, shapes)
        }
    }

    @Test
    fun `codepoint escapes are decoded for the check`() {
        assertRejected("SELECT ${d}this WHERE { # x${bs}u000A $service }")
        assertRejected("SELECT ${d}this WHERE { ${bs}u0053ERVICE <http://127.0.0.1:1/s> { ?s ?p ?o } }")
        assertRejected("SELECT ${d}this WHERE { ${bs}U00000053ERVICE <http://127.0.0.1:1/s> { ?s ?p ?o } }")
        assertRejected("SELECT ${d}this WHERE { ${bs}u0056ALUES ${d}this { 1 } }")
        assertRejected("SELECT ?x WHERE { BIND (1 AS ${bs}u003Fthis) }")
        // Turtle \\ is one backslash, so the query holds the six characters \u000A.
        for (shapes in allPaths("SELECT ${d}this WHERE { # x${bs}${bs}u000A $service }")) {
            assertThrows(SparqlPreBindingRestrictionException::class.java, { compile(shapes) }, shapes)
        }
    }

    @Test
    fun `invalid codepoint escapes are ill-formed`() {
        for (escape in listOf("${bs}uZZZZ", "${bs}u12", "${bs}uD800", "${bs}U00110000", "${bs}U0000004")) {
            val e = assertThrows(ShapeCompileException::class.java, { restricted("SELECT ${d}this WHERE { ?s ?p \"$escape\" }") }, escape)
            assertFalse(e is SparqlPreBindingRestrictionException, escape)
        }
    }

    // --- the other lexer divergences ------------------------------------------------------------------------------

    @Test
    fun `keywords glued to a boolean or a number are seen`() {
        // SPARQL reads `true` / `1` / `1.5` / `1e5` and then the keyword: no token spans both.
        for (lead in listOf("true", "false", "1", "1.5", "1e5", "a")) {
            assertRejected("SELECT ${d}this WHERE { ${d}this ?p $lead$service }")
        }
        assertRejected("SELECT ${d}this WHERE { ${d}this ?p 1VALUES ${d}this { 1 } }")
        assertRejected("SELECT ?x WHERE { BIND (1 AS ?x) BIND (2 trueAS ${d}this) }")
    }

    @Test
    fun `an unterminated long string is an empty string and a quote`() {
        assertRejected("SELECT ${d}this WHERE { ${d}this ?p ( \"\"\" \" ) . $service }")
        assertRejected("SELECT ${d}this WHERE { ${d}this ?p ( ''' ' ) . $service }")
    }

    @Test
    fun `prefixed names are read like SPARQL reads them`() {
        val prologue = "PREFIX ex: <http://e/>\n"
        // \' and \# are local-name escapes, not the start of a string or a comment.
        assertRejected("SELECT ${d}this WHERE { ${d}this ?p ex:a$bs' . $service # '\n}", prologue)
        assertRejected("SELECT ${d}this WHERE { ${d}this ?p ex:a$bs# . $service }", prologue)
        // A local name cannot start with '.', so ex:.SERVICE is ex: then . then SERVICE.
        assertRejected("SELECT ${d}this WHERE { ${d}this ?p ex:.$service }", prologue)
    }

    @Test
    fun `a short string ends at a carriage return`() {
        // Both parsers reject the CR inside the string, so the query never runs; the lexer agrees with them.
        val tokens = SparqlLexer.tokenize("\"a\rb\"")
        assertEquals(SparqlToken.Kind.STRING, tokens.first().kind)
        assertEquals("\"a", tokens.first().text)
    }

    // --- no false positives ---------------------------------------------------------------------------------------

    @Test
    fun `legitimate queries are accepted`() {
        val prologue = "PREFIX ex: <http://e/>\nPREFIX values: <http://v/>\n"
        for (body in listOf(
            "SELECT ${d}this WHERE { ${d}this ex:service ex:values . ${d}this values:minus ex:Service }",
            "SELECT ${d}this WHERE { ${d}this ?p ?o . FILTER (REGEX(STR(?o), \"C:${bs}${bs}users\")) }",
            "SELECT ${d}this WHERE { ${d}this ?p \"${bs}${bs}u0041 caf${bs}u00E9\" }",
            "SELECT ${d}this WHERE { # SERVICE in a comment\r\n ${d}this ?p \"\"\"VALUES\"\"\" }",
            "SELECT ${d}this WHERE { ${d}this ?p \"x\"@en ; ex:p 1.5e3 , true , _:b1 . }",
            "SELECT ${d}this (GROUP_CONCAT(?o) AS ?all) WHERE { ${d}this ?p ?o } GROUP BY ${d}this",
        )) {
            restricted(body, prologue)
        }
    }

    @Test
    fun `substituted literals escape single quotes`() {
        val rendered = SparqlQueryTemplate.renderTerm(string("it's"))!!
        assertTrue(rendered.startsWith("\"it${bs}'s\""), rendered)
    }

    private fun ex(local: String) = Iri("http://example.org/$local")

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `non-ASCII strings and CRLF comments still validate`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ;
              sh:sparql [ sh:select '''SELECT ${d}this WHERE { # first comment\r\n  ${d}this <http://example.org/p> ?v . # second\r\n  FILTER (?v = "${'é'}t${'é'}") }''' ] .
        """.trimIndent()
        val data = "ex:a ex:p \"été\" . ex:b ex:p \"ete\" ."
        val report = NativeShaclValidator(ValidationConfig.default()) { repository(provider) }
            .validate(Rdf.parse("$header\n$data", RdfFormat.TURTLE), Rdf.parse("$header\n$shapes", RdfFormat.TURTLE))
        assertEquals(listOf(ex("a")), report.violations.map { it.focusNode })
    }
}
