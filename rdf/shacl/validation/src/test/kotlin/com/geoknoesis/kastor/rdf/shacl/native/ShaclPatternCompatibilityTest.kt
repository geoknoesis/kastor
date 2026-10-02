package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Edge cases of the character class parser, the `java.util.regex` / POSIX idioms that are translated with their
 * intended meaning (a leading inline flag group, script names, POSIX bracket expressions) and the scope of the `i`
 * flag (XPath F&O 5.6.2: only characters, ranges and back-references are case-insensitive).
 */
class ShaclPatternCompatibilityTest {
    private fun matches(pattern: String, flags: String?, value: String): Boolean = compileShaclPattern(pattern, flags).containsMatchIn(value)

    private fun assertRejected(pattern: String, flags: String? = null, vararg expected: String) {
        val error = assertThrows(ShapeCompileException::class.java, { compileShaclPattern(pattern, flags) }, pattern)
        val message = error.message.orEmpty()
        assertTrue(message.contains(pattern), "$pattern: the message names the pattern: $message")
        for (part in expected) assertTrue(message.contains(part), "$pattern: expected \"$part\" in: $message")
    }

    private val alpha = Char(0x3B1).toString()
    private val greekExtended = Char(0x1F00).toString()
    private val eAcute = Char(0xE9).toString()
    private val eAcuteUpper = Char(0xC9).toString()
    private val han = Char(0x4E00).toString()

    // --- character classes: brackets ---------------------------------------------------------------------------------

    @Test
    fun `a closing bracket right after the opening one is rejected instead of being read as a member`() {
        // java.util.regex reads "[].]" as "a bracket or a dot" and POSIX too; the translation used to close the class at
        // the first bracket and produce "[]" followed by "any character" and a literal bracket.
        assertRejected("[].]", null, "']'", "\\]")
        assertRejected("[^].]", null, "']'", "\\]")
        assertRejected("^[]a]+$", null, "']'")
        assertRejected("[]", null, "']'")
        assertRejected("[^]", null, "']'")
        assertRejected("a[]", null, "']'")
        // The subtrahend of a subtraction is a class of its own.
        assertRejected("[a-z-[]]", null, "']'")
        assertRejected("[a-z-[]x]]", null, "']'")
    }

    @Test
    fun `escaped brackets and backslashes are members`() {
        assertTrue(matches("^[a\\]b]+$", null, "a]b"))
        assertFalse(matches("^[a\\]b]+$", null, "c"))
        assertTrue(matches("^[\\].]+$", null, "]."))
        assertFalse(matches("^[\\].]+$", null, "x"))
        assertTrue(matches("^[^\\].]+$", null, "x"))
        assertFalse(matches("^[^\\].]+$", null, "]"))
        assertTrue(matches("^[\\\\]$", null, "\\"))
        assertFalse(matches("^[\\\\]$", null, "a"))
        // The class ends at the first unescaped bracket; a later bracket is an ordinary character.
        assertTrue(matches("^[\\\\]]$", null, "\\]"))
        assertTrue(matches("^[\\[\\]]+$", null, "[]"))
    }

    @Test
    fun `an unterminated class is rejected`() {
        assertRejected("[a", null)
        assertRejected("[a\\]", null)
        assertRejected("[^", null)
        assertRejected("[", null)
        assertRejected("[a-", null)
    }

    // --- character classes: hyphens ------------------------------------------------------------------------------------

    @Test
    fun `a leading or trailing hyphen is a literal`() {
        assertTrue(matches("^[-a]+$", null, "a-"))
        assertFalse(matches("^[-a]+$", null, "b"))
        assertTrue(matches("^[a-]+$", null, "-a"))
        assertFalse(matches("^[a-]+$", null, "b"))
        assertTrue(matches("^[^-a]$", null, "b"))
        assertFalse(matches("^[^-a]$", null, "-"))
        assertFalse(matches("^[^a-]$", null, "-"))
        assertTrue(matches("^[-]$", null, "-"))
        assertTrue(matches("^[--]$", null, "-"))
        assertTrue(matches("^[a-z-]+$", null, "x-y"))
        assertTrue(matches("^[-a-z]+$", null, "x-y"))
        assertTrue(matches("^[a\\-z]+$", null, "a-z"))
        assertFalse(matches("^[a\\-z]+$", null, "b"))
    }

    @Test
    fun `a hyphen after a range is a literal and not a second range`() {
        assertTrue(matches("^[a-c-e]+$", null, "abc-e"))
        assertFalse(matches("^[a-c-e]+$", null, "d"))
    }

