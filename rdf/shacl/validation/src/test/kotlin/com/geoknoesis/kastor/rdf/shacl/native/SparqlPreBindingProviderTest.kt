package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * SHACL-SPARQL pre-binding is performed by the SHACL engine itself, so Jena- and RDF4J-backed evaluation must give
 * identical results (including sub-selects and blank node focus nodes).
 */
class SparqlPreBindingProviderTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
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

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `pre-binding reaches a nested SELECT star`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:bad , ex:good ;
              sh:sparql [ sh:select '''SELECT ${'$'}this WHERE { { SELECT * WHERE { FILTER (${'$'}this = <http://example.org/bad>) } } }''' ] .
        """
        assertEquals(listOf(ex("bad")), validate(provider, "ex:bad ex:p 1 . ex:good ex:p 1 .", shapes).violations.map { it.focusNode })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `pre-binding reaches a sub-select projecting this`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:bad , ex:good ;
              sh:sparql [ sh:select '''SELECT ${'$'}this WHERE { { SELECT ${'$'}this WHERE { FILTER (${'$'}this = <http://example.org/bad>) } } }''' ] .
        """
        assertEquals(listOf(ex("bad")), validate(provider, "ex:bad ex:p 1 . ex:good ex:p 1 .", shapes).violations.map { it.focusNode })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `blank node focus nodes are pre-bound`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:p ;
              sh:sparql [ sh:select '''SELECT ${'$'}this ?value WHERE { ${'$'}this <http://example.org/p> ?value . FILTER (?value > 1) }''' ] .
        """
        val v = validate(provider, "[] ex:p 1 . [] ex:p 2 .", shapes).violations.single()
        assertTrue(v.focusNode is BlankNode)
        assertEquals("2", (v.value as Literal).lexical)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `PATH and pre-bound names inside strings and comments are left alone`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:sparql [ sh:select '''
                SELECT ${'$'}this ?value WHERE {
                  ${'$'}this ${'$'}PATH ?value . # ${'$'}PATH MINUS VALUES
                  FILTER (?value != "${'$'}PATH" && ?value != "${'$'}this MINUS SERVICE VALUES")
                }''' ] ] .
        """
        val report = validate(provider, "ex:a ex:p \"${'$'}PATH\" , \"${'$'}this MINUS SERVICE VALUES\" , \"x\" .", shapes)
        assertEquals(listOf("x"), report.violations.map { (it.value as Literal).lexical })
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `message binding takes precedence over sh message`(provider: String) {
        val shapes = """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql [ sh:message "static" ; sh:select '''SELECT ${'$'}this ("dynamic" AS ?message) WHERE { ${'$'}this ?p ?o }''' ] .
        """
        assertEquals("dynamic", validate(provider, "ex:a ex:p 1 .", shapes).violations.single().message)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `VALUES MINUS and AS rebinding of pre-bound variables are rejected`(provider: String) {
        for (body in listOf("VALUES ?any { true }", "MINUS { ${'$'}this ex:p 1 }", "BIND (true AS ${'$'}this)")) {
            val shapes = "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select '''SELECT ${'$'}this WHERE { $body }''' ] ."
            assertThrows(ShaclValidationException::class.java, { validate(provider, "ex:a ex:p 1 .", shapes) }, body)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `validateDataset on a repository queries it in place without copying`(provider: String) {
        val repo = repository(provider)
        repo.use {
            it.transaction { editDefaultGraph().addTriples(g("ex:a ex:p 1 . ex:b ex:p 2 .").getTriples()) }
            val shapes = """
                ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:p ;
                  sh:sparql [ sh:select '''SELECT ${'$'}this ?value WHERE { ${'$'}this <http://example.org/p> ?value . FILTER (?value > 1) }''' ] .
            """
            val validator = NativeShaclValidator(ValidationConfig.default()) { error("the data graph must not be copied") }
            assertEquals(listOf(ex("b")), validator.validateDataset(it, g(shapes)).violations.map { v -> v.focusNode })
        }
    }
}
