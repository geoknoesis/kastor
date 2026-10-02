package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one rule of the `sh:pattern` translation: a pattern is an XPath / XML Schema regular expression, evaluated by
 * `java.util.regex`. XML Schema syntax is translated; `java.util.regex` syntax with the same meaning is passed
 * through; `java.util.regex` syntax that would mean something else is **rejected** - never silently reinterpreted.
 */
class ShaclPatternTranslationTest {
    private fun matches(pattern: String, flags: String?, value: String) = compileShaclPattern(pattern, flags).containsMatchIn(value)

    private fun assertRejected(pattern: String, flags: String? = null, vararg expected: String) {
        val error = assertThrows(ShapeCompileException::class.java, { compileShaclPattern(pattern, flags) }, pattern)
        val message = error.message.orEmpty()
        assertTrue(message.contains(pattern), "$pattern: the message names the pattern: $message")
        for (part in expected) assertTrue(message.contains(part), "$pattern: expected \"$part\" in: $message")
    }

    // --- character class subtraction -------------------------------------------------------------------------------

    @Test
    fun `class subtraction removes the subtracted class`() {
        assertTrue(matches("^[a-z-[aeiou]]+$", null, "xyz"))
        assertFalse(matches("^[a-z-[aeiou]]+$", null, "xay"))
        assertTrue(matches("^[\\w-[_\\d]]+$", null, "abc"))
        assertFalse(matches("^[\\w-[_\\d]]+$", null, "ab_"))
        assertFalse(matches("^[\\w-[_\\d]]+$", null, "ab1"))
        assertTrue(matches("^[\\p{L}-[\\p{Lu}]]+$", null, "abc"))
        assertFalse(matches("^[\\p{L}-[\\p{Lu}]]+$", null, "aBc"))
    }

    @Test
    fun `class subtraction nests and combines with negation`() {
        // a-z minus (the vowels minus e): the consonants and e.
        assertTrue(matches("^[a-z-[aeiou-[e]]]+$", null, "xez"))
        assertFalse(matches("^[a-z-[aeiou-[e]]]+$", null, "xaz"))
        // Three levels: a-z minus (a-m minus (a-f minus (c))) keeps n-z, a, b, d, e, f and drops c and g-m.
        val three = "^[a-z-[a-m-[a-f-[c]]]]+$"
        assertTrue(matches(three, null, "abdefnz"))
        assertFalse(matches(three, null, "c"))
        assertFalse(matches(three, null, "g"))
        // A negated base: everything but a-z, minus the digits.
        assertTrue(matches("^[^a-z-[0-9]]+$", null, "AB"))
        assertFalse(matches("^[^a-z-[0-9]]+$", null, "A1"))
        // A negated subtrahend: a-z minus everything but the vowels = the vowels.
        assertTrue(matches("^[a-z-[^aeiou]]+$", null, "aei"))
        assertFalse(matches("^[a-z-[^aeiou]]+$", null, "abc"))
    }

    @Test
    fun `a subtraction must end the class`() {
        assertRejected("[a-z-[aeiou]x]", null, "subtraction")
        assertRejected("[a-z-[aeiou]", null)
    }

    // --- java.util.regex idioms that are not translated ----------------------------------------------------------------

    @Test
    fun `java class intersection is rejected instead of being read as literal ampersands`() {
        assertRejected("[a-z&&[^aeiou]]", null, "&&")
        assertRejected("^[a-z&&b]$", null, "&&")
        // A single ampersand is a literal, and so is an escaped one.
        assertTrue(matches("^[a&b]+$", null, "a&b"))
        assertTrue(matches("^[a\\&\\&b]+$", null, "a&&b"))
        assertFalse(matches("^[a&b]+$", null, "c"))
    }

    @Test
    fun `java nested classes and unions are rejected instead of being read as literal brackets`() {
        assertRejected("[a-c[x-z]]", null, "[")
        assertRejected("^[[:alpha:]]+$", null, "[")
        assertRejected("[^a[b]]", null, "[")
        // An escaped bracket is a literal.
        assertTrue(matches("^[a\\[\\]b]+$", null, "a[]b"))
    }

