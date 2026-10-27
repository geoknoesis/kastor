package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.shacl.providers.NodeExprOutcome
import com.geoknoesis.kastor.rdf.shacl.providers.evaluateNodeExpression
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * How pre-bound values are written into a query: a language tag is checked in linear time (a data literal with a very
 * long tag must not overflow the stack and abort the run), and a pre-bound variable of the outermost projection keeps
 * its name, so `SELECT $this` outputs the focus node whatever its kind (SHACL 1.2 SPARQL Appendix A: pre-binding is a
 * join with the solution that binds it).
 */
class SparqlPreBoundRenderingTest {

    private val d = "$"

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <http://example.org/> .
    """.trimIndent()

    private fun g(ttl: String) = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    private fun validator(provider: String) = NativeShaclValidator(ValidationConfig.default()) { repository(provider) }

    /** A language tag of 100,000 subtags (300,000 characters): a regex with a repeated group overflows the stack on it. */
    private val longTag = "en" + "-ab".repeat(100_000)

    private val ex = "http://example.org/"

    private fun dataWithLongTag() = Rdf.graph { }.also {
        val a = Iri("${ex}a")
        it.addTriple(RdfTriple(a, Iri("${ex}p"), LangString("x", longTag)))
        it.addTriple(RdfTriple(a, Iri("${ex}p"), TypedLiteral("y", XSD.string)))
    }

    @Test
    fun `a language tag is checked in linear time and a long one is not written into the text`() {
        assertEquals("SELECT * WHERE { ?s ?p \"x\"@en-GB-1996  }", SparqlQueryTemplate.compile("SELECT * WHERE { ?s ?p ${d}this }", setOf("this"))
            .bind(mapOf("this" to LangString("x", "en-GB-1996"))).text)
        assertNull(SparqlQueryTemplate.renderTerm(LangString("x", longTag)))
        assertTrue(SparqlQueryTemplate.isRenderableLanguageTag("a".repeat(SparqlQueryTemplate.MAX_RENDERED_LANGUAGE_TAG)))
        assertFalse(SparqlQueryTemplate.isRenderableLanguageTag("a".repeat(SparqlQueryTemplate.MAX_RENDERED_LANGUAGE_TAG + 1)))
        for (bad in listOf("", "-en", "en-", "en--GB", "e n", "en_GB", "1en", "en-Gé")) {
            assertFalse(SparqlQueryTemplate.isRenderableLanguageTag(bad), bad)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a pre-bound value with a very long language tag is validated`(provider: String) {
        val shapes = g(
            """
            ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:tagged ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:ask '''ASK { FILTER (STRLEN(LANG(${d}value)) > 0) }''' ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; ex:tagged true ] .
            """,
        )
        val report = validator(provider).validate(dataWithLongTag(), shapes)
        assertEquals(listOf("y"), report.violations.map { (it.value as Literal).lexical })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a pre-bound focus node with a very long language tag is validated`(provider: String) {
        val shapes = g(
            """
            ex:T a sh:NodeShape ; sh:sparql [ sh:select '''SELECT ?x WHERE { FILTER (LANG(${d}this) = "") BIND (1 AS ?x) }''' ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:node ex:T ] .
            """,
        )
        val report = validator(provider).validate(dataWithLongTag(), shapes)
        assertEquals(listOf("y"), report.violations.map { (it.value as Literal).lexical })
    }

    @Test
    fun `a pre-bound variable of the outermost projection keeps its name, nested ones get a fresh one`() {
        val template = SparqlQueryTemplate.compile(
            "SELECT ${d}this ?n WHERE { { SELECT ${d}this (COUNT(*) AS ?n) WHERE { ${d}this ?p ?o } GROUP BY ${d}this } }",
            setOf("this"),
        )
        val text = template.bind(mapOf("this" to Iri("${ex}a"))).text
        assertTrue(text.startsWith("SELECT (<${ex}a> AS ?this) ?n WHERE"), text)
        assertTrue(text.contains("{ SELECT (<${ex}a> AS ?__kastor_prebound_"), text)
        // Projected twice: only the first keeps the name (a second `AS ?this` would be an error).
        val twice = SparqlQueryTemplate.compile("SELECT ${d}this ${d}this WHERE {}", setOf("this")).bind(mapOf("this" to Iri("${ex}a"))).text
        assertEquals(1, Regex("AS \\?this\\b").findAll(twice).count(), twice)
    }

    private fun projectsFocus(provider: String, query: String, focus: RdfTerm): List<RdfTerm> {
        val graph = g("ex:holder ex:expr [ sh:select '''$query''' ] .")
        val node = graph.find(Iri("${ex}holder"), Iri("${ex}expr"), null).single().obj
        val outcome = validator(provider).evaluateNodeExpression(graph, node, focus, emptyMap())
        return (outcome as? NodeExprOutcome.Nodes)?.nodes ?: error("expected nodes, got $outcome")
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a SPARQL node expression that selects this outputs the focus node of every kind`(provider: String) {
        val foci = listOf(
            Iri("${ex}a"),
            TypedLiteral("42", XSD.integer),
            LangString("chat", "fr"),
            BlankNode("b1"),
        )
        for (focus in foci) {
            assertEquals(listOf(focus), projectsFocus(provider, "SELECT ${d}this WHERE { }", focus), "focus $focus")
            assertEquals(listOf(focus), projectsFocus(provider, "SELECT DISTINCT ${d}this WHERE { FILTER (true) }", focus), "focus $focus")
        }
    }
}
