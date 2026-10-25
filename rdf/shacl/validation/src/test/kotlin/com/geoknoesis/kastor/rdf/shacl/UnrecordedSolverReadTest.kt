package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A read of the recursion solver that the recording pass of the reading question did not make. The recording pass
 * answers every recursive read "conforms", so a shape whose `sh:deactivated` reads another shape is deactivated
 * there, and its constraints, with their reads, are not evaluated. A later evaluation that finds the shape active
 * reads questions its recorded dependencies lack. Such a read was answered with the group's value without being a
 * dependency, so the group could be split as if the question did not depend on it, and a question undefined by
 * recursion was settled as conforming. It now restarts the solve with the read recorded, as any other missed read.
 * (Found by the expression generator of [RecursionSolverDifferentialTest].)
 */
class UnrecordedSolverReadTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        @prefix shnex: <http://www.w3.org/ns/shacl-node-expr#> .
    """.trimIndent()

    private fun conformsTo(shape: String) = """[ shnex:conformsToShape ( [ shnex:var "focusNode" ] ex:$shape ) ]"""

    @Test
    fun `a constraint evaluated only once the deactivation reads a definite false is a dependency`() {
        // ex:S0 fails sh:minCount, so ex:S2 is active; ex:S2's sh:expression then reads ex:S2 itself (a negative,
        // self-referential read): undefined, which fails closed. ex:S2 used to conform.
        val shapes = """
            $prefixes
            ex:S2 a sh:NodeShape ; sh:targetNode ex:n0 ; sh:expression ${conformsTo("S2")} ; sh:deactivated ${conformsTo("S0")} .
            ex:S0 a sh:NodeShape ; sh:property [ sh:path ex:p ; sh:minCount 1 ] ; sh:not ex:S2 .
        """
        val data = "$prefixes\nex:n0 a ex:Thing ."
        val outcome = runCatching { NativeShaclValidator(ValidationConfig.default()).validate(Rdf.parse(data, RdfFormat.TURTLE), Rdf.parse(shapes, RdfFormat.TURTLE)) }
        val report = outcome.getOrNull()
        assertTrue(
            outcome.exceptionOrNull() is ShaclValidationException || (report != null && report.violations.any { it.isUndefinedRecursion }),
            "ex:S2 must not conform: ${outcome.exceptionOrNull() ?: report?.violations}",
        )
    }
}
