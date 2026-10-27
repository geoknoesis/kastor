package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.sparql.internal.XPathRegex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Generated `sh:pattern` checks ([ShaclPattern.containsMatchIn]) charge the work of the linear-time engine to a
 * deterministic step budget, with the weights of the Kastor SHACL validator: each character read costs as many steps
 * as the compiled program has instructions. Before, `.{0,1000}x` took 36 s on a 1 MB value and the accepted
 * `(a{0,100}){0,100}x` 12 s on 30 KB.
 */
class ShaclPatternBudgetTest {
    @Test
    fun `the reviewer's patterns fail with a limit error instead of running for seconds`() {
        val cases = listOf(
            ".{0,1000}x" to "a".repeat(1_000_000),
            "(.{0,1000}x|.{0,1000}y|.{0,1000}z)" to "a".repeat(100_000),
            "(a{0,100}){0,100}x" to "a".repeat(30_000),
            "[\\p{L}\\p{N}]{0,1000}x" to "a".repeat(300_000),
        )
        for ((pattern, value) in cases) {
            val error = assertThrows<ShaclPatternBudgetException>(pattern) { ShaclPattern(pattern).containsMatchIn(value) }
            assertTrue(error.message!!.contains(pattern), error.message)
            assertTrue(error.message!!.contains("${ShaclPattern.MAX_STEPS} steps"), error.message)
            assertTrue(error.message!!.contains("sh:maxLength"), error.message)
            assertEquals(value.length, error.valueLength)
        }
    }

    /** Differential: within the budget, the answer is the unbounded engine's. */
    @Test
    fun `within the budget the answer is the engine's`() {
        val patterns = listOf(
            "^[a-z]+$", "^[^@]+@[^@]+$", "a|b", "(ab)*c", "\\d{3}-\\d{4}", "^$", "x", "[\\p{L}]{2,5}", ".{0,1000}x",
            "(a{0,100}){0,100}x", "^(.*a){20}$",
        )
        val values = listOf("", "a", "abc", "ab@cd", "555-1234", "ababc", "x", "a".repeat(20), "a".repeat(2_000) + "x", "Ünïcödé", "\n")
        for (pattern in patterns) {
            for (flags in listOf(null, "i", "s")) {
                val reference = XPathRegex.compile(pattern, flags)
                val bounded = ShaclPattern(pattern, flags)
                for (value in values) assertEquals(reference.containsMatchIn(value), bounded.containsMatchIn(value), "$pattern / $flags / ${value.take(20)}")
            }
        }
    }

    @Test
    fun `the charge is deterministic and at least the program size per character`() {
        for (pattern in listOf("^[a-z]+$", "(a{0,10}){0,10}x", "[\\p{L}\\p{N}]{0,100}x", "ab|cd|ef")) {
            val size = XPathRegex.compile(pattern, null).programSize.toLong()
            for (length in listOf(1, 10, 100, 1_000)) {
                val value = "a".repeat(length) + "!"
                // A value the pattern does not match is read to its end: at least one read per character.
                assertThrows<ShaclPatternBudgetException> { ShaclPattern(pattern, null, maxSteps = size * value.length - 1).containsMatchIn(value) }
                val cost = cost(pattern, value)
                assertEquals(cost, cost(pattern, value), "$pattern / $length")
                assertTrue(cost >= size * value.length, "$pattern / $length: $cost")
                ShaclPattern(pattern, null, maxSteps = cost).containsMatchIn(value)
                assertThrows<ShaclPatternBudgetException> { ShaclPattern(pattern, null, maxSteps = cost - 1).containsMatchIn(value) }
            }
        }
    }

    /** The smallest budget within which [pattern] decides [value], by bisection. */
    private fun cost(pattern: String, value: String): Long {
        var low = 1L
        var high = ShaclPattern.MAX_STEPS
        while (low < high) {
            val mid = (low + high) / 2
            if (runCatching { ShaclPattern(pattern, null, maxSteps = mid).containsMatchIn(value) }.isSuccess) high = mid else low = mid + 1
        }
        return low
    }

    @Test
    fun `each call has its own budget`() {
        val pattern = ShaclPattern("^[a-z]+$")
        val value = "a".repeat(100_000)
        repeat(50) { assertTrue(pattern.containsMatchIn(value)) }
    }
}
