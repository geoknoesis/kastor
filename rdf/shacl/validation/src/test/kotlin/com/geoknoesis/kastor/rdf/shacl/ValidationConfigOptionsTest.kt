@file:Suppress("DEPRECATION")

package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ValidationConfig] switches say what they do: `includeWarnings` controls [ValidationReport.warnings], and the
 * switches no engine ever read (`validateInactiveShapes`, `batchSize`, `enableExplanations`, `enableSuggestions`) are
 * deprecated and documented as having no effect.
 */
class ValidationConfigOptionsTest {

    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://example.org/> .\n"
    private fun graph(turtle: String) = Rdf.parse(prefixes + turtle, RdfFormat.TURTLE)

    private val data = graph("ex:a ex:p 1 . ex:b ex:q 1 .")

    /** One failing shape, one construct the engine skips with a warning, one deactivated shape that would fail. */
    private val shapes = graph(
        """
        ex:S a sh:NodeShape ; sh:targetNode ex:a , ex:b ;
          sh:property [ sh:path ex:p ; sh:minCount 1 ] ;
          sh:property [ sh:path ex:q ; sh:values [ sh:path ex:p ] ] .
        ex:Off a sh:NodeShape ; sh:targetNode ex:a ; sh:deactivated true ; sh:property [ sh:path ex:missing ; sh:minCount 1 ] .
        """,
    )

    private fun validate(config: ValidationConfig) =
        NativeShaclValidator(config.copy(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)).validate(data, shapes)

    @Test
    fun `includeWarnings controls the report-level warnings and nothing else`() {
        val with = validate(ValidationConfig())
        assertEquals(1, with.warnings.size, with.warnings.toString())
        assertTrue(with.toShaclValidationReportRdf().getTriples().any { it.predicate == KastorShaclVocabulary.warning })

        val without = validate(ValidationConfig(includeWarnings = false))
        assertTrue(without.warnings.isEmpty(), without.warnings.toString())
        assertTrue(without.toShaclValidationReportRdf().getTriples().none { it.predicate == KastorShaclVocabulary.warning })
        // Results (of any severity) and conformance are the same.
        assertEquals(with.violations, without.violations)
        assertEquals(with.isValid, without.isValid)
        assertFalse(without.isValid)
        assertTrue(ValidationConfig().includeWarnings, "warnings are reported by default")
    }

    @Test
    fun `includeWarnings false does not drop results of severity sh Warning`() {
        val warningShape = graph("ex:S a sh:NodeShape ; sh:targetNode ex:b ; sh:property [ sh:path ex:p ; sh:minCount 1 ; sh:severity sh:Warning ] .")
        val report = NativeShaclValidator(ValidationConfig(includeWarnings = false)).validate(data, warningShape)
        assertEquals(listOf(ViolationSeverity.WARNING), report.violations.map { it.severity })
        assertFalse(report.isValid)
    }

    @Test
    fun `the deprecated switches have no effect`() {
        val baseline = validate(ValidationConfig())
        val flipped = validate(
            ValidationConfig(validateInactiveShapes = true, batchSize = 1, enableExplanations = false, enableSuggestions = false),
        )
        assertEquals(baseline.violations, flipped.violations)
        assertEquals(baseline.warnings, flipped.warnings)
        assertEquals(1, flipped.violations.size, "the deactivated shape is not validated, whatever validateInactiveShapes says")
        assertTrue(flipped.violations.all { it.explanation == null && it.suggestedFix == null })
        assertTrue(baseline.violations.all { it.explanation == null && it.suggestedFix == null })
    }

    @Test
    fun `the switches without effect are marked deprecated for Kotlin and Java callers`() {
        for (property in listOf("validateInactiveShapes", "batchSize", "enableExplanations", "enableSuggestions")) {
            val annotations = ValidationConfig::class.java.getDeclaredMethod("get${property.replaceFirstChar { it.uppercase() }}\$annotations")
            assertTrue(annotations.isAnnotationPresent(Deprecated::class.java), "$property is not @Deprecated")
            val message = annotations.getAnnotation(Deprecated::class.java).message
            assertTrue(message.contains("no effect"), "$property: $message")
        }
        for (property in listOf("includeWarnings", "strictMode", "maxViolations", "validateClosedShapes")) {
            val hasMarker = ValidationConfig::class.java.declaredMethods.any { it.name == "get${property.replaceFirstChar { c -> c.uppercase() }}\$annotations" }
            assertFalse(hasMarker, "$property must not be deprecated")
        }
    }
}