    @Test
    fun `an unescaped hyphen is never the endpoint of a range`() {
        // XML Schema 1.1: "it is an error if either of the two singleChars in a charRange is an unescaped hyphen".
        // java.util.regex reads "[+--]" as the range from "+" to "-" (which contains ","), "[--a]" as "-" to "a".
        assertRejected("[a--b]", null, "'-'", "\\-")
        assertRejected("[+--]", null, "'-'", "\\-")
        assertRejected("[--a]", null, "'-'", "\\-")
        // Escaped, the hyphen is an ordinary endpoint.
        assertTrue(matches("^[+-\\-]+$", null, "+,-"))
        assertTrue(matches("^[\\--a]+$", null, "-0A"))
        assertFalse(matches("^[\\--a]+$", null, "b"))
    }

    @Test
    fun `ranges between escapes keep their endpoints`() {
        assertTrue(matches("^[\\x41-\\x5A]+$", null, "AMZ"))
        assertFalse(matches("^[\\x41-\\x5A]+$", null, "a"))
        assertFalse(matches("^[\\x41-\\x5A]+$", null, "4"))
        assertTrue(matches("^[\\x{41}-\\x{5A}]+$", null, "AMZ"))
        assertTrue(matches("^[\\t-\\r]+$", null, "\t\n\r"))
        assertFalse(matches("^[\\t-\\r]+$", null, " "))
        assertTrue(matches("^[!-\\]]+$", null, "!A]"))
        assertFalse(matches("^[!-\\]]+$", null, "a"))
        // A reversed range is an error, as in java.util.regex.
        assertRejected("[z-a]", null)
    }

    @Test
    fun `subtraction is told apart from a hyphen`() {
        assertTrue(matches("^[a-z-[b]]+$", null, "acz"))
        assertFalse(matches("^[a-z-[b]]+$", null, "b"))
        assertFalse(matches("^[a-z-[b]]+$", null, "-"))
        // A hyphen member before the subtraction.
        assertTrue(matches("^[a-z\\--[b]]+$", null, "a-z"))
        assertFalse(matches("^[a-z\\--[b]]+$", null, "b"))
        // A subtraction needs something to subtract from.
        assertRejected("[-[a]]", null, "subtraction")
        assertRejected("[^-[a]]", null, "subtraction")
    }

    // --- leading inline flags ------------------------------------------------------------------------------------------

    @Test
    fun `a leading inline flag group means the same as sh flags`() {
        assertTrue(matches("(?i)^abc$", null, "ABC"))
        assertFalse(matches("^abc$", null, "ABC"))
        // Unicode aware, as the i flag of sh:flags.
        assertTrue(matches("(?i)^" + eAcute + "$", null, eAcuteUpper))
        assertTrue(matches("(?s)^a.c$", null, "a\nc"))
        assertFalse(matches("^a.c$", null, "a\nc"))
        assertTrue(matches("(?m)^b$", null, "a\nb"))
        assertFalse(matches("^b$", null, "a\nb"))
        // The translated anchors follow the flag: with m, "$" matches before a line feed only (XPath).
        assertFalse(matches("(?m)^a$", null, "a\rb"))
        assertTrue(matches("(?x)^ a b $", null, "ab"))
        assertFalse(matches("(?x)^ a b $", null, "a b"))
        assertTrue(matches("(?im)^B$", null, "a\nb"))
        assertTrue(matches("(?is)^A.C$", null, "a\nc"))
        assertTrue(matches("(?i)(?m)^B$", null, "a\nb"))
    }

    @Test
    fun `leading inline flags are merged with sh flags`() {
        assertTrue(matches("(?i)^B$", "m", "a\nb"))
        assertTrue(matches("(?m)^B$", "i", "a\nb"))
        assertTrue(matches("(?i)^b$", "i", "B"))
        assertFalse(matches("(?i)^B$", null, "a\nb"))
        // With q the pattern is literal text, flag group included.
        assertTrue(matches("(?i)a", "q", "(?i)a"))
        assertFalse(matches("(?i)a", "q", "A"))
    }

