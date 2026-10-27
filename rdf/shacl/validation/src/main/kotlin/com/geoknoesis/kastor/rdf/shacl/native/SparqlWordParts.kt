package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.shacl.native.SparqlQueryTemplate.Companion.isPnChars
import com.geoknoesis.kastor.rdf.shacl.native.SparqlQueryTemplate.Companion.isPnCharsBase
import com.geoknoesis.kastor.rdf.shacl.native.SparqlQueryTemplate.Companion.isPnCharsU

/** How the pre-binding restriction check ([SparqlQueryTemplate.restrictionViolation]) reads a WORD token. */
internal object SparqlWordParts {

    /**
     * The parts of a WORD token that SPARQL does not read as a prefixed name (PNAME_NS / PNAME_LN) or a blank node
     * label: keywords, booleans, numbers and anything ill-formed, in order.
     *
     * Linear in the length of [word]: one backward pass records where each run of PN_CHARS and `.` ends, so the
     * prefixed-name test at a position is constant time (a run that does not end in `:` holds no PN_PREFIX), and
     * every prefixed name or label found is skipped as a whole.
     */
    fun bareParts(word: String): List<String> {
        val runEnd = IntArray(word.length + 1)
        runEnd[word.length] = word.length
        for (i in word.indices.reversed()) {
            runEnd[i] = if (isPnChars(word[i].code) || word[i] == '.') runEnd[i + 1] else i
        }
        val parts = ArrayList<String>()
        val bare = StringBuilder()
        var i = 0
        while (i < word.length) {
            val end = prefixedNameEnd(word, i, runEnd) ?: blankNodeLabelEnd(word, i)
            if (end != null && end > i) {
                if (bare.isNotEmpty()) { parts.add(bare.toString()); bare.setLength(0) }
                i = end
            } else {
                bare.append(word[i])
                i++
            }
        }
        if (bare.isNotEmpty()) parts.add(bare.toString())
        return parts
    }

    /**
     * End of a prefixed name (`PN_PREFIX? ':' PN_LOCAL?`) starting at [start] in [w], or null. [runEnd] gives,
     * for each position, the end of the run of PN_CHARS and `.` that starts there ([bareParts]).
     */
    private fun prefixedNameEnd(w: String, start: Int, runEnd: IntArray): Int? {
        var i = start
        if (i < w.length && isPnCharsBase(w[i].code)) {
            i = runEnd[i]
            // PN_PREFIX does not end in '.': the run would stop at a '.', never at the ':'.
            if (w[i - 1] == '.') return null
        }
        if (i >= w.length || w[i] != ':') return null
        return localNameEnd(w, i + 1)
    }

    /** End of the PN_LOCAL starting at [start] in [w] ([start] itself when there is none). */
    private fun localNameEnd(w: String, start: Int): Int {
        fun escapeLength(i: Int): Int = when {
            w[i] == '%' && i + 2 < w.length && isHex(w[i + 1]) && isHex(w[i + 2]) -> 3
            w[i] == '\\' && i + 1 < w.length && w[i + 1] in SparqlLexer.LOCAL_NAME_ESCAPES -> 2
            else -> 0
        }
        var i = start
        if (i >= w.length) return start
        val first = escapeLength(i)
        when {
            first > 0 -> i += first
            isPnCharsU(w[i].code) || w[i] == ':' || w[i] in '0'..'9' -> i++
            else -> return start
        }
        var end = i
        while (i < w.length) {
            val escape = escapeLength(i)
            when {
                escape > 0 -> { i += escape; end = i }
                isPnChars(w[i].code) || w[i] == ':' -> { i++; end = i }
                w[i] == '.' -> i++ // allowed inside, not at the end
                else -> break
            }
        }
        return end
    }

    /** End of a blank node label (`_:` then PN_CHARS_U or a digit, then PN_CHARS or '.', not ending in '.'), or null. */
    private fun blankNodeLabelEnd(w: String, start: Int): Int? {
        if (!w.startsWith("_:", start) || start + 2 >= w.length) return null
        var i = start + 2
        if (!(isPnCharsU(w[i].code) || w[i] in '0'..'9')) return null
        i++
        var end = i
        while (i < w.length) {
            when {
                isPnChars(w[i].code) -> { i++; end = i }
                w[i] == '.' -> i++
                else -> break
            }
        }
        return end
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
