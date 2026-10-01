package com.geoknoesis.kastor.gen.processor.utils

import com.geoknoesis.kastor.gen.processor.internal.utils.ShaclPatterns
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** XPath (`sh:pattern`) regular expressions keep their XPath meaning after translation to java.util.regex. */
class ShaclPatternTranslationTest {

    private fun matches(pattern: String, flags: String?, input: String): Boolean =
        Regex(ShaclPatterns.toJava(pattern, flags), ShaclPatterns.options(flags).map { RegexOption.valueOf(it) }.toSet())
            .containsMatchIn(input)

    @Test
    fun `dollar matches only at the very end of the input unless the m flag is set`() {
        assertTrue(matches("^\\d+$", null, "123"))
        assertFalse(matches("^\\d+$", null, "123\n"), "XPath end anchor does not match before a final newline")
        assertFalse(matches("^\\d+$", "i", "123\n"))
        assertTrue(matches("^a$", "m", "a\nb"), "with m, the end anchor matches before a newline")
        assertTrue(matches("^b$", "m", "a\nb"))
        assertFalse(matches("^a$", "m", "a\r\nb"), "only a newline ends a line in XPath")
        assertTrue(matches("^[$]$", null, "$"), "a dollar inside a class is literal")
        assertTrue(matches("^\\$$", null, "$"), "an escaped dollar is literal")
        assertTrue(matches("a$", "q", "a$"), "q matches the pattern literally")
    }

    @Test
    fun `x flag removes whitespace outside character classes only and does not start comments`() {
        assertTrue(matches("^a b # c$", "x", "ab#c"))
        assertFalse(matches("^a b # c$", "x", "ab"))
        assertTrue(matches("^[a b]+$", "x", "a b"))
        assertFalse("COMMENTS" in ShaclPatterns.options("x"))
    }

    @Test
    fun `digit word and space escapes follow XPath semantics`() {
        val arabicThree = Char(0x0663).toString()
        val arabicFour = Char(0x0664).toString()
        val superscriptTwo = Char(0x00B2).toString()
        val eAcute = Char(0x00E9).toString()
        val verticalTab = Char(0x000B).toString()
        assertTrue(matches("^\\d+$", null, arabicThree + arabicFour), "Arabic-Indic digits are Nd")
        assertFalse(matches("^\\d$", null, superscriptTwo), "superscript two is No, not Nd")
        assertTrue(matches("^\\D$", null, "a"))
        assertFalse(matches("^\\D$", null, arabicThree))
        assertTrue(matches("^\\w+$", null, eAcute + "t" + eAcute + "\$+"), "letters and symbols are word characters")
        assertFalse(matches("^\\w$", null, "_"), "punctuation is not a word character in XPath")
        assertFalse(matches("^\\w$", null, " "))
        assertTrue(matches("^[\\w-]+$", null, "a-b"))
        assertTrue(matches("^[\\W]$", null, "_"))
        assertFalse(matches("^[\\W]$", null, "a"))
        assertTrue(matches("^\\s$", null, "\t"))
        assertFalse(matches("^\\s$", null, verticalTab), "vertical tab is not XPath whitespace")
        assertTrue(matches("^[\\s\\d]+$", null, " $arabicThree"))
        assertFalse(matches("^\\S$", null, "\r"))
    }

    @Test
    fun `character class subtraction keeps XPath precedence`() {
        // [^a-z-[0-9]] is (not a-z) minus digits.
        assertTrue(matches("^[^a-z-[0-9]]$", null, "A"))
        assertFalse(matches("^[^a-z-[0-9]]$", null, "5"))
        assertFalse(matches("^[^a-z-[0-9]]$", null, "b"))
        assertTrue(matches("^[a-z-[aeiou]]+$", null, "bcd"))
        assertFalse(matches("^[a-z-[aeiou]]+$", null, "abc"))
        // a-z minus (not vowels) is the vowels.
        assertTrue(matches("^[a-z-[^aeiou]]+$", null, "aei"))
        assertFalse(matches("^[a-z-[^aeiou]]+$", null, "b"))
        // a-z minus (vowels minus e).
        assertTrue(matches("^[a-z-[aeiou-[e]]]+$", null, "bed"))
        assertFalse(matches("^[a-z-[aeiou-[e]]]+$", null, "bad"))
        // Name escapes still work inside a subtraction.
        assertTrue(matches("^[\\i-[:]][\\c-[:]]*$", null, "ab-c"))
        assertFalse(matches("^[\\i-[:]][\\c-[:]]*$", null, "a:b"))
    }