    @Test
    fun `inline flag groups are rejected because the translation depends on sh flags`() {
        // (?m) would be ignored by the translated `$`, (?s) by the translated `.`, (?x) by the whitespace handling,
        // and (?i) would be ASCII-only.
        assertRejected("(?m)^a$", null, "(?m)", "sh:flags")
        assertRejected("(?s)a.b", null, "(?s)", "sh:flags")
        assertRejected("(?i)abc", null, "(?i)", "sh:flags")
        assertRejected("a(?x) b", null, "(?x)", "sh:flags")
        assertRejected("(?im)^a$", null, "(?im)")
        assertRejected("(?-i)a", "i", "(?-i)")
        assertRejected("a(?i:b)c", null, "(?i:")
        assertRejected("(?m)^a$", "m", "(?m)")
        // The flags themselves work through sh:flags.
        assertTrue(matches("^b$", "m", "a\nb"))
        assertTrue(matches("^ABC$", "i", "abc"))
        // Quoted or bracketed text that looks like a flag group is literal.
        assertTrue(matches("^\\Q(?m)\\E$", null, "(?m)"))
        assertTrue(matches("^[(?m)]+$", null, "(m?)"))
        assertTrue(matches("^\\(?m\\)$", null, "m)"))
    }

    @Test
    fun `groups that are not flag groups pass through`() {
        assertTrue(matches("^(?:ab)+$", null, "abab"))
        assertTrue(matches("^a(?=b)", null, "ab"))
        assertFalse(matches("^a(?=b)", null, "ac"))
        assertTrue(matches("^a(?!b)", null, "ac"))
        assertTrue(matches("(?<=a)b$", null, "ab"))
        assertTrue(matches("(?<!a)b$", null, "cb"))
        assertTrue(matches("^(?<x>a)\\k<x>$", null, "aa"))
        // `$` inside a lookahead keeps its XPath meaning: the end of the value, not before a final line feed.
        assertTrue(matches("^a(?=$)", null, "a"))
        assertFalse(matches("^a(?=$)", null, "a\n"))
    }

    // --- quantifiers and back-references ---------------------------------------------------------------------------

    @Test
    fun `lazy quantifiers are reluctant`() {
        val greedy = compileShaclPattern("<.+>", null)
        val lazy = compileShaclPattern("<.+?>", null)
        assertEquals("<a><b>", greedy.find("<a><b>")!!.value)
        assertEquals("<a>", lazy.find("<a><b>")!!.value)
        assertEquals("a", compileShaclPattern("a*?a", null).find("aaa")!!.value)
        assertEquals("", compileShaclPattern("a??", null).find("aaa")!!.value)
        assertEquals("aa", compileShaclPattern("a{2,3}?", null).find("aaaa")!!.value)
        assertTrue(matches("^a+?b$", null, "aaab"))
        assertFalse(matches("^a+?b$", null, "b"))
        // The translated `.` under a lazy quantifier still stops at a line feed.
        assertFalse(matches("^<.+?>$", null, "<a\nb>"))
        assertTrue(matches("^<.+?>$", "s", "<a\nb>"))
    }

    @Test
    fun `back-references match the captured text`() {
        assertTrue(matches("^(a|b)\\1$", null, "aa"))
        assertFalse(matches("^(a|b)\\1$", null, "ab"))
        assertTrue(matches("^(\\w+)-\\1$", null, "foo_1-foo_1"))
        assertFalse(matches("^(\\w+)-\\1$", null, "foo-bar"))
        assertTrue(matches("^(a)(b)\\2\\1$", null, "abba"))
        assertTrue(matches("^(a)\\1$", "i", "aA"))
        assertTrue(matches("^(['\"]).*\\1$", null, "'quoted'"))
        assertFalse(matches("^(['\"]).*\\1$", null, "'quoted\""))
    }

    // --- XML name escapes ------------------------------------------------------------------------------------------

    @Test
    fun `name start and name characters follow the XML definitions`() {
        // NameStartChar: ":" | [A-Z] | "_" | [a-z] | the listed ranges. Not digits, "-", "." or the middle dot.
        for (c in listOf(":", "A", "z", "_", "\u00C0", "\u00D8", "\u0370", "\u200C", "\u2070", "\u3001", "\uF900", "\uD800\uDC00")) {
            assertTrue(matches("^\\i$", null, c), "\\i should match U+${c.codePointAt(0).toString(16)}")
            assertFalse(matches("^\\I$", null, c), "\\I should not match U+${c.codePointAt(0).toString(16)}")
            assertTrue(matches("^\\c$", null, c), "\\c should match U+${c.codePointAt(0).toString(16)}")
        }
        // Excluded from NameStartChar although they are letters or symbols: multiplication and division signs, the
        // Greek question mark (U+037E), general punctuation and the non-characters.
        for (c in listOf("1", "-", ".", "\u00B7", "\u00D7", "\u00F7", "\u037E", "\u2028", "\u0300", "\u203F", "\uFFFE", " ", "$", "+")) {
            assertFalse(matches("^\\i$", null, c), "\\i should not match U+${c.codePointAt(0).toString(16)}")
            assertTrue(matches("^\\I$", null, c), "\\I should match U+${c.codePointAt(0).toString(16)}")
        }
        // NameChar adds "-", ".", [0-9], U+00B7, the combining diacritical marks and U+203F / U+2040.
        for (c in listOf("1", "-", ".", "\u00B7", "\u0300", "\u036F", "\u203F", "\u2040")) {
            assertTrue(matches("^\\c$", null, c), "\\c should match U+${c.codePointAt(0).toString(16)}")
            assertFalse(matches("^\\C$", null, c), "\\C should not match U+${c.codePointAt(0).toString(16)}")
        }
        for (c in listOf(" ", "$", "+", "\u00D7", "\u037E", "\u2028", "\uFFFE", "/", "@")) {
            assertFalse(matches("^\\c$", null, c), "\\c should not match U+${c.codePointAt(0).toString(16)}")
            assertTrue(matches("^\\C$", null, c), "\\C should match U+${c.codePointAt(0).toString(16)}")
        }
    }

