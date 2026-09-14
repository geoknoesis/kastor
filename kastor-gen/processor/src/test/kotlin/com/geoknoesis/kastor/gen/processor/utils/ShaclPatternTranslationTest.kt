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
}
