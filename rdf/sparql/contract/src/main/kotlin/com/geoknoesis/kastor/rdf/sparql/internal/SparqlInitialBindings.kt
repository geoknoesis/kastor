package com.geoknoesis.kastor.rdf.sparql.internal

/**
 * **Not public API.** Applies initial bindings to a SPARQL SELECT query by syntactic substitution,
 * following the same rules as Jena's `QueryExecution...substitution(...)` (`QueryTransformOps`),
 * which is what the Jena provider uses for `SparqlQueryable.withSelectRows` with bindings. It lives
 * here so every provider that has to emulate initial bindings over query text (the SPARQL HTTP
 * endpoint adapter, or any provider that wants Jena-identical semantics) shares one implementation.
 * It is public only because Kotlin `internal` cannot be shared across modules; it may change
 * without notice.
 *
 * Substitution rules:
 * - Every occurrence of a bound variable in the WHERE clause (including FILTER, BIND expressions,
 *   OPTIONAL, MINUS, EXISTS/NOT EXISTS, sub-selects), GROUP BY, HAVING and ORDER BY is replaced by
 *   the constant, so the binding restricts the query *before* aggregation, LIMIT and filtering.
 *   A bare `ORDER BY ?var` becomes `ORDER BY (constant)`.
 * - A bound variable listed in a projection is returned as `(constant AS ?var)`, so it is bound in
 *   every row. `GROUP BY ?var` becomes `GROUP BY (constant AS ?var)` and the projection keeps `?var`.
 * - `(constant AS ?var)` is only legal when `?var` is not already in scope, so when a sub-select
 *   also projects the variable, the sub-select's copy is renamed to a fresh, unused variable. That
 *   copy only ever held the constant (or nothing), so joins are unchanged.
 * - `BOUND(?var)` becomes `(true)`: `BOUND(constant)` is not legal SPARQL text, and Jena evaluates its
 *   substituted `BOUND` to true.
 * - `SELECT *` does not return bound variables (as in Jena).
 * - Because variables are replaced, a `MINUS` whose only shared variable is bound no longer shares
 *   a variable and removes nothing (identical to the Jena provider).
 *
 * Rejected with [IllegalArgumentException], where substitution cannot produce a valid query with
 * the same meaning:
 * - the query is not a SELECT query;
 * - the variable is assigned by the query (`BIND(... AS ?var)`, `(expr AS ?var)`, `VALUES ?var`);
 * - the variable is used inside a sub-select that does not project it. Such a variable is local to
 *   the sub-select, so an outer binding must not apply to it (Jena substitutes it anyway);
 * - the variable is bound to a literal or a triple term and is used as the predicate of a triple
 *   pattern, or as the name of a GRAPH or SERVICE. Only an IRI is legal there, so the substituted
 *   text would not parse (Jena's own substitution silently matches nothing).
 *
 * Lexical handling:
 * - SPARQL 1.1 codepoint escapes (backslash-u plus 4 hex digits, backslash-U plus 8 hex digits;
 *   SPARQL 1.1 section 19.2) are decoded over the whole text before tokenizing, as a SPARQL 1.1
 *   parser does. As in Java and Jena's SPARQL 1.1 parser, a backslash preceded by an odd number of
 *   raw backslashes does not start an escape. Text outside the replaced tokens is kept exactly as
 *   written. Literals rendered by [SparqlLexical.escapeString] read the same with or without this
 *   pre-pass, so they tokenize identically either way.
 * - Comments, string literals and IRIs are skipped, so text inside them is never rewritten.
 *   Escaped characters in prefixed local names (`ex:a\#b`) belong to the name.
 * - A number ends where its digits end, as in the SPARQL grammar: `(1AS ?x)` is the number `1`
 *   followed by the keyword `AS`.
 */
object SparqlInitialBindings {

    /**
     * Rewrite [sparql] with [bindings], which maps variable names (without `?`) to SPARQL constant
     * terms already rendered with [SparqlLexical] (IRIs, literals). The constants are inserted as
     * given. A constant that is not an IRI (it starts like a string, a number, a boolean or a triple
     * term `<<`) is rejected where only an IRI is legal; a constant written as a variable (a
     * placeholder the caller binds natively) is not checked.
     */
    fun apply(sparql: String, bindings: Map<String, String>): String = apply(sparql, bindings, emptyMap())

