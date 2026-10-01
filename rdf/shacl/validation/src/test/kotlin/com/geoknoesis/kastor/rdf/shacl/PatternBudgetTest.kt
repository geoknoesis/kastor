package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * One `sh:pattern` evaluation has its own time budget ([ValidationConfig.patternTimeout]): a backtracking pattern is
 * stopped long before the run-wide timeout (5 minutes by default) and the failure names the pattern.
 */
class PatternBudgetTest {

    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://example.org/> .\n"

    /** `(.*a){30}` needs astronomically many backtracking steps to reject sixty `a`s followed by `!`. */
    private val catastrophic = "^(.*a){30}$"
    private val shapes = Rdf.parse(
        prefixes + """ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:p ; sh:pattern "$catastrophic" ] .""",
        RdfFormat.TURTLE,
    )
    private fun data(value: String) = Rdf.parse(prefixes + """ex:a a ex:T ; ex:p "$value" .""", RdfFormat.TURTLE)

    @Test
    fun `a backtracking pattern is stopped by the per-pattern budget and named`() {
        // The run-wide timeout stays at its 5 minute default: only the pattern budget can end this evaluation.
        val validator = NativeShaclValidator(ValidationConfig(patternTimeout = Duration.ofMillis(200)))
        val error = assertThrows(ShaclValidationException::class.java) { validator.validate(data("a".repeat(60) + "!"), shapes) }
        val message = error.message.orEmpty()
        assertTrue(message.contains(catastrophic), message)
        assertTrue(message.contains("patternTimeout"), message)
        assertTrue(message.contains("http://example.org/S") || message.contains("http://example.org/a"), message)
    }

    @Test
    fun `ordinary evaluations are unaffected by the budget`() {
        val validator = NativeShaclValidator(ValidationConfig(patternTimeout = Duration.ofMillis(200)))
        // The backtracking pattern itself is fine on inputs it decides quickly.
        assertFalse(validator.validate(data("b"), shapes).isValid)
        assertFalse(validator.validate(data("aaa"), shapes).isValid)
        // A linear pattern over a long value reads many characters without exhausting the budget.
        val linear = Rdf.parse(
            prefixes + """ex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:property [ sh:path ex:p ; sh:pattern "^[ab]+$" ] .""",
            RdfFormat.TURTLE,
        )
        assertTrue(validator.validate(data("ab".repeat(100_000)), linear).isValid)
        assertFalse(validator.validate(data("ab".repeat(100_000) + "c"), linear).isValid)
    }

    @Test
    fun `the pattern budget must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { NativeShaclValidator(ValidationConfig(patternTimeout = Duration.ZERO)) }
        assertThrows(IllegalArgumentException::class.java) { NativeShaclValidator(ValidationConfig(patternTimeout = Duration.ofMillis(-1))) }
    }
}
