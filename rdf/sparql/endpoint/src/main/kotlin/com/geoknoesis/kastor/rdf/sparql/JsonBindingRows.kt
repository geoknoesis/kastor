package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.MapBindingSet
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Streaming decoder for the SPARQL 1.1 Query Results JSON Format (`application/sparql-results+json`):
 * SELECT rows ([rows]) are decoded one at a time, straight from the stream and in a single pass, and
 * an ASK result ([ask]) is read the same way.
 *
 * Only what a result needs is stored: the variable names of `head.vars` (when `head` precedes the
 * rows), the variable names of a row and, per binding, its `type`, `value`, `xml:lang` and
 * `datatype`. Everything else (the other members of `head`, unknown members, nested values) is
 * checked and skipped without being stored.
 *
 * JSON: the whole document, skipped content included, must match the JSON grammar (RFC 8259), with
 * nothing after it.
 * - The input must be UTF-8; malformed byte sequences are an error, not replaced. One leading byte
 *   order mark is ignored.
 * - Only space, tab, line feed and carriage return are white space.
 * - Inside a string every control character (U+0000 to U+001F) must be escaped.
 * - Beyond the grammar, a string must not hold an unpaired surrogate: a `\u` escape of a high
 *   surrogate must be followed at once by the escape of a low surrogate, and a low surrogate
 *   must follow a high one. (Such text is not Unicode and cannot be part of an RDF term.)
 * - No single value (a row, a field name, `head`, or a skipped value) may be longer than
 *   [maxValueChars] characters of JSON text, and none may be nested deeper than [MAX_DEPTH].
 *
 * SPARQL results:
 * - The members this decoder reads must not be repeated: `head`, `vars`, `results`, `bindings` (and
 *   `boolean` for ASK), a variable within a row, and `type`, `value`, `xml:lang` and `datatype`
 *   within a binding. A general JSON parser would keep one of them, the first or the last. Members
 *   that are skipped are not compared with each other.
 * - `head` is an object and its `vars`, when present, an array of strings. A row may only bind
 *   variables that `vars` lists, when a `head` with `vars` precedes `results`; a `head` that follows
 *   the rows is only checked for its form (the rows were delivered by then), and a result without
 *   `head` or `vars` is read as it is.
 * - `type`, `value`, `xml:lang` and `datatype` are strings; a number, `true`, `false` or `null` in
 *   their place is an error. An empty `xml:lang` is read as no language tag.
 * - A language tag goes with no `datatype` or with `rdf:langString`; with any other datatype the
 *   binding is contradictory and rejected.
 * - RDF 1.2 result terms (`"type":"triple"`, and literals with a base direction, `its:dir` or
 *   `direction`) are not supported and are rejected by name.
 *
 * Terms the RDF model refuses (an invalid IRI or datatype IRI, a language tag that is not
 * well-formed, a blank node without a label, an unpaired surrogate inside a row) are handled as
 * [malformedTerms] says: [MalformedTermPolicy.FAIL] fails the result at that row;
 * [MalformedTermPolicy.SKIP_ROW] leaves the row out, counts it in [skippedRows] and goes on. Under
 * both, everything else in this list is an error.
 *
 * Every failure is an [IllegalStateException] whose message never quotes more than a short,
 * printable excerpt of the input; I/O failures of the underlying stream propagate as they are.
 *
 * @param created called for every object created for decoded content (a string, a term, a row);
 *   skipped content creates none, which is what the tests assert through it. `null` in production.
 */
