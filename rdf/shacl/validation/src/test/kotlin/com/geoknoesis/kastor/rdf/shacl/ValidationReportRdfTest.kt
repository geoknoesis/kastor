package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.FalseLiteral
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.vocab.SHACL
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidationReportRdfTest {

    @Test
    fun `toShaclValidationReportRdf emits conforms and violation rows`() {
        val ex = "http://example.org/"
        val v =
            ValidationViolation(
                severity = ViolationSeverity.VIOLATION,
                constraint =
                    ShaclConstraint(
                        constraintType = ConstraintType.MIN_COUNT,
                        path = "${ex}label",
                    ),
                focusNode = Iri("${ex}a"),
                message = "Minimum cardinality 1 required",
                path = listOf(Iri("${ex}label")),
                value = TypedLiteral("bad", XSD.integer),
                shapeUri = "${ex}Shape",
            )
        val report =
            ValidationReport(
                isValid = false,
                violations = listOf(v),
                warnings = emptyList(),
                statistics =
                    ValidationStatistics(
                        totalResources = 1,
                        validatedResources = 1,
                        totalConstraints = 1,
                        validatedConstraints = 1,
                        shapesProcessed = 1,
                        constraintsByType = mapOf(ConstraintType.MIN_COUNT to 1),
                        violationsByType = mapOf(ConstraintType.MIN_COUNT to 1),
                        warningsByType = emptyMap(),
                        averageValidationTimePerResource = Duration.ZERO,
                    ),
                validationTime = Duration.ZERO,
                validatedResources = 1,
                validatedConstraints = 1,
            )
        val g = report.toShaclValidationReportRdf()
        val triples = g.getTriples()
        assertTrue(triples.any { it.predicate == SHACL.conforms && it.obj == FalseLiteral })
        assertTrue(triples.any { it.predicate == SHACL.result })
        assertTrue(triples.any { it.predicate == SHACL.focusNode && it.obj == v.focusNode })
        assertTrue(triples.any { it.predicate == SHACL.sourceShape && it.obj == Iri("${ex}Shape") })
        assertTrue(triples.any { it.predicate == SHACL.sourceConstraintComponent })
        assertTrue(triples.any { it.predicate == SHACL.resultPath && it.obj == Iri("${ex}label") })
        assertTrue(triples.any { it.predicate == SHACL.value && it.obj == TypedLiteral("bad", XSD.integer) })
        assertTrue(triples.none { it.predicate == KastorShaclVocabulary.resultStatus }, "an ordinary failure has no result status")
    }

    @Test
    fun `undefined recursion results keep their marker through an RDF round trip`() {
        val ex = "http://example.org/"
        val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <$ex> .\n"
        // ex:Undef is undefined for nodes with an ex:self loop (recursion through negation); ex:x also definitely
        // fails sh:minCount, ex:y conforms.
        val shapes = Rdf.parse(
            prefixes + """
            ex:T a sh:NodeShape ; sh:targetClass ex:Person ; sh:node ex:Undef ;
              sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            ex:Undef sh:property [ sh:path ex:self ; sh:not ex:Undef ] .
            """.trimIndent(),
            RdfFormat.TURTLE,
        )
        val data = Rdf.parse(prefixes + "ex:x a ex:Person ; ex:self ex:x . ex:y a ex:Person ; ex:name 'y' .", RdfFormat.TURTLE)
        val report = NativeShaclValidator(ValidationConfig.default()).validate(data, shapes)
        assertEquals(1, report.violations.count { it.isUndefinedRecursion }, report.violations.toString())
        assertEquals(1, report.violations.count { !it.isUndefinedRecursion }, report.violations.toString())

        // Round trip through a serialization: an RDF consumer only sees the triples.
        val turtle = com.geoknoesis.kastor.rdf.jena.JenaProvider().serializeGraph(report.toShaclValidationReportRdf(), "TURTLE")
        val parsed = Rdf.parse(turtle, RdfFormat.TURTLE).getTriples()
        fun component(result: Any) = parsed.single { it.subject == result && it.predicate == SHACL.sourceConstraintComponent }.obj
        val results = parsed.filter { it.predicate == SHACL.result }.map { it.obj }
        assertEquals(2, results.size)
        val undefined = parsed.filter { it.predicate == KastorShaclVocabulary.resultStatus }
        assertEquals(listOf<Any>(KastorShaclVocabulary.UndefinedRecursion), undefined.map { it.obj })
        // The undefined result is the sh:node one; it keeps its standard component and severity.
        assertEquals(Iri(SHACL.namespace + "NodeConstraintComponent"), component(undefined.single().subject))
        assertTrue(parsed.any { it.subject == undefined.single().subject && it.predicate == SHACL.resultSeverity && it.obj == SHACL.Violation })
        val definite = results.single { it != undefined.single().subject }
        assertEquals(Iri(SHACL.namespace + "MinCountConstraintComponent"), component(definite))
        assertEquals("https://kastor.geoknoesis.com/ns/shacl#resultStatus", KastorShaclVocabulary.resultStatus.value)
    }
}
