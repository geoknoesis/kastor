package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.PitfallReference
import com.geoknoesis.kastor.ontoquality.QualityCategory
import com.geoknoesis.kastor.ontoquality.QualityFinding
import com.geoknoesis.kastor.ontoquality.QualityTier
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ValidationViolation
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class FindingRefTest {
    @Test
    fun stableForSameInputs() {
        val f = sampleFinding()
        assertEquals(FindingRef.from(f, 0), FindingRef.from(f, 0))
    }

    @Test
    fun stableAcrossIndices() {
        val f = sampleFinding()
        assertEquals(FindingRef.from(f, 0), FindingRef.from(f, 99))
        assertEquals(FindingRef.from(f), FindingRef.from(f, 0))
    }

    @Test
    fun distinctFindingsDiffer() {
        val a = sampleFinding()
        val b =
            sampleFinding().copy(
                violation =
                    a.violation.copy(
                        message = "other message",
                    ),
            )
        assertNotEquals(FindingRef.from(a), FindingRef.from(b))
    }

    private fun sampleFinding(): QualityFinding =
        QualityFinding(
            violation =
                ValidationViolation(
                    severity = ViolationSeverity.WARNING,
                    constraint =
                        ShaclConstraint(
                            constraintType = ConstraintType.MIN_COUNT,
                            severity = ViolationSeverity.WARNING,
                        ),
                    focusNode = Iri("http://ex.org/C"),
                    message = "example message",
                    shapeUri = "http://ex.org/Shape1",
                ),
            category = QualityCategory.OWL_NAMING,
            pitfall = PitfallReference.OntoQuality("N1"),
            tier = QualityTier.STRUCTURAL,
        )
}
