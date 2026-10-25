package com.geoknoesis.kastor.rdf.sparql.internal

/**
 * The lexer of [SparqlInitialBindings]: codepoint escapes decoded before tokenizing (as the SPARQL grammar
 * says), and a tokenizer that knows enough of SPARQL to find variables, IRIs, strings, punctuation and words.
 */
internal object SparqlBindingsLexer {
    // ------------------------------------------------------------------ codepoint escapes

    /** Decoded query text; [originalOffset] maps an offset in [text] back to the original text. */
    class Decoded(val text: String, private val offsets: IntArray?) {
        fun originalOffset(index: Int): Int = offsets?.get(index) ?: index
    }

    fun decodeCodepointEscapes(text: String): Decoded {
        if (text.indexOf('\\') < 0) return Decoded(text, null)
        val out = StringBuilder(text.length)
        // Decoding never makes the text longer.
        val offsets = IntArray(text.length + 1)
        var i = 0
        var rawBackslashes = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && rawBackslashes % 2 == 0) {
                val digits = when (text.getOrNull(i + 1)) {
                    'u' -> 4
                    'U' -> 8
                    else -> 0
                }
                val codepoint = if (digits == 0) -1 else hexCodepoint(text, i + 2, digits)
                if (codepoint >= 0) {
                    val start = out.length
                    out.appendCodePoint(codepoint)
                    for (k in start until out.length) offsets[k] = i
                    i += 2 + digits
                    rawBackslashes = 0
                    continue
                }
            }
            offsets[out.length] = i
            out.append(c)
            rawBackslashes = if (c == '\\') rawBackslashes + 1 else 0
            i++
        }
        offsets[out.length] = text.length
        return Decoded(out.toString(), offsets)
    }

    /** The codepoint written as [digits] hex digits starting at [from], or -1. */
    fun hexCodepoint(text: String, from: Int, digits: Int): Int {
        if (from + digits > text.length) return -1
        var value = 0L
        for (k in from until from + digits) {
            val digit = Character.digit(text[k], 16)
            if (digit < 0) return -1
            value = value * 16 + digit
        }
        return if (value > Character.MAX_CODE_POINT) -1 else value.toInt()
    }

    // ------------------------------------------------------------------ tokenizer

    enum class Kind { VAR, IRI, STRING, PUNCT, WORD }

    class Token(val kind: Kind, val text: String, val start: Int, val end: Int) {
        val name: String get() = text.substring(1)
        fun isKeyword(keyword: String) = kind == Kind.WORD && text.equals(keyword, ignoreCase = true)
        fun isPunct(c: Char) = kind == Kind.PUNCT && text.length == 1 && text[0] == c
    }

    val IRIREF = Regex("<[^<>\"{}|^`\\\\\\u0000-\\u0020]*>")
    private const val PUNCT = "{}()[],;"
    // `*` never occurs inside a name, and `SELECT*WHERE{` / `SELECT DISTINCT*{` are legal SPARQL.
    private const val WORD_BREAK = "{}()[],;\"'<?$#*"

    fun isVarChar(c: Char) =
        c == '_' || c.isLetterOrDigit() || c == '·' || c in '̀'..'ͯ' || c in '‿'..'⁀' ||
            Character.isSurrogate(c)

    fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '#' -> while (i < n && text[i] != '\n' && text[i] != '\r') i++
                c == '"' || c == '\'' -> {
                    val start = i
                    val long = text.startsWith("$c$c$c", i)
                    i += if (long) 3 else 1
                    while (true) {
                        require(i < n) { "Malformed SPARQL query: unterminated string literal" }
                        if (text[i] == '\\') { i += 2; continue }
                        if (long && text.startsWith("$c$c$c", i)) { i += 3; break }
                        if (!long && text[i] == c) { i++; break }
                        i++
                    }
                    tokens.add(Token(Kind.STRING, text.substring(start, minOf(i, n)), start, minOf(i, n)))
                }
                c == '<' && IRIREF.matchAt(text, i) != null -> {
                    val m = IRIREF.matchAt(text, i)!!
                    tokens.add(Token(Kind.IRI, m.value, i, i + m.value.length))
                    i += m.value.length
                }
                (c == '?' || c == '$') && i + 1 < n && isVarChar(text[i + 1]) -> {
                    val start = i
                    i++
                    while (i < n && isVarChar(text[i])) i++
                    tokens.add(Token(Kind.VAR, "?" + text.substring(start + 1, i), start, i))
                }
                c in PUNCT -> { tokens.add(Token(Kind.PUNCT, c.toString(), i, i + 1)); i++ }
                c == '<' || c == '?' || c == '$' || c == '*' -> { tokens.add(Token(Kind.WORD, c.toString(), i, i + 1)); i++ }
                else -> {
                    val start = i
                    // Inside a name (keyword, prefixed name, language tag) digits, `-` and `.` continue the name.
                    var inName = false
                    while (i < n && !text[i].isWhitespace() && text[i] !in WORD_BREAK) {
                        val ch = text[i]
                        if (ch == '\\' && i + 1 < n) {
                            // PN_LOCAL_ESC (`ex:a\#b`): the escaped character belongs to the name.
                            i += 2
                            inName = true
                            continue
                        }
                        if (!inName && ch in '0'..'9') {
                            // A number ends its word: `1AS` is the number 1 followed by the keyword AS.
                            i = numberEnd(text, i)
                            break
                        }
                        inName = if (inName) isVarChar(ch) || ch in NAME_PUNCTUATION else isVarChar(ch) || ch == ':'
                        i++
                    }
                    tokens.add(Token(Kind.WORD, text.substring(start, minOf(i, n)), start, minOf(i, n)))
                }
            }
        }
        return tokens
    }

    /** Characters that continue a prefixed name or language tag once it has started. */
    private const val NAME_PUNCTUATION = ":-.%"

    fun digitsEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i] in '0'..'9') i++
        return i
    }

    /** The end of the exponent (`e`, optional sign, digits) at [from], or [from] when there is none. */
    fun exponentEnd(text: String, from: Int): Int {
        if (from >= text.length || (text[from] != 'e' && text[from] != 'E')) return from
        var i = from + 1
        if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
        val end = digitsEnd(text, i)
        return if (end > i) end else from
    }

    /** The end of the INTEGER, DECIMAL or DOUBLE whose first digit is at [from]. */
    fun numberEnd(text: String, from: Int): Int {
        var i = digitsEnd(text, from)
        if (i < text.length && text[i] == '.') {
            val fraction = digitsEnd(text, i + 1)
            // `1.` is the integer 1 and a dot, unless digits or an exponent follow (`1.5`, `1.e3`).
            if (fraction > i + 1 || exponentEnd(text, fraction) > fraction) i = fraction
        }
        return exponentEnd(text, i)
    }
}
