package com.geoknoesis.kastor.rdf

/**
 * The character classes and small lexical scans shared by the term model and the SPARQL scanner: name and
 * variable-name characters (SPARQL 1.1 section 19.8, the same productions as Turtle and N-Triples), exponents and
 * codepoint escapes. [SparqlDatasetClauses] tokenizes with them; [isValidBlankNodeLabel] uses [isPnCharsBase].
 */
internal object RdfNameChars {
    /** The SPARQL production `PN_CHARS_BASE`: the letters a name or a variable name may start with. */
    fun isPnCharsBase(codePoint: Int): Boolean =
        codePoint in 'A'.code..'Z'.code || codePoint in 'a'.code..'z'.code ||
            codePoint in 0x00C0..0x00D6 || codePoint in 0x00D8..0x00F6 || codePoint in 0x00F8..0x02FF ||
            codePoint in 0x0370..0x037D || codePoint in 0x037F..0x1FFF || codePoint in 0x200C..0x200D ||
            codePoint in 0x2070..0x218F || codePoint in 0x2C00..0x2FEF || codePoint in 0x3001..0xD7FF ||
            codePoint in 0xF900..0xFDCF || codePoint in 0xFDF0..0xFFFD || codePoint in 0x10000..0xEFFFF

    /** First character of the SPARQL production `VARNAME`: `PN_CHARS_U | [0-9]`. */
    fun isVarNameStart(codePoint: Int): Boolean =
        isPnCharsBase(codePoint) || codePoint == '_'.code || codePoint in '0'.code..'9'.code

    /**
     * Later characters of `VARNAME`: `PN_CHARS_U | [0-9] | U+00B7 | [U+0300-U+036F] | [U+203F-U+2040]` - the
     * middle dot, the combining diacritical marks, and the undertie and character tie.
     */
    fun isVarNamePart(codePoint: Int): Boolean =
        isVarNameStart(codePoint) || codePoint == 0x00B7 || codePoint in 0x0300..0x036F || codePoint in 0x203F..0x2040

    /**
     * Index after the name character at [index] of [q] - `PN_CHARS` (what [isVarNamePart] allows, and `-`) or a dot -
     * or [index] itself if there is none.
     */
    fun nameCharEnd(q: String, index: Int): Int {
        val codePoint = q.codePointAt(index)
        return if (isVarNamePart(codePoint) || codePoint == '-'.code || codePoint == '.'.code) {
            index + Character.charCount(codePoint)
        } else index
    }

    /** End of the exponent (`e`, optional sign, digits) starting at [from], or -1 if there is none. */
    fun exponentEnd(q: String, from: Int): Int {
        if (from >= q.length || (q[from] != 'e' && q[from] != 'E')) return -1
        var k = from + 1
        if (k < q.length && (q[k] == '+' || q[k] == '-')) k++
        val digits = k
        while (k < q.length && q[k] in '0'..'9') k++
        return if (k > digits) k else -1
    }

    /** End of the codepoint escape that starts with the backslash at [from], or -1 if there is none. */
    fun escapeEnd(q: String, from: Int): Int {
        val digits = when (q.getOrNull(from + 1)) {
            'u' -> 4
            'U' -> 8
            else -> return -1
        }
        val end = from + 2 + digits
        if (end > q.length) return -1
        for (k in from + 2 until end) if (Character.digit(q[k], 16) < 0) return -1
        return end
    }
}
