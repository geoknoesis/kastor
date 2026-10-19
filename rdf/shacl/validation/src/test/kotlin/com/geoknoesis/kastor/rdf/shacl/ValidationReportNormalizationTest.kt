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

    private fun violation(
        value: com.geoknoesis.kastor.rdf.RdfTerm? = null,
        path: List<com.geoknoesis.kastor.rdf.RdfTerm>? = null,
        shapeUri: String? = null,
    ) =
        ValidationViolation(
            severity = ViolationSeverity.VIOLATION,
            constraint = ShaclConstraint(ConstraintType.DATATYPE),
            focusNode = Iri("http://example.org/a"),
            message = "m",
            path = path,
            value = value,
            shapeUri = shapeUri,
        )

    @Test
    fun `parity keys distinguish source shapes`() {
        assertNotEquals(
            violation(shapeUri = "http://example.org/S1").paritySortKey(),
            violation(shapeUri = "http://example.org/S2").paritySortKey(),
        )
    }

    @Test
    fun `parity keys ignore language tag casing`() {
        assertEquals(
            violation(value = com.geoknoesis.kastor.rdf.LangString("x", "en-US")).paritySortKey(),
            violation(value = com.geoknoesis.kastor.rdf.LangString("x", "en-us")).paritySortKey(),
        )
    }

    @Test
    fun `parity keys are injective across separator characters`() {
        // Old path encoding joined with '|': one blank node whose id contains '|B|' collided with two blank nodes.
        assertNotEquals(
            violation(path = listOf(com.geoknoesis.kastor.rdf.BlankNode("x|B|y"))).paritySortKey(),
            violation(path = listOf(com.geoknoesis.kastor.rdf.BlankNode("x"), com.geoknoesis.kastor.rdf.BlankNode("y"))).paritySortKey(),
        )
    }

    @Test
    fun `parity keys do not depend on the engine's label of a blank source shape`() {
        // The native engine and RDF4J label the same anonymous property shape differently.
        assertEquals(violation(shapeUri = "_:b0").paritySortKey(), violation(shapeUri = "_:node1h2k3").paritySortKey())
        assertNotEquals(violation(shapeUri = "_:b0").paritySortKey(), violation(shapeUri = "http://example.org/S1").paritySortKey())
    }

    @Test
    fun `native and RDF4J reports of a blank property shape have the same parity keys`() {
        val document =
            """
            @prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://example.org/> .
            ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .
            ex:a a ex:T .
            """.trimIndent()
        // Each engine validates its own parse of the same document, as a parity job does: blank labels differ.
        fun keys(providerId: String): List<String> {
            val graph = com.geoknoesis.kastor.rdf.Rdf.parse(document, com.geoknoesis.kastor.rdf.RdfFormat.TURTLE)
            return ShaclValidation.validator(
                ValidationConfig(profile = ValidationProfile.SHACL_CORE, providerId = providerId, parallelValidation = false),
            ).validate(graph, graph).also { assertEquals(1, it.violations.size, "$providerId: ${it.violations}") }
                .sortedParityViolationKeys()
        }
        assertEquals(keys("kastor"), keys("rdf4j"))
    }
}
