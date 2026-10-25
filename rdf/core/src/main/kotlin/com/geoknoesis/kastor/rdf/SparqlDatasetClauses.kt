package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.RdfNameChars.escapeEnd
import com.geoknoesis.kastor.rdf.RdfNameChars.exponentEnd
import com.geoknoesis.kastor.rdf.RdfNameChars.isVarNamePart
import com.geoknoesis.kastor.rdf.RdfNameChars.isVarNameStart
import com.geoknoesis.kastor.rdf.RdfNameChars.nameCharEnd
import com.geoknoesis.kastor.rdf.RdfNameChars.isPnCharsBase

/**
 * Analyses and rewrites the dataset-related parts of a SPARQL query: detects `FROM` and `GRAPH`, inserts dataset
 * clauses (FROM / FROM NAMED) at the grammatically correct place - after the SELECT projection, CONSTRUCT template,
 * DESCRIBE targets or ASK keyword, and before the WHERE clause (SPARQL 1.1 section 13.2) - and rewrites `GRAPH`
 * patterns for a dataset without named graphs.
 *
 * The scanner is token-aware: string literals, IRIs, comments, language tags and prefixed names (including escaped
 * characters in local names) are skipped, numbers and booleans are separated from a keyword that follows them
 * (`1.GRAPH`), only the top-level query form is considered when inserting (sub-selects are left untouched), and
 * `PREFIX :`, `BASE` and `VERSION` prologue declarations are supported.
 *
 * Codepoint escapes (`\uXXXX`, `\UXXXXXXXX`) are read both ways engines read them: decoded before tokenizing, as
 * the SPARQL grammar says (the "early" reading), and decoded inside strings and IRIs after tokenizing, as Jena does
 * (the "late" reading). See [canonical] and the notes on [DatasetImpl].
 *
 * Known limits: this is a hand-written scanner over the query text, not a SPARQL parser. It recognises the
 * constructs above and nothing else; a query it cannot analyse with confidence is rejected (or left unrewritten)
 * rather than guessed at, and a grammar feature added to SPARQL later is invisible to it until the scanner learns
 * it. `SparqlScannerDifferentialTest` compares it with a real parser; extend that test before extending the scanner.
 */
internal object SparqlDatasetClauses {
    internal enum class Kind { WORD, IRI, STRING, VAR, PUNCT }
    internal class Token(val kind: Kind, val start: Int, val end: Int)

    private val SOLUTION_MODIFIERS = setOf("ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET", "VALUES")

    /** Words, besides `a`, numbers and prefixed names, of a pattern whose written variables are all in scope. */
    internal val SIMPLE_PATTERN_WORDS = setOf("OPTIONAL", "UNION", "GRAPH", "TRUE", "FALSE")

    /** A SPARQL codepoint escape: `\u` + 4 hex digits or `\U` + 8 hex digits. */
    private val CODEPOINT_ESCAPE = Regex("""\\(?:u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8})""")

