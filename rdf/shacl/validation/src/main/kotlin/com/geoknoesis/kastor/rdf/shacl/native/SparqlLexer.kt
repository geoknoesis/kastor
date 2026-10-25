package com.geoknoesis.kastor.rdf.shacl.native

/** Lexical token of a SPARQL query: enough structure to rewrite variables safely (not a full parser). */
internal class SparqlToken(val kind: Kind, val text: String) {
    enum class Kind { WHITESPACE, COMMENT, STRING, IRI, VARIABLE, WORD, PUNCT }

    /** Variable name without its `?` / `$` sigil (only meaningful for [Kind.VARIABLE]). */
    val variableName: String get() = text.substring(1)

    val significant: Boolean get() = kind != Kind.WHITESPACE && kind != Kind.COMMENT

    fun isWord(keyword: String): Boolean = kind == Kind.WORD && text.equals(keyword, ignoreCase = true)
}

/**
 * Splits SPARQL text into tokens, keeping string literals, IRI references and comments intact.
 *
 * Where a token boundary decides what the restriction check sees, the lexer follows the SPARQL grammar (and the
 * JavaCC longest-match tokenizers of Jena and RDF4J): comments and short strings end at CR or LF, an unterminated
 * long-string opener is an empty string followed by a quote, and a word keeps the `\` escapes of a local name.
 * A WORD token may still span several SPARQL tokens (`trueSERVICE` is `true` then `SERVICE`); see
 * [SparqlQueryTemplate.restrictionViolation].
 */
internal object SparqlLexer {
    private val iriRef = java.util.regex.Pattern.compile("<[^<>\"{}|^`\\\\\\u0000-\\u0020]*>")

    /** Characters a `\` escapes in a prefixed local name (SPARQL PN_LOCAL_ESC). */
    const val LOCAL_NAME_ESCAPES = "_~.-!$&'()*+,;=/?#@%"

    /** A SPARQL VARNAME character after the first: PN_CHARS_U, digits, U+00B7, U+0300..U+036F, U+203F..U+2040. */
    fun isVariableChar(c: Int): Boolean =
        SparqlQueryTemplate.isPnCharsU(c) || c in '0'.code..'9'.code || c == 0xB7 || c in 0x300..0x36F || c in 0x203F..0x2040

    /** First character of a SPARQL VARNAME: PN_CHARS_U or a digit (the middle dot and combining marks only follow). */
    fun isVariableStart(c: Int): Boolean = SparqlQueryTemplate.isPnCharsU(c) || c in '0'.code..'9'.code

    private fun isWordStart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == ':'

    private fun isWordChar(c: Char): Boolean = isWordStart(c) || c == '-' || c == '.' || c == '%'

    private fun isLineEnd(c: Char): Boolean = c == '\n' || c == '\r'

    fun tokenize(query: String): List<SparqlToken> {
        val out = ArrayList<SparqlToken>()
        val matcher = iriRef.matcher(query)
        var i = 0
        while (i < query.length) {
            val c = query[i]
            val start = i
            val kind: SparqlToken.Kind
            when {
                c.isWhitespace() -> {
                    while (i < query.length && query[i].isWhitespace()) i++
                    kind = SparqlToken.Kind.WHITESPACE
                }
                c == '#' -> {
                    // SPARQL ends a comment at either line-end character.
                    while (i < query.length && !isLineEnd(query[i])) i++
                    kind = SparqlToken.Kind.COMMENT
                }
                query.startsWith("\"\"\"", i) || query.startsWith("'''", i) -> {
                    i = longStringEnd(query, i)
                    kind = SparqlToken.Kind.STRING
                }
                c == '"' || c == '\'' -> {
                    i = shortStringEnd(query, i, c)
                    kind = SparqlToken.Kind.STRING
                }
                c == '<' && matcher.region(i, query.length).lookingAt() -> {
                    i = matcher.end()
                    kind = SparqlToken.Kind.IRI
                }
                (c == '?' || c == '$') && i + 1 < query.length && isVariableStart(query.codePointAt(i + 1)) -> {
                    i = variableEnd(query, i + 1)
                    kind = SparqlToken.Kind.VARIABLE
                }
                isWordStart(c) -> {
                    i = wordEnd(query, start)
                    kind = SparqlToken.Kind.WORD
                }
                else -> {
                    i++
                    kind = SparqlToken.Kind.PUNCT
                }
            }
            out.add(SparqlToken(kind, query.substring(start, i)))
        }
        return out
    }

    /** The end of the long string that opens at [i] (three double or three single quotes). */
    private fun longStringEnd(query: String, i: Int): Int {
        val delimiter = query.substring(i, i + 3)
        var j = i + 3
        while (j < query.length && !query.startsWith(delimiter, j)) {
            if (query[j] == '\\') j++
            j++
        }
        // Unterminated, the opener is (longest match) an empty short string, then a quote.
        return if (j < query.length) j + 3 else i + 2
    }

    /** The end of the short string that opens at [i] with the quote [c]. */
    private fun shortStringEnd(query: String, i: Int, c: Char): Int {
        var j = i + 1
        while (j < query.length && query[j] != c && !isLineEnd(query[j])) {
            if (query[j] == '\\') j++
            j++
        }
        // An unterminated string stops before the line end, which the parsers reject.
        return if (j < query.length && query[j] == c) j + 1 else minOf(query.length, j)
    }

    /** The end of the variable name that starts at [from], after the sigil. */
    private fun variableEnd(query: String, from: Int): Int {
        var i = from
        while (i < query.length) {
            val cp = query.codePointAt(i)
            if (!isVariableChar(cp)) break
            i += Character.charCount(cp)
        }
        return i
    }

    /** The end of the word that starts at [start]. */
    private fun wordEnd(query: String, start: Int): Int {
        var i = start
        while (i < query.length) {
            when {
                // `ex:a\'` and `ex:a\#` are local names, not the start of a string or a comment.
                query[i] == '\\' && i + 1 < query.length && query[i + 1] in LOCAL_NAME_ESCAPES -> i += 2
                isWordChar(query[i]) -> i++
                else -> break
            }
        }
        // A trailing '.' terminates a triple pattern rather than belonging to the name (unless escaped).
        while (i - start > 1 && query[i - 1] == '.' && !(i - start > 2 && query[i - 2] == '\\')) i--
        return i
    }
}