    /**
     * Like [apply], for constants whose syntax the target parser accepts in triple patterns and `BIND` but not in
     * other expressions (RDF4J's `<< s p o >>`). A bound variable `v` in [expressionVariables] is written as its
     * constant in triple patterns, and as the variable `?x` (`x = expressionVariables[v]`, a name the query does not
     * use) in expression positions: the projection, `GROUP BY`, `HAVING`, `ORDER BY`, and anything inside parentheses
     * in a WHERE group (FILTER, BIND, function calls; also RDF collections). Every group that uses `?x` starts with
     * `BIND(constant AS ?x)`, so `?x` holds the constant wherever it is read and the query means the same.
     */
    fun apply(sparql: String, bindings: Map<String, String>, expressionVariables: Map<String, String>): String {
        if (bindings.isEmpty()) return sparql
        require(bindings.keys.containsAll(expressionVariables.keys)) { "expressionVariables must only name bound variables" }
        expressionVariables.values.forEach { SparqlLexical.varName(it) }
        bindings.keys.forEach { SparqlLexical.varName(it) }
        val decoded = decodeCodepointEscapes(sparql)
        val tokens = tokenize(decoded.text)
        val form = tokens.indexOfFirst { token -> QUERY_FORMS.any { token.isKeyword(it) } }
        require(form >= 0 && tokens[form].isKeyword("SELECT")) { "Initial bindings can only be applied to a SELECT query" }
        val replacements = HashMap<Int, String>()
        require(tokens.none { it.kind == Kind.VAR && it.name in expressionVariables.values }) {
            "expressionVariables must be variables the query does not use"
        }
        val nonIris = bindings.filterValues(::isNotAnIri).keys
        val iriOnly = if (nonIris.isEmpty()) emptySet() else IriOnlyPositions(tokens).scan()
        Rewriter(tokens, bindings, expressionVariables, replacements, nonIris, iriOnly).query(form, tokens.size)
        return buildString(sparql.length + 64) {
            var last = 0
            tokens.forEachIndexed { index, token ->
                val replacement = replacements[index] ?: return@forEachIndexed
                append(sparql, last, decoded.originalOffset(token.start))
                append(replacement)
                last = decoded.originalOffset(token.end)
            }
            append(sparql, last, sparql.length)
        }
    }

    /**
     * Checks that initial bindings for [variables] (names without `?`) can be applied to [sparql] under the rules
     * above, without rewriting it: throws [IllegalArgumentException] exactly when [apply] would. Providers that bind
     * natively (Jena's `substitution`) call this first so that every provider accepts and rejects the same queries.
     */
    fun validate(sparql: String, variables: Set<String>) = validate(sparql, variables, emptySet())

    /**
     * Like [validate], also saying which of the [variables] are bound to a literal or a triple term
     * ([literalVariables]): those are rejected where only an IRI is legal (a predicate, the name of a GRAPH or
     * SERVICE), exactly as [apply] rejects a constant that is not an IRI there.
     */
    fun validate(sparql: String, variables: Set<String>, literalVariables: Set<String>) {
        require(variables.containsAll(literalVariables)) { "literalVariables must only name bound variables" }
        if (variables.isEmpty()) return
        apply(sparql, variables.associateWith { if (it in literalVariables) VALIDATION_LITERAL else VALIDATION_CONSTANT })
    }

    private const val VALIDATION_CONSTANT = "<urn:kastor:initial-binding>"
    private const val VALIDATION_LITERAL = "\"kastor:initial-binding\""

    private val QUERY_FORMS = listOf("SELECT", "ASK", "CONSTRUCT", "DESCRIBE")

