package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm

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

    private fun isVariableChar(c: Char): Boolean =
        // SPARQL VARNAME: PN_CHARS_U, digits, U+00B7, U+0300..U+036F, U+203F..U+2040 (code points kept ASCII in source).
        c.isLetterOrDigit() || c == '_' || c.code == 0xB7 || c.code in 0x300..0x36F || c.code in 0x203F..0x2040

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
                (c == '?' || c == '$') && i + 1 < query.length && isVariableChar(query[i + 1]) -> {
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
 * A SHACL-SPARQL query with its pre-bound variables (`$this`, `$currentShape`, `$shapesGraph`) located at compile
 * time, so pre-binding is performed by the SHACL engine itself and behaves identically on every SPARQL provider,
 * including inside sub-queries.
 *
 * [bind] substitutes IRIs and literals syntactically: in a triple pattern or expression the term replaces the
 * variable; in a SELECT projection it becomes `(term AS ?fresh)`, in `GROUP BY` / `ORDER BY` `(term)`, and
 * `BOUND(?var)` of a substituted variable becomes `true` (BOUND only accepts a variable). Values
 * without a standard SPARQL syntax that denotes the same RDF term (blank nodes, triple terms, directional language
 * strings, IRIs with characters that are illegal in an IRIREF) keep the variable and are returned as provider
 * initial bindings.
 */
internal class SparqlQueryTemplate private constructor(private val parts: List<Part>) {

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
        val PRE_BOUND_NAMES: Set<String> = setOf("this", "currentShape", "shapesGraph")

        fun compile(query: String): SparqlQueryTemplate {
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
                    val bound = matchBoundOfPreBound(tokens, index)
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
                if (token.kind == SparqlToken.Kind.VARIABLE && token.variableName in PRE_BOUND_NAMES) {
                    if (text.isNotEmpty()) { parts.add(Text(text.toString())); text.setLength(0) }
                    val slotContext = if (context != Context.PLAIN && depth == 0) context else Context.PLAIN
                    parts.add(Slot(token.text, token.variableName, slotContext, ordinal++))
                } else {
                    text.append(token.text)
                }
                index++
            }
            if (text.isNotEmpty()) parts.add(Text(text.toString()))
            return SparqlQueryTemplate(parts)
        }

        /** For `BOUND ( ?preBound )` starting at [start]: (index of the closing parenthesis, variable name), else null. */
        private fun matchBoundOfPreBound(tokens: List<SparqlToken>, start: Int): Pair<Int, String>? {
            var i = start + 1
            fun nextSignificant(): SparqlToken? {
                while (i < tokens.size && !tokens[i].significant) i++
                return tokens.getOrNull(i)?.also { i++ }
            }
            if (nextSignificant()?.text != "(") return null
            val variable = nextSignificant() ?: return null
            if (variable.kind != SparqlToken.Kind.VARIABLE || variable.variableName !in PRE_BOUND_NAMES) return null
            if (nextSignificant()?.text != ")") return null
            return (i - 1) to variable.variableName
        }

        /**
         * SHACL-SPARQL pre-binding restrictions, checked on tokens (never inside strings, IRIs or comments): no
         * `MINUS`, `SERVICE` or `VALUES`, no `AS` re-binding a pre-bound variable, and sub-queries must project
         * `$this` when the query uses it (`SELECT *` projects every in-scope variable). Returns a description of
         * the first violation, or null.
         */
        fun restrictionViolation(query: String): String? {
            val tokens = SparqlLexer.tokenize(query).filter { it.significant }
            for ((index, token) in tokens.withIndex()) {
                if (token.kind != SparqlToken.Kind.WORD) continue
                when (token.text.uppercase()) {
                    "MINUS", "SERVICE", "VALUES" -> return token.text.uppercase()
                    "AS" -> tokens.getOrNull(index + 1)?.let { next ->
                        if (next.kind == SparqlToken.Kind.VARIABLE && next.variableName in PRE_BOUND_NAMES) {
                            return "AS to re-bind the pre-bound variable \$${next.variableName}"
                        }
                    }
                }
            }
            val usesThis = tokens.any { it.kind == SparqlToken.Kind.VARIABLE && it.variableName == "this" }
            if (!usesThis) return null
            val selects = tokens.indices.filter { tokens[it].isWord("SELECT") }
            for (select in selects.drop(1)) {
                var j = select + 1
                var projectsThis = false
                while (j < tokens.size && !tokens[j].isWord("WHERE") && !tokens[j].isWord("FROM") && tokens[j].text != "{") {
                    val t = tokens[j]
                    if (t.kind == SparqlToken.Kind.PUNCT && t.text == "*") projectsThis = true
                    if (t.kind == SparqlToken.Kind.VARIABLE && t.variableName == "this") projectsThis = true
                    j++
                }
                if (!projectsThis) return "a sub-query that does not project \$this"
            }
            return null
        }

        /** Replaces every `$PATH` variable token (outside strings, IRIs and comments) with [rendered]. */
        fun substitutePath(query: String, rendered: () -> String): String {
            val tokens = SparqlLexer.tokenize(query)
            if (tokens.none { isPathToken(it) }) return query
            val path = rendered()
            return tokens.joinToString("") { if (isPathToken(it)) path else it.text }
        }

        fun usesPath(query: String): Boolean = SparqlLexer.tokenize(query).any { isPathToken(it) }

        private fun isPathToken(token: SparqlToken) = token.kind == SparqlToken.Kind.VARIABLE && token.text == "\$PATH"

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