    @Test
    fun `name escapes compose into NCName and QName patterns and work inside classes`() {
        val ncName = "^[\\i-[:]][\\c-[:]]*$"
        assertTrue(matches(ncName, null, "_a-1.b"))
        assertTrue(matches(ncName, null, "\u00E9l\u00E9ment"))
        assertFalse(matches(ncName, null, "a:b"))
        assertFalse(matches(ncName, null, "1a"))
        assertFalse(matches(ncName, null, "-a"))
        assertTrue(matches("^\\i\\c*$", null, "xs:string"))
        assertFalse(matches("^\\i\\c*$", null, "a b"))
        assertTrue(matches("^[\\c/]+$", null, "a/b-c"))
        assertTrue(matches("^[^\\c]+$", null, " /@"))
        assertFalse(matches("^[^\\c]+$", null, "a"))
        assertTrue(matches("^[\\C]+$", null, " /@"))
        assertTrue(matches("^[\\I\\d]+$", null, "1 2"))
        assertTrue(matches("^\\I\\C$", null, "1 "))
    }

    // --- Unicode blocks --------------------------------------------------------------------------------------------

    /** The block names of XML Schema 1.0 (Unicode 3.1), each with one character of the block. */
    private val xsdBlocks = mapOf(
        "BasicLatin" to "a", "Latin-1Supplement" to "\u00E9", "LatinExtended-A" to "\u0100", "LatinExtended-B" to "\u0180",
        "IPAExtensions" to "\u0250", "SpacingModifierLetters" to "\u02B0", "CombiningDiacriticalMarks" to "\u0300",
        "Greek" to "\u03B1", "Cyrillic" to "\u0416", "Armenian" to "\u0531", "Hebrew" to "\u05D0", "Arabic" to "\u0627",
        "Syriac" to "\u0710", "Thaana" to "\u0780", "Devanagari" to "\u0905", "Bengali" to "\u0985", "Gurmukhi" to "\u0A05",
        "Gujarati" to "\u0A85", "Oriya" to "\u0B05", "Tamil" to "\u0B85", "Telugu" to "\u0C05", "Kannada" to "\u0C85",
        "Malayalam" to "\u0D05", "Sinhala" to "\u0D85", "Thai" to "\u0E01", "Lao" to "\u0E81", "Tibetan" to "\u0F40",
        "Myanmar" to "\u1000", "Georgian" to "\u10D0", "HangulJamo" to "\u1100", "Ethiopic" to "\u1200", "Cherokee" to "\u13A0",
        "UnifiedCanadianAboriginalSyllabics" to "\u1401", "Ogham" to "\u1681", "Runic" to "\u16A0", "Khmer" to "\u1780",
        "Mongolian" to "\u1820", "LatinExtendedAdditional" to "\u1E00", "GreekExtended" to "\u1F00",
        "GeneralPunctuation" to "\u2010", "SuperscriptsandSubscripts" to "\u2070", "CurrencySymbols" to "\u20AC",
        "CombiningMarksforSymbols" to "\u20D0", "LetterlikeSymbols" to "\u2100", "NumberForms" to "\u2153", "Arrows" to "\u2190",
        "MathematicalOperators" to "\u2200", "MiscellaneousTechnical" to "\u2300", "ControlPictures" to "\u2400",
        "OpticalCharacterRecognition" to "\u2440", "EnclosedAlphanumerics" to "\u2460", "BoxDrawing" to "\u2500",
        "BlockElements" to "\u2580", "GeometricShapes" to "\u25A0", "MiscellaneousSymbols" to "\u2600", "Dingbats" to "\u2701",
        "BraillePatterns" to "\u2800", "CJKRadicalsSupplement" to "\u2E80", "KangxiRadicals" to "\u2F00",
        "IdeographicDescriptionCharacters" to "\u2FF0", "CJKSymbolsandPunctuation" to "\u3001", "Hiragana" to "\u3042",
        "Katakana" to "\u30A2", "Bopomofo" to "\u3105", "HangulCompatibilityJamo" to "\u3131", "Kanbun" to "\u3190",
        "BopomofoExtended" to "\u31A0", "EnclosedCJKLettersandMonths" to "\u3200", "CJKCompatibility" to "\u3300",
        "CJKUnifiedIdeographsExtensionA" to "\u3400", "CJKUnifiedIdeographs" to "\u4E00", "YiSyllables" to "\uA000",
        "YiRadicals" to "\uA490", "HangulSyllables" to "\uAC00", "PrivateUse" to "\uE000",
        "CJKCompatibilityIdeographs" to "\uF900", "AlphabeticPresentationForms" to "\uFB00",
        "ArabicPresentationForms-A" to "\uFB50", "CombiningHalfMarks" to "\uFE20", "CJKCompatibilityForms" to "\uFE30",
        "SmallFormVariants" to "\uFE50", "ArabicPresentationForms-B" to "\uFE70", "Specials" to "\uFFFD",
        "HalfwidthandFullwidthForms" to "\uFF21", "OldItalic" to "\uD800\uDF00", "Gothic" to "\uD800\uDF30",
        "Deseret" to "\uD801\uDC00", "ByzantineMusicalSymbols" to "\uD834\uDC00", "MusicalSymbols" to "\uD834\uDD00",
        "MathematicalAlphanumericSymbols" to "\uD835\uDC00", "CJKUnifiedIdeographsExtensionB" to "\uD840\uDC00",
        "CJKCompatibilityIdeographsSupplement" to "\uD87E\uDC00", "Tags" to "\uDB40\uDC01",
    )

