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

    private val reportNode = Iri("urn:x-test:report")

    private fun roundTrip(report: ValidationReport) =
        Rdf.parse(com.geoknoesis.kastor.rdf.jena.JenaProvider().serializeGraph(report.toShaclValidationReportRdf(reportNode), "TURTLE"), RdfFormat.TURTLE)
            .getTriples()

    @Test
    fun `report-level warnings are exported as ksh warning and survive an RDF round trip`() {
        val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://example.org/> .\n"
        // Two constructs the engine cannot evaluate are skipped under IGNORE_WITH_WARNING: the report conforms, and the
        // RDF report used to say sh:conforms true with no trace of what was not validated.
        val shapes = Rdf.parse(
            prefixes + """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:property [ sh:path ex:p ; sh:values [ sh:path ex:q ] ] ;
              sh:property [ sh:path ex:r ; sh:minCount 0 ] ;
              sh:target [ a sh:SPARQLTarget ] .
            """.trimIndent(),
            RdfFormat.TURTLE,
        )
        val data = Rdf.parse(prefixes + "ex:a ex:p 1 .", RdfFormat.TURTLE)
        val report = NativeShaclValidator(ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING)).validate(data, shapes)
        assertTrue(report.isValid, report.violations.toString())
        assertEquals(2, report.warnings.size, report.warnings.toString())

        val parsed = roundTrip(report)
        assertTrue(parsed.any { it.subject == reportNode && it.predicate == SHACL.conforms && (it.obj as com.geoknoesis.kastor.rdf.Literal).lexical == "true" })
        val exported = parsed.filter { it.predicate == KastorShaclVocabulary.warning }
        assertTrue(exported.all { it.subject == reportNode }, exported.toString())
        assertEquals(report.warnings.map { it.message }.toSet(), exported.map { (it.obj as com.geoknoesis.kastor.rdf.Literal).lexical }.toSet())
        assertTrue(exported.all { (it.obj as com.geoknoesis.kastor.rdf.Literal).datatype == XSD.string })
        assertTrue(parsed.none { it.predicate == SHACL.result }, "a report-level warning is not a validation result")
        assertEquals("https://kastor.geoknoesis.com/ns/shacl#warning", KastorShaclVocabulary.warning.value)
    }

    @Test
    fun `a warning about a resource stays a result and is not repeated as ksh warning`() {
        val about = ValidationWarning("check this resource", resource = Iri("http://example.org/a"), shapeUri = "http://example.org/S")
        val general = ValidationWarning("something was skipped")
        val statistics = ValidationStatistics(1, 1, 1, 1, 1, emptyMap(), emptyMap(), emptyMap(), Duration.ZERO)
        val report = ValidationReport(true, emptyList(), listOf(about, general), statistics, Duration.ZERO, 1, 1)
        val parsed = roundTrip(report)
        assertEquals(listOf("something was skipped"), parsed.filter { it.predicate == KastorShaclVocabulary.warning }.map { (it.obj as com.geoknoesis.kastor.rdf.Literal).lexical })
        val row = parsed.single { it.predicate == SHACL.result }.obj
        assertTrue(parsed.any { it.subject == row && it.predicate == SHACL.focusNode && it.obj == Iri("http://example.org/a") })
        assertTrue(parsed.any { it.subject == row && it.predicate == SHACL.resultSeverity && it.obj == SHACL.Warning })
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