    @Test
    fun `flag groups that are not leading, scoped or with other flags stay rejected`() {
        assertRejected("a(?i)b", null, "(?i)", "sh:flags")
        assertRejected("^(?i)abc$", null, "(?i)", "sh:flags")
        assertRejected("(?i:abc)", null, "(?i:", "sh:flags")
        assertRejected("(?i)a(?-i)b", null, "(?-i)")
        assertRejected("(?-i)abc", "i", "(?-i)")
        assertRejected("(?u)abc", null, "(?u)")
        assertRejected("(?d)^abc$", null, "(?d)")
        assertRejected("(?U)abc", null, "(?U)")
        assertRejected("(?iu)abc", null, "(?iu)")
        assertRejected("(?i-s)abc", null, "(?i-s)")
        assertRejected("(?i)(?s:a.b)", null, "(?s:")
        assertRejected(" (?i)abc", "x", "(?i)")
    }

    @Test
    fun `a leading x flag group rejects what java comments mode would read differently`() {
        // java.util.regex: "#" starts a comment and whitespace is ignored inside classes too. XPath: neither.
        assertRejected("(?x)a # the letter a", null, "#")
        assertRejected("(?x)[a b]", null, "whitespace")
        assertTrue(matches("(?x)^a \\# b$", null, "a#b"))
        // Through sh:flags the XPath rule applies unchanged.
        assertTrue(matches("^a # b$", "x", "a#b"))
        assertTrue(matches("^a[ ]b$", "x", "a b"))
    }

    // --- \p{IsX}: blocks and scripts -----------------------------------------------------------------------------------

    @Test
    fun `IsX is a block when X names a block and a script otherwise`() {
        // Latin is not a block: java.util.regex script.
        assertTrue(matches("^\\p{IsLatin}+$", null, "abc" + eAcute))
        assertFalse(matches("^\\p{IsLatin}+$", null, alpha))
        assertFalse(matches("^\\P{IsLatin}$", null, "a"))
        assertTrue(matches("^\\P{IsLatin}$", null, alpha))
        assertTrue(matches("^\\p{IsHan}$", null, han))
        assertTrue(matches("^[\\p{IsLatin}\\d]+$", null, "a1"))
        // Greek is a block in XML Schema (U+0370..U+03FF): the block wins over the script of the same name, which
        // would also contain the Greek Extended block.
        assertTrue(matches("^\\p{IsGreek}$", null, alpha))
        assertFalse(matches("^\\p{IsGreek}$", null, greekExtended))
        assertTrue(matches("^\\p{IsGreekExtended}$", null, greekExtended))
        // Neither a block nor a script: rejected (java.util.regex binary properties are not supported).
        assertRejected("^\\p{IsAlphabetic}+$", null, "Alphabetic", "block", "script")
        assertRejected("\\p{IsNoSuchThing}", null, "NoSuchThing")
    }

    // --- POSIX bracket expressions -------------------------------------------------------------------------------------

    @Test
    fun `the twelve POSIX classes are translated inside a character class`() {
        val cases = listOf(
            Triple("alpha", "aZ", "1"), Triple("digit", "09", "a"), Triple("alnum", "aZ9", "_"), Triple("upper", "AZ", "a"),
            Triple("lower", "az", "A"), Triple("space", " \t\n", "a"), Triple("punct", "!-~", "a"), Triple("xdigit", "09afAF", "g"),
            Triple("blank", " \t", "\n"), Triple("cntrl", "\t\n", "a"), Triple("graph", "a!~", " "), Triple("print", "a! ", "\t"),
        )
        for ((name, inside, outside) in cases) {
            val pattern = "^[[:$name:]]+$"
            assertTrue(matches(pattern, null, inside), "$pattern should match \"$inside\"")
            assertFalse(matches(pattern, null, outside), "$pattern should not match \"$outside\"")
        }
    }

    @Test
    fun `POSIX classes combine with other members negation and subtraction`() {
        assertTrue(matches("^[[:alpha:]_-]+$", null, "a_-B"))
        assertFalse(matches("^[[:alpha:]_-]+$", null, "1"))
        assertTrue(matches("^[[:alpha:][:digit:]]+$", null, "a1"))
        assertTrue(matches("^[^[:space:]]+$", null, "ab"))
        assertFalse(matches("^[^[:space:]]+$", null, "a b"))
        assertTrue(matches("^[[:alnum:]-[[:digit:]]]+$", null, "abc"))
        assertFalse(matches("^[[:alnum:]-[[:digit:]]]+$", null, "ab1"))
        // POSIX classes are the ASCII ones.
        assertFalse(matches("^[[:alpha:]]$", null, eAcute))
    }