    @Test
    fun `every XML Schema block name is mapped to its Unicode block`() {
        for ((block, sample) in xsdBlocks) {
            assertTrue(matches("^\\p{Is$block}$", null, sample), "\\p{Is$block} should match U+${sample.codePointAt(0).toString(16)}")
            assertFalse(matches("^\\P{Is$block}$", null, sample), "\\P{Is$block} should not match U+${sample.codePointAt(0).toString(16)}")
            // No block but BasicLatin contains "a"; BasicLatin does not contain e-acute.
            val outside = if (block == "BasicLatin") "\u00E9" else "a"
            assertFalse(matches("^\\p{Is$block}$", null, outside), "\\p{Is$block} should not match $outside")
            assertTrue(matches("^\\P{Is$block}$", null, outside), "\\P{Is$block} should match $outside")
        }
    }

    @Test
    fun `block escapes work inside classes and surrogate blocks compile`() {
        assertTrue(matches("^[\\p{IsGreek}\\p{IsCyrillic}]+$", null, "\u03B1\u0416"))
        assertFalse(matches("^[\\p{IsGreek}\\p{IsCyrillic}]+$", null, "a"))
        assertTrue(matches("^[^\\p{IsBasicLatin}]+$", null, "\u03B1"))
        assertTrue(matches("^[\\p{IsBasicLatin}-[\\p{Lu}]]+$", null, "abc"))
        assertFalse(matches("^[\\p{IsBasicLatin}-[\\p{Lu}]]+$", null, "aBc"))
        for (block in listOf("HighSurrogates", "HighPrivateUseSurrogates", "LowSurrogates")) compileShaclPattern("\\p{Is$block}", null)
        // Blocks added to Unicode after XML Schema 1.0 are accepted under their Unicode names.
        assertTrue(matches("^\\p{IsEmoticons}$", null, "\uD83D\uDE00"))
        assertTrue(matches("^\\p{IsCyrillicSupplement}$", null, "\u0500"))
    }

    @Test
    fun `unknown block names are rejected with the name`() {
        assertRejected("\\p{IsNoSuchBlock}", null, "NoSuchBlock", "block")
        // java.util.regex reads \p{IsLatin} as a script and \p{IsAlphabetic} as a binary property; XML Schema has neither.
        assertRejected("^\\p{IsLatin}+$", null, "Latin", "block")
        assertRejected("^\\p{IsAlphabetic}+$", null, "Alphabetic", "block")
        assertRejected("\\P{IsLetter}", null, "Letter")
        assertRejected("\\p{IsGreek", null, "}")
        assertRejected("[\\p{IsNoSuchBlock}]", null, "NoSuchBlock")
        // General categories are not block names and stay as they are.
        assertTrue(matches("^\\p{Lu}\\p{Ll}+$", null, "Abc"))
        assertTrue(matches("^\\p{L}+$", null, "\u00E9t\u00E9"))
    }
}
