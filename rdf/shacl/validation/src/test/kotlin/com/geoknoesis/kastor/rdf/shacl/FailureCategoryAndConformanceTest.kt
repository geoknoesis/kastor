package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The engine decides `sh:conforms` under `sh:conformanceDisallows`, and setup failures carry a machine-checkable
 * category instead of only a message.
 */
class FailureCategoryAndConformanceTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
    """.trimIndent()

    private fun g(ttl: String): RdfGraph = Rdf.parse(prefixes + "\n" + ttl, RdfFormat.TURTLE)
    private fun ex(local: String) = Iri("http://example.org/$local")
    private val data by lazy { g("ex:a ex:q 1 .") }
    private fun shapes(severity: String) =
        g("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:severity $severity ; sh:minCount 1 ] .")
    private fun validate(config: ValidationConfig, shapes: RdfGraph) = NativeShaclValidator(config).validate(data, shapes)

    @Test fun `conformanceDisallows decides conformance in the engine and in the exported report`() {
        assertFalse(validate(ValidationConfig(), shapes("sh:Warning")).isValid)
        val onlyViolations = ValidationConfig(conformanceDisallows = setOf(SHACL.Violation))
        val report = validate(onlyViolations, shapes("sh:Warning"))
        assertTrue(report.isValid)
        assertEquals(1, report.violations.size)
        val exported = report.toShaclValidationReportRdf(Iri("urn:x-test:report")).getTriples().single { it.predicate == SHACL.conforms }.obj
        assertEquals("true", (exported as Literal).lexical)

        assertTrue(validate(ValidationConfig(), shapes("sh:Debug")).isValid)
        assertFalse(validate(ValidationConfig(conformanceDisallows = setOf(SHACL.Debug)), shapes("sh:Debug")).isValid)
        assertTrue(validate(onlyViolations, shapes("ex:Custom")).isValid)
        assertFalse(validate(ValidationConfig(conformanceDisallows = setOf(ex("Custom"))), shapes("ex:Custom")).isValid)
    }

    private fun failure(shapesTtl: String): ShaclValidationException =
        assertThrows(ShaclValidationException::class.java) { validate(ValidationConfig(), g(shapesTtl)) }

    private inline fun <reified T : Throwable> Throwable.causeOfType(): T? =
        generateSequence(this) { it.cause }.filterIsInstance<T>().firstOrNull()

    @Test fun `unsupported features fail with their category`() {
        val cases = listOf(
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:expression [ sh:path ex:q ] ." to UnsupportedShaclFeature.NODE_EXPRESSION,
            "ex:S a sh:NodeShape ; sh:targetNode [ sh:select \"SELECT ?x WHERE { ?x ?p ?o }\" ] ; sh:class ex:Q ." to
                UnsupportedShaclFeature.SPARQL_NODE_EXPRESSION,
            "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:target [ a ex:CustomTarget ] ." to UnsupportedShaclFeature.CUSTOM_TARGET,
        )
        for ((shapesTtl, category) in cases) {
            val unsupported = failure(shapesTtl).causeOfType<UnsupportedShaclFeatureException>()
            assertEquals(setOf(category), unsupported?.features, shapesTtl)
        }
    }

    @Test fun `SPARQL pre-binding restrictions fail with their own category`() {
        val error = failure("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:select '''SELECT ${'$'}this WHERE { VALUES ?x { 1 } }''' ] .")
        assertTrue(error.causeOfType<SparqlPreBindingRestrictionException>() != null, error.toString())
        assertTrue(error.causeOfType<UnsupportedShaclFeatureException>() == null)
    }
}
