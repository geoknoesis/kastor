package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `sh:pattern` follows XPath `fn:matches` (XML Schema regular expressions), not `java.util.regex` defaults:
 * - `$` matches at the end of input only, or (flag `m`) before a line feed; never before a final newline;
 * - `^` (flag `m`) matches at the start of input and after a line feed only;
 * - `.` matches everything except line feed and carriage return (everything with flag `s`);
 * - `\d`, `\w`, `\s` and their negations are the XML Schema (Unicode) classes, except that `\w` also accepts `_`
 *   (as in `java.util.regex`, which the other SHACL engines use), so `\W` does not match it.
 *
 * The class name is historical: it started as the test of `$`. Syntax translation and rejected `java.util.regex`
 * idioms are covered by [ShaclPatternTranslationTest].
 */
class ShaclPatternDollarTest {
    private fun matches(pattern: String, flags: String?, value: String) = compileShaclPattern(pattern, flags).containsMatchIn(value)

    private val nel = "\u0085"
    private val lineSeparator = "\u2028"
    private val paragraphSeparator = "\u2029"
    private val javaOnlyLineTerminators = listOf("\r", nel, lineSeparator, paragraphSeparator)

    // --- $ ---------------------------------------------------------------------------------------------------------

    @Test
    fun `dollar does not match before a final newline`() {
        assertTrue(matches("^\\d+$", null, "123"))
        assertFalse(matches("^\\d+$", null, "123\n"))
        assertFalse(matches("^\\d+$", "x", "123\n"))
        for (terminator in javaOnlyLineTerminators) assertFalse(matches("^\\d+$", null, "123$terminator"), terminator)
    }

    @Test
    fun `multi-line dollar matches only before a line feed or at the end`() {
        assertTrue(matches("^a$", "m", "a\nb"))
        assertTrue(matches("^b$", "m", "a\nb"))
        assertTrue(matches("a$", "m", "a\n"))
        for (terminator in javaOnlyLineTerminators) assertFalse(matches("^a$", "m", "a${terminator}b"), terminator)
    }

    @Test
    fun `escaped and class dollars stay literal`() {
        assertTrue(matches("\\$\\d+", null, "cost \$42"))
        assertFalse(matches("^\\$$", null, "x"))
        assertTrue(matches("^\\$$", null, "\$"))
        assertTrue(matches("^[$]$", null, "\$"))
        assertFalse(matches("^[$]$", null, "\$\n"))
        assertTrue(matches("^[^$]+$", null, "abc"))
        assertFalse(matches("^[^$]+$", null, "a\$c"))
        assertTrue(matches("^[\\]$]+$", null, "]\$]"))
        assertFalse(matches("^[\\]$]+$", null, "]a"))
        assertTrue(matches("a$", "q", "a\$"))
        // An escaped backslash does not escape the dollar that follows it.
        assertTrue(matches("^a\\\\$", null, "a\\"))
        assertFalse(matches("^a\\\\$", null, "a\\\n"))
    }

    @Test
    fun `dollar and caret inside a quoted section are literal`() {
        assertTrue(matches("^\\Qa\$b\\E$", null, "a\$b"))
        assertFalse(matches("^\\Qa\$b\\E$", null, "a"))
        assertTrue(matches("^\\Q^.$\\E$", "m", "^.$"))
        assertFalse(matches("^\\Q^.$\\E$", "m", "^x$"))
        // An unterminated quote runs to the end of the pattern.
        assertTrue(matches("^\\Qa$", null, "a\$"))
        // Whitespace in a quoted section survives the x flag.
        assertTrue(matches("^ \\Qa b\\E $", "x", "a b"))
        // Quoting inside a character class.
        assertTrue(matches("^[\\Q$^\\E]+$", null, "\$^"))
    }

    // --- ^ ---------------------------------------------------------------------------------------------------------

    @Test
    fun `multi-line caret matches only at the start or after a line feed`() {
        assertTrue(matches("^b", "m", "a\nb"))
        assertTrue(matches("^a", "m", "a\nb"))
        for (terminator in javaOnlyLineTerminators) assertFalse(matches("^b", "m", "a${terminator}b"), terminator)
        // XPath: the position after a final line feed starts a (last, empty) line.
        assertTrue(matches("^$", "m", "a\n"))
        assertFalse(matches("^$", "m", "a"))
        assertTrue(matches("^b", "m", "a\r\nb"))
    }

