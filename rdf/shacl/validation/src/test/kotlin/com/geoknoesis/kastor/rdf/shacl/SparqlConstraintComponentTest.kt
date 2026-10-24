package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.lang
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** SHACL 1.2 SPARQL 4.3: validation with SPARQL-based constraint components (ASK and SELECT validators). */
class SparqlConstraintComponentTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex: <http://example.org/> .
    """.trimIndent()

    private fun g(ttl: String) = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.org/$local")

    private fun repository(provider: String): RdfRepository =
        when (provider) {
            "jena" -> JenaProvider().createRepository("memory", RdfConfig())
            else -> Rdf4jProvider().createRepository("memory", RdfConfig())
        }

    private fun validate(provider: String, data: String, shapes: String) =
        NativeShaclValidator(ValidationConfig.default()) { repository(provider) }.validate(g(data), g(shapes))

    private val d = "$"

    private val languageComponent = """
        ex:LanguageConstraintComponentUsingSELECT a sh:ConstraintComponent ;
          sh:parameter [ sh:path ex:lang ; sh:datatype xsd:string ; sh:name "language" ] ;
          sh:propertyValidator [
            a sh:SPARQLSelectValidator ;
            sh:message "Values are literals with language \"{?lang}\"" ;
            sh:select '''SELECT DISTINCT ${d}this ?value WHERE {
              ${d}this ${d}PATH ?value .
              FILTER (!isLiteral(?value) || !langMatches(lang(?value), ${d}lang)) }''' ] .
        ex:LanguageExampleShape a sh:NodeShape ; sh:targetClass ex:Country ;
          sh:property [ sh:path ex:germanLabel ; ex:lang "de" ] .
    """

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `select property validator reports spec example 7`(provider: String) {
        val report = validate(provider, """ex:Bad a ex:Country ; ex:germanLabel "x"@en . ex:Good a ex:Country ; ex:germanLabel "y"@de .""", languageComponent)
        val v = report.violations.single()
        assertEquals(ex("Bad"), v.focusNode)
        assertEquals(lang("x", "en"), v.value)
        assertEquals(listOf(ex("germanLabel")), v.path)
        assertEquals(ex("LanguageConstraintComponentUsingSELECT").value, v.sourceConstraintComponent)
        assertEquals("Values are literals with language \"de\"", v.resultMessages.single().lexical)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `ask validator on a node shape uses the focus node as value`(provider: String) {
        val shapes = """
            ex:C a sh:ConstraintComponent ;
              sh:parameter [ sh:path ex:test1 ] , [ sh:path ex:test2 ] ;
              sh:validator [ a sh:SPARQLAskValidator ;
                sh:ask '''ASK { FILTER (?value = CONCAT(${d}test1, ${d}test2)) }''' ] .
            ex:S a sh:NodeShape ; ex:test1 "Hello " ; ex:test2 "World" ;
              sh:targetNode "Hallo Welt" , "Hello World" .
        """
        val v = validate(provider, "", shapes).violations.single()
        assertEquals("Hallo Welt", (v.value as Literal).lexical)
        assertEquals("Hallo Welt", (v.focusNode as Literal).lexical)
        assertEquals(ex("C").value, v.sourceConstraintComponent)
        assertTrue(v.message.contains(ex("C").value), v.message)
        assertFalse(v.message.contains("null"), v.message)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `ASK validator flags a blank-node value node on both providers`(provider: String) {
        val shapes = """
            ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:flag ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:ask '''ASK { FILTER (isBlank(${d}value)) }''' ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; ex:flag true ] .
        """
        // The ASK is true only for a bound blank node: an unbound $value would flag both value nodes.
        val v = validate(provider, "ex:a ex:p [] , ex:b .", shapes).violations.single()
        assertEquals(ex("b"), v.value)
        assertEquals(ex("a"), v.focusNode)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `optional parameter left unbound`(provider: String) {
        val shapes = """
            ex:C a sh:ConstraintComponent ;
              sh:parameter [ sh:path ex:optionalParam ; sh:optional true ] , [ sh:path ex:requiredParam ] ;
              sh:validator [ a sh:SPARQLAskValidator ;
                sh:ask '''ASK { FILTER (${d}value != ${d}requiredParam && ${d}value != COALESCE(?optionalParam, "Three")) }''' ] .
            ex:S1 a sh:NodeShape ; ex:requiredParam "One" ; sh:targetNode "One" , "Two" , "Three" .
            ex:S2 a sh:NodeShape ; ex:optionalParam "Two" ; ex:requiredParam "One" ; sh:targetNode "One" , "Two" , "Three" .
        """
        val got = validate(provider, "", shapes).violations
            .map { Triple((it.focusNode as Literal).lexical, (it.value as Literal).lexical, it.shapeUri) }.toSet()
        assertEquals(setOf(
            Triple("One", "One", ex("S1").value), Triple("One", "One", ex("S2").value),
            Triple("Three", "Three", ex("S1").value), Triple("Two", "Two", ex("S2").value),
        ), got)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `failure binding in a select validator fails validation`(provider: String) {
        val shapes = """
            ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:flag ] ;
              sh:nodeValidator [ a sh:SPARQLSelectValidator ; sh:select '''SELECT ${d}this (true AS ?failure) {}''' ] .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; ex:flag true .
        """
        assertThrows(ShaclValidationException::class.java) { validate(provider, "ex:a ex:p 1 .", shapes) }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `conformance check stops at the first result`(provider: String) {
        val shapes = """
            ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:flag ] ;
              sh:nodeValidator [ a sh:SPARQLSelectValidator ; sh:select '''SELECT ${d}this WHERE { ${d}this ?p ?o }''' ] .
            ex:Inner a sh:NodeShape ; ex:flag true .
            ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:not ex:Inner .
        """
        val report = validate(provider, "ex:a ex:p 1 , 2 .", shapes)
        // Two solutions: Inner is violated, so sh:not holds (the conformance check stops at the first one).
        assertTrue(report.isValid)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `component inside sh not reports a violation when the validator finds nothing`(provider: String) {
        val shapes = """
            ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:flag ] ;
              sh:validator [ a sh:SPARQLAskValidator ; sh:ask '''ASK { FILTER (${d}value = <http://example.org/ok>) }''' ] .
            ex:Inner a sh:NodeShape ; ex:flag true .
            ex:S a sh:NodeShape ; sh:targetNode ex:ok , ex:other ; sh:not ex:Inner .
        """
        // ex:ok satisfies Inner (the ASK is true), so sh:not fails for it; ex:other violates Inner, so sh:not holds.
        assertEquals(listOf(ex("ok")), validate(provider, "", shapes).violations.map { it.focusNode })
    }
}
