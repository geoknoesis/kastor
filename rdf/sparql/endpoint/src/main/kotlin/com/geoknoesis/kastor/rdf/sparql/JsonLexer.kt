package com.geoknoesis.kastor.rdf.sparql

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The JSON layer of [JsonBindingRows]: a strict, single-pass reader of RFC 8259 JSON from a UTF-8 stream, with the
 * per-value character budget ([maxValueChars]) and the nesting limit ([MAX_DEPTH]) that bound what one hostile value
 * can cost. It knows nothing of SPARQL: the subclass reads the result structure with these primitives.
 *
 * - The input must be UTF-8; malformed byte sequences are an error, not replaced. One leading byte order mark is
 *   ignored.
 * - Only space, tab, line feed and carriage return are white space.
 * - Inside a string every control character (U+0000 to U+001F) must be escaped, and a string must not hold an
 *   unpaired surrogate escape ([unpairedSurrogate]).
 * - Skipped values ([skip]) are checked against the grammar and stored nowhere.
 *
 * @param created called for every string created for decoded content; `null` in production.
 */
internal abstract class JsonLexer(
    input: InputStream,
    protected val maxValueChars: Int,
    protected val created: (() -> Unit)?,
) {
    // Decoding in 8K chunks into a private buffer: no per-character lock (PushbackReader and the
    // buffered reader it wrapped synchronize every read call).
    protected val source = InputStreamReader(
        input,
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT),
    )
    protected val buffer = CharArray(8192)
    protected var position = 0
    protected var limit = 0
    protected var started = false

    /** Characters the value being decoded may still take; unlimited between values. */
    protected var remaining = Long.MAX_VALUE

    /** Scratch space for one string or scalar. */
    protected var text = StringBuilder()


    protected fun fill(): Boolean {
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
    protected fun peek(): Int {
        while (position == limit) if (!fill()) return -1
        return buffer[position].code
    }

    /** Consumes [count] buffered characters, charging them to the value being decoded. */
    protected fun charge(count: Int) {
        remaining -= count
        check(remaining >= 0) { "SPARQL JSON result row or value exceeds $maxValueChars characters (maxResultRowChars)" }
    }

    /** Consumes the character [peek] returned. */
    protected fun advance() {
        charge(1)
        position++
    }

    /** Skips white space; returns the next character (not consumed), or -1. */
    protected fun skipWhitespace(): Int {
        while (true) {
            val c = peek()
            if (!isWhitespace(c)) return c
            advance()
        }
    }

    protected fun malformed(next: Int, expected: String): Nothing =
        error(if (next < 0) "Truncated SPARQL JSON" else "Malformed SPARQL JSON: expected $expected")

    protected fun expect(c: Char) {
        val next = skipWhitespace()
        if (next != c.code) malformed(next, "$c")
        advance()
    }

    /** Runs [decode] for one value, whose characters count against [maxValueChars]. */
    protected inline fun <T> bounded(decode: () -> T): T {
        remaining = maxValueChars.toLong()
        try {
            return decode()
        } finally {
            remaining = Long.MAX_VALUE
        }
    }

    protected fun checkDepth(depth: Int) =
        check(depth < MAX_DEPTH) { "SPARQL JSON result is nested more than $MAX_DEPTH levels deep" }

    // ------------------------------------------------------------------ skipping

    /** Checks and skips the value that starts at the next non-white-space character; stores nothing. */
    protected fun skip(depth: Int) {
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
    protected fun skipValue() {
        skipWhitespace()
        bounded { skip(0) }
    }

    // ------------------------------------------------------------------ strings and scalars

    /**
     * Reads the string whose opening quote is the next character, into [out] when given (which is
     * cleared first); with `null` the string is only checked.
     */
    protected fun string(out: StringBuilder?) {
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

    /** An unpaired surrogate escape: an error, unless a subclass makes it a fault of the value being decoded. */
    protected open fun unpairedSurrogate() {
        error("Malformed SPARQL JSON: a string holds an unpaired surrogate escape")
    }

    /** Decodes the string whose opening quote is the next character. */
    protected fun string(): String {
        string(text)
        return takeText()
    }

    /** Reads the field name whose opening quote is the next character into [text], without creating a string. */
    protected fun fieldName() = string(text)

    protected fun nameIs(name: String): Boolean = text.length == name.length && text.contentEquals(name)

    /**
     * Reads `true`, `false`, `null` or a number, into [out] when given (cleared first), and returns
     * which of them it is ([TRUE], [FALSE], [NULL], [NUMBER]). The number grammar is that of JSON.
     */
    protected fun scalar(out: StringBuilder?): Int {
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

    protected fun word(word: String, out: StringBuilder?, kind: Int): Int {
        for (expected in word) {
            val c = peek()
            if (c != expected.code) malformed(c, "a value")
            advance()
        }
        out?.append(word)
        return kind
    }

    protected fun number(out: StringBuilder?): Int {
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

    protected fun takeText(): String {
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
    protected inline fun members(what: String, member: () -> Unit) {
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

    protected fun once(seen: Boolean, name: String) = check(!seen) { "Malformed SPARQL JSON: more than one '$name' member" }

    protected fun end() = check(skipWhitespace() == -1) { "Trailing content in SPARQL JSON" }

    internal companion object {
        /** Far deeper than any SPARQL result (RDF 1.2 triple terms nest a few levels); bounds the decoder's recursion. */
        const val MAX_DEPTH = 128
        const val RETAINED_TEXT_CHARS = 64 * 1024
        const val BYTE_ORDER_MARK = 0xFEFF

        const val NUMBER = 0
        const val TRUE = 1
        const val FALSE = 2
        const val NULL = 3

        /** JSON white space (RFC 8259): space, tab, line feed, carriage return. */
        fun isWhitespace(c: Int): Boolean = c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code
    }
}
