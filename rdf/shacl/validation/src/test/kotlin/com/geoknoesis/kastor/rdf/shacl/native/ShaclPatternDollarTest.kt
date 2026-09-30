package com.geoknoesis.kastor.rdf.shacl.native

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `$` follows XPath `fn:matches`: end of input only, or before `\n` with the `m` flag (never before a final newline). */
class ShaclPatternDollarTest {
    private fun matches(pattern: String, flags: String?, value: String) = compileShaclPattern(pattern, flags).containsMatchIn(value)

    @Test
    fun `dollar does not match before a final newline`() {
        assertTrue(matches("^\\d+$", null, "123"))
        assertFalse(matches("^\\d+$", null, "123\n"))
        assertFalse(matches("^\\d+$", "x", "123\n"))
    }

    @Test
    fun `multi-line dollar matches only before a line feed or at the end`() {
        assertTrue(matches("^a$", "m", "a\nb"))
        assertTrue(matches("^b$", "m", "a\nb"))
        assertFalse(matches("^a$", "m", "a\rb"))
    }

    @Test
    fun `escaped and class dollars stay literal`() {
        assertTrue(matches("\\$\\d+", null, "cost \$42"))
        assertTrue(matches("^[$]$", null, "\$"))
        assertFalse(matches("^[$]$", null, "\$\n"))
        assertTrue(matches("a$", "q", "a\$"))
    }
}
