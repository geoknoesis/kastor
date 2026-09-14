package com.geoknoesis.kastor.rdf.sparql

/**
 * Applies initial bindings to a SPARQL SELECT query by syntactic substitution, following the same
 * rules as Jena's `QueryExecution...substitution(...)` (`QueryTransformOps`), which is what the
 * Jena provider uses for [com.geoknoesis.kastor.rdf.SparqlQueryable.withSelectRows] with bindings:
 *
 * - Every occurrence of a bound variable in the WHERE clause (including FILTER, BIND expressions,
 *   OPTIONAL, MINUS, EXISTS/NOT EXISTS, sub-selects), GROUP BY, HAVING and ORDER BY is replaced by
 *   the constant, so the binding restricts the query *before* aggregation, LIMIT and filtering.
 *   A bare `ORDER BY ?var` becomes `ORDER BY (constant)`.
 * - A bound variable listed in the projection is kept in the results as `(constant AS ?var)`.
 *   `GROUP BY ?var` becomes `GROUP BY (constant AS ?var)` and the projection keeps `?var`.
 * - `SELECT *` does not return bound variables (as in Jena).
 * - Because variables are replaced, a `MINUS` whose only shared variable is bound no longer shares
 *   a variable and removes nothing (identical to the Jena provider).
 *
 * Rejected with [IllegalArgumentException], where substitution cannot produce a valid query with
 * the same meaning:
 * - the variable is assigned by the query (`BIND(... AS ?var)`, `(expr AS ?var)`, `VALUES ?var`);
 * - the variable is used inside a sub-select that does not project it. Such a variable is local to
 *   the sub-select, so an outer binding must not apply to it (Jena substitutes it anyway).
 *
 * Tokenizing skips comments, string literals and IRIs, so text inside them is never rewritten.
 */
internal object InitialBindings {

    /** [bindings] maps variable names (without `?`) to already-rendered SPARQL constants. */
    fun apply(sparql: String, bindings: Map<String, String>): String {
        if (bindings.isEmpty()) return sparql
        bindings.keys.forEach { SparqlTermFormat.varName(it) }
        val tokens = tokenize(sparql)
        val select = tokens.indices.firstOrNull { tokens[it].isKeyword("SELECT") }
            ?: throw IllegalArgumentException("Initial bindings can only be applied to a SELECT query")
        val replacements = HashMap<Int, String>()
        Rewriter(tokens, bindings, replacements).query(select, tokens.size)
        return buildString(sparql.length + 64) {
            var last = 0
            tokens.forEachIndexed { index, token ->
                val replacement = replacements[index] ?: return@forEachIndexed
                append(sparql, last, token.start)
                append(replacement)
                last = token.end
            }
            append(sparql, last, sparql.length)
        }
    }

    // ------------------------------------------------------------------ rewriting