    @Test
    fun `caret without the m flag matches only at the start and stays a negation in classes`() {
        assertFalse(matches("^b", null, "a\nb"))
        assertTrue(matches("^[^a]$", "m", "b"))
        assertFalse(matches("^[^a]$", "m", "a"))
        assertTrue(matches("^[a^]+$", "m", "a^"))
        assertTrue(matches("^\\^$", "m", "^"))
    }

    // --- . ---------------------------------------------------------------------------------------------------------

    @Test
    fun `dot matches everything except line feed and carriage return`() {
        for (terminator in listOf(nel, lineSeparator, paragraphSeparator)) assertTrue(matches("^a.b$", null, "a${terminator}b"), terminator)
        assertFalse(matches("^a.b$", null, "a\nb"))
        assertFalse(matches("^a.b$", null, "a\rb"))
        assertTrue(matches("^a.b$", null, "a😀b"), "a supplementary character is one character")
        assertTrue(matches("^.*$", null, "x${nel}y"))
        assertFalse(matches("^.*$", null, "x\ny"))
    }

    @Test
    fun `dot matches every character with the s flag and is literal in classes and escapes`() {
        assertTrue(matches("^a.b$", "s", "a\nb"))
        assertTrue(matches("^a.b$", "s", "a\rb"))
        assertTrue(matches("^a[.]b$", null, "a.b"))
        assertFalse(matches("^a[.]b$", null, "axb"))
        assertTrue(matches("^a\\.b$", null, "a.b"))
        assertFalse(matches("^a\\.b$", null, "axb"))
    }

    // --- \d \w \s --------------------------------------------------------------------------------------------------

    @Test
    fun `word characters are Unicode letters, marks, digits, symbols and the underscore`() {
        assertTrue(matches("^\\w+$", null, "José"))
        assertTrue(matches("^\\w+$", null, "東京"))
        assertTrue(matches("^\\w+$", null, "é"), "combining marks are word characters")
        assertTrue(matches("^\\w+$", null, "a1+\$"), "symbols are word characters in XML Schema")
        assertFalse(matches("^\\w+$", null, "a b"))
        assertFalse(matches("^\\w+$", null, "a-b"))
        // XML Schema: \w is everything except punctuation, separators and "other". '_' is connector punctuation, but
        // it is accepted as in java.util.regex: shapes are usually written against engines that use Java regex.
        assertTrue(matches("^\\w+$", null, "a_b"))
        assertTrue(matches("^\\w+$", null, "_"))
        assertTrue(matches("^\\W+$", null, "- !"))
        assertFalse(matches("^\\W+$", null, "-_ !"))
        assertFalse(matches("^\\W$", null, "_"))
        // Only U+005F is added: the other connector punctuation characters stay non-word characters.
        assertFalse(matches("^\\w$", null, "\u203F"))
        assertTrue(matches("^\\W$", null, "\u203F"))
        assertFalse(matches("^\\W+$", null, "é"))
    }

    @Test
    fun `digits are Unicode decimal digits`() {
        assertTrue(matches("^\\d+$", null, "١٢٣"))
        assertTrue(matches("^\\d+$", null, "42"))
        assertFalse(matches("^\\d+$", null, "½"), "vulgar fraction one half is No, not Nd")
        assertTrue(matches("^\\D+$", null, "ab"))
        assertFalse(matches("^\\D+$", null, "١"))
    }

    @Test
    fun `whitespace is exactly space, tab, line feed and carriage return`() {
        assertTrue(matches("^\\s+$", null, " \t\n\r"))
        assertFalse(matches("^\\s$", null, "\u000B"))
        assertFalse(matches("^\\s$", null, "\u000C"))
        assertFalse(matches("^\\s$", null, "\u00A0"))
        assertTrue(matches("^\\S$", null, "\u000B"))
        assertFalse(matches("^\\S$", null, " "))
    }

