package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.shacl.providers.NodeExprOutcome
import com.geoknoesis.kastor.rdf.shacl.providers.evaluateNodeExpression
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Pre-bound values are written into the query text as RDF term syntax. SPARQL lets an engine decode `\uXXXX` and
 * `\UXXXXXXXX` over the whole query text before tokenizing it (RDF4J does, honouring escaped backslashes; Jena does
 * not), so a data value holding a backslash sequence must not be able to leave the term it is written in once
 * decoded: not to change the query's result, not to reach SERVICE (127.0.0.1:1 is unreachable, so a query that got
 * there would fail), and not under a naive pre-pass that decodes every `\u` whatever precedes it either.
 */
class SparqlPreBindingEscapeTest {

    private val d = "$"

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
        @prefix ex: <http://example.org/> .
    """.trimIndent()

    private fun g(ttl: String) = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    private fun validator(provider: String) = NativeShaclValidator(ValidationConfig.default()) { repository(provider) }

    private fun validate(provider: String, data: String, shapes: String) = validator(provider).validate(g(data), g(shapes))

    /** [s] as a Turtle string literal. */
    private fun ttl(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** Lexical forms that would break out of a `"..."` term if the query text were decoded after rendering. */
    private val payloads = listOf(
        "x\\u0022) || (\\u0022a\\u0022 = \\u0022a",
        "x\\U00000022) || (\\U00000022a\\U00000022 = \\U00000022a",
        "x\\u0022)) SERVICE <http://127.0.0.1:1/x> { ?s ?p ?o } #",
        "x\\\\u0022) || (true",
        "x\\\\\\u0022) || (true",
        "\\u005C\\u0022 \\u0027 \\u003E \\u000A",
        "\\uD800 \\u12 \\U0011FFFF \\uuuu0022",
        "trailing \\",
        "plain \" and ' quotes",
    )

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a pre-bound literal reaches the query unchanged`(provider: String) {
        val data = payloads.flatMapIndexed { i, p ->
            listOf("ex:a$i ex:p ${ttl(p)} .", "ex:b$i ex:p ${ttl(p)}@en .", "ex:c$i ex:p ${ttl(p)}^^ex:dt .")
        }.joinToString("\n")
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetObjectsOf ex:p ;
              sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { BIND (CONCAT(STR(${d}this), "|", LANG(${d}this), "|", STR(DATATYPE(${d}this))) AS ?value) }''' ] .
        """
        val got = validate(provider, data, shapes).violations.map { (it.value as Literal).lexical }.toSet()
        val expected = payloads.flatMap {
            listOf("$it||${XSD.string.value}", "$it|en|http://www.w3.org/1999/02/22-rdf-syntax-ns#langString", "$it||http://example.org/dt")
        }.toSet()
        assertEquals(expected, got)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a pre-bound focus node cannot make a FILTER always true or always false`(provider: String) {
        // Decoded, the first payload reads FILTER (STR("x") || ("a" = "a") = "bad"): a violation for a good node.
        val alwaysTrue = "x\\u0022) || (\\u0022a\\u0022 = \\u0022a"
        val alwaysTrue8 = "x\\U00000022) || (\\U00000022a\\U00000022 = \\U00000022a"
        val bad = validate(
            provider,
            "ex:a ex:p ${ttl(alwaysTrue)} , ${ttl(alwaysTrue8)} , \"bad\" .",
            "ex:S a sh:NodeShape ; sh:targetObjectsOf ex:p ; sh:sparql [ sh:select '''SELECT ${d}this WHERE { FILTER (STR(${d}this) = \"bad\") }''' ] .",
        )
        assertEquals(listOf("bad"), bad.violations.map { (it.focusNode as Literal).lexical })
        // Decoded, this one reads FILTER (STR("x") = "y" && STR("ok") != "ok"): no violation for a non-"ok" node.
        val alwaysFalse = "x\\u0022) = \\u0022y\\u0022 && STR(\\u0022ok"
        val skipped = validate(
            provider,
            "ex:a ex:p ${ttl(alwaysFalse)} , \"ok\" .",
            "ex:S a sh:NodeShape ; sh:targetObjectsOf ex:p ; sh:sparql [ sh:select '''SELECT ${d}this WHERE { FILTER (STR(${d}this) != \"ok\") }''' ] .",
        )
        assertEquals(listOf(alwaysFalse), skipped.violations.map { (it.focusNode as Literal).lexical })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a pre-bound focus node cannot reach SERVICE`(provider: String) {
        // Decoded, line 2 reads FILTER (STR("x")) SERVICE <http://127.0.0.1:1/x> { ?s ?p ?o } #..., a valid query.
        // (Not projecting $this keeps line 1 free of the value.)
        val payload = "x\\u0022)) SERVICE <http://127.0.0.1:1/x> { ?s ?p ?o } #"
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetObjectsOf ex:p ;
              sh:sparql [ sh:select '''SELECT ?s WHERE {
                FILTER (STR(${d}this) = "bad")
              }''' ] .
        """
        assertTrue(validate(provider, "ex:a ex:p ${ttl(payload)} .", shapes).isValid)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a pre-bound value node of a SPARQL component cannot change the result`(provider: String) {
        // Decoded: FILTER (STR("x") || ("ok") = "ok"), true for every value node.
        val payload = "x\\u0022) || (\\u0022ok"
        val shapes = """
            ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:expected ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:ask '''ASK { FILTER (STR(${d}value) = ${d}expected) }''' ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; ex:expected "ok" ] .
        """
        val report = validate(provider, "ex:a ex:p ${ttl(payload)} , \"ok\" .", shapes)
        assertEquals(listOf(payload), report.violations.map { (it.value as Literal).lexical })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a custom SPARQL function argument reaches the body unchanged`(provider: String) {
        val shapes = """
            ex:echo a sh:ListParameterExpressionFunction ; rdfs:subClassOf sh:ListParameterExpression ;
              sh:parameter [ sh:path shnex:arg0 ] ;
              sh:bodyExpression [ sh:select "SELECT ?r WHERE { BIND (STR(${d}arg0) AS ?r) }" ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:select '''SELECT ${d}this ?value WHERE { ${d}this <http://example.org/p> ?o . BIND (<http://example.org/echo>(?o) AS ?value) }''' ] .
        """
        val data = "ex:a ex:p " + payloads.joinToString(" , ") { ttl(it) } + " ."
        val got = validate(provider, data, shapes).violations.map { (it.value as Literal).lexical }.toSet()
        assertEquals(payloads.toSet(), got)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `shnex sum inputs cannot add values to the VALUES block`(provider: String) {
        // Decoded: VALUES ?v { "1"^^xsd:integer 1000 } } #..., a sum of 1001 instead of an error on ex:dt.
        val payload = "1\\u0022^^<http://www.w3.org/2001/XMLSchema#integer> 1000 } } #"
        val graph = g("ex:holder ex:expr [ shnex:sum ( ${ttl(payload)}^^ex:dt ) ] .")
        val node = graph.find(Iri("http://example.org/holder"), Iri("http://example.org/expr"), null).single().obj
        val outcome = validator(provider).evaluateNodeExpression(graph, node, null, emptyMap())
        assertTrue(outcome is NodeExprOutcome.Failure, "expected a SUM error, got $outcome")
    }

    // --- rendering ---------------------------------------------------------------------------------------------------

    /**
     * The rendered text decodes to itself under a Java-style pre-pass (RDF4J), and to the same text under a naive one
     * (which decodes every well-formed `\u` / `\U` whatever precedes it), so no reading of the text can move a term
     * boundary. Without a pre-pass (Jena) the text is used as rendered.
     */
    @Test
    fun `rendered literals are stable under every codepoint pre-pass`() {
        for (p in payloads + listOf("\\u", "\\U", "\\\\u0041", "u\\", "\\\u0001u")) {
            for (term in listOf(TypedLiteral(p, XSD.string), LangString(p, "en"), TypedLiteral(p, Iri("http://example.org/dt")))) {
                val rendered = SparqlQueryTemplate.renderTerm(term)
                assertNotNull(rendered, "$term is written into the text")
                val javaStyle = SparqlQueryTemplate.decodeCodepointEscapes(rendered!!, strict = true)
                val naive = SparqlQueryTemplate.decodeCodepointEscapes(rendered, strict = false)
                assertEquals(javaStyle, naive, "rendering of $term under a naive pre-pass")
                // The term still starts and ends where it did: one string, no unescaped quote inside it.
                val body = javaStyle.substring(1, javaStyle.lastIndexOf('"'))
                assertTrue(Regex("""(?<!\\)(\\\\)*"""").find(body) == null, "unescaped quote in $javaStyle")
            }
        }
    }

    @Test
    fun `IRIs with a backslash are never written into the text`() {
        assertNull(SparqlQueryTemplate.renderIri("http://example.org/\\u003E"))
        assertNull(SparqlQueryTemplate.renderIri("http://example.org/a\\b"))
    }
}
