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
 * Only what a binding needs is stored: the variable names of a row and, per binding, its `type`,
 * `value`, `xml:lang` and `datatype`. Everything else (`head`, unknown members, nested values) is
 * checked to be JSON and skipped without being stored.
 *
 * Strictness:
 * - The input must be UTF-8; malformed byte sequences are an error, not replaced. One leading byte
 *   order mark is ignored.
 * - Only space, tab, line feed and carriage return are white space.
 * - No single value (a row, a field name, or a skipped value such as `head`) may be longer than
 *   [maxValueChars] characters of JSON text, and none may be nested deeper than [MAX_DEPTH].
 * - The members `results` and `bindings` (and `boolean` for ASK) must not be repeated after the one
 *   that was read.
 * - RDF 1.2 result terms (`"type":"triple"`, and literals with a base direction, `its:dir` or
 *   `direction`) are not supported and are rejected by name.
 *
 * Every such failure is an [IllegalStateException] whose message never quotes more than a short,
 * printable excerpt of the input; I/O failures of the underlying stream propagate as they are.
 */
internal class JsonBindingRows(input: InputStream, private val maxValueChars: Int = SparqlEndpointConfig.DEFAULT_MAX_RESULT_ROW_CHARS) {
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

    /**
     * Objects created for decoded content so far: strings, terms and rows. Skipped content creates
     * none, which is what the tests assert through this counter.
     */
    internal var materialised = 0L
        private set

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
        while (true) {
            if (position == limit && !fill()) malformed(-1, "'\"'")
            // Take the run of plain characters in the buffer in one go.
            var end = position
            while (end < limit && buffer[end] != '"' && buffer[end] != '\\') end++
            if (end > position) {
                charge(end - position)
                out?.append(buffer, position, end - position)
                position = end
                if (end == limit) continue
            }
            val c = buffer[position]
            advance()
            if (c == '"') return
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
                            else -> malformed(digit, "four hex digits after \\u")
                        }
                        advance()
                        code = code * 16 + value
                    }
                    code.toChar()
                }
                else -> malformed(escape, "a JSON escape after '\\'")
            }
            out?.append(decoded)
        }
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
        materialised++
        // One unusually large value must not keep a large buffer alive for the rest of the stream.
        if (text.capacity() > RETAINED_TEXT_CHARS) text = StringBuilder()
        return result
    }

    // ------------------------------------------------------------------ document structure

    /**
     * Enters the object that starts next and skips its members up to [name], leaving the reader in
     * front of that member's value.
     */
    private fun member(name: String) {
        expect('{')
        while (true) {
            val c = skipWhitespace()
            check(c == '"'.code) { if (c < 0) "Truncated SPARQL JSON" else "SPARQL JSON missing $name" }
            val found = bounded {
                fieldName()
                nameIs(name)
            }
            expect(':')
            if (found) return
            skipValue()
            val next = skipWhitespace()
            check(next == ','.code) { if (next < 0) "Truncated SPARQL JSON" else "SPARQL JSON missing $name" }
            advance()
        }
    }

    /**
     * Skips the remaining members of the current object and its closing brace. The member [read],
     * whose value was consumed already, must not occur again: a general JSON parser would keep the
     * last one, which a streaming reader cannot.
     */
    private fun finishObject(read: String) {
        var c = skipWhitespace()
        while (c == ','.code) {
            advance()
            val next = skipWhitespace()
            if (next != '"'.code) malformed(next, "a field name")
            val repeated = bounded {
                fieldName()
                nameIs(read)
            }
            check(!repeated) { "Malformed SPARQL JSON: more than one '$read' member" }
            expect(':')
            skipValue()
            c = skipWhitespace()
        }
        if (c != '}'.code) malformed(c, "',' or '}'")
        advance()
    }

    private fun end() = check(skipWhitespace() == -1) { "Trailing content in SPARQL JSON" }

    /** The rows of a SELECT result, decoded as they are read. */
    fun rows(): Sequence<BindingSet> = sequence {
        member("results")
        member("bindings")
        expect('[')
        var c = skipWhitespace()
        if (c == ']'.code) {
            advance()
        } else {
            while (true) {
                if (c != '{'.code) malformed(c, "a result row (an object)")
                yield(bounded { row() })
                c = skipWhitespace()
                if (c == ']'.code) {
                    advance()
                    break
                }
                if (c != ','.code) malformed(c, "',' or ']' in the bindings array")
                advance()
                c = skipWhitespace()
            }
        }
        finishObject("bindings")
        finishObject("results")
        end()
    }.constrainOnce()

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

    /** Decodes the row whose opening brace is the next character. */
    private fun row(): BindingSet {
        advance()
        val terms = LinkedHashMap<String, RdfTerm>()
        materialised++
        var c = skipWhitespace()
        if (c == '}'.code) {
            advance()
            return MapBindingSet(terms)
        }
        while (true) {
            if (c != '"'.code) malformed(c, "a field name")
            val variable = string()
            expect(':')
            val open = skipWhitespace()
            if (open != '{'.code) malformed(open, "a binding (an object)")
            terms[variable] = binding()
            materialised++
            c = skipWhitespace()
            if (c == '}'.code) {
                advance()
                return MapBindingSet(terms)
            }
            if (c != ','.code) malformed(c, "',' or '}'")
            advance()
            c = skipWhitespace()
        }
    }

    /** Decodes the binding (an RDF term) whose opening brace is the next character. */
    private fun binding(): RdfTerm {
        advance()
        var type: String? = null
        var value: String? = null
        var lang: String? = null
        var datatype: String? = null
        var structuredValue = false
        var directional = false
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
                expect(':')
                val start = skipWhitespace()
                val structured = start == '{'.code || start == '['.code
                when (member) {
                    TYPE -> type = if (structured) notAString("type") else memberText()
                    VALUE -> {
                        // The value of a triple term is an object; it is reported once the type is known.
                        structuredValue = structured
                        value = if (structured) null.also { skip(BINDING_MEMBER_DEPTH) } else memberText()
                    }
                    LANG -> lang = if (structured) notAString("xml:lang") else memberText()
                    DATATYPE -> datatype = if (structured) notAString("datatype") else memberText()
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
        check(type != null && value != null) { "SPARQL binding missing required field" }
        return try {
            when (type) {
                "uri" -> Iri(value)
                // Kept verbatim (e.g. Virtuoso `nodeID://b1`); see SparqlGraph for what can be done with it.
                "bnode" -> BlankNode(value)
                "literal", "typed-literal" -> {
                    check(!directional) {
                        "Literals with a base direction in SPARQL results (RDF 1.2 'its:dir' / 'direction') are not supported " +
                            "by this adapter, which decodes SPARQL 1.1 results only"
                    }
                    when {
                        !lang.isNullOrEmpty() -> LangString(value, lang)
                        datatype != null -> Literal(value, Iri(datatype))
                        else -> Literal(value, XSD.string)
                    }
                }
                else -> error("Unsupported SPARQL result binding type: '${excerpt(type)}'")
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("SPARQL result holds an invalid term: ${excerpt(e.message.orEmpty())}", e)
        }
    }

    private fun notAString(member: String): Nothing = error("Malformed SPARQL JSON: the '$member' of a binding must be a string")

    /** The text of a binding member that is a string or another scalar; `null` for JSON `null`. */
    private fun memberText(): String? {
        if (peek() == '"'.code) return string()
        return if (scalar(text) == NULL) null else takeText()
    }

    private companion object {
        /** Far deeper than any SPARQL result (RDF 1.2 triple terms nest a few levels); bounds the decoder's recursion. */
        const val MAX_DEPTH = 128

        /** A row is at depth 0, its bindings at 1, their members at 2. */
        const val BINDING_MEMBER_DEPTH = 2
        const val RETAINED_TEXT_CHARS = 64 * 1024
        const val BYTE_ORDER_MARK = 0xFEFF
        const val TRIPLE_TYPE = "triple"
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

        const val EXCERPT_CHARS = 80

        /** JSON white space (RFC 8259): space, tab, line feed, carriage return. */
        fun isWhitespace(c: Int): Boolean = c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code

        /** At most [EXCERPT_CHARS] characters of [text], with anything that is not printable ASCII replaced. */
        fun excerpt(text: String): String {
            val shown = text.take(EXCERPT_CHARS).map { if (it in ' '..'~') it else '?' }.joinToString("")
            return if (text.length > EXCERPT_CHARS) "$shown..." else shown
        }
    }
}