    /** The word `FROM`, in any case, wherever it stands: the fail-closed test for a query that cannot be analysed. */
    private val FROM_WORD = Regex("""(?<![\p{L}\p{N}_])FROM(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)

    /**
     * Deepest nesting of groups [withoutNamedGraphs] analyses. The scope analysis recurses per group and reads each
     * nested group again, so a deeper query is refused (it is unanalysable) instead of overflowing the stack or costing
     * time quadratic in its length. Real queries nest a handful of groups.
     */
    const val MAX_GROUP_DEPTH = 128

    /** Stands in the early reading for an escape above U+10FFFF, which has no character. */
    private val REPLACEMENT_CHARACTER = 0xFFFD.toChar()

    /**
     * A query in the two readings of its codepoint escapes.
     *
     * - [text] is the query with the escapes outside strings, IRIs and comments decoded (they spell parts of
     *   names), and [tokens] are its tokens when the remaining escapes stay inside their tokens: the late reading.
     * - [earlyText] is [text] with every escape decoded and [earlyTokens] are its tokens: the early reading. They
     *   are null when that text has an unterminated string, which no engine reading the query that way accepts, so
     *   only the late reading can run. [origin] maps an offset of [earlyText] to the offset of [text] it came from.
     *
     * For a query without escapes the two readings are the same object.
     */
    private class Analysis(
        val text: String,
        val tokens: List<Token>,
        val earlyText: String,
        val earlyTokens: List<Token>?,
        private val origin: IntArray?,
    ) {
        /** True if both readings have the same tokens before offset [before] of [text] (by default: everywhere). */
        fun agree(before: Int = Int.MAX_VALUE): Boolean {
            val early = earlyTokens ?: return true
            if (early === tokens) return true
            val map = origin ?: return false
            var k = 0
            while (k < tokens.size && tokens[k].start < before) {
                val late = tokens[k]
                val other = early.getOrNull(k) ?: return false
                if (other.kind != late.kind || map[other.start] != late.start || map[other.end] != late.end) return false
                k++
            }
            val next = early.getOrNull(k) ?: return true
            return map[next.start] >= before
        }

        /** True if either reading has [keyword] as a keyword token. */
        fun hasKeyword(keyword: String): Boolean =
            tokens.any { isKeyword(text, it, keyword) } ||
                (earlyTokens != null && earlyTokens !== tokens && earlyTokens.any { isKeyword(earlyText, it, keyword) })
    }

    /** The two readings of [query], or null if it has an unterminated string or an escape that cannot be placed. */
    private fun analyse(query: String): Analysis? {
        if (!CODEPOINT_ESCAPE.containsMatchIn(query)) {
            val tokens = tokenize(query) ?: return null
            return Analysis(query, tokens, query, tokens, null)
        }
        // 1. Escapes outside strings, IRIs and comments are decoded: only the grammar's rule can read them.
        val opaque = ArrayList<Token>()
        tokenize(query, opaque) ?: return null
        val text = decodeOutside(query, opaque) ?: return null
        opaque.clear()
        val tokens = tokenize(text, opaque) ?: return null
        val escapes = CODEPOINT_ESCAPE.findAll(text).toList()
        if (escapes.isEmpty()) return Analysis(text, tokens, text, tokens, null)
        // Decoding must not have put a new escape together (a decoded 'u' after a backslash).
        if (escapes.any { !inside(opaque, it.range) }) return null
        // 2. The early reading: every remaining escape decoded, with the way back to the offsets of the text.
        val early = StringBuilder(text.length)
        val origin = IntArray(text.length + 1)
        var copied = 0
        for (escape in escapes) {
            for (k in copied until escape.range.first) {
                origin[early.length] = k
                early.append(text[k])
            }
            val codePoint = text.substring(escape.range.first + 2, escape.range.last + 1).toLong(16)
            val start = early.length
            if (codePoint > Character.MAX_CODE_POINT) early.append(REPLACEMENT_CHARACTER) else early.appendCodePoint(codePoint.toInt())
            for (k in start until early.length) origin[k] = escape.range.first
            copied = escape.range.last + 1
        }
        for (k in copied until text.length) {
            origin[early.length] = k
            early.append(text[k])
        }
        origin[early.length] = text.length
        val earlyText = early.toString()
        return Analysis(text, tokens, earlyText, tokenize(earlyText), origin)
    }

    /**
     * True if [range] lies within one of the [regions] (strings, IRIs and comments). The regions come from one
     * tokenizer pass, so they are in text order and do not overlap: a binary search finds the only candidate, which
     * keeps the scan of a query with many escapes and many strings linear (up to a log factor), not quadratic.
     */
    private fun inside(regions: List<Token>, range: IntRange): Boolean {
        var low = 0
        var high = regions.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val region = regions[mid]
            when {
                region.end <= range.first -> low = mid + 1
                region.start > range.first -> high = mid - 1
                else -> return range.last < region.end
            }
        }
        return false
    }

    /**
     * [query] with the escapes outside the [opaque] regions decoded; [query] itself if there are none. Null if such
     * an escape is anything but a letter, a digit or `_`: there it can only spell part of a keyword or a name.
     */
    private fun decodeOutside(query: String, opaque: List<Token>): String? {
        var out: StringBuilder? = null
        var copied = 0
        for (escape in CODEPOINT_ESCAPE.findAll(query)) {
            if (inside(opaque, escape.range)) continue
            val codePoint = query.substring(escape.range.first + 2, escape.range.last + 1).toLong(16)
            if (codePoint > Character.MAX_CODE_POINT) return null
            if (codePoint.toInt() != '_'.code && !Character.isLetterOrDigit(codePoint.toInt())) return null
            val builder = out ?: StringBuilder(query.length).also { out = it }
            builder.append(query, copied, escape.range.first).appendCodePoint(codePoint.toInt())
            copied = escape.range.last + 1
        }
        return out?.append(query, copied, query.length)?.toString() ?: query
    }

    /**
     * The one text both readings of [query] agree on: [query] itself when its codepoint escapes are all inside
     * strings, IRIs and comments (they are left as written), otherwise [query] with the escapes outside them
     * decoded (`\u0047RAPH` becomes `GRAPH`).
     *
     * @return null if the readings differ - an escape ends a string, an IRI or a comment early for an engine that
     *   decodes escapes first (`"a\u0022 # "`), or lets a string run on (`"\u005C"`) - if an escape outside
     *   strings, IRIs and comments is not a letter, a digit or `_`, or if the query has an unterminated string.
     */
    fun canonical(query: String): String? = analyse(query)?.takeIf { it.agree() }?.text

    internal fun isKeyword(query: String, token: Token, keyword: String): Boolean =
        token.kind == Kind.WORD && token.end - token.start == keyword.length &&
            query.regionMatches(token.start, keyword, 0, keyword.length, ignoreCase = true)

    /**
     * True if [query] contains a `FROM` keyword in either reading of its escapes, i.e. declares `FROM` or
     * `FROM NAMED` dataset clauses. `FROM` inside strings, IRIs, comments, variables, language tags and prefixed
     * names does not count; `FROM` is not used by any other SPARQL construct. For a query that cannot be analysed,
     * true if the word `FROM` appears anywhere in it, with or without its escapes decoded: an engine may read it as
     * a dataset clause, so it fails closed.
     */
    fun declaresDataset(query: String): Boolean = analyse(query)?.hasKeyword("FROM")
        ?: (FROM_WORD.containsMatchIn(query) || FROM_WORD.containsMatchIn(CODEPOINT_ESCAPE.replace(query) { escape ->
            val codePoint = escape.value.substring(2).toLong(16)
            if (codePoint > Character.MAX_CODE_POINT) REPLACEMENT_CHARACTER.toString() else String(Character.toChars(codePoint.toInt()))
        }))

    /**
     * True if [query] contains the `GRAPH` keyword (a graph pattern, or a graph reference in an update) in either
     * reading of its escapes. `GRAPH` inside strings, IRIs, comments, variables, language tags and prefixed names
     * does not count. A query that cannot be analysed (unterminated string, misplaced escapes) counts as using
     * `GRAPH`.
     */
    fun usesGraphPattern(query: String): Boolean = analyse(query)?.hasKeyword("GRAPH") ?: true

    /**
     * Rewrites [query] so that every top-level-or-nested `GRAPH` pattern of its WHERE clause evaluates as it would
     * against an empty named-graph set - no solutions - while the default graph is still read in place:
     *
     * - `GRAPH name { P }`, where the variables in scope of `P` can be told ([patternVariables]: `P` consists of
     *   triple patterns, `OPTIONAL`, `UNION`, nested groups, `GRAPH`, `FILTER`, `BIND`, `MINUS`, `VALUES` and
     *   sub-selects), becomes `{ VALUES (vars) { } FILTER EXISTS { P } }` over the graph variable and the variables
     *   in scope of `P`: exactly the variables `GRAPH name { P }` has in scope. The empty table has no solution for
     *   the filter to test, so `P` is never evaluated; it stays in the query, so the engine still checks its syntax.
     * - Any other `GRAPH ?g { P }` (with `SERVICE`, a sub-select followed by `VALUES`, an expression the scanner
     *   cannot read reliably, or a word it does not know) becomes `{ VALUES ?g { } { P } }`, which evaluates `P`.
     * - Any other `GRAPH <iri> { P }` (or a prefixed name), including one without variables in scope, becomes
     *   `{ { P } FILTER(false) }`.
     *
     * All replacements are standard SPARQL 1.1 and produce no solutions, exactly as a GRAPH pattern does when the
     * dataset has no named graphs; joins, `OPTIONAL`, `UNION`, `MINUS` and `[NOT] EXISTS` around them therefore
     * behave as specified. A `CONSTRUCT` template is left untouched (only the pattern is rewritten), and so is
     * everything inside `SERVICE [SILENT] name { ... }`: that group is evaluated by the remote endpoint against its
     * own dataset.
     *
     * @return [query] itself if it has no `GRAPH` in either reading of its escapes (with escapes outside strings,
     *   IRIs and comments decoded, see [canonical]); the rewritten query; or null if a `GRAPH` keyword is not
     *   followed by a variable or IRI and a balanced group, a `SERVICE` keyword is not followed by a name and a
     *   balanced group, the query has an unterminated string or its readings differ, it uses `GRAPH` in a
     *   `CONSTRUCT WHERE` short form, or it nests groups deeper than [MAX_GROUP_DEPTH].
     */
    fun withoutNamedGraphs(query: String): String? {
        val analysis = analyse(query) ?: return null
        val text = analysis.text
        if (!analysis.hasKeyword("GRAPH")) return if (text == query) query else text
        if (!analysis.agree()) return null
        val tokens = analysis.tokens
        if (groupDepth(text, tokens) > MAX_GROUP_DEPTH) return null
        fun isGraph(t: Token) = isKeyword(text, t, "GRAPH")
        fun punct(t: Token, c: Char) = t.kind == Kind.PUNCT && text[t.start] == c
        fun isName(t: Token) = t.kind == Kind.VAR || t.kind == Kind.IRI || (t.kind == Kind.WORD && isPrefixedName(text, t))

        // Skip a CONSTRUCT template: GRAPH there (a Jena quad-template extension) describes output, not a pattern.
        var firstPatternToken = 0
        val construct = tokens.indexOfFirst { isKeyword(text, it, "CONSTRUCT") }
        if (construct >= 0 && tokens.subList(0, construct).none { punct(it, '{') }) {
            val next = tokens.getOrNull(construct + 1)
            if (next != null && isKeyword(text, next, "WHERE")) {
                if (tokens.any(::isGraph)) return null
            } else if (next != null && punct(next, '{')) {
                val close = matchingBrace(tokens, construct + 1, text) ?: return null
                firstPatternToken = close + 1
            }
        }

        class Edit(val start: Int, val end: Int, val text: String)
        val edits = ArrayList<Edit>()
        var i = firstPatternToken
        while (i < tokens.size) {
            val token = tokens[i]
            if (isKeyword(text, token, "SERVICE")) {
                // The remote endpoint evaluates this group against its own dataset: leave it exactly as written.
                val nameIndex = if (tokens.getOrNull(i + 1)?.let { isKeyword(text, it, "SILENT") } == true) i + 2 else i + 1
                val name = tokens.getOrNull(nameIndex) ?: return null
                val open = tokens.getOrNull(nameIndex + 1) ?: return null
                if (!isName(name) || !punct(open, '{')) return null
                i = (matchingBrace(tokens, nameIndex + 1, text) ?: return null) + 1
                continue
            }
            if (isGraph(token)) {
                val term = tokens.getOrNull(i + 1) ?: return null
                val open = tokens.getOrNull(i + 2) ?: return null
                if (!isName(term) || !punct(open, '{')) return null
                val close = matchingBrace(tokens, i + 2, text) ?: return null
                val graphVariable = if (term.kind == Kind.VAR) text.substring(term.start, term.end) else null
                val variables = patternVariables(text, tokens, i + 3, close)
                    ?.let { written -> (listOfNotNull(graphVariable) + written).distinctBy { it.substring(1) } }
                when {
                    !variables.isNullOrEmpty() -> {
                        // An empty table over the variables in scope. A filter is asked once per solution, of which
                        // there are none, so P is parsed but never evaluated. Nested GRAPH patterns are still
                        // rewritten below, like everywhere else.
                        val table = if (variables.size == 1) variables[0] else variables.joinToString(" ", "(", ")")
                        edits.add(Edit(token.start, open.end, "{ VALUES $table { } FILTER EXISTS {"))
                        edits.add(Edit(tokens[close].end, tokens[close].end, " }"))
                    }
                    graphVariable != null -> {
                        edits.add(Edit(token.start, term.end, "{ VALUES $graphVariable { }"))
                        edits.add(Edit(tokens[close].end, tokens[close].end, " }"))
                    }
                    else -> {
                        edits.add(Edit(token.start, term.end, "{"))
                        edits.add(Edit(tokens[close].end, tokens[close].end, " FILTER(false) }"))
                    }
                }
            }
            i++
        }
        // One left-to-right pass over the original offsets. Where a group closes exactly where the next GRAPH starts
        // (`}GRAPH`), the insertion that closes the first pattern comes before the replacement that opens the next.
        val out = StringBuilder(text.length + edits.size * 16)
        var copied = 0
        for (edit in edits.sortedWith(compareBy<Edit> { it.start }.thenBy { it.end })) {
            out.append(text, copied, edit.start).append(edit.text)
            copied = edit.end
        }
        return out.append(text, copied, text.length).toString()
    }

    /**
     * The variables in scope of the group whose inside is the tokens [from] until [until] (SPARQL 1.1 section
     * 18.2.1), in order of first appearance, or null if they cannot be told with certainty.
     *
     * - Triple patterns (with paths, blank node property lists, collections and triple terms), nested groups,
     *   `OPTIONAL`, `UNION` and `GRAPH`: every variable written is in scope.
     * - `FILTER`: no variable of the constraint is in scope.
     * - `MINUS { ... }`: no variable of its group is in scope.
     * - `BIND(expr AS ?v)`: `?v` is in scope, the variables of `expr` are not.
     * - `VALUES ?v { ... }` and `VALUES (?v ?w) { ... }`: the variables of the table are in scope.
     * - A sub-select: the variables it projects are in scope - those listed, the `?v` of each `(expr AS ?v)`, or for
     *   `SELECT *` the variables in scope of its own pattern.
     *
     * Null for anything else: `SERVICE` (the remote group is not analysed), a sub-select with a trailing `VALUES`
     * clause, a word that is none of the above (a keyword this scanner does not know), an unbalanced bracket, and an
     * expression in which a `<` may have been read as the start of an IRI (`?a<?b&&?c>1`).
     */
    private fun patternVariables(text: String, tokens: List<Token>, from: Int, until: Int): List<String>? {
        val variables = LinkedHashSet<String>()
        return if (SparqlGroupScope(text, tokens).group(from, until, variables)) variables.toList() else null
    }

    /** Deepest nesting of `{` groups in [tokens] of [text]. */
    private fun groupDepth(text: String, tokens: List<Token>): Int {
        var depth = 0
        var deepest = 0
        for (t in tokens) {
            if (t.kind != Kind.PUNCT) continue
            when (text[t.start]) {
                '{' -> deepest = maxOf(deepest, ++depth)
                '}' -> depth--
            }
        }
        return deepest
    }

    /** Index of the `}` token closing the `{` at [openIndex], or null if unbalanced. */
    private fun matchingBrace(tokens: List<Token>, openIndex: Int, query: String): Int? {
        var depth = 0
        for (j in openIndex until tokens.size) {
            val t = tokens[j]
            if (t.kind != Kind.PUNCT) continue
            when (query[t.start]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return j
            }
        }
        return null
    }

    /**
     * Inserts the dataset [clauses] into [query] (with escapes outside strings, IRIs and comments decoded, see
     * [canonical]).
     *
     * @return the rewritten query; the query itself if it already declares a dataset; or null if no insertion point
     *   could be found, the query cannot be analysed, or the two readings of its escapes differ before the insertion
     *   point or about a `FROM` keyword.
     */
    fun insert(query: String, clauses: String): String? {
        val analysis = analyse(query) ?: return null
        val text = analysis.text
        val tokens = analysis.tokens
        fun word(t: Token) = if (t.kind == Kind.WORD) text.substring(t.start, t.end).uppercase() else null
        fun punct(t: Token, c: Char) = t.kind == Kind.PUNCT && text[t.start] == c

        var i = tokens.indexOfFirst { word(it) in setOf("SELECT", "CONSTRUCT", "DESCRIBE", "ASK") }
        if (i < 0 || tokens.subList(0, i).any { punct(it, '{') }) return null
        val form = word(tokens[i])!!
        i++
        if (form == "CONSTRUCT" && i < tokens.size && punct(tokens[i], '{')) {
            var depth = 0
            while (i < tokens.size) {
                if (punct(tokens[i], '{')) depth++
                if (punct(tokens[i], '}') && --depth == 0) break
                i++
            }
            if (i >= tokens.size) return null
            i++
        }
        var parens = 0
        var insertAt = -1
        while (i < tokens.size) {
            val t = tokens[i]
            val w = word(t)
            when {
                punct(t, '(') -> parens++
                punct(t, ')') -> parens--
                parens > 0 -> Unit
                w == "FROM" -> return text
                w == "WHERE" || punct(t, '{') -> { insertAt = t.start; break }
                form == "DESCRIBE" && w in SOLUTION_MODIFIERS -> { insertAt = t.start; break }
            }
            i++
        }
        if (insertAt < 0) {
            if (form != "DESCRIBE") return null
            insertAt = text.length
        }
        // What follows the clauses may read differently (the engine then accepts one reading or none), but not what
        // decides where they go, and neither reading may bring dataset clauses of its own.
        if (analysis.hasKeyword("FROM") || !analysis.agree(insertAt)) return null
        return text.substring(0, insertAt) + "\n" + clauses + "\n" + text.substring(insertAt)
    }

    /** True if the WORD [token] is a prefixed name or blank node label (it contains a colon). */
    internal fun isPrefixedName(query: String, token: Token): Boolean {
        for (k in token.start until token.end) if (query[k] == ':') return true
        return false
    }

    /**
     * Tokenizes enough of SPARQL to find keywords and balanced groups; returns null on an unterminated string.
     * Codepoint escapes are left alone: one inside a string or an IRI belongs to it, like any other character.
     *
     * WORD tokens are keywords and booleans (letters, digits and `_` only), numbers, and prefixed names or blank
     * node labels (the only words that contain a colon). As in the SPARQL grammar, a dot belongs to a number only
     * when a digit or an exponent follows it and to a prefixed name only when it is not its last character, so
     * `1.GRAPH`, `true.GRAPH` and `ex:o. GRAPH` end before the dot; `\`-escaped characters belong to a local name;
     * a language tag (`@en-US`) is one token that is never a keyword.
     *
     * [opaque], when given, receives the regions whose text is not SPARQL syntax: strings, IRIs and comments.
     */
    private fun tokenize(q: String, opaque: MutableList<Token>? = null): List<Token>? {
        val tokens = ArrayList<Token>()
        var i = 0
        val n = q.length
        while (i < n) {
            val c = q[i]
            when {
                c.isWhitespace() -> i++
                c == '#' -> {
                    val start = i
                    while (i < n && q[i] != '\n' && q[i] != '\r') i++
                    opaque?.add(Token(Kind.PUNCT, start, i))
                }
                c == '"' || c == '\'' -> {
                    val start = i
                    val long = i + 2 < n && q[i + 1] == c && q[i + 2] == c
                    i += if (long) 3 else 1
                    var closed = false
                    while (i < n) {
                        val d = q[i]
                        if (d == '\\') { i += 2; continue }
                        if (long) {
                            if (d == c && i + 2 < n && q[i + 1] == c && q[i + 2] == c) { i += 3; closed = true; break }
                        } else {
                            if (d == c) { i++; closed = true; break }
                            if (d == '\n' || d == '\r') break
                        }
                        i++
                    }
                    if (!closed) return null
                    tokens.add(Token(Kind.STRING, start, i).also { opaque?.add(it) })
                }
                c == '<' -> {
                    var j = i + 1
                    while (j < n) {
                        val d = q[j]
                        if (d == '\\') {
                            val end = escapeEnd(q, j)
                            if (end < 0) break
                            j = end
                        } else if (d > ' ' && d !in "<>\"{}|^`") j++ else break
                    }
                    if (j < n && q[j] == '>') {
                        tokens.add(Token(Kind.IRI, i, j + 1).also { opaque?.add(it) }); i = j + 1
                    } else {
                        tokens.add(Token(Kind.PUNCT, i, i + 1)); i++
                    }
                }
                (c == '?' || c == '$') && i + 1 < n && isVarNameStart(q.codePointAt(i + 1)) -> {
                    // VAR1 / VAR2: the sign and a VARNAME.
                    val start = i
                    i++
                    while (i < n) {
                        val codePoint = q.codePointAt(i)
                        if (!isVarNamePart(codePoint)) break
                        i += Character.charCount(codePoint)
                    }
                    tokens.add(Token(Kind.VAR, start, i))
                }
                c == '@' && i + 1 < n && q[i + 1].isLetter() -> {
                    // Language tag: letters, then '-' separated alphanumeric subtags (and a '--' direction).
                    val start = i
                    i++
                    while (i < n && q[i].isLetter()) i++
                    while (i < n && q[i] == '-') {
                        var k = i + 1
                        if (k < n && q[k] == '-') k++
                        val subtag = k
                        while (k < n && q[k].isLetterOrDigit()) k++
                        if (k == subtag) break
                        i = k
                    }
                    tokens.add(Token(Kind.PUNCT, start, i))
                }
                c in '0'..'9' || (c == '.' && i + 1 < n && q[i + 1] in '0'..'9') -> {
                    val start = i
                    while (i < n && q[i] in '0'..'9') i++
                    if (i < n && q[i] == '.') {
                        var k = i + 1
                        while (k < n && q[k] in '0'..'9') k++
                        val exponent = exponentEnd(q, k)
                        if (exponent > 0) i = exponent else if (k > i + 1) i = k
                    } else {
                        val exponent = exponentEnd(q, i)
                        if (exponent > 0) i = exponent
                    }
                    tokens.add(Token(Kind.WORD, start, i))
                }
                isPnCharsBase(q.codePointAt(i)) || c == '_' || c == ':' -> {
                    val start = i
                    // A prefix (possibly empty) directly followed by a colon makes a prefixed name or blank node label.
                    var j = i
                    while (j < n) {
                        val next = nameCharEnd(q, j)
                        if (next == j) break
                        j = next
                    }
                    if (j < n && q[j] == ':' && (j == i || q[j - 1] != '.')) {
                        var k = j + 1
                        while (k < n) {
                            val d = q[k]
                            if (d == '\\' && k + 1 < n) { k += 2; continue }
                            if (d == ':' || d == '%') { k++; continue }
                            val next = nameCharEnd(q, k)
                            if (next == k) break
                            k = next
                        }
                        // A local name does not end with an (unescaped) dot.
                        while (k > j + 1 && q[k - 1] == '.' && q[k - 2] != '\\') k--
                        i = k
                    } else {
                        // A keyword or a boolean: letters, digits and '_'.
                        while (i < n) {
                            val codePoint = q.codePointAt(i)
                            if (!(isPnCharsBase(codePoint) || codePoint == '_'.code || codePoint in '0'.code..'9'.code)) break
                            i += Character.charCount(codePoint)
                        }
                    }
                    tokens.add(Token(Kind.WORD, start, i))
                }
                else -> { tokens.add(Token(Kind.PUNCT, i, i + 1)); i++ }
            }
        }
        return tokens
    }
}