internal class JsonBindingRows(
    input: InputStream,
    private val maxValueChars: Int = SparqlEndpointConfig.DEFAULT_MAX_RESULT_ROW_CHARS,
    private val malformedTerms: MalformedTermPolicy = MalformedTermPolicy.FAIL,
    private val created: (() -> Unit)? = null,
) {
    /** Rows left out under [MalformedTermPolicy.SKIP_ROW] so far. */
    var skippedRows = 0L
        private set

    /** Why the first of the [skippedRows] was skipped: its position, the variable and the term's fault; printable and short. */
    var firstSkipped: String? = null
        private set

    /** Rows read so far, skipped ones included. */
    private var rowNumber = 0L

    /** Whether a row is being decoded whose malformed terms are to be skipped rather than reported. */
    private var skipping = false

    /** Why the row being decoded is skipped, once a malformed term was met in it. */
    private var defect: String? = null

    // Decoding in 8K chunks into a private buffer: no per-character lock (PushbackReader and the
    // buffered reader it wrapped synchronize every read call).
    private val source = InputStreamReader(
        input,
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT),
    )
    private val buffer = CharArray(8192)
    private var position = 0
    private var limit = 0
    private var started = false

    /** Characters the value being decoded may still take; unlimited between values. */
    private var remaining = Long.MAX_VALUE

    /** Scratch space for one string or scalar. */
    private var text = StringBuilder()


    private fun fill(): Boolean {
        val count = try {
            source.read(buffer, 0, buffer.size)
        } catch (e: CharacterCodingException) {
            throw IllegalStateException("Malformed SPARQL JSON: the response is not valid UTF-8", e)
        }
        if (count <= 0) return false
        position = 0
        limit = count
        if (!started) {
            started = true
            if (buffer[0].code == BYTE_ORDER_MARK) position = 1
        }
        return true
    }

    /** The next character without consuming it, or -1 at the end of the input. */
    private fun peek(): Int {
        while (position == limit) if (!fill()) return -1
        return buffer[position].code
    }

    /** Consumes [count] buffered characters, charging them to the value being decoded. */
    private fun charge(count: Int) {
        remaining -= count
        check(remaining >= 0) { "SPARQL JSON result row or value exceeds $maxValueChars characters (maxResultRowChars)" }
    }

    /** Consumes the character [peek] returned. */
    private fun advance() {
        charge(1)
        position++
    }

    /** Skips white space; returns the next character (not consumed), or -1. */
    private fun skipWhitespace(): Int {
        while (true) {
            val c = peek()
            if (!isWhitespace(c)) return c
            advance()
        }
    }

    private fun malformed(next: Int, expected: String): Nothing =
        error(if (next < 0) "Truncated SPARQL JSON" else "Malformed SPARQL JSON: expected $expected")

    private fun expect(c: Char) {
        val next = skipWhitespace()
        if (next != c.code) malformed(next, "$c")
        advance()
    }

    /** Runs [decode] for one value, whose characters count against [maxValueChars]. */
    private inline fun <T> bounded(decode: () -> T): T {
        remaining = maxValueChars.toLong()
        try {
            return decode()
        } finally {
            remaining = Long.MAX_VALUE
        }
    }

    private fun checkDepth(depth: Int) =
        check(depth < MAX_DEPTH) { "SPARQL JSON result is nested more than $MAX_DEPTH levels deep" }

    // ------------------------------------------------------------------ skipping

    /** Checks and skips the value that starts at the next non-white-space character; stores nothing. */
    private fun skip(depth: Int) {
        when (val c = skipWhitespace()) {
            '{'.code -> {
                checkDepth(depth)
                advance()
                var next = skipWhitespace()
                if (next == '}'.code) {
                    advance()
                    return
                }
                while (true) {
                    if (next != '"'.code) malformed(next, "a field name")
                    string(null)
                    expect(':')
                    skip(depth + 1)
                    next = skipWhitespace()
                    if (next == '}'.code) {
                        advance()
                        return
                    }
                    if (next != ','.code) malformed(next, "',' or '}'")
                    advance()
                    next = skipWhitespace()
                }
            }
            '['.code -> {
                checkDepth(depth)
                advance()
                var next = skipWhitespace()
                if (next == ']'.code) {
                    advance()
                    return
                }
                while (true) {
                    skip(depth + 1)
                    next = skipWhitespace()
                    if (next == ']'.code) {
                        advance()
                        return
                    }
                    if (next != ','.code) malformed(next, "',' or ']'")
                    advance()
                }
            }
            '"'.code -> string(null)
            else -> if (c < 0) malformed(c, "a value") else scalar(null)
        }
    }

    /** Skips a top-level value, bounded like a row. */
    private fun skipValue() {
        skipWhitespace()
        bounded { skip(0) }
    }

    // ------------------------------------------------------------------ strings and scalars

    /**
     * Reads the string whose opening quote is the next character, into [out] when given (which is
     * cleared first); with `null` the string is only checked.
     */
    private fun string(out: StringBuilder?) {
        advance()
        out?.setLength(0)
        // Whether the character before was the escape of a high surrogate, which only the escape of a low one may follow.
        var high = false
        while (true) {
            if (position == limit && !fill()) malformed(-1, "'\"'")
            // Take the run of plain characters in the buffer in one go.
            var end = position
            while (end < limit) {
                val plain = buffer[end]
                if (plain == '"' || plain == '\\' || plain < ' ') break
                end++
            }
            if (end > position) {
                if (high) {
                    unpairedSurrogate()
                    high = false
                }
                charge(end - position)
                out?.append(buffer, position, end - position)
                position = end
                if (end == limit) continue
            }
            val c = buffer[position]
            if (c < ' ') error("Malformed SPARQL JSON: a control character (U+%04X) inside a string must be escaped".format(c.code))
            advance()
            if (c == '"') {
                if (high) unpairedSurrogate()
                return
            }
            val escape = peek()
            if (escape < 0) malformed(escape, "an escape")
            advance()
            val decoded = when (escape.toChar()) {
                '"', '\\', '/' -> escape.toChar()
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    var code = 0
                    repeat(4) {
                        val digit = peek()
                        val value = when (digit) {
                            in '0'.code..'9'.code -> digit - '0'.code
                            in 'a'.code..'f'.code -> digit - 'a'.code + 10
                            in 'A'.code..'F'.code -> digit - 'A'.code + 10
                            else -> malformed(digit, "four hex digits in a unicode escape")
                        }
                        advance()
                        code = code * 16 + value
                    }
                    code.toChar()
                }
                else -> malformed(escape, "a JSON escape after '\\'")
            }
            val low = escape == 'u'.code && Character.isLowSurrogate(decoded)
            if (high != low) unpairedSurrogate()
            high = escape == 'u'.code && Character.isHighSurrogate(decoded)
            out?.append(decoded)
        }
    }

    /**
     * An unpaired surrogate escape: a fault of the term it is in when rows with such terms are
     * skipped, and an error anywhere else.
     */
    private fun unpairedSurrogate() {
        if (!skipping) error("Malformed SPARQL JSON: a string holds an unpaired surrogate escape")
        if (defect == null) defect = "a string holds an unpaired surrogate escape"
    }

    /** Decodes the string whose opening quote is the next character. */
    private fun string(): String {
        string(text)
        return takeText()
    }

    /** Reads the field name whose opening quote is the next character into [text], without creating a string. */
    private fun fieldName() = string(text)

    private fun nameIs(name: String): Boolean = text.length == name.length && text.contentEquals(name)

    /**
     * Reads `true`, `false`, `null` or a number, into [out] when given (cleared first), and returns
     * which of them it is ([TRUE], [FALSE], [NULL], [NUMBER]). The number grammar is that of JSON.
     */
    private fun scalar(out: StringBuilder?): Int {
        out?.setLength(0)
        val first = peek()
        val kind = when (first) {
            't'.code -> word("true", out, TRUE)
            'f'.code -> word("false", out, FALSE)
            'n'.code -> word("null", out, NULL)
            else -> number(out)
        }
        val next = peek()
        if (next >= 0 && !isWhitespace(next) && next != ','.code && next != ']'.code && next != '}'.code) malformed(next, "a value")
        return kind
    }

    private fun word(word: String, out: StringBuilder?, kind: Int): Int {
        for (expected in word) {
            val c = peek()
            if (c != expected.code) malformed(c, "a value")
            advance()
        }
        out?.append(word)
        return kind
    }

    private fun number(out: StringBuilder?): Int {
        fun take(c: Int) {
            out?.append(c.toChar())
            advance()
        }
        fun digits(): Int {
            var count = 0
            while (true) {
                val c = peek()
                if (c < '0'.code || c > '9'.code) return count
                take(c)
                count++
            }
        }
        var c = peek()
        if (c == '-'.code) {
            take(c)
            c = peek()
        }
        when {
            c == '0'.code -> take(c)
            c >= '1'.code && c <= '9'.code -> digits()
            else -> malformed(c, "a value")
        }
        c = peek()
        if (c == '.'.code) {
            take(c)
            if (digits() == 0) malformed(peek(), "a digit")
            c = peek()
        }
        if (c == 'e'.code || c == 'E'.code) {
            take(c)
            c = peek()
            if (c == '+'.code || c == '-'.code) take(c)
            if (digits() == 0) malformed(peek(), "a digit")
        }
        return NUMBER
    }

    private fun takeText(): String {
        val result = text.toString()
        created?.invoke()
        // One unusually large value must not keep a large buffer alive for the rest of the stream.
        if (text.capacity() > RETAINED_TEXT_CHARS) text = StringBuilder()
        return result
    }

    // ------------------------------------------------------------------ document structure

    /**
     * Runs [member] for each member of the object that starts next, with its name in [text] and the
     * reader in front of its value. The object is part of one bounded value.
     */
    private inline fun members(what: String, member: () -> Unit) {
        val open = skipWhitespace()
        if (open != '{'.code) malformed(open, what)
        advance()
        var c = skipWhitespace()
        if (c == '}'.code) {
            advance()
            return
        }
        while (true) {
            if (c != '"'.code) malformed(c, "a field name")
            fieldName()
            expect(':')
            member()
            c = skipWhitespace()
            if (c == '}'.code) {
                advance()
                return
            }
            if (c != ','.code) malformed(c, "',' or '}'")
            advance()
            c = skipWhitespace()
        }
    }

    private fun once(seen: Boolean, name: String) = check(!seen) { "Malformed SPARQL JSON: more than one '$name' member" }

    private fun end() = check(skipWhitespace() == -1) { "Trailing content in SPARQL JSON" }

    /**
     * Reads `head`, which counts as one value: the variables of its `vars`, or `null` when it has
     * none. Its other members are checked and skipped.
     */
    private fun head(): Set<String>? {
        skipWhitespace()
        return bounded {
            var vars: MutableSet<String>? = null
            members("the 'head' object") {
                if (nameIs("vars")) {
                    once(vars != null, "vars")
                    vars = vars()
                } else {
                    skip(1)
                }
            }
            vars
        }
    }

    private fun vars(): MutableSet<String> {
        val vars = LinkedHashSet<String>()
        expect('[')
        var c = skipWhitespace()
        if (c == ']'.code) {
            advance()
            return vars
        }
        while (true) {
            if (c != '"'.code) malformed(c, "a variable name (a string) in 'vars'")
            vars.add(string())
            c = skipWhitespace()
            if (c == ']'.code) {
                advance()
                return vars
            }
            if (c != ','.code) malformed(c, "',' or ']'")
            advance()
            c = skipWhitespace()
        }
    }

    /** The rows of a SELECT result, decoded as they are read. */
    fun rows(): Sequence<BindingSet> = sequence {
        var headRead = false
        var resultsRead = false
        var declared: Set<String>? = null
        var open = skipWhitespace()
        if (open != '{'.code) malformed(open, "a SPARQL JSON result (an object)")
        advance()
        open = skipWhitespace()
        if (open != '}'.code) {
            while (true) {
                if (open != '"'.code) malformed(open, "a field name")
                bounded { fieldName() }
                val isHead = nameIs("head")
                val isResults = nameIs("results")
                expect(':')
                when {
                    isHead -> {
                        once(headRead, "head")
                        headRead = true
                        val vars = head()
                        // A head that follows the rows cannot be held against them: they were delivered.
                        if (!resultsRead) declared = vars
                    }
                    isResults -> {
                        once(resultsRead, "results")
                        resultsRead = true
                        results(declared)
                    }
                    else -> skipValue()
                }
                open = skipWhitespace()
                if (open == '}'.code) break
                if (open != ','.code) malformed(open, "',' or '}'")
                advance()
                open = skipWhitespace()
            }
        }
        advance()
        check(resultsRead) { "SPARQL JSON missing results" }
        end()
    }.constrainOnce()

    /** Reads the `results` object and yields the rows of its `bindings`. */
    private suspend fun SequenceScope<BindingSet>.results(declared: Set<String>?) {
        var bindingsRead = false
        var open = skipWhitespace()
        if (open != '{'.code) malformed(open, "the 'results' object")
        advance()
        open = skipWhitespace()
        if (open != '}'.code) {
            while (true) {
                if (open != '"'.code) malformed(open, "a field name")
                bounded { fieldName() }
                val isBindings = nameIs("bindings")
                expect(':')
                if (isBindings) {
                    once(bindingsRead, "bindings")
                    bindingsRead = true
                    bindings(declared)
                } else {
                    skipValue()
                }
                open = skipWhitespace()
                if (open == '}'.code) break
                if (open != ','.code) malformed(open, "',' or '}'")
                advance()
                open = skipWhitespace()
            }
        }
        advance()
        check(bindingsRead) { "SPARQL JSON missing bindings" }
    }

    private suspend fun SequenceScope<BindingSet>.bindings(declared: Set<String>?) {
        expect('[')
        var c = skipWhitespace()
        if (c == ']'.code) {
            advance()
            return
        }
        while (true) {
            if (c != '{'.code) malformed(c, "a result row (an object)")
            val row = bounded { row(declared) }
            if (row != null) yield(row)
            c = skipWhitespace()
            if (c == ']'.code) {
                advance()
                return
            }
            if (c != ','.code) malformed(c, "',' or ']' in the bindings array")
            advance()
            c = skipWhitespace()
        }
    }

    /**
     * An ASK result: the `boolean` member of the JSON document, or the bare `true`/`false` (in any
     * case) that some endpoints return as plain text.
     */
    fun ask(): Boolean {
        val first = skipWhitespace()
        if (first != '{'.code) return plainBoolean()
        advance()
        var result: Boolean? = null
        var c = skipWhitespace()
        if (c != '}'.code) {
            while (true) {
                if (c != '"'.code) malformed(c, "a field name")
                val isBoolean = bounded {
                    fieldName()
                    nameIs("boolean")
                }
                expect(':')
                if (isBoolean) {
                    check(result == null) { "Malformed SPARQL JSON: more than one 'boolean' member" }
                    skipWhitespace()
                    result = bounded { booleanValue() }
                } else {
                    skipValue()
                }
                c = skipWhitespace()
                if (c == '}'.code) break
                if (c != ','.code) malformed(c, "',' or '}'")
                advance()
                c = skipWhitespace()
            }
        }
        advance()
        end()
        return result ?: error("SPARQL ASK response missing 'boolean' field")
    }

    /** `true` or `false`; also as a string, which some endpoints send. */
    private fun booleanValue(): Boolean {
        val c = peek()
        val kind = when (c) {
            '"'.code -> {
                string(text)
                if (nameIs("true")) TRUE else if (nameIs("false")) FALSE else NULL
            }
            '{'.code, '['.code -> NULL
            else -> if (c < 0) malformed(c, "a value") else scalar(null)
        }
        check(kind == TRUE || kind == FALSE) { "SPARQL ASK response 'boolean' is neither true nor false" }
        return kind == TRUE
    }

    private fun plainBoolean(): Boolean {
        val word = text
        word.setLength(0)
        while (true) {
            val c = peek()
            if (c < 0 || isWhitespace(c)) break
            check(word.length < "false".length) { NOT_AN_ASK_RESULT }
            word.append(c.toChar())
            advance()
        }
        check(skipWhitespace() == -1) { NOT_AN_ASK_RESULT }
        return when {
            word.contentEquals("true", ignoreCase = true) -> true
            word.contentEquals("false", ignoreCase = true) -> false
            else -> error(NOT_AN_ASK_RESULT)
        }
    }

    // ------------------------------------------------------------------ rows

    /**
     * Decodes the row whose opening brace is the next character; `null` for a row that is skipped
     * because of a malformed term, which is then counted.
     */
    private fun row(declared: Set<String>?): BindingSet? {
        rowNumber++
        skipping = malformedTerms == MalformedTermPolicy.SKIP_ROW
        defect = null
        try {
            val terms = terms(declared)
            val reason = defect ?: return MapBindingSet(terms)
            skippedRows++
            if (firstSkipped == null) firstSkipped = "row $rowNumber: $reason"
            return null
        } finally {
            skipping = false
        }
    }

    private fun terms(declared: Set<String>?): Map<String, RdfTerm> {
        advance()
        val terms = LinkedHashMap<String, RdfTerm>()
        created?.invoke()
        var c = skipWhitespace()
        if (c == '}'.code) {
            advance()
            return terms
        }
        while (true) {
            if (c != '"'.code) malformed(c, "a field name")
            val variable = string()
            check(variable !in terms) { "Malformed SPARQL JSON: more than one binding for variable '${excerpt(variable)}' in a row" }
            check(declared == null || variable in declared) {
                "Malformed SPARQL JSON: a row binds variable '${excerpt(variable)}', which 'vars' in 'head' does not declare"
            }
            expect(':')
            val open = skipWhitespace()
            if (open != '{'.code) malformed(open, "a binding (an object)")
            val term = binding(variable)
            if (term != null) {
                terms[variable] = term
                created?.invoke()
            } else {
                // The row is skipped; the name still stands for its binding, so that a repeat is noticed.
                terms[variable] = PLACEHOLDER
            }
            c = skipWhitespace()
            if (c == '}'.code) {
                advance()
                return terms
            }
            if (c != ','.code) malformed(c, "',' or '}'")
            advance()
            c = skipWhitespace()
        }
    }

    /**
     * Decodes the binding (an RDF term) of [variable] whose opening brace is the next character;
     * `null` when the term is malformed and its row is skipped.
     */
    private fun binding(variable: String): RdfTerm? {
        advance()
        var type: String? = null
        var value: String? = null
        var lang: String? = null
        var datatype: String? = null
        var structuredValue = false
        var directional = false
        var seen = 0
        var c = skipWhitespace()
        if (c == '}'.code) {
            advance()
        } else {
            while (true) {
                if (c != '"'.code) malformed(c, "a field name")
                fieldName()
                val member = when {
                    nameIs("type") -> TYPE
                    nameIs("value") -> VALUE
                    nameIs("xml:lang") -> LANG
                    nameIs("datatype") -> DATATYPE
                    nameIs("its:dir") || nameIs("direction") -> DIRECTION
                    else -> OTHER
                }
                if (member in TYPE..DATATYPE) {
                    once(seen and (1 shl member) != 0, MEMBER_NAMES[member])
                    seen = seen or (1 shl member)
                }
                expect(':')
                val start = skipWhitespace()
                when (member) {
                    TYPE -> type = memberText("type", start)
                    VALUE -> {
                        // The value of a triple term is an object; it is reported once the type is known.
                        structuredValue = start == '{'.code || start == '['.code
                        if (structuredValue) skip(BINDING_MEMBER_DEPTH) else value = memberText("value", start)
                    }
                    LANG -> lang = memberText("xml:lang", start)
                    DATATYPE -> datatype = memberText("datatype", start)
                    DIRECTION -> {
                        directional = true
                        skip(BINDING_MEMBER_DEPTH)
                    }
                    else -> skip(BINDING_MEMBER_DEPTH)
                }
                c = skipWhitespace()
                if (c == '}'.code) {
                    advance()
                    break
                }
                if (c != ','.code) malformed(c, "',' or '}'")
                advance()
                c = skipWhitespace()
            }
        }
        check(type != TRIPLE_TYPE) {
            "RDF 1.2 triple terms in SPARQL results (\"type\":\"triple\") are not supported by this adapter, " +
                "which decodes SPARQL 1.1 results only"
        }
        if (structuredValue) notAString("value")
        val kind = type ?: error("SPARQL binding missing required field")
        val text = value ?: error("SPARQL binding missing required field")
        val tag = lang?.takeIf { it.isNotEmpty() }
        val datatypeIri = datatype
        val literal = kind == "literal" || kind == "typed-literal"
        check(literal || kind == "uri" || kind == "bnode") { "Unsupported SPARQL result binding type: '${excerpt(kind)}'" }
        if (literal) {
            check(!directional) {
                "Literals with a base direction in SPARQL results (RDF 1.2 'its:dir' / 'direction') are not supported " +
                    "by this adapter, which decodes SPARQL 1.1 results only"
            }
            check(tag == null || datatypeIri == null || datatypeIri == LANG_STRING) {
                "Malformed SPARQL JSON: a literal has the 'xml:lang' '${excerpt(tag.orEmpty())}' and the 'datatype' " +
                    "'${excerpt(datatypeIri.orEmpty())}'; a language tag only goes with rdf:langString"
            }
        }
        // A fault met while this row's strings were read (an unpaired surrogate) is one of this row, whatever the term.
        if (defect != null) return null
        return try {
            when {
                kind == "uri" -> Iri(text)
                // Kept verbatim (e.g. Virtuoso `nodeID://b1`); see SparqlGraph for what can be done with it.
                kind == "bnode" -> BlankNode(text)
                tag != null -> LangString(text, tag)
                datatypeIri != null -> Literal(text, Iri(datatypeIri))
                else -> Literal(text, XSD.string)
            }
        } catch (e: IllegalArgumentException) {
            val fault = excerpt(e.message.orEmpty())
            if (!skipping) throw IllegalStateException("SPARQL result holds an invalid term: $fault", e)
            defect = "variable '${excerpt(variable, VARIABLE_EXCERPT_CHARS)}': $fault"
            null
        }
    }

    private fun notAString(member: String): Nothing = error("Malformed SPARQL JSON: the '$member' of a binding must be a string")

    /** The text of the binding member [member], which must be a string; [start] is its first character. */
    private fun memberText(member: String, start: Int): String {
        if (start != '"'.code) {
            if (start < 0) malformed(start, "a value")
            notAString(member)
        }
        return string()
    }

    private companion object {
        /** Far deeper than any SPARQL result (RDF 1.2 triple terms nest a few levels); bounds the decoder's recursion. */
        const val MAX_DEPTH = 128

        /** A row is at depth 0, its bindings at 1, their members at 2. */
        const val BINDING_MEMBER_DEPTH = 2
        const val RETAINED_TEXT_CHARS = 64 * 1024
        const val BYTE_ORDER_MARK = 0xFEFF
        const val TRIPLE_TYPE = "triple"
        const val LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"

        /** Stands for the binding of a variable in a row that is skipped. */
        val PLACEHOLDER: RdfTerm = BlankNode("skipped")
        const val NOT_AN_ASK_RESULT = "SPARQL ASK response is neither a SPARQL JSON result nor true/false"

        const val NUMBER = 0
        const val TRUE = 1
        const val FALSE = 2
        const val NULL = 3

        const val OTHER = 0
        const val TYPE = 1
        const val VALUE = 2
        const val LANG = 3
        const val DATATYPE = 4
        const val DIRECTION = 5

        /** The members that must not be repeated, by their number. */
        val MEMBER_NAMES = arrayOf("", "type", "value", "xml:lang", "datatype")

        const val EXCERPT_CHARS = 80
        const val VARIABLE_EXCERPT_CHARS = 40

        /** JSON white space (RFC 8259): space, tab, line feed, carriage return. */
        fun isWhitespace(c: Int): Boolean = c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code

        /** At most [EXCERPT_CHARS] characters of [text], with anything that is not printable ASCII replaced. */
        fun excerpt(text: String, chars: Int = EXCERPT_CHARS): String {
            val shown = text.take(chars).map { if (it in ' '..'~') it else '?' }.joinToString("")
            return if (text.length > chars) "$shown..." else shown
        }
    }
}
