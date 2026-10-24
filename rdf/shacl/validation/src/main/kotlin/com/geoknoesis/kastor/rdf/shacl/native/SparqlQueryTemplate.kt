package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.SparqlPreBindingRestrictionException

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
                    val delimiter = query.substring(i, i + 3)
                    var j = i + 3
                    while (j < query.length && !query.startsWith(delimiter, j)) {
                        if (query[j] == '\\') j++
                        j++
                    }
                    // Unterminated, the opener is (longest match) an empty short string, then a quote.
                    i = if (j < query.length) j + 3 else i + 2
                    kind = SparqlToken.Kind.STRING
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < query.length && query[j] != c && !isLineEnd(query[j])) {
                        if (query[j] == '\\') j++
                        j++
                    }
                    // An unterminated string stops before the line end, which the parsers reject.
                    i = if (j < query.length && query[j] == c) j + 1 else minOf(query.length, j)
                    kind = SparqlToken.Kind.STRING
                }
                c == '<' && matcher.region(i, query.length).lookingAt() -> {
                    i = matcher.end()
                    kind = SparqlToken.Kind.IRI
                }
                (c == '?' || c == '$') && i + 1 < query.length && isVariableStart(query.codePointAt(i + 1)) -> {
                    i++
                    while (i < query.length) {
                        val cp = query.codePointAt(i)
                        if (!isVariableChar(cp)) break
                        i += Character.charCount(cp)
                    }
                    kind = SparqlToken.Kind.VARIABLE
                }
                isWordStart(c) -> {
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
}

/**
 * A SHACL-SPARQL query with its pre-bound variables (`$this` and, per query form, `$value` or a component's
 * parameter names) located at compile time, so pre-binding is performed by the SHACL engine itself and behaves
 * identically on every SPARQL provider, including inside sub-queries.
 *
 * [bind] substitutes IRIs and literals syntactically: in a triple pattern or expression the term replaces the
 * variable; in a SELECT projection it becomes `(term AS ?fresh)`, in `GROUP BY` / `ORDER BY` `(term)`, and
 * `BOUND(?var)` of a substituted variable becomes `true` (BOUND only accepts a variable). Values
 * without a standard SPARQL syntax that denotes the same RDF term (blank nodes, triple terms, directional language
 * strings, IRIs with characters that are illegal in an IRIREF) keep the variable and are returned as provider
 * initial bindings.
 */