    @Test
    fun `class escapes keep their meaning inside character classes`() {
        assertTrue(matches("^[\\w-]+$", null, "José-x"))
        assertFalse(matches("^[\\w-]+$", null, "a b"))
        assertTrue(matches("^[^\\w]$", null, "-"))
        assertFalse(matches("^[^\\w]$", null, "é"))
        assertFalse(matches("^[\\W]$", null, "_"))
        assertTrue(matches("^[\\W]$", null, "-"))
        assertFalse(matches("^[^\\w]$", null, "_"))
        assertTrue(matches("^[^\\W]$", null, "_"))
        assertTrue(matches("^[\\W_]+$", null, "-_"))
        assertFalse(matches("^[^\\W-]$", null, "-"))
        assertTrue(matches("^[\\s,]+$", null, " ,\t"))
        assertFalse(matches("^[\\s,]+$", null, "\u000B"))
        assertTrue(matches("^[\\S]+$", null, "ab"))
        assertFalse(matches("^[\\S]+$", null, "a b"))
        assertTrue(matches("^[^\\S]+$", null, " \t"))
        assertFalse(matches("^[^\\S]+$", null, "a"))
        assertTrue(matches("^[\\d.]+$", null, "١.5"))
        assertTrue(matches("^[^\\d]+$", null, "ab"))
        assertFalse(matches("^[^\\d]+$", null, "١"))
        assertTrue(matches("^[\\D]+$", null, "ab"))
        // A hyphen after a class escape is a literal hyphen (the e-mail pattern of the SHACL DSL guide).
        val email = "^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$"
        assertTrue(matches(email, null, "john.doe-x@mail.example.com"))
        assertFalse(matches(email, null, "john doe@example.com"))
        assertTrue(matches(email, null, "john_doe@example.org"), "'_' is a word character")
        assertTrue(matches("^[\\w_.-]+$", null, "john_doe"))
        // A class escape next to a hyphen never forms a range with its neighbours.
        assertTrue(matches("^[+-\\w]+$", null, "+-a_"))
        assertFalse(matches("^[+-\\w]+$", null, ","))
        assertTrue(matches("^[\\s-\\w]+$", null, "a -_"))
    }

    @Test
    fun `an escaped backslash before a letter is not a class escape`() {
        assertTrue(matches("^\\\\d$", null, "\\d"))
        assertFalse(matches("^\\\\d$", null, "\\7"))
        assertTrue(matches("^\\\\w$", null, "\\w"))
    }

    // --- XML Schema only syntax --------------------------------------------------------------------------------------

    @Test
    fun `character class subtraction, name escapes and block escapes are translated`() {
        assertTrue(matches("^[a-z-[aeiou]]+$", null, "xyz"))
        assertFalse(matches("^[a-z-[aeiou]]+$", null, "xay"))
        assertTrue(matches("^[^a-z-[0-9]]+$", null, "AB"))
        assertFalse(matches("^[^a-z-[0-9]]+$", null, "A1"))
        assertTrue(matches("^\\i\\c*$", null, "_a-1.b"))
        assertFalse(matches("^\\i\\c*$", null, "1a"))
        assertTrue(matches("^\\p{IsBasicLatin}+$", null, "abc"))
        assertFalse(matches("^\\p{IsBasicLatin}+$", null, "é"))
        assertTrue(matches("^[a&b]+$", null, "a&b"))
        assertTrue(matches("^[a\\[b]+$", null, "a[b"))
    }

    // --- flags -----------------------------------------------------------------------------------------------------

    @Test
    fun `x removes whitespace outside classes only`() {
        assertTrue(matches("^ a b $", "x", "ab"))
        assertFalse(matches("^ a b $", "x", "a b"))
        assertTrue(matches("^a[ ]b$", "x", "a b"))
    }

    @Test
    fun `q matches literally and only combines with i`() {
        assertFalse(matches("a.b", "q", "axb"))
        assertTrue(matches("a.b", "q", "a.b"))
        assertTrue(matches("a.b", "qi", "A.B"))
        assertTrue(matches("^a", "qm", "x^a"))
        assertFalse(matches("^a", "qm", "x\na"))
        assertTrue(matches("\\d", "q", "\\d"))
    }

    @Test
    fun `i is Unicode aware`() {
        assertTrue(matches("^é$", "i", "É"))
        assertTrue(matches("^straße$", "i", "STRAßE"))
    }

    @Test
    fun `invalid patterns and flags are compile errors`() {
        assertThrows(ShapeCompileException::class.java) { compileShaclPattern("(", null) }
        assertThrows(ShapeCompileException::class.java) { compileShaclPattern("[a", null) }
        assertThrows(ShapeCompileException::class.java) { compileShaclPattern("a", "z") }
    }
}
