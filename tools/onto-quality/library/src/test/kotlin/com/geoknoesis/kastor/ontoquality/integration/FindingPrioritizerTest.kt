package com.geoknoesis.kastor.ontoquality.integration

import com.geoknoesis.kastor.ontoquality.QualityCategory
import com.geoknoesis.kastor.ontoquality.QualityFinding
import com.geoknoesis.kastor.ontoquality.QualityTier
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FindingPrioritizerTest {

    @Test
    fun `sorts by importance descending`() {
        val findings =
            listOf(
                fakeFinding(focus = "ex:Animal", severity = ViolationSeverity.WARNING),
                fakeFinding(focus = "ex:Carbohydrate", severity = ViolationSeverity.WARNING),
            )
        val importance = mapOf("ex:Animal" to 0.9, "ex:Carbohydrate" to 0.2)
        val sorted = FindingPrioritizer.sort(findings, importance)
        assertEquals("ex:Animal", sorted[0].focusNodeIri())
    }

    @Test
    fun `breaks ties on severity`() {
        val findings =
            listOf(
                fakeFinding(focus = "ex:X", severity = ViolationSeverity.INFO),
                fakeFinding(focus = "ex:Y", severity = ViolationSeverity.VIOLATION),
            )
        val importance = mapOf("ex:X" to 0.5, "ex:Y" to 0.5)
        val sorted = FindingPrioritizer.sort(findings, importance)
        assertEquals(ViolationSeverity.VIOLATION, sorted[0].violation.severity)
    }

    @Test
    fun `missing entities get default importance`() {
        val findings =
            listOf(
                fakeFinding(focus = "ex:Known", severity = ViolationSeverity.WARNING),
                fakeFinding(focus = "ex:Unknown", severity = ViolationSeverity.WARNING),
            )
        val importance = mapOf("ex:Known" to 0.9)
        val sorted = FindingPrioritizer.sort(findings, importance)
        assertEquals("ex:Known", sorted[0].focusNodeIri())
    }

    @Test
    fun `deterministic on equal inputs`() {
        val findings = (1..50).map { fakeFinding(focus = "ex:Class$it", severity = ViolationSeverity.WARNING) }
        val importance = findings.associate { (it.focusNodeIri() ?: error("iri")) to 0.5 }
        val sorted1 = FindingPrioritizer.sort(findings, importance)
        val sorted2 = FindingPrioritizer.sort(findings, importance)
        assertEquals(sorted1, sorted2)
    }

    private fun fakeFinding(focus: String, severity: ViolationSeverity): QualityFinding {
        val v =
            ValidationViolation(
                severity = severity,
                constraint = ShaclConstraint(constraintType = ConstraintType.NODE),
                focusNode = Iri(focus),
                message = "test message",
                shapeUri = "http://example.org/shapes/TestShape",
            )
        return QualityFinding(
            violation = v,
            category = QualityCategory.OWL_METADATA,
            pitfall = null,
            tier = QualityTier.STRUCTURAL,
        )
    }

    private fun QualityFinding.focusNodeIri(): String? = (violation.focusNode as? Iri)?.value
}
