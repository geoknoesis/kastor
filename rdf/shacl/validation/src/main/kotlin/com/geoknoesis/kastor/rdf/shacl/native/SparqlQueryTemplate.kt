package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.SparqlPreBindingRestrictionException
import java.util.concurrent.atomic.AtomicLong

/**
 * A SHACL-SPARQL query with its pre-bound variables (`$this` and, per query form, `$value` or a component's
 * parameter names) located at compile time, so pre-binding is performed by the SHACL engine itself and behaves
 * identically on every SPARQL provider, including inside sub-queries.
 *
 * [bind] substitutes IRIs and literals syntactically: in a triple pattern or expression the term replaces the
 * variable; in the outermost SELECT projection it becomes `(term AS ?var)`, so solutions still bind the variable, in
 * a sub-select's projection `(term AS ?fresh)`, in `GROUP BY` / `ORDER BY` `(term)`, and
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
    /**
     * A pre-bound variable token. [projectedAs] is the name a substituted value takes in a projection: the variable's
     * own name in the outermost `SELECT` (its first mention there), so a solution binds it as SHACL 1.2 SPARQL
     * Appendix A requires (pre-binding is a join with the solution that binds it), a fresh name elsewhere (a sub-select
     * projecting it, whose enclosing query may project it too).
     */
    private class Slot(val token: String, val name: String, val context: Context, val projectedAs: String) : Part
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
                            Context.PROJECTION -> text.append('(').append(rendered).append(" AS ?").append(part.projectedAs).append(')')
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

        /**
         * How many queries were compiled, and how many of them after the pre-binding restriction checks
         * ([compileRestricted]): a test seam, so a test can show that a constraint's query is compiled and checked
         * once per validation run, not once per focus node.
         */
        internal val compilations = AtomicLong()
        internal val restrictedCompilations = AtomicLong()

        fun compile(query: String, preBound: Set<String>, budget: ValidationBudget = ValidationBudget.NONE): SparqlQueryTemplate {
            compilations.incrementAndGet()
            val parts = ArrayList<Part>()
            val text = StringBuilder()
            var context = Context.PLAIN
            var depth = 0
            var previousWord: String? = null
            var ordinal = 0
            // Braces open around the current token, whether the current SELECT is the outermost one, and the
            // pre-bound variables the outermost projection already names.
            var braces = 0
            var outermost = false
            val projectedOuter = HashSet<String>()
            val tokens = SparqlLexer.tokenize(query)
            var index = 0
            while (index < tokens.size) {
                budget.tick(PHASE)
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
                            word == "SELECT" -> { context = Context.PROJECTION; depth = 0; outermost = braces == 0 }
                            (word == "WHERE" || word == "FROM") && context == Context.PROJECTION -> context = Context.PLAIN
                            word == "BY" && (previousWord == "GROUP" || previousWord == "ORDER") -> { context = Context.GROUP_OR_ORDER; depth = 0 }
                            word in setOf("HAVING", "LIMIT", "OFFSET", "VALUES", "ORDER", "GROUP") && context == Context.GROUP_OR_ORDER -> context = Context.PLAIN
                        }
                        previousWord = word
                    }
                    token.kind == SparqlToken.Kind.PUNCT -> when (token.text) {
                        "(" -> depth++
                        ")" -> depth--
                        "{" -> { context = Context.PLAIN; braces++ }
                        "}" -> { context = Context.PLAIN; braces-- }
                    }
                }
                if (token.kind == SparqlToken.Kind.VARIABLE && token.variableName in preBound) {
                    if (text.isNotEmpty()) { parts.add(Text(text.toString())); text.setLength(0) }
                    val slotContext = if (context != Context.PLAIN && depth == 0) context else Context.PLAIN
                    val name = token.variableName
                    val keepsName = slotContext == Context.PROJECTION && outermost && projectedOuter.add(name)
                    parts.add(Slot(token.text, name, slotContext, if (keepsName) name else "__kastor_prebound_$ordinal"))
                    ordinal++
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
        fun restrictionViolation(query: String, preBound: Set<String>, budget: ValidationBudget = ValidationBudget.NONE): String? {
            val tokens = SparqlLexer.tokenize(query).filter { it.significant }
            val valuesBinding = ValuesBindings(tokens, preBound)
            for ((index, token) in tokens.withIndex()) {
                budget.tick(PHASE)
                if (token.kind != SparqlToken.Kind.WORD) continue
                // SPARQL splits a run like `trueSERVICE` or `1SERVICE` into a literal and a keyword, so the forbidden
                // keywords are searched in every part of the word that is not a prefixed name or blank node label.
                // No SPARQL keyword or built-in contains one of them, so a valid query gives no false positive.
                val bare = bareParts(token.text)
                for (part in bare) {
                    val upper = part.uppercase()
                    FORBIDDEN_KEYWORDS.firstOrNull { it in upper }?.let { return it }
                    if ("VALUES" in upper) valuesBinding.after(index)?.let { return "VALUES for the pre-bound variable \$$it" }
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

        /** The budget phase of the compile-time query checks. */
        private const val PHASE = "SPARQL query compilation"

        /**
         * SHACL 1.2 (Appendix A) forbids only a `VALUES` clause that mentions a potentially pre-bound variable: its
         * variables are those between the keyword and the `{` that opens its data block (a data block holds terms and
         * `UNDEF`, never variables). One backward pass over [tokens] finds, for every position, the next `{` and the
         * next pre-bound variable, so that each `VALUES` is checked in constant time: a query of many `VALUES` words
         * costs linear time, not one scan to the next `{` per word.
         */
        private class ValuesBindings(tokens: List<SparqlToken>, preBound: Set<String>) {
            private val nextBrace = IntArray(tokens.size + 1)
            private val nextPreBound = IntArray(tokens.size + 1)
            private val names = arrayOfNulls<String>(tokens.size)

            init {
                nextBrace[tokens.size] = tokens.size
                nextPreBound[tokens.size] = tokens.size
                for (i in tokens.indices.reversed()) {
                    val t = tokens[i]
                    nextBrace[i] = if (t.kind == SparqlToken.Kind.PUNCT && t.text == "{") i else nextBrace[i + 1]
                    val preBoundHere = t.kind == SparqlToken.Kind.VARIABLE && t.variableName in preBound
                    if (preBoundHere) names[i] = t.variableName
                    nextPreBound[i] = if (preBoundHere) i else nextPreBound[i + 1]
                }
            }

            /** The pre-bound variable the `VALUES` keyword at [index] mentions before its data block, or null. */
            fun after(index: Int): String? {
                val variable = nextPreBound[index + 1]
                return if (variable < nextBrace[index + 1]) names[variable] else null
            }
        }

        /**
         * The parts of a WORD token that SPARQL does not read as a prefixed name (PNAME_NS / PNAME_LN) or a blank node
         * label: keywords, booleans, numbers and anything ill-formed, in order.
         *
         * Linear in the length of [word]: one backward pass records where each run of PN_CHARS and `.` ends, so the
         * prefixed-name test at a position is constant time (a run that does not end in `:` holds no PN_PREFIX), and
         * every prefixed name or label found is skipped as a whole.
         */
        private fun bareParts(word: String): List<String> {
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
         * [SparqlPreBindingRestrictionException] message. Every pass is linear in the query length and ticks [budget]
         * once per token.
         */
        fun compileRestricted(
            prologue: String,
            body: String,
            preBound: Set<String>,
            subject: String,
            budget: ValidationBudget = ValidationBudget.NONE,
        ): SparqlQueryTemplate {
            budget.check(PHASE)
            restrictedCompilations.incrementAndGet()
            val query = prologue + body
            for (text in listOf(body, query)) {
                // RDF4J decodes codepoint escapes in the whole query before parsing, Jena does not: check every view.
                val decoded = try {
                    decodeCodepointEscapes(text, strict = true)
                } catch (e: ShapeCompileException) {
                    throw ShapeCompileException("$subject is ill-formed: ${e.message}", e)
                }
                for (view in linkedSetOf(text, decoded, decodeCodepointEscapes(text, strict = false))) {
                    restrictionViolation(view, preBound, budget)?.let { what ->
                        throw SparqlPreBindingRestrictionException("$subject uses $what, which is not supported with pre-bound variables")
                    }
                }
            }
            // A codepoint escape can spell a pre-bound variable (`?th`, the escape of `i`, `s`), or end a string or comment early so that
            // one appears: the template, built from the text as written, has no slot for it, and RDF4J, which decodes
            // the escapes first, would see the variable unbound. Every view must mention the same pre-bound variables.
            val asWritten = preBoundMentions(query, preBound)
            for (view in listOf(decodeCodepointEscapes(query, strict = true), decodeCodepointEscapes(query, strict = false))) {
                if (preBoundMentions(view, preBound) != asWritten) {
                    throw SparqlPreBindingRestrictionException(
                        "$subject spells a pre-bound variable with a codepoint escape, which is not supported with pre-bound variables",
                    )
                }
            }
            budget.check(PHASE)
            return compile(query, preBound, budget)
        }

        /** The pre-bound variables [query] mentions as variable tokens, in order. */
        private fun preBoundMentions(query: String, preBound: Set<String>): List<String> =
            SparqlLexer.tokenize(query).mapNotNull { t -> t.variableName.takeIf { t.kind == SparqlToken.Kind.VARIABLE && it in preBound } }

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

        /**
         * The longest language tag written into a query; a longer one stays a provider binding. Tags in use are a few
         * dozen characters (BCP 47 recommends implementations support 35 at least).
         */
        internal const val MAX_RENDERED_LANGUAGE_TAG = 256

        /**
         * Whether [tag] is a SPARQL LANGTAG without its `@` (`[a-zA-Z]+ ('-' [a-zA-Z0-9]+)*`) of at most
         * [MAX_RENDERED_LANGUAGE_TAG] characters. A linear scan: a regular expression with a repeated group recurses
         * once per subtag on java.util.regex and overflows the stack on a data literal with a very long tag.
         */
        internal fun isRenderableLanguageTag(tag: String): Boolean {
            if (tag.isEmpty() || tag.length > MAX_RENDERED_LANGUAGE_TAG) return false
            var subtag = 0
            var first = true
            for (c in tag) {
                when {
                    c == '-' -> {
                        if (subtag == 0) return false
                        subtag = 0
                        first = false
                    }
                    c in 'A'..'Z' || c in 'a'..'z' || (!first && c in '0'..'9') -> subtag++
                    else -> return false
                }
            }
            return subtag > 0
        }

        fun renderTerm(term: RdfTerm): String? =
            when (term) {
                is Iri -> renderIri(term.value)
                is LangString ->
                    if (term.direction != null || !isRenderableLanguageTag(term.lang)) null else "\"${escape(term.lexical)}\"@${term.lang}"
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
