package com.geoknoesis.kastor.rdf.sparql.internal

/**
 * **Not public API.** Finds a number of too many digits in SPARQL text before a provider parses it: a SPARQL parser
 * (or the store it writes to) turns a numeric literal into a value, with `BigInteger` / `BigDecimal`, in time quadratic
 * in its digits. The digits are counted on the text as a SPARQL parser reads it: a `\u` escape (four hex digits) or
 * a `\U` escape (eight) counts as the character it stands for, wherever it is, and a run of digits continues across
 * one `.` and one exponent marker (`e`/`E`, with its sign), so `"<5,001 digits>.<5,001 digits>"^^xsd:decimal` counts
 * 10,002 digits, as its lexical form does. A string literal that holds such a run is caught too; no realistic query
 * or update does.
 */
object SparqlNumberText {

    /** The index of the first character of the first number of [sparql] with more than [limit] digits, or -1. */
    @JvmStatic
    fun numberLongerThan(sparql: String, limit: Int): Int {
        val number = DigitRun()
        var i = 0
        var runStart = 0
        while (i < sparql.length) {
            val start = i
            val c = escapeAt(sparql, i)?.also { (_, end) -> i = end }?.first ?: sparql[i++].code
            val digits = number.next(c)
            if (digits == 1) runStart = start
            if (digits > limit) return runStart
        }
        return -1
    }

    private class DigitRun {
        private var digits = 0
        private var point = false
        private var exponent = false
        private var previous = -1

        /** The digit count after the character [c]. */
        fun next(c: Int): Int {
            when {
                c in '0'.code..'9'.code -> digits++
                c == '.'.code && digits > 0 && !point && !exponent -> point = true
                (c == 'e'.code || c == 'E'.code) && digits > 0 && !exponent -> exponent = true
                (c == '+'.code || c == '-'.code) && (previous == 'e'.code || previous == 'E'.code) && exponent -> Unit
                else -> { digits = 0; point = false; exponent = false }
            }
            previous = c
            return digits
        }
    }

    /** The code point of the `\u` / `\U` escape at [index] of [text] and the index after it, or null for none. */
    private fun escapeAt(text: String, index: Int): Pair<Int, Int>? {
        if (text[index] != '\\' || index + 1 >= text.length) return null
        var i = index + 1
        val hexDigits = when (text[i]) {
            'u' -> { while (i < text.length && text[i] == 'u') i++; 4 }
            'U' -> { i++; 8 }
            else -> return null
        }
        if (i + hexDigits > text.length) return null
        val hex = text.substring(i, i + hexDigits)
        if (!hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        val code = hex.toLongOrNull(16)?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
        return code to i + hexDigits
    }
}