    /** A word ending in the `BOUND` keyword, not as part of a name (`ex:BOUND`, `myBOUND`); group 1 is the operator prefix. */
    private val BOUND_CALL = Regex("(|.*[^\\p{L}\\p{N}_:.\\-\\\\])BOUND", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------ rewriting

    /** Per bound variable a query projects, the tokens that project it; and whether it is `SELECT *`. */
    private class Projection(val exposures: Map<String, List<Int>>, val star: Boolean)

    private class Rewriter(
        private val tokens: List<Token>,
        private val bindings: Map<String, String>,
        private val expressionVariables: Map<String, String>,
        private val out: MutableMap<Int, String>,
        /** Bound variables whose constant is not an IRI. */
        private val nonIris: Set<String>,
        /** Variable tokens in predicate position or naming a GRAPH or SERVICE. */
        private val iriOnly: Set<Int>,
    ) {
        /** A group (or query level) being rewritten: its opening brace, and the expression variables it reads. */
        private class Frame(var open: Int = -1) {
            val declared = LinkedHashSet<String>()
        }

        /** Innermost group last. */
        private val frames = ArrayDeque<Frame>()

        /** The text of bound variable [name] in an expression position; [declare] records the use in the current group. */
        private fun expressionConstant(name: String, declare: Boolean = true): String {
            val variable = expressionVariables[name] ?: return bindings.getValue(name)
            if (declare) frames.last().declared.add(name)
            return "?$variable"
        }

        /** Starts [frame]'s group with a `BIND` for every expression variable it reads. */
        private fun close(frame: Frame) {
            if (frame.declared.isEmpty() || frame.open < 0) return
            out[frame.open] = frame.declared.joinToString(" ", prefix = "{ ") { name ->
                "BIND(${bindings.getValue(name)} AS ?${expressionVariables.getValue(name)})"
            }
        }
        /** Token indices currently rendered as `(constant AS ?name)`. */
        private val aliased = HashSet<Int>()

        /** Variable names used by the query or generated by [rename]. */
        private val taken: MutableSet<String> = tokens.filter { it.kind == Kind.VAR }.mapTo(HashSet()) { it.name }

        private fun bound(index: Int): Boolean = tokens[index].kind == Kind.VAR && tokens[index].name in bindings

        /** Substitutes a bound variable at [index]; [pattern] when it is in a triple-pattern position of a WHERE group. */
        private fun substitute(index: Int, pattern: Boolean = false) {
            if (!bound(index)) return
            if (index in iriOnly && tokens[index].name in nonIris) {
                reject(tokens[index].name, "it is used as a predicate or as the name of a GRAPH or SERVICE, where only an IRI can be bound")
            }
            // `BOUND(constant)` is not legal SPARQL text (BOUND takes a variable); a bound variable is always
            // bound, which is how Jena evaluates its substituted `BOUND`. `(true)` is legal wherever BOUND(...) is,
            // including directly after FILTER.
            // Operators such as `!` or `&&` are not word breaks, so the keyword may end a longer word (`!BOUND`).
            val keyword = if (index >= 2 && tokens[index - 2].kind == Kind.WORD) BOUND_CALL.matchEntire(tokens[index - 2].text) else null
            if (keyword != null && index + 1 < tokens.size && tokens[index - 1].isPunct('(') && tokens[index + 1].isPunct(')')) {
                out[index - 2] = keyword.groupValues[1] + "(true)"
                out[index - 1] = ""
                out[index] = ""
                out[index + 1] = ""
                return
            }
            out[index] = if (pattern) bindings.getValue(tokens[index].name) else expressionConstant(tokens[index].name)
        }

        private fun alias(index: Int, name: String) {
            out[index] = "(${expressionConstant(name)} AS ?$name)"
            aliased.add(index)
        }

        /** Give the projection tokens [indices] of bound variable [name] one fresh variable name. */
        private fun rename(indices: List<Int>, name: String) {
            var fresh = "${name}_bound"
            var n = 0
            while (fresh in taken) fresh = "${name}_bound${++n}"
            taken.add(fresh)
            for (index in indices) {
                // Re-renders a sub-select's alias; its group already declares the expression variable.
                out[index] = if (index in aliased) "(${expressionConstant(name, declare = false)} AS ?$fresh)" else "?$fresh"
            }
        }

        private fun reject(name: String, why: String): Nothing =
            throw IllegalArgumentException("Cannot apply an initial binding for ?$name: $why")

        /**
         * Rewrite the SELECT query whose `SELECT` keyword is at [select] and which ends before [end]
         * (the closing brace of a sub-select, or the end of the text).
         */
        fun query(select: Int, end: Int): Projection {
            val level = Frame()
            frames.addLast(level)
            try {
                return queryLevel(select, end, level)
            } finally {
                frames.removeLast()
                close(level)
            }
        }

        private fun queryLevel(select: Int, end: Int, level: Frame): Projection {
            // ---- projection
            var i = select + 1
            if (i < end && (tokens[i].isKeyword("DISTINCT") || tokens[i].isKeyword("REDUCED"))) i++
            var depth = 0
            var star = false
            val bareProjected = ArrayList<Int>()
            while (i < end) {
                val t = tokens[i]
                if (depth == 0 && (t.isPunct('{') || t.isKeyword("WHERE") || t.isKeyword("FROM"))) break
                when {
                    t.isPunct('(') -> depth++
                    t.isPunct(')') -> depth--
                    // Only a top-level `*` is `SELECT *`; the one in `COUNT(*)` is not.
                    depth == 0 && t.kind == Kind.WORD && t.text == "*" -> star = true
                    t.kind == Kind.VAR && depth == 0 -> if (t.name in bindings) bareProjected.add(i)
                    t.kind == Kind.VAR -> {
                        if (tokens[i - 1].isKeyword("AS") && t.name in bindings) reject(t.name, "the query assigns it with AS")
                        substitute(i)
                    }
                }
                i++
            }

            // ---- dataset clauses and WHERE group
            while (i < end && !tokens[i].isPunct('{')) i++
            require(i < end) { "Malformed SELECT query: no WHERE group" }
            val groupOpen = i
            level.open = groupOpen
            val groupClose = matching(groupOpen, end)
            val children = group(groupOpen + 1, groupClose)

            // ---- solution modifiers and trailing VALUES
            val groupAliases = HashMap<String, MutableList<Int>>()
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
                            alias(k, t.name)
                            groupAliases.getOrPut(t.name) { ArrayList() }.add(k)
                        }
                        // A bare constant is not an OrderCondition (an IRI would parse as a function
                        // call); a bracketed expression is, and ordering by a constant is a no-op.
                        clause == "ORDER" && depth == 0 -> out[k] = "(${expressionConstant(t.name)})"
                        clause != "VALUES" -> substitute(k)
                    }
                }
                k++
            }

            // ---- projection of bound variables
            val exposures = HashMap<String, MutableList<Int>>()
            for (index in bareProjected) {
                val name = tokens[index].name
                exposures.getOrPut(name) { ArrayList() }.add(index)
                if (name !in groupAliases) alias(index, name)
            }
            for ((name, indices) in groupAliases) exposures[name]?.addAll(indices)
            // Every `AS ?name` emitted at this level needs ?name out of scope in the WHERE group.
            for (name in bareProjected.map { tokens[it].name } + groupAliases.keys) {
                children.remove(name)?.let { rename(it, name) }
            }
            if (star) children.forEach { (name, indices) -> exposures.getOrPut(name) { ArrayList() }.addAll(indices) }
            return Projection(exposures, star)
        }

        /** Rewrite the group content in [from, to); returns the projection tokens of its sub-selects. */
        private fun group(from: Int, to: Int): MutableMap<String, MutableList<Int>> {
            val exposed = HashMap<String, MutableList<Int>>()
            // Parenthesis depth within the innermost enclosing braces: > 0 is an expression position.
            var parens = 0
            val outer = ArrayDeque<Int>()
            var i = from
            while (i < to) {
                val t = tokens[i]
                when {
                    t.isPunct('{') && i + 1 < to && tokens[i + 1].isKeyword("SELECT") -> {
                        val close = matching(i, to)
                        subSelect(i + 1, close).forEach { (name, indices) -> exposed.getOrPut(name) { ArrayList() }.addAll(indices) }
                        i = close
                    }
                    t.isPunct('{') -> {
                        outer.addLast(parens)
                        parens = 0
                        frames.addLast(Frame(i))
                    }
                    t.isPunct('}') && outer.isNotEmpty() -> {
                        parens = outer.removeLast()
                        close(frames.removeLast())
                    }
                    t.isPunct('(') -> parens++
                    t.isPunct(')') -> parens--
                    t.isKeyword("VALUES") -> valuesDeclaration(i, to)
                    t.kind == Kind.VAR && t.name in bindings -> {
                        if (i > from && tokens[i - 1].isKeyword("AS")) reject(t.name, "the query assigns it with BIND(... AS ?${t.name})")
                        substitute(i, pattern = parens <= 0)
                    }
                }
                i++
            }
            return exposed
        }

        private fun subSelect(select: Int, close: Int): Map<String, List<Int>> {
            val usedBound = (select until close).filter { bound(it) }.map { tokens[it].name }.toSet()
            val projection = query(select, close)
            for (name in usedBound) {
                if (name !in projection.exposures && !projection.star) {
                    reject(name, "it is used inside a sub-select that does not project it, so it is a different, local variable there")
                }
            }
            return projection.exposures
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

    // ------------------------------------------------------------------ codepoint escapes

    /** Decoded query text; [originalOffset] maps an offset in [text] back to the original text. */
    private class Decoded(val text: String, private val offsets: IntArray?) {
        fun originalOffset(index: Int): Int = offsets?.get(index) ?: index
    }

    private fun decodeCodepointEscapes(text: String): Decoded {
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
    private fun hexCodepoint(text: String, from: Int, digits: Int): Int {
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

    private enum class Kind { VAR, IRI, STRING, PUNCT, WORD }

    private class Token(val kind: Kind, val text: String, val start: Int, val end: Int) {
        val name: String get() = text.substring(1)
        fun isKeyword(keyword: String) = kind == Kind.WORD && text.equals(keyword, ignoreCase = true)
        fun isPunct(c: Char) = kind == Kind.PUNCT && text.length == 1 && text[0] == c
    }

    private val IRIREF = Regex("<[^<>\"{}|^`\\\\\\u0000-\\u0020]*>")
    private const val PUNCT = "{}()[],;"
    // `*` never occurs inside a name, and `SELECT*WHERE{` / `SELECT DISTINCT*{` are legal SPARQL.
    private const val WORD_BREAK = "{}()[],;\"'<?$#*"

    private fun isVarChar(c: Char) =
        c == '_' || c.isLetterOrDigit() || c == '·' || c in '̀'..'ͯ' || c in '‿'..'⁀' ||
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

    private fun digitsEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i] in '0'..'9') i++
        return i
    }

    /** The end of the exponent (`e`, optional sign, digits) at [from], or [from] when there is none. */
    private fun exponentEnd(text: String, from: Int): Int {
        if (from >= text.length || (text[from] != 'e' && text[from] != 'E')) return from
        var i = from + 1
        if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
        val end = digitsEnd(text, i)
        return if (end > i) end else from
    }

    /** The end of the INTEGER, DECIMAL or DOUBLE whose first digit is at [from]. */
    private fun numberEnd(text: String, from: Int): Int {
        var i = digitsEnd(text, from)
        if (i < text.length && text[i] == '.') {
            val fraction = digitsEnd(text, i + 1)
            // `1.` is the integer 1 and a dot, unless digits or an exponent follow (`1.5`, `1.e3`).
            if (fraction > i + 1 || exponentEnd(text, fraction) > fraction) i = fraction
        }
        return exponentEnd(text, i)
    }

    // ------------------------------------------------------------------ IRI-only positions

    /** A constant that is certainly not an IRI: a literal (quoted, numeric or boolean) or a triple term. */
    private fun isNotAnIri(constant: String): Boolean {
        val first = constant.firstOrNull() ?: return false
        return first == '"' || first == '\'' || first in '0'..'9' || first == '+' || first == '-' || first == '.' ||
            constant.startsWith("<<") || constant.equals("true", ignoreCase = true) || constant.equals("false", ignoreCase = true)
    }

    private val PATTERN_KEYWORDS = setOf("OPTIONAL", "MINUS", "UNION", "LATERAL", "GRAPH", "SERVICE", "SILENT", "FILTER", "BIND", "VALUES", "NOT", "EXISTS")

    /**
     * Finds the variable tokens that stand where the grammar allows an IRI but no other constant: the verb of a
     * triple pattern, and the name of a GRAPH or SERVICE.
     *
     * It follows the triple-pattern grammar on tokens (subject, verb or property path, object lists, blank node
     * property lists, collections, and the keywords that start other patterns) only as far as needed to tell which
     * term is the verb. Where it meets syntax it does not know (RDF 1.2 triple terms, for example) it reports nothing
     * until the next `.`, `;`, brace or pattern keyword, so it never reports a position that is not a verb.
     */
    private class IriOnlyPositions(private val tokens: List<Token>) {
        private enum class Scope { QUERY, GROUP, DATA, PROPERTY_LIST, COLLECTION, EXPRESSION, PATH }

        private enum class Expect {
            /** A subject, or a keyword starting another pattern. */
            SUBJECT,
            /** A verb: a variable, or the start (or next element) of a property path. */
            VERB,
            /** A path element was read: a path operator, or the object. */
            PATH_OR_OBJECT,
            OBJECT,
            /** An object was read: `,`, `;`, `.`, or the end of the triples. */
            SEPARATOR,
            /** The name after GRAPH or SERVICE. */
            NAME,
            /** After FILTER or BIND: a bracketed expression, a call, or `[NOT] EXISTS { }`. */
            CONSTRAINT,
            /** After VALUES: the variable (list) and the data block. */
            VALUES,
            /** Unknown syntax: nothing is reported until the next reset. */
            LOST,
        }

        private class Frame(val scope: Scope, var expect: Expect)

        private val frames = ArrayDeque<Frame>().apply { addLast(Frame(Scope.QUERY, Expect.LOST)) }
        private val found = HashSet<Int>()

        /** The state after a complete term (variable, IRI, literal, blank node, collection) in state [expect]. */
        private fun afterTerm(expect: Expect): Expect = when (expect) {
            Expect.SUBJECT -> Expect.VERB
            Expect.PATH_OR_OBJECT, Expect.OBJECT -> Expect.SEPARATOR
            Expect.NAME -> Expect.SUBJECT
            Expect.CONSTRAINT, Expect.VALUES -> expect
            Expect.VERB, Expect.SEPARATOR, Expect.LOST -> Expect.LOST
        }

        private fun push(scope: Scope, expect: Expect = Expect.LOST) = frames.addLast(Frame(scope, expect))

        /** Leaves the innermost scope of one of [scopes], closing unbalanced inner scopes with it; never the outermost query. */
        private fun pop(vararg scopes: Scope) {
            val depth = frames.indexOfLast { it.scope in scopes }
            if (depth <= 0) return
            while (frames.size > depth) frames.removeLast()
        }

        fun scan(): Set<Int> {
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                val frame = frames.last()
                when (frame.scope) {
                    Scope.DATA -> if (token.isPunct('}')) pop(Scope.DATA)
                    Scope.QUERY -> when {
                        token.isKeyword("VALUES") -> frame.expect = Expect.VALUES
                        token.isPunct('{') && frame.expect == Expect.VALUES -> {
                            frame.expect = Expect.LOST
                            push(Scope.DATA)
                        }
                        token.isPunct('{') -> open(i)
                        token.isPunct('}') -> pop(Scope.QUERY)
                    }
                    Scope.PATH -> when {
                        token.isPunct('(') -> push(Scope.PATH)
                        token.isPunct(')') -> pop(Scope.PATH)
                        token.isPunct('}') -> pop(Scope.GROUP, Scope.QUERY)
                    }
                    Scope.EXPRESSION -> when {
                        token.isPunct('(') -> push(Scope.EXPRESSION)
                        token.isPunct(')') -> pop(Scope.EXPRESSION)
                        token.isPunct('{') -> open(i)
                        token.isPunct('}') -> pop(Scope.GROUP, Scope.QUERY)
                    }
                    Scope.COLLECTION -> when {
                        token.isPunct('(') -> push(Scope.COLLECTION)
                        token.isPunct(')') -> pop(Scope.COLLECTION)
                        token.isPunct('[') -> push(Scope.PROPERTY_LIST, Expect.VERB)
                        token.isPunct('{') -> open(i)
                        token.isPunct('}') -> pop(Scope.GROUP, Scope.QUERY)
                    }
                    Scope.GROUP, Scope.PROPERTY_LIST -> i = pattern(i, frame)
                }
                i++
            }
            return found
        }

        /** Opens the group or sub-select whose `{` is at [index]. */
        private fun open(index: Int) {
            if (index + 1 < tokens.size && tokens[index + 1].isKeyword("SELECT")) push(Scope.QUERY) else push(Scope.GROUP, Expect.SUBJECT)
        }

        /** Handles the token at [index] in a group or blank node property list; returns the index of the last token consumed. */
        private fun pattern(index: Int, frame: Frame): Int {
            val token = tokens[index]
            when (token.kind) {
                Kind.PUNCT -> punctuation(token.text[0], index, frame)
                Kind.VAR -> {
                    if (frame.expect == Expect.VERB || frame.expect == Expect.NAME) found.add(index)
                    frame.expect = if (frame.expect == Expect.VERB) Expect.OBJECT else afterTerm(frame.expect)
                }
                Kind.IRI -> frame.expect = if (frame.expect == Expect.VERB) Expect.PATH_OR_OBJECT else afterTerm(frame.expect)
                Kind.STRING -> {
                    frame.expect = afterTerm(frame.expect)
                    return literalEnd(index, frame)
                }
                Kind.WORD -> word(token.text, frame)
            }
            return index
        }

        private fun punctuation(c: Char, index: Int, frame: Frame) {
            when (c) {
                '{' -> if (frame.expect == Expect.VALUES) {
                    frame.expect = Expect.SUBJECT
                    push(Scope.DATA)
                } else {
                    // Whatever the braces hold (a group, a sub-select, the pattern of EXISTS), a new pattern follows them.
                    frame.expect = Expect.SUBJECT
                    open(index)
                }
                '}' -> pop(Scope.GROUP, Scope.QUERY)
                '[' -> {
                    frame.expect = afterTerm(frame.expect)
                    push(Scope.PROPERTY_LIST, Expect.VERB)
                }
                ']' -> pop(Scope.PROPERTY_LIST)
                '(' -> when (frame.expect) {
                    Expect.CONSTRAINT -> {
                        frame.expect = Expect.SUBJECT
                        push(Scope.EXPRESSION)
                    }
                    Expect.VALUES -> push(Scope.EXPRESSION)
                    Expect.VERB -> {
                        frame.expect = Expect.PATH_OR_OBJECT
                        push(Scope.PATH)
                    }
                    Expect.SUBJECT, Expect.OBJECT, Expect.PATH_OR_OBJECT -> {
                        frame.expect = afterTerm(frame.expect)
                        push(Scope.COLLECTION)
                    }
                    Expect.SEPARATOR, Expect.NAME, Expect.LOST -> {
                        frame.expect = Expect.LOST
                        push(Scope.EXPRESSION)
                    }
                }
                ')' -> Unit
                ',' -> frame.expect = if (frame.expect == Expect.SEPARATOR) Expect.OBJECT else Expect.LOST
                ';' -> frame.expect = when (frame.expect) {
                    // `;` may be repeated, and may end the property list.
                    Expect.SEPARATOR, Expect.VERB, Expect.LOST -> Expect.VERB
                    else -> Expect.LOST
                }
            }
        }

        /** Skips the language tag or datatype of the string literal at [index]; returns the index of its last token. */
        private fun literalEnd(index: Int, frame: Frame): Int {
            val suffix = tokens.getOrNull(index + 1)?.takeIf { it.kind == Kind.WORD } ?: return index
            if (suffix.text.startsWith("@")) {
                if (endsTriples(suffix.text)) dot(frame)
                return index + 1
            }
            if (suffix.text == "^^") return if (tokens.getOrNull(index + 2)?.kind == Kind.IRI) index + 2 else index + 1
            if (suffix.text.startsWith("^^")) {
                if (endsTriples(suffix.text)) dot(frame)
                return index + 1
            }
            return index
        }

        /** Whether [word] ends with the `.` that ends a triples block (names and numbers never end with a dot). */
        private fun endsTriples(word: String): Boolean =
            word.length > 1 && word.endsWith(".") && word[word.length - 2] != '\\'

        private fun dot(frame: Frame) {
            frame.expect = if (frame.scope == Scope.GROUP) Expect.SUBJECT else Expect.LOST
        }

        private fun word(text: String, frame: Frame) {
            var core = text
            // A dot written without white space before the next term (`. ex:s`) or after a term (`ex:o.`).
            if (core.startsWith(".") && (core.length == 1 || core[1] !in '0'..'9')) {
                dot(frame)
                core = core.substring(1)
                if (core.isEmpty()) return
            }
            val dotAfter = endsTriples(core)
            if (dotAfter) core = core.dropLast(1)
            element(core, frame)
            if (dotAfter) dot(frame)
        }

        /** One word without leading or trailing dot: a keyword, a term, or a piece of a property path. */
        private fun element(core: String, frame: Frame) {
            val keyword = core.uppercase().takeIf { it in PATTERN_KEYWORDS && frame.scope == Scope.GROUP }
            frame.expect = when {
                keyword == "OPTIONAL" || keyword == "MINUS" || keyword == "UNION" || keyword == "LATERAL" -> Expect.SUBJECT
                keyword == "GRAPH" || keyword == "SERVICE" -> Expect.NAME
                keyword == "FILTER" || keyword == "BIND" -> Expect.CONSTRAINT
                keyword == "VALUES" -> Expect.VALUES
                keyword == "SILENT" -> if (frame.expect == Expect.NAME) Expect.NAME else Expect.LOST
                // NOT, EXISTS and function names belong to the constraint being read.
                frame.expect == Expect.CONSTRAINT || frame.expect == Expect.VALUES -> frame.expect
                keyword != null -> Expect.LOST
                frame.expect == Expect.VERB -> verb(core)
                frame.expect == Expect.PATH_OR_OBJECT && continuesPath(core) -> afterPathPiece(core)
                isTerm(core) -> afterTerm(frame.expect)
                else -> Expect.LOST
            }
        }

        /** A constant term written as a word: a prefixed name, blank node label, number or boolean. */
        private fun isTerm(core: String): Boolean {
            val first = core[0]
            return when {
                first in '0'..'9' -> true
                first == '+' || first == '-' || first == '.' -> core.length > 1 && (core[1] in '0'..'9' || core[1] == '.')
                core.equals("true", ignoreCase = true) || core.equals("false", ignoreCase = true) -> true
                else -> (first == ':' || first == '_' || first.isLetter() || Character.isSurrogate(first)) && ':' in core
            }
        }

        /** The state after the word [core] in verb position: `a`, a prefixed name, or path operators around them. */
        private fun verb(core: String): Expect {
            val name = core.trimStart('^', '!')
            return when {
                // `^` or `!` on its own: the element they apply to follows.
                name.isEmpty() -> Expect.VERB
                name == "a" || ((name[0] == ':' || name[0].isLetter() || Character.isSurrogate(name[0])) && ':' in name) || name[0] == 'a' && name.length > 1 && name[1] in PATH_OPERATORS ->
                    afterPathPiece(name)
                else -> Expect.LOST
            }
        }

        /** Whether [core], read after a path element, continues the path instead of being the object. */
        private fun continuesPath(core: String): Boolean = when (core[0]) {
            '/', '|', '*', '?' -> true
            // `+` is a path modifier, `+1` a number.
            '+' -> core.length == 1 || (core[1] !in '0'..'9' && core[1] != '.')
            else -> false
        }

        /** After a piece of a path: another element is due when it ends with an operator that takes one. */
        private fun afterPathPiece(core: String): Expect {
            val last = core.last()
            val escaped = core.length > 1 && core[core.length - 2] == '\\'
            return if (!escaped && (last == '/' || last == '|' || last == '^' || last == '!')) Expect.VERB else Expect.PATH_OR_OBJECT
        }

        private companion object {
            const val PATH_OPERATORS = "/|*+?"
        }
    }
}
