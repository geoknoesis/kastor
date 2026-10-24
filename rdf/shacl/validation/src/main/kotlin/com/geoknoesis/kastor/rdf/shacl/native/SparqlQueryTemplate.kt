package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.SparqlPreBindingRestrictionException

/** Lexical token of a SPARQL query: enough structure to rewrite variables safely (not a full parser). */
internal class SparqlToken(val kind: Kind, val text: String) {
    enum class Kind { WHITESPACE, COMMENT, STRING, IRI, VARIABLE, WORD, PUNCT }

    /** Variable name without its `?` / `$` sigil (only meaningful for [Kind.VARIABLE]). */
    val variableName: String get() = text.substring(1)

    val significant: Boolean get() = kind != Kind.WHITESPACE && kind != Kind.COMMENT

    fun isWord(keyword: String): Boolean = kind == Kind.WORD && text.equals(keyword, ignoreCase = true)
}

/** Splits SPARQL text into tokens, keeping string literals, IRI references and comments intact. */
internal object SparqlLexer {
    private val iriRef = java.util.regex.Pattern.compile("<[^<>\"{}|^`\\\\\\u0000-\\u0020]*>")

    fun isVariableChar(c: Char): Boolean =
        // SPARQL VARNAME: PN_CHARS_U, digits, U+00B7, U+0300..U+036F, U+203F..U+2040 (code points kept ASCII in source).
        c.isLetterOrDigit() || c == '_' || c.code == 0xB7 || c.code in 0x300..0x36F || c.code in 0x203F..0x2040

    /** First character of a SPARQL VARNAME: PN_CHARS_U or a digit (the middle dot and combining marks only follow). */
    fun isVariableStart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun isWordStart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == ':'

    private fun isWordChar(c: Char): Boolean = isWordStart(c) || c == '-' || c == '.' || c == '%'

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
                    val end = query.indexOf('\n', i)
                    i = if (end < 0) query.length else end
                    kind = SparqlToken.Kind.COMMENT
                }
                query.startsWith("\"\"\"", i) || query.startsWith("'''", i) -> {
                    val delimiter = query.substring(i, i + 3)
                    var j = i + 3
                    while (j < query.length && !query.startsWith(delimiter, j)) {
                        if (query[j] == '\\') j++
                        j++
                    }
                    i = minOf(query.length, j + 3)
                    kind = SparqlToken.Kind.STRING
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < query.length && query[j] != c && query[j] != '\n') {
                        if (query[j] == '\\') j++
                        j++
                    }
                    i = minOf(query.length, j + 1)
                    kind = SparqlToken.Kind.STRING
                }
                c == '<' && matcher.region(i, query.length).lookingAt() -> {
                    i = matcher.end()
                    kind = SparqlToken.Kind.IRI
                }
                (c == '?' || c == '$') && i + 1 < query.length && isVariableStart(query[i + 1]) -> {
                    i++
                    while (i < query.length && isVariableChar(query[i])) i++
                    kind = SparqlToken.Kind.VARIABLE
                }
                isWordStart(c) -> {
                    while (i < query.length && isWordChar(query[i])) i++
                    // A trailing '.' terminates a triple pattern rather than belonging to the name.
                    while (i - start > 1 && query[i - 1] == '.') i--
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
 * A SHACL-SPARQL query with its pre-bound variables (`$this` and, per query form, `$currentShape`,
 * `$shapesGraph`, `$value` or a component's parameter names) located at compile time, so pre-binding is performed by the SHACL engine itself and behaves identically on every SPARQL provider,
 * including inside sub-queries.
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
                            Context.PLAIN -> text.append(rendered)
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
        /** Pre-bound variables of a `sh:sparql` constraint: `$this` plus Kastor's `$currentShape` / `$shapesGraph` extension. */
        val SPARQL_CONSTRAINT_PRE_BOUND: Set<String> = setOf("this", "currentShape", "shapesGraph")

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
         * comments): no `MINUS`, `SERVICE` or `VALUES`, and no `AS` re-binding any of the [preBound] variables.
         * A sub-select that uses a pre-bound variable without projecting it is allowed (SHACL 1.2). Returns a
         * description of the first violation, or null.
         */
        fun restrictionViolation(query: String, preBound: Set<String>): String? {
            val tokens = SparqlLexer.tokenize(query).filter { it.significant }
            for ((index, token) in tokens.withIndex()) {
                if (token.kind != SparqlToken.Kind.WORD) continue
                when (token.text.uppercase()) {
                    "MINUS", "SERVICE", "VALUES" -> return token.text.uppercase()
                    "AS" -> tokens.getOrNull(index + 1)?.let { next ->
                        if (next.kind == SparqlToken.Kind.VARIABLE && next.variableName in preBound) {
                            return "AS to re-bind the pre-bound variable \$${next.variableName}"
                        }
                    }
                }
            }
            return null
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
                restrictionViolation(text, preBound)?.let { what ->
                    throw SparqlPreBindingRestrictionException("$subject uses $what, which is not supported with pre-bound variables")
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
        private fun isPnCharsBase(c: Int): Boolean =
            c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in 0xC0..0xD6 || c in 0xD8..0xF6 || c in 0xF8..0x2FF ||
                c in 0x370..0x37D || c in 0x37F..0x1FFF || c in 0x200C..0x200D || c in 0x2070..0x218F || c in 0x2C00..0x2FEF ||
                c in 0x3001..0xD7FF || c in 0xF900..0xFDCF || c in 0xFDF0..0xFFFD || c in 0x10000..0xEFFFF

        /** SPARQL PN_CHARS: PN_CHARS_BASE, `_`, `-`, digits, U+00B7, U+0300..U+036F, U+203F..U+2040. */
        private fun isPnChars(c: Int): Boolean =
            isPnCharsBase(c) || c == '_'.code || c == '-'.code || c in '0'.code..'9'.code || c == 0xB7 ||
                c in 0x300..0x36F || c in 0x203F..0x2040

        /** Whether [name] is a SPARQL VARNAME, so `?name` is a legal variable. */
        fun isVarName(name: String): Boolean =
            name.isNotEmpty() && SparqlLexer.isVariableStart(name[0]) && name.all { SparqlLexer.isVariableChar(it) }

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

        private fun escape(s: String): String =
            buildString(s.length + 8) {
                for (ch in s) {
                    when {
                        ch == '\\' -> append("\\\\")
                        ch == '"' -> append("\\\"")
                        ch == '\n' -> append("\\n")
                        ch == '\r' -> append("\\r")
                        ch == '\t' -> append("\\t")
                        ch.code < 0x20 -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                        else -> append(ch)
                    }
                }
            }
    }
}
