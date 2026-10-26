package com.geoknoesis.kastor.rdf.sparql.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.regex.Pattern
import kotlin.random.Random

/**
 * The XPath regular expression translation for RE2 ([XPathRegex]): flags, the constructs RE2 lacks (refused, never
 * reinterpreted), the structural bounds, and agreement with java.util.regex wherever the two syntaxes mean the same.
 * The full `sh:pattern` semantics are also covered through the native SHACL engine's tests.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class XPathRegexTest {

    private fun matches(pattern: String, flags: String?, text: String) = XPathRegex.compile(pattern, flags).containsMatchIn(text)

    private fun assertRefused(pattern: String, flags: String? = null, vararg expected: String) {
        val error = assertThrows(XPathRegexException::class.java, { XPathRegex.compile(pattern, flags) }, pattern)
        assertEquals(pattern, error.pattern)
        for (part in expected) assertTrue(error.reason.contains(part), "$pattern: expected \"$part\" in: ${error.reason}")
    }

    @Test
    fun `flags follow fn matches`() {
        assertTrue(matches("^abc$", "i", "ABC"))
        assertTrue(matches("^b$", "m", "a\nb"))
        assertFalse(matches("^b$", "m", "a\rb"))
        assertTrue(matches("^a.c$", "s", "a\nc"))
        assertFalse(matches("^a.c$", null, "a\nc"))
        assertFalse(matches("^a.c$", null, "a\rc"))
        assertTrue(matches("^a b$", "x", "ab"))
        assertTrue(matches("^a[ ]b$", "x", "a b"))
        assertTrue(matches("a.b(", "q", "xa.b(y"))
        assertFalse(matches("a.b", "q", "axb"))
        assertTrue(matches("A\\E.B", "qi", "a\\e.b"))
        assertRefused("a", "u", "flag")
        assertRefused("a", "d", "flag")
    }

    @Test
    fun `case-insensitive matching uses case orbits and leaves escapes alone`() {
        // The Kelvin sign is a case variant of k, as are the dotted capital I and the dotless small i of i.
        assertTrue(matches("^k$", "i", "K"))
        assertTrue(matches("^[a-z]$", "i", "K"))
        assertTrue(matches("^i$", "i", "İ"))
        assertFalse(matches("^\\p{Lu}$", "i", "a"))
        assertTrue(matches("^[^q]$", "i", "a"))
        assertFalse(matches("^[^q]$", "i", "Q"))
    }

    @Test
    fun `characters outside the basic plane are one character`() {
        val emoji = "😀"
        assertTrue(matches("^.$", null, emoji))
        assertTrue(matches("^$emoji+$", null, emoji + emoji))
        assertTrue(matches("^[$emoji-🙏]$", null, "😁"))
        assertTrue(matches("^\\p{So}$", null, emoji))
        assertTrue(matches("^\\x{1F600}$", null, emoji))
    }

    @Test
    fun `categories include the unassigned code points`() {
        assertTrue(matches("^\\p{Cn}$", null, "￿".let { if (Character.getType(0xFFFF) == Character.UNASSIGNED.toInt()) it else "͸" }))
        assertTrue(matches("^\\p{C}$", null, "\u0000"))
        assertFalse(matches("^\\p{C}$", null, "a"))
    }

    @Test
    fun `what the linear-time engine lacks is refused with a reason`() {
        assertRefused("(a)\\1", null, "back-reference")
        assertRefused("a(?=b)", null, "lookaround")
        assertRefused("a(?!b)", null, "lookaround")
        assertRefused("(?<=a)b", null, "lookaround")
        assertRefused("(?<!a)b", null, "lookaround")
        assertRefused("(?>a)", null, "atomic")
        assertRefused("a*+", null, "possessive")
        assertRefused("a{2}+", null, "possessive")
        assertRefused("\\bword\\b", null, "word boundary")
        assertRefused("a{1001}", null, "1000")
        assertRefused("a(?i)b", null, "inline flag")
        assertRefused("\\h", null, "not supported")
        assertRefused("\\p{Alpha}", null, "general category")
    }

    @Test
    fun `structural bounds refuse hostile patterns before RE2 builds them`() {
        assertRefused("(".repeat(65) + "a" + ")".repeat(65), null, "nested")
        assertRefused("(".repeat(100_000) + "a" + ")".repeat(100_000), null, "nested")
        assertRefused("((a{1000}){1000}){1000}", null, "too large")
        assertRefused("(a{100}){600}", null, "too large")
        assertRefused("[a-".repeat(100_000) + "[b]" + "]".repeat(100_000), null, "nested")
        // Many large classes: the translation is bounded too.
        assertRefused("\\w".repeat(20_000), null, "too large")
        // Moderate structure is fine.
        assertTrue(matches("^" + "(".repeat(60) + "a" + ")".repeat(60) + "$", null, "a"))
        assertTrue(matches("^(a{10}){10}$", null, "a".repeat(100)))
        assertTrue(matches("^\\w{1000}$", null, "a".repeat(1000)))
    }

    @Test
    fun `malformed patterns are refused`() {
        for (bad in listOf("(", ")", "[a", "a{", "a{2,1}", "*a", "a\\", "[]", "[^]", "\\x{110000}", "\\u12", "[b-a]", "a**")) {
            assertThrows(XPathRegexException::class.java, { XPathRegex.compile(bad, null) }, bad)
        }
    }

    @Test
    fun `groups count what a replacement can refer to`() {
        assertEquals(2, XPathRegex.compile("(a)(?:b)(?<n>c)", null).groupCount)
        assertEquals(0, XPathRegex.compile("(a)", "q").groupCount)
    }

    /**
     * Where XPath and java.util.regex mean the same (ASCII text, no `$`, `.`, `\w`, `\s` or flags), random patterns of
     * literals, classes, ranges, groups, alternations and greedy or reluctant quantifiers match the same substrings.
     */
    @Test
    fun `random patterns find what java util regex finds`() {
        val random = Random(20261025)
        val atoms = listOf("a", "b", "c", "\\.", "[ab]", "[^a]", "[a-c]", "[b-c-[c]]", "\\d", "x")
        fun pattern(depth: Int): String = buildString {
            repeat(random.nextInt(1, 4)) {
                append(
                    when {
                        depth < 2 && random.nextInt(4) == 0 -> "(" + pattern(depth + 1) + (if (random.nextBoolean()) "|" + pattern(depth + 1) else "") + ")"
                        else -> atoms[random.nextInt(atoms.size)]
                    },
                )
                // Inside a group, nothing may match the empty string: java.util.regex and RE2 handle empty iterations
                // of a repeated group differently (neither is what XPath specifies in detail).
                val quantifiers = if (depth == 0) listOf("", "", "*", "+", "?", "{1,2}", "*?", "+?", "??", "{2}") else listOf("", "+", "{1,2}", "+?", "{2}")
                append(quantifiers[random.nextInt(quantifiers.size)])
            }
        }
        repeat(3000) {
            val regex = pattern(0)
            val java = Pattern.compile(regex.replace("[b-c-[c]]", "[b]"))
            val ours = XPathRegex.compile(regex, null)
            repeat(5) {
                val text = (1..random.nextInt(0, 9)).map { "abcx.1"[random.nextInt(6)] }.joinToString("")
                val m = java.matcher(text)
                val expected = if (m.find()) m.start() until m.end() else null
                assertEquals(expected, ours.firstMatch(text), "$regex on \"$text\"")
            }
        }
    }
}
