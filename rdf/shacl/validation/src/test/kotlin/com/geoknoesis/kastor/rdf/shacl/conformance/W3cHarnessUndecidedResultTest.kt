package com.geoknoesis.kastor.rdf.shacl.conformance

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.KastorShaclVocabulary
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.shacl.toShaclValidationReportRdf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

/**
 * The W3C harness compares result graphs after removing the `ksh:` triples. An **undecided** result
 * (`ksh:resultStatus`) then looks exactly like a failure with the same component, shape and focus node, so it used to
 * satisfy an expected definite result. The harness now fails such a case.
 */
class W3cHarnessUndecidedResultTest {

    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://example.org/> .\n"
    private fun graph(turtle: String) = Rdf.parse(prefixes + turtle, RdfFormat.TURTLE)

    /** The report as an "expected" report: what a manifest with these definite results would contain. */
    private fun asExpected(report: com.geoknoesis.kastor.rdf.shacl.ValidationReport): ExpectedConformanceReport {
        val none = ExpectedConformanceReport(conforms = report.isValid, results = Rdf.graph { }, expectedMessages = emptySet())
        return none.copy(results = report.toW3cResultGraph(none))
    }

    @Test
    fun `an undecided result does not satisfy an expected definite result`() {
        // ex:S is undefined for ex:x (recursion through sh:not): the engine reports one sh:NotConstraintComponent result
        // marked ksh:UndefinedRecursion. The expected report below has the same result as a definite failure.
        val shapes = graph("ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:property ex:P . ex:P sh:path ex:self ; sh:not ex:S .")
        val report = NativeShaclValidator(ValidationConfig.default()).validate(graph("ex:x ex:self ex:x ."), shapes)
        assertEquals(listOf(true), report.violations.map { it.isUndecided })
        val expected = asExpected(report)
        assertTrue(expected.results.getTriples().none { it.predicate.value.startsWith(KastorShaclVocabulary.NAMESPACE) })
        // Without the check the stripped graphs are identical.
        assertResultGraphsIsomorphic(expected.results, report.toW3cResultGraph(expected), "stripped graphs")

        val failure = assertThrows(AssertionFailedError::class.java) { assertMatchesW3cExpected(report, expected, "undecided") }
        assertTrue(failure.message.orEmpty().contains("undecided result"), failure.message)
        assertTrue(failure.message.orEmpty().contains(KastorShaclVocabulary.UndefinedRecursion.value), failure.message)

        // A documented deviation is the only way through.
        assertMatchesW3cExpected(report, expected, "documented", undecidedAllowed = "recursion through sh:not is undefined")
    }

    @Test
    fun `definite results are compared as before`() {
        val shapes = graph("ex:S a sh:NodeShape ; sh:targetNode ex:x ; sh:property ex:P . ex:P sh:path ex:name ; sh:minCount 1 .")
        val report = NativeShaclValidator(ValidationConfig.default()).validate(graph("ex:x ex:self ex:x ."), shapes)
        assertEquals(listOf(false), report.violations.map { it.isUndecided })
        assertMatchesW3cExpected(report, asExpected(report), "definite")
        assertTrue(report.toShaclValidationReportRdf().getTriples().none { it.predicate == KastorShaclVocabulary.resultStatus })
    }

    @Test
    fun `no W3C case is exempted`() {
        // The deviation list for undecided results is empty: keep it that way unless a case is documented there.
        val anyCase = java.nio.file.Path.of("tests", "core", "node", "not-001.ttl")
        assertEquals(null, W3cKnownDeviations.undecidedResultsAllowed(anyCase))
    }
}