internal class SparqlQueryTemplate private constructor(
    private val parts: List<Part>,
    /**
     * Whether a solution can bind `?failure`: the query mentions the variable (as a variable token, never inside a
     * string, IRI or comment). A variable the query never mentions is never bound, not even by `SELECT *`.
     */
    val mayBindFailure: Boolean,
) {

    private enum class Context { PLAIN, PROJECTION, GROUP_OR_ORDER }

    private sealed interface Part
    private class Text(val text: String) : Part
    private class Slot(val token: String, val name: String, val context: Context, val ordinal: Int) : Part
    /** `BOUND(?name)` on a pre-bound variable, kept verbatim in [text] when the value is not substituted. */
    private class BoundCheck(val text: String, val name: String) : Part

    /** Pre-bound variables the query references. */
    val preBound: Set<String> = parts.mapNotNullTo(LinkedHashSet()) {
        when (it) {
            is Slot -> it.name
            is BoundCheck -> it.name
            is Text -> null
        }
    }

    /** Query text with renderable [values] substituted, plus the remaining values as provider initial bindings. */
    fun bind(values: Map<String, RdfTerm>): Pair<String, Map<String, RdfTerm>> {
        val text = StringBuilder()
        val providerBindings = LinkedHashMap<String, RdfTerm>()
        for (part in parts) {
            when (part) {
                is Text -> text.append(part.text)
                is BoundCheck -> {
                    val value = values[part.name]
                    if (value != null && renderTerm(value) != null) {
                        // Bracketed so it stays valid where SPARQL requires a Constraint (e.g. `FILTER bound($this)`).
                        text.append("(true)")
                    } else {
                        text.append(part.text)
                        if (value != null) providerBindings[part.name] = value
                    }
                }
                is Slot -> {
                    val value = values[part.name]
                    val rendered = value?.let { renderTerm(it) }
                    if (rendered == null) {
                        text.append(part.token)
                        if (value != null) providerBindings[part.name] = value
                    } else {
                        when (part.context) {
                            // A space ends a language tag, so template text right after the slot (`$p-1`) cannot extend it.
                            Context.PLAIN -> text.append(rendered).append(if (value is LangString) " " else "")
                            Context.PROJECTION -> text.append('(').append(rendered).append(" AS ?__kastor_prebound_").append(part.ordinal).append(')')
                            Context.GROUP_OR_ORDER -> text.append('(').append(rendered).append(')')
                        }
                    }
                }
            }
        }
        return text.toString() to providerBindings
    }

    companion object {
        /** Pre-bound variables of a `sh:sparql` constraint: only `$this` (SHACL 1.2 no longer pre-binds `$shapesGraph` / `$currentShape`). */
        val SPARQL_CONSTRAINT_PRE_BOUND: Set<String> = setOf("this")

        fun compile(query: String, preBound: Set<String>): SparqlQueryTemplate {
            val parts = ArrayList<Part>()
            val text = StringBuilder()
            var context = Context.PLAIN
            var depth = 0
            var previousWord: String? = null
            var ordinal = 0
            val tokens = SparqlLexer.tokenize(query)
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index]
                if (token.isWord("BOUND")) {
                    val bound = matchBoundOfPreBound(tokens, index, preBound)
                    if (bound != null) {
                        if (text.isNotEmpty()) { parts.add(Text(text.toString())); text.setLength(0) }
                        parts.add(BoundCheck(tokens.subList(index, bound.first + 1).joinToString("") { it.text }, bound.second))
                        index = bound.first + 1
                        continue
                    }
                }
                when {
                    token.kind == SparqlToken.Kind.WORD -> {
                        val word = token.text.uppercase()
                        when {
                            word == "SELECT" -> { context = Context.PROJECTION; depth = 0 }
                            (word == "WHERE" || word == "FROM") && context == Context.PROJECTION -> context = Context.PLAIN
                            word == "BY" && (previousWord == "GROUP" || previousWord == "ORDER") -> { context = Context.GROUP_OR_ORDER; depth = 0 }
                            word in setOf("HAVING", "LIMIT", "OFFSET", "VALUES", "ORDER", "GROUP") && context == Context.GROUP_OR_ORDER -> context = Context.PLAIN
                        }
                        previousWord = word
                    }
                    token.kind == SparqlToken.Kind.PUNCT -> when (token.text) {
                        "(" -> depth++
                        ")" -> depth--
                        "{", "}" -> context = Context.PLAIN
                    }
                }
                if (token.kind == SparqlToken.Kind.VARIABLE && token.variableName in preBound) {
                    if (text.isNotEmpty()) { parts.add(Text(text.toString())); text.setLength(0) }
                    val slotContext = if (context != Context.PLAIN && depth == 0) context else Context.PLAIN
                    parts.add(Slot(token.text, token.variableName, slotContext, ordinal++))
                } else {
                    text.append(token.text)
                }
                index++
            }
            if (text.isNotEmpty()) parts.add(Text(text.toString()))
            val mayBindFailure = tokens.any { it.kind == SparqlToken.Kind.VARIABLE && it.variableName == "failure" }
            return SparqlQueryTemplate(parts, mayBindFailure)
        }

        /** For `BOUND ( ?preBound )` starting at [start]: (index of the closing parenthesis, variable name), else null. */
        private fun matchBoundOfPreBound(tokens: List<SparqlToken>, start: Int, preBound: Set<String>): Pair<Int, String>? {
            var i = start + 1
            fun nextSignificant(): SparqlToken? {
                while (i < tokens.size && !tokens[i].significant) i++
                return tokens.getOrNull(i)?.also { i++ }
            }
            if (nextSignificant()?.text != "(") return null
            val variable = nextSignificant() ?: return null
            if (variable.kind != SparqlToken.Kind.VARIABLE || variable.variableName !in preBound) return null
            if (nextSignificant()?.text != ")") return null
            return (i - 1) to variable.variableName
        }

        /**
         * SHACL-SPARQL pre-binding restrictions (spec Appendix A), checked on tokens (never inside strings, IRIs or
         * comments): no `MINUS` or `SERVICE`, no `VALUES` that mentions one of the [preBound] variables, and no `AS`
         * re-binding any of them.
         * A sub-select that uses a pre-bound variable without projecting it is allowed (SHACL 1.2). Returns a
         * description of the first violation, or null.
         */
        fun restrictionViolation(query: String, preBound: Set<String>): String? {
            val tokens = SparqlLexer.tokenize(query).filter { it.significant }
            for ((index, token) in tokens.withIndex()) {
                if (token.kind != SparqlToken.Kind.WORD) continue
                // SPARQL splits a run like `trueSERVICE` or `1SERVICE` into a literal and a keyword, so the forbidden
                // keywords are searched in every part of the word that is not a prefixed name or blank node label.
                // No SPARQL keyword or built-in contains one of them, so a valid query gives no false positive.
                val bare = bareParts(token.text)
                for (part in bare) {
                    val upper = part.uppercase()
                    FORBIDDEN_KEYWORDS.firstOrNull { it in upper }?.let { return it }
                    if ("VALUES" in upper) valuesBindingPreBound(tokens, index, preBound)?.let { return it }
                }
                val last = bare.lastOrNull()
                if (last != null && token.text.endsWith(last) && last.endsWith("AS", ignoreCase = true)) {
                    tokens.getOrNull(index + 1)?.let { next ->
                        if (next.kind == SparqlToken.Kind.VARIABLE && next.variableName in preBound) {
                            return "AS to re-bind the pre-bound variable \$${next.variableName}"
                        }
                    }
                }
            }
            return null
        }

        private val FORBIDDEN_KEYWORDS = listOf("MINUS", "SERVICE")

        /**
         * SHACL 1.2 (Appendix A) forbids only a `VALUES` clause that mentions a potentially pre-bound variable: its
         * variables are those between the keyword at [index] and the `{` that opens its data block (a data block holds
         * terms and `UNDEF`, never variables). Returns a description of the violation, or null.
         */
        private fun valuesBindingPreBound(tokens: List<SparqlToken>, index: Int, preBound: Set<String>): String? {
            for (token in tokens.subList(index + 1, tokens.size)) {
                if (token.kind == SparqlToken.Kind.PUNCT && token.text == "{") return null
                if (token.kind == SparqlToken.Kind.VARIABLE && token.variableName in preBound) {
                    return "VALUES for the pre-bound variable \$${token.variableName}"
                }
            }
            return null
        }

        /**
         * The parts of a WORD token that SPARQL does not read as a prefixed name (PNAME_NS / PNAME_LN) or a blank node
         * label: keywords, booleans, numbers and anything ill-formed, in order.
         */
        private fun bareParts(word: String): List<String> {
            val parts = ArrayList<String>()
            val bare = StringBuilder()
            var i = 0
            while (i < word.length) {
                val end = prefixedNameEnd(word, i) ?: blankNodeLabelEnd(word, i)
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

        /** End of a prefixed name (`PN_PREFIX? ':' PN_LOCAL?`) starting at [start] in [w], or null. */
        private fun prefixedNameEnd(w: String, start: Int): Int? {
            var i = start
            if (i < w.length && isPnCharsBase(w[i].code)) {
                i++
                while (i < w.length && (isPnChars(w[i].code) || w[i] == '.')) i++
                while (w[i - 1] == '.') i-- // PN_PREFIX does not end in '.'
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

        /**
         * [text] with its SPARQL codepoint escapes (`\uXXXX`, `\UXXXXXXXX`, SPARQL 1.1 19.2) decoded, the way RDF4J
         * decodes a whole query before parsing it. As in RDF4J (and Java), a backslash starts an escape only when the
         * run of backslashes before the `u` has odd length (`\\u0041` is an escaped backslash, then `u0041`).
         * With [strict], an ill-formed escape or a code point that is a surrogate or beyond U+10FFFF throws
         * [ShapeCompileException]; otherwise every well-formed `\u` / `\U` sequence is decoded whatever precedes it
         * and ill-formed ones are kept, a second, more suspicious view for the check.
         */
        fun decodeCodepointEscapes(text: String, strict: Boolean): String {
            if (text.indexOf('\\') < 0) return text
            val out = StringBuilder(text.length)
            var i = 0
            var backslashes = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length && (text[i + 1] == 'u' || text[i + 1] == 'U') && (!strict || backslashes % 2 == 0)) {
                    val digits = if (text[i + 1] == 'u') 4 else 8
                    val hex = text.substring(i + 2, minOf(text.length, i + 2 + digits))
                    val codePoint = if (hex.length == digits && hex.all { isHex(it) }) hex.toLong(16) else -1L
                    val valid = codePoint in 0L..0x10FFFFL && codePoint !in 0xD800L..0xDFFFL
                    if (valid) {
                        out.appendCodePoint(codePoint.toInt())
                        i += 2 + digits
                        backslashes = 0
                        continue
                    }
                    if (strict) {
                        throw ShapeCompileException("Ill-formed SPARQL codepoint escape \"${text.substring(i, minOf(text.length, i + 2 + digits))}\"")
                    }
                }
                backslashes = if (c == '\\') backslashes + 1 else 0
                out.append(c)
                i++
            }
            return out.toString()
        }

        /**
         * Compiles [prologue] + [body] after checking the pre-binding restrictions ([restrictionViolation]) on the
         * body alone and again on the full query, so text joined from several shapes-graph values (prefix
         * declarations, query) cannot smuggle a forbidden construct past the check. [subject] names the query in the
         * [SparqlPreBindingRestrictionException] message.
         */
        fun compileRestricted(prologue: String, body: String, preBound: Set<String>, subject: String): SparqlQueryTemplate {
            val query = prologue + body
            for (text in listOf(body, query)) {
                // RDF4J decodes codepoint escapes in the whole query before parsing, Jena does not: check every view.
                val decoded = try {
                    decodeCodepointEscapes(text, strict = true)
                } catch (e: ShapeCompileException) {
                    throw ShapeCompileException("$subject is ill-formed: ${e.message}", e)
                }
                for (view in linkedSetOf(text, decoded, decodeCodepointEscapes(text, strict = false))) {
                    restrictionViolation(view, preBound)?.let { what ->
                        throw SparqlPreBindingRestrictionException("$subject uses $what, which is not supported with pre-bound variables")
                    }
                }
            }
            return compile(query, preBound)
        }

        /**
         * Replaces every `$PATH` / `?PATH` variable token (outside strings, IRIs and comments) with the rendered
         * path. A null [rendered] (node shapes) leaves the variable unbound and the query unchanged.
         */
        fun substitutePath(query: String, rendered: (() -> String)?): String {
            if (rendered == null) return query
            val tokens = SparqlLexer.tokenize(query)
            if (tokens.none { isPathToken(it) }) return query
            val path = rendered()
            return tokens.joinToString("") { if (isPathToken(it)) path else it.text }
        }

        private fun isPathToken(token: SparqlToken) = token.kind == SparqlToken.Kind.VARIABLE && token.variableName == "PATH"

        /** Rewrites an `ASK` query into `SELECT (1 AS ?__kastor_ask)`, so one solution means the ASK is true. */
        fun askAsSelect(query: String): String {
            val tokens = SparqlLexer.tokenize(query)
            val ask = tokens.indexOfFirst { it.isWord("ASK") }
            if (ask < 0) return query
            return tokens.withIndex().joinToString("") { (i, t) -> if (i == ask) "SELECT (1 AS ?__kastor_ask)" else t.text }
        }

        /**
         * Variables projected by the outermost `SELECT` (explicit variables and `(expr AS ?v)` aliases, in order), or
         * null when the query has no `SELECT` or projects `*`.
         */
        fun projectedVariables(query: String): List<String>? {
            val tokens = SparqlLexer.tokenize(query).filter { it.significant }
            var i = tokens.indexOfFirst { it.isWord("SELECT") }
            if (i < 0) return null
            i++
            while (i < tokens.size && (tokens[i].isWord("DISTINCT") || tokens[i].isWord("REDUCED"))) i++
            val projected = ArrayList<String>()
            var depth = 0
            while (i < tokens.size) {
                val t = tokens[i]
                if (depth == 0 && (t.isWord("WHERE") || t.isWord("FROM") || t.text == "{")) break
                when {
                    t.kind == SparqlToken.Kind.PUNCT && t.text == "(" -> depth++
                    t.kind == SparqlToken.Kind.PUNCT && t.text == ")" -> depth--
                    t.kind == SparqlToken.Kind.PUNCT && t.text == "*" && depth == 0 -> return null
                    t.kind == SparqlToken.Kind.VARIABLE && depth == 0 -> projected.add(t.variableName)
                    t.isWord("AS") && depth == 1 ->
                        tokens.getOrNull(i + 1)?.takeIf { it.kind == SparqlToken.Kind.VARIABLE }?.let { projected.add(it.variableName) }
                }
                i++
            }
            return projected
        }

        /**
         * The local name of [iri] per SHACL 1.2 SPARQL 4.2.1: the longest NCName at the end of the IRI that is not
         * immediately preceded by the first colon of the IRI.
         */
        fun localName(iri: Iri): String {
            val value = iri.value
            val firstColon = value.indexOf(':')
            var start = value.length
            while (start > 0 && isNcNameChar(value[start - 1])) start--
            while (start < value.length && (!isNcNameStart(value[start]) || start - 1 == firstColon)) start++
            return value.substring(start)
        }

        private fun isNcNameStart(c: Char) = c.isLetter() || c == '_'

        private fun isNcNameChar(c: Char) =
            c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c.code == 0xB7 || c.code in 0x300..0x36F || c.code in 0x203F..0x2040

        /**
         * Whether [label] is a SPARQL PN_PREFIX (or empty), so `PREFIX label: <ns>` is one well-formed declaration:
         * a PN_CHARS_BASE, then PN_CHARS or `.`, not ending in `.`.
         */
        fun isPrefixLabel(label: String): Boolean {
            if (label.isEmpty()) return true
            val codePoints = label.codePoints().toArray()
            return isPnCharsBase(codePoints.first()) && codePoints.last() != '.'.code &&
                codePoints.drop(1).all { it == '.'.code || isPnChars(it) }
        }

        /** SPARQL PN_CHARS_BASE. */
        internal fun isPnCharsBase(c: Int): Boolean =
            c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in 0xC0..0xD6 || c in 0xD8..0xF6 || c in 0xF8..0x2FF ||
                c in 0x370..0x37D || c in 0x37F..0x1FFF || c in 0x200C..0x200D || c in 0x2070..0x218F || c in 0x2C00..0x2FEF ||
                c in 0x3001..0xD7FF || c in 0xF900..0xFDCF || c in 0xFDF0..0xFFFD || c in 0x10000..0xEFFFF

        /** SPARQL PN_CHARS_U: PN_CHARS_BASE or `_`. */
        internal fun isPnCharsU(c: Int): Boolean = isPnCharsBase(c) || c == '_'.code

        /** SPARQL PN_CHARS: PN_CHARS_U, `-`, digits, U+00B7, U+0300..U+036F, U+203F..U+2040. */
        internal fun isPnChars(c: Int): Boolean =
            isPnCharsU(c) || c == '-'.code || c in '0'.code..'9'.code || c == 0xB7 ||
                c in 0x300..0x36F || c in 0x203F..0x2040

        /** Whether [name] is a SPARQL VARNAME, so `?name` is a legal variable. */
        fun isVarName(name: String): Boolean =
            name.isNotEmpty() && SparqlLexer.isVariableStart(name.codePointAt(0)) && name.codePoints().allMatch { SparqlLexer.isVariableChar(it) }

        /** `<iri>`, or null when the IRI contains characters that SPARQL's IRIREF production forbids. */
        fun renderIri(iri: String): String? =
            if (iri.any { it in "<>\"{}|^`\\" || it.code <= 0x20 }) null else "<$iri>"

        private val languageTag = Regex("[A-Za-z]+(-[A-Za-z0-9]+)*")

        fun renderTerm(term: RdfTerm): String? =
            when (term) {
                is Iri -> renderIri(term.value)
                is LangString ->
                    if (term.direction != null || !languageTag.matches(term.lang)) null else "\"${escape(term.lexical)}\"@${term.lang}"
                is Literal -> renderIri(term.datatype.value)?.let { "\"${escape(term.lexical)}\"^^$it" }
                else -> null
            }

        /**
         * [s] escaped for a `"..."` string. Every backslash is doubled, so a Java-style codepoint pre-pass (RDF4J
         * decodes `\u` only after an odd run of backslashes) leaves the text alone, and a parser without a pre-pass
         * (Jena) reads it as written. A `u` or `U` right after a backslash of [s] is itself written as a codepoint
         * escape (backslash-u-0075, backslash-u-0055, as `SparqlLexical.escapeString` does), so the text also reads
         * the same under a naive pre-pass that decodes every well-formed `\u` whatever precedes it: with doubling
         * alone, such a pass would turn the value's backslash-u-0022 into an escaped quote, changing the value.
         */
        private fun escape(s: String): String =
            buildString(s.length + 8) {
                var afterBackslash = false
                for (ch in s) {
                    when {
                        afterBackslash && ch == 'u' -> append("\\u0075")
                        afterBackslash && ch == 'U' -> append("\\u0055")
                        ch == '\\' -> append("\\\\")
                        ch == '"' -> append("\\\"")
                        // So a literal stays one string even where a parser reads a single-quoted context.
                        ch == '\'' -> append("\\'")
                        ch == '\n' -> append("\\n")
                        ch == '\r' -> append("\\r")
                        ch == '\t' -> append("\\t")
                        ch.code < 0x20 -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                        else -> append(ch)
                    }
                    afterBackslash = ch == '\\'
                }
            }
    }
}