    @Test
    fun `unknown POSIX names and other nested brackets are rejected`() {
        assertRejected("[[:word:]]", null, "word", "POSIX")
        assertRejected("[[:ALPHA:]]", null, "ALPHA", "POSIX")
        assertRejected("[[:^alpha:]]", null, "POSIX")
        assertRejected("[[:alpha]]", null, "POSIX")
        assertRejected("[[.a.]]", null, "[")
        assertRejected("[[=a=]]", null, "[")
        assertRejected("[a-c[x-z]]", null, "[")
        // Outside a class, "[:alpha:]" is an ordinary class of its characters.
        assertTrue(matches("^[:alpha:]+$", null, "pal:"))
    }

    // --- the i flag ----------------------------------------------------------------------------------------------------

    @Test
    fun `i does not affect category block and script escapes`() {
        // XPath F&O 5.6.2: "\p{Lu} continues to match upper-case letters only". java.util.regex makes \p{Lu}, \p{Ll}
        // and \p{Lt} match every cased letter under CASE_INSENSITIVE.
        assertTrue(matches("^\\p{Lu}$", "i", "A"))
        assertFalse(matches("^\\p{Lu}$", "i", "a"))
        assertTrue(matches("^\\p{Ll}+$", "i", "abc"))
        assertFalse(matches("^\\p{Ll}+$", "i", "aBc"))
        assertFalse(matches("^\\P{Lu}$", "i", "A"))
        assertTrue(matches("^\\P{Lu}$", "i", "a"))
        assertFalse(matches("(?i)^\\p{Lu}$", null, "a"))
        // The rest of the pattern is case-insensitive.
        assertTrue(matches("^x\\p{Lu}y$", "i", "XAY"))
        assertFalse(matches("^x\\p{Lu}y$", "i", "XaY"))
        assertTrue(matches("^\\p{Lu}{2}x$", "i", "ABX"))
    }

    @Test
    fun `i affects the characters and ranges of a class but not its category escapes`() {
        assertTrue(matches("^[\\p{Lu}x]+$", "i", "AxX"))
        assertFalse(matches("^[\\p{Lu}x]+$", "i", "a"))
        assertTrue(matches("^[^\\p{Lu}x]+$", "i", "ab"))
        assertFalse(matches("^[^\\p{Lu}x]+$", "i", "A"))
        assertFalse(matches("^[^\\p{Lu}x]+$", "i", "x"))
        assertFalse(matches("^[^\\p{Lu}x]+$", "i", "X"))
        assertTrue(matches("^[\\p{Lu}]+$", "i", "AB"))
        assertFalse(matches("^[\\p{Lu}]+$", "i", "ab"))
        assertTrue(matches("^[^\\p{Lu}]+$", "i", "ab"))
        assertFalse(matches("^[^\\p{Lu}]+$", "i", "AB"))
        // Subtraction: letters that are not upper case.
        assertTrue(matches("^[\\p{L}-[\\p{Lu}]]+$", "i", "abc"))
        assertFalse(matches("^[\\p{L}-[\\p{Lu}]]+$", "i", "aBc"))
        assertTrue(matches("^[a-z-[\\p{Lu}]]+$", "i", "abc"))
        assertFalse(matches("^[a-z-[\\p{Lu}]]+$", "i", "ABC"), "A-Z are case variants of a-z, but upper case letters are subtracted")
        assertTrue(matches("^[[:upper:]x]+$", "i", "AxX"))
        assertFalse(matches("^[[:upper:]x]+$", "i", "a"))
    }

    @Test
    fun `i makes characters ranges negated classes and subtractions case-insensitive`() {
        // XPath F&O 5.6.2, rules 1 to 4.
        assertTrue(matches("^z$", "i", "Z"))
        assertTrue(matches("^[A-Z]+$", "i", "abc"))
        assertTrue(matches("^[A-Z-[IO]]+$", "i", "aAbB"))
        assertFalse(matches("^[A-Z-[IO]]+$", "i", "i"))
        assertFalse(matches("^[A-Z-[IO]]+$", "i", "O"))
        assertTrue(matches("^[^Q]$", "i", "a"))
        assertFalse(matches("^[^Q]$", "i", "q"))
        assertFalse(matches("^[^Q]$", "i", "Q"))
        // Without i, the same category escapes and classes are unchanged.
        assertTrue(matches("^[\\p{Lu}x]+$", null, "Ax"))
        assertFalse(matches("^[\\p{Lu}x]+$", null, "xa"))
    }
}
