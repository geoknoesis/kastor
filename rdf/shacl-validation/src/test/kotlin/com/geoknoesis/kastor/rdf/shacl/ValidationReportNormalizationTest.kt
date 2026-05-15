package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidationReportNormalizationTest {

    @Test
    fun `parity keys ignore message but include value term`() {
        val base =
            ValidationViolation(
                severity = ViolationSeverity.VIOLATION,
                constraint = ShaclConstraint(ConstraintType.DATATYPE, path = "http://example.org/p"),
                focusNode = Iri("http://example.org/a"),
                message = "ignored prose",
                path = listOf(Iri("http://example.org/p")),
                value = TypedLiteral("1", XSD.integer),
            )
        val sameDifferentMessage = base.copy(message = "other prose")
        assertEquals(base.paritySortKey(), sameDifferentMessage.paritySortKey())

        val differentValue = base.copy(value = TypedLiteral("2", XSD.integer))
        assertNotEquals(base.paritySortKey(), differentValue.paritySortKey())
    }

    @Test
    fun sortedParityViolationKeys_sorts_deterministically() {
        val v1 =
            ValidationViolation(
                ViolationSeverity.VIOLATION,
                ShaclConstraint(ConstraintType.MIN_COUNT),
                Iri("http://example.org/z"),
                "m",
            )
        val v2 =
            ValidationViolation(
                ViolationSeverity.VIOLATION,
                ShaclConstraint(ConstraintType.MIN_COUNT),
                Iri("http://example.org/a"),
                "m",
            )
        val report =
            ValidationReport(
                isValid = false,
                violations = listOf(v1, v2),
                warnings = emptyList(),
                statistics =
                    ValidationStatistics(
                        1,
                        2,
                        1,
                        2,
                        1,
                        emptyMap(),
                        emptyMap(),
                        emptyMap(),
                        Duration.ZERO,
                    ),
                validationTime = Duration.ZERO,
                validatedResources = 2,
                validatedConstraints = 2,
            )
        val keys = report.sortedParityViolationKeys()
        assertEquals(2, keys.size)
        assertEquals(keys.sorted(), keys)
        assertTrue(keys[0] < keys[1])
    }
}
