package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** SHACL 1.2 SPARQL 3.3.2 and 5: `sh:resultAnnotation` copies solution bindings into result annotations. */
class SparqlResultAnnotationTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
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

    private fun shapes(annotation: String) = """
        ex:S a sh:NodeShape ; sh:targetNode ex:a ;
          sh:sparql [
            sh:message "The message." ;
            sh:select '''SELECT ${'$'}this ?time WHERE { ${'$'}this ?p ?o . BIND ("t" AS ?time) }''' ;
            sh:resultAnnotation [ $annotation ]
          ] .
    """

    private val data = "ex:a ex:p 1 ."

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `annotation copies a binding by var name`(provider: String) {
        val v = validate(provider, data, shapes("""sh:annotationProperty ex:time ; sh:annotationVarName "time" """)).violations.single()
        assertEquals(mapOf(ex("time") to listOf(string("t"))), v.resultAnnotations)
        assertEquals("The message.", v.resultMessages.single().lexical)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `annotation var name defaults to the property local name`(provider: String) {
        val v = validate(provider, data, shapes("sh:annotationProperty ex:time")).violations.single()
        assertEquals(mapOf(ex("time") to listOf(string("t"))), v.resultAnnotations)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `unbound annotation variable falls back to annotationValue`(provider: String) {
        val v = validate(provider, data, shapes("sh:annotationProperty ex:other ; sh:annotationValue 42")).violations.single()
        assertEquals(mapOf(ex("other") to listOf(TypedLiteral("42", XSD.integer))), v.resultAnnotations)
    }

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `annotation property must be a single IRI`(provider: String) {
        // The validator wraps a shapes-graph compile failure in a ShaclValidationException.
        for (annotation in listOf("sh:annotationVarName \"time\"", "sh:annotationProperty \"time\"")) {
            val e = assertThrows(ShaclValidationException::class.java) { validate(provider, data, shapes(annotation)) }
            assertTrue(e.cause is ShapeCompileException, "cause was ${e.cause}")
        }
    }
}