    @Test
    fun `q flag is literal and ignores m s and x`() {
        assertTrue(matches("a b.", "qx", "xa b."))
        assertFalse(matches("a b.", "qx", "ab!"))
        assertEquals(listOf("LITERAL"), ShaclPatterns.options("qxms"))
        assertEquals(listOf("IGNORE_CASE", "LITERAL"), ShaclPatterns.options("iq"))
        assertTrue(matches("A B", "qi", "a b"))
    }

    @Test
    fun `caret with the m flag matches at the start and after a newline only`() {
        val lineSeparator = Char(0x2028).toString()
        val nextLine = Char(0x0085).toString()
        assertTrue(matches("^b", "m", "a\nb"))
        assertTrue(matches("^a", "m", "a\nb"))
        assertFalse(matches("^b", "m", "a\rb"), "a carriage return does not start a line in XPath")
        assertFalse(matches("^b", "m", "a" + lineSeparator + "b"))
        assertFalse(matches("^b", "m", "a" + nextLine + "b"))
        assertFalse(matches("^b", null, "a\nb"), "without m the caret only matches at the start of the input")
        assertTrue(matches("^[^b]$", "m", "a"), "a caret inside a class is a negation")
    }

    @Test
    fun `dot matches everything but newline and carriage return unless the s flag is set`() {
        val lineSeparator = Char(0x2028).toString()
        val nextLine = Char(0x0085).toString()
        assertTrue(matches("^a.c$", null, "abc"))
        assertFalse(matches("^a.c$", null, "a\nc"))
        assertFalse(matches("^a.c$", null, "a\rc"))
        assertTrue(matches("^a.c$", null, "a" + nextLine + "c"), "U+0085 is an ordinary character in XPath")
        assertTrue(matches("^a.c$", null, "a" + lineSeparator + "c"), "U+2028 is an ordinary character in XPath")
        assertTrue(matches("^a.c$", "s", "a\nc"), "with s the dot matches every character")
        assertTrue(matches("^a.c$", "s", "a\rc"))
        assertTrue(matches("^a[.]c$", null, "a.c"), "a dot inside a class is literal")
        assertFalse(matches("^a[.]c$", null, "abc"))
        assertTrue(matches("^a\\.c$", null, "a.c"), "an escaped dot is literal")
        assertFalse(matches("^a\\.c$", null, "abc"))
        assertTrue(matches("^.+$", null, "x" + nextLine), "a dot before the end anchor")
    }

    @Test
    fun `quoted sections are copied verbatim`() {
        assertTrue(matches("^\\Qa\$b^c.\\E$", null, "a\$b^c."))
        assertFalse(matches("^\\Qa\$b^c.\\E$", null, "a\$b^cx"), "the dot inside the quotation is literal")
        assertTrue(matches("\\Q^$\\E", "m", "x^\$y"), "anchors inside a quotation are literal with the m flag too")
        assertFalse(matches("\\Q^$\\E", "m", "x\ny"))
        assertTrue(matches("^\\Q a b \\E$", "x", " a b "), "the x flag keeps whitespace inside a quotation")
        assertTrue(matches("^\\Q[$\\E]$", null, "[\$]"), "a quotation does not open a character class")
        assertTrue(matches("^a\\Q$", null, "a\$"), "an unterminated quotation runs to the end of the pattern")
    }
}
