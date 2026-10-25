package com.geoknoesis.kastor.rdf.shacl.native

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LanguageRangesTest {
    /** The per-range check that [LanguageRanges] replaced, kept as the oracle. */
    private fun matchesOne(valueLang: String, range: String): Boolean {
        if (range == "*") return true
        val v = valueLang.lowercase()
        val r = range.lowercase()
        if (v == r) return true
        if (r.endsWith("-*")) {
            val prefix = r.dropLast(2)
            return prefix.isEmpty() || v == prefix || v.startsWith("$prefix-")
        }
        return v.startsWith("$r-")
    }

    @Test
    fun `the lookup agrees with the per-range check`() {
        val tags = listOf("en", "EN-gb", "en-gb-oed", "de", "de-CH", "deu", "fr", "zh-Hant-TW", "x-private", "i-klingon")
        val ranges = listOf("", "*", "-*", "en", "EN-*", "en-GB", "de-*", "zh-hant", "deu-*", "x", "i-klingon", "en-", "fr-*-*")
        for (tag in tags) {
            for (range in ranges) assertEquals(matchesOne(tag, range), LanguageRanges(listOf(range)).matches(tag), "$tag / $range")
            for (a in ranges) for (b in ranges) {
                assertEquals(matchesOne(tag, a) || matchesOne(tag, b), LanguageRanges(listOf(a, b)).matches(tag), "$tag / $a, $b")
            }
        }
    }
}
