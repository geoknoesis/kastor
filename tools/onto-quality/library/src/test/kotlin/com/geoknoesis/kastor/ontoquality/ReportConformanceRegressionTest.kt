package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.shacl.*
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ReportConformanceRegressionTest {
    @Test fun `reasoner warning cannot erase SHACL nonconformance`() {
        val shapes = Rdf.parse("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            <urn:example:shape> a sh:NodeShape; sh:targetNode <urn:example:subject>;
                sh:severity sh:Warning; sh:class <urn:example:RequiredClass> .
        """.trimIndent(), RdfFormat.TURTLE)
        val base = NativeShaclValidatorProvider().createValidator(ValidationConfig())
            .validate(Rdf.graph {}, shapes)
        assertFalse(base.isValid)
        assertEquals(ViolationSeverity.WARNING, base.violations.single().severity)
        val merged = mergeValidationReport(base, emptyList(), listOf("reasoner diagnostic"))
        assertFalse(merged.isValid, "Adding a diagnostic must preserve the engine's conformance decision")
        val truncated = base.copy(violations = emptyList(), violationsTruncated = true)
        assertFalse(mergeValidationReport(truncated, emptyList(), listOf("diagnostic")).isValid)
        val allowed = base.copy(isValid = true)
        assertTrue(mergeValidationReport(allowed, emptyList(), listOf("diagnostic")).isValid)
        val blocking = base.violations.single().copy(severity = ViolationSeverity.VIOLATION)
        assertFalse(mergeValidationReport(allowed, listOf(blocking), emptyList()).isValid)
    }
}