    private class Rewriter(
        private val tokens: List<Token>,
        private val bindings: Map<String, String>,
        private val out: MutableMap<Int, String>,
    ) {
        private fun bound(index: Int): Boolean = tokens[index].kind == Kind.VAR && tokens[index].name in bindings

        private fun substitute(index: Int) {
            if (bound(index)) out[index] = bindings.getValue(tokens[index].name)
        }

        private fun reject(name: String, why: String): Nothing =
            throw IllegalArgumentException("Cannot apply an initial binding for ?$name: $why")

        /**
         * Rewrite the SELECT query whose `SELECT` keyword is at [select] and which ends before [end]
         * (the closing brace of a sub-select, or the end of the text). Returns the bound variables it
         * projects.
         */
        fun query(select: Int, end: Int): Set<String> {
            // ---- projection
            var i = select + 1
            if (i < end && (tokens[i].isKeyword("DISTINCT") || tokens[i].isKeyword("REDUCED"))) i++
            val projectionStart = i
            var depth = 0
            while (i < end) {
                val t = tokens[i]
                if (depth == 0 && (t.isPunct('{') || t.isKeyword("WHERE") || t.isKeyword("FROM"))) break
                if (t.isPunct('(')) depth++ else if (t.isPunct(')')) depth--
                i++
            }
            val projectionEnd = i
            val star = (projectionStart until projectionEnd).any { tokens[it].kind == Kind.WORD && tokens[it].text == "*" }
            val bareProjected = LinkedHashSet<Int>()
            depth = 0
            for (k in projectionStart until projectionEnd) {
                val t = tokens[k]
                when {
                    t.isPunct('(') -> depth++
                    t.isPunct(')') -> depth--
                    t.kind == Kind.VAR && depth == 0 -> if (t.name in bindings) bareProjected.add(k)
                    t.kind == Kind.VAR -> {
                        if (k > 0 && tokens[k - 1].isKeyword("AS") && t.name in bindings) reject(t.name, "the query assigns it with AS")
                        substitute(k)
                    }
                }
            }

            // ---- dataset clauses and WHERE group
            while (i < end && !tokens[i].isPunct('{')) i++
            require(i < end) { "Malformed SELECT query: no WHERE group" }
            val groupOpen = i
            val groupClose = matching(groupOpen, end)
            val exposedByChildren = group(groupOpen + 1, groupClose)

            // ---- solution modifiers and trailing VALUES
            val groupAliased = HashSet<String>()
            var k = groupClose + 1
            var clause = ""
            depth = 0
            while (k < end) {
                val t = tokens[k]
                when {
                    t.isKeyword("GROUP") -> clause = "GROUP"
                    t.isKeyword("HAVING") || t.isKeyword("ORDER") || t.isKeyword("LIMIT") || t.isKeyword("OFFSET") -> clause = t.text.uppercase()
                    t.isKeyword("VALUES") -> { valuesDeclaration(k, end); clause = "VALUES" }
                    t.isPunct('(') -> depth++
                    t.isPunct(')') -> depth--
                    t.kind == Kind.VAR && t.name in bindings -> when {
                        tokens[k - 1].isKeyword("AS") -> reject(t.name, "the query assigns it with AS")
                        clause == "GROUP" && depth == 0 -> {
                            out[k] = "(${bindings.getValue(t.name)} AS ?${t.name})"
                            groupAliased.add(t.name)
                        }
                        // A bare constant is not an OrderCondition (an IRI would parse as a function
                        // call); a bracketed expression is, and ordering by a constant is a no-op.
                        clause == "ORDER" && depth == 0 -> out[k] = "(${bindings.getValue(t.name)})"
                        clause != "VALUES" -> substitute(k)
                    }
                }
                k++
            }

            // ---- projection of bound variables
            val exposed = HashSet<String>()
            for (index in bareProjected) {
                val name = tokens[index].name
                exposed.add(name)
                if (name !in groupAliased && name !in exposedByChildren) out[index] = "(${bindings.getValue(name)} AS ?$name)"
            }
            if (star) exposed.addAll(exposedByChildren)
            return exposed
        }

        /** Rewrite the group content in [from, to); returns bound variables projected by sub-selects. */
        private fun group(from: Int, to: Int): Set<String> {
            val exposed = HashSet<String>()
            var i = from
            while (i < to) {
                val t = tokens[i]
                when {
                    t.isPunct('{') && i + 1 < to && tokens[i + 1].isKeyword("SELECT") -> {
                        val close = matching(i, to)
                        val projected = subSelect(i + 1, close)
                        exposed.addAll(projected)
                        i = close
                    }
                    t.isKeyword("VALUES") -> valuesDeclaration(i, to)
                    t.kind == Kind.VAR && t.name in bindings -> {
                        if (i > from && tokens[i - 1].isKeyword("AS")) reject(t.name, "the query assigns it with BIND(... AS ?${t.name})")
                        substitute(i)
                    }
                }
                i++
            }
            return exposed
        }

        private fun subSelect(select: Int, close: Int): Set<String> {
            val usedBound = (select until close).filter { bound(it) }.map { tokens[it].name }.toSet()
            val projected = query(select, close)
            val starProjection = projectionIsStar(select, close)
            for (name in usedBound) {
                if (name !in projected && !starProjection) {
                    reject(name, "it is used inside a sub-select that does not project it, so it is a different, local variable there")
                }
            }
            return projected
        }

        private fun projectionIsStar(select: Int, end: Int): Boolean {
            var i = select + 1
            while (i < end && !tokens[i].isPunct('{') && !tokens[i].isKeyword("WHERE") && !tokens[i].isKeyword("FROM")) {
                if (tokens[i].kind == Kind.WORD && tokens[i].text == "*") return true
                if (tokens[i].isPunct('(')) return false
                i++
            }
            return false
        }

        /** Reject a `VALUES ?v` / `VALUES (?a ?b)` declaration of a bound variable starting at [values]. */
        private fun valuesDeclaration(values: Int, end: Int) {
            var i = values + 1
            if (i < end && tokens[i].kind == Kind.VAR) {
                if (bound(i)) reject(tokens[i].name, "the query assigns it with VALUES")
                return
            }
            if (i < end && tokens[i].isPunct('(')) {
                i++
                while (i < end && !tokens[i].isPunct(')')) {
                    if (bound(i)) reject(tokens[i].name, "the query assigns it with VALUES")
                    i++
                }
            }
        }

        private fun matching(open: Int, end: Int): Int {
            var depth = 0
            for (i in open until end) {
                if (tokens[i].isPunct('{')) depth++
                else if (tokens[i].isPunct('}')) {
                    depth--
                    if (depth == 0) return i
                }
            }
            throw IllegalArgumentException("Malformed SPARQL query: unbalanced braces")
        }
    }

    // ------------------------------------------------------------------ tokenizer

    private enum class Kind { VAR, IRI, STRING, PUNCT, WORD }

    private class Token(val kind: Kind, val text: String, val start: Int, val end: Int) {
        val name: String get() = text.substring(1)
        fun isKeyword(keyword: String) = kind == Kind.WORD && text.equals(keyword, ignoreCase = true)
        fun isPunct(c: Char) = kind == Kind.PUNCT && text.length == 1 && text[0] == c
    }

    private val IRIREF = Regex("<[^<>\"{}|^`\\\\\\u0000-\\u0020]*>")
    private const val PUNCT = "{}()[],;"
    private const val WORD_BREAK = "{}()[],;\"'<?$#"

    private fun isVarChar(c: Char) =
        c == '_' || c.isLetterOrDigit() || c == '\u00B7' || c in '\u0300'..'\u036F' || c in '\u203F'..'\u2040' ||
            Character.isSurrogate(c)

    private fun tokenize(text: String): List<Token> {
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
                c == '<' || c == '?' || c == '$' -> { tokens.add(Token(Kind.WORD, c.toString(), i, i + 1)); i++ }
                else -> {
                    val start = i
                    while (i < n && !text[i].isWhitespace() && text[i] !in WORD_BREAK) i++
                    tokens.add(Token(Kind.WORD, text.substring(start, i), start, i))
                }
            }
        }
        return tokens
    }
}
