package com.geoknoesis.kastor.rdf.sparql

import java.io.InputStream
import java.io.InputStreamReader
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * Streaming decoder for SPARQL JSON results: binding rows are decoded one at a time, straight from
 * the stream and in a single pass (a row is never first copied as text and then parsed).
 *
 * No single value (a row, or a skipped value such as `head`) may be longer than [maxValueChars]
 * characters of JSON text, and none may be nested deeper than [MAX_DEPTH]; both fail with
 * [IllegalStateException], as does any text that is not JSON.
 */
internal class JsonBindingRows(input: InputStream, private val maxValueChars: Int = SparqlEndpointConfig.DEFAULT_MAX_RESULT_ROW_CHARS) {
    // Decoding in 8K chunks into a private buffer: no per-character lock (PushbackReader and the
    // buffered reader it wrapped synchronize every read call).
    private val source = InputStreamReader(input, Charsets.UTF_8)
    private val buffer = CharArray(8192)
    private var position = 0
    private var limit = 0

    /** Characters the value being decoded may still take; unlimited between values. */
    private var remaining = Long.MAX_VALUE

    /** Scratch space for one string or scalar. */
    private var text = StringBuilder()

    private fun fill(): Boolean {
        val count = source.read(buffer, 0, buffer.size)
        if (count <= 0) return false
        position = 0
        limit = count
        return true
    }

    /** The next character without consuming it, or -1 at the end of the input. */
    private fun peek(): Int = if (position == limit && !fill()) -1 else buffer[position].code

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
            if (c < 0 || !c.toChar().isWhitespace()) return c
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

    /** Decodes the value that starts at the next non-white-space character. */
    private fun value(): JsonElement {
        skipWhitespace()
        return bounded { element(0) }
    }

    /** Decodes the field name that starts at the next character (a quote). */
    private fun key(): String = bounded { string() }

    private fun element(depth: Int): JsonElement = when (val c = skipWhitespace()) {
        '{'.code -> obj(depth)
        '['.code -> array(depth)
        '"'.code -> JsonPrimitive(string())
        else -> if (c < 0) malformed(c, "a value") else scalar()
    }

    private fun obj(depth: Int): JsonObject {
        check(depth < MAX_DEPTH) { "SPARQL JSON result is nested more than $MAX_DEPTH levels deep" }
        advance()
        val fields = LinkedHashMap<String, JsonElement>()
        var c = skipWhitespace()
        if (c == '}'.code) {
            advance()
            return JsonObject(fields)
        }
        while (true) {
            if (c != '"'.code) malformed(c, "a field name")
            val name = string()
            expect(':')
            fields[name] = element(depth + 1)
            c = skipWhitespace()
            if (c == '}'.code) {
                advance()
                return JsonObject(fields)
            }
            if (c != ','.code) malformed(c, "',' or '}'")
            advance()
            c = skipWhitespace()
        }
    }

    private fun array(depth: Int): JsonArray {
        check(depth < MAX_DEPTH) { "SPARQL JSON result is nested more than $MAX_DEPTH levels deep" }
        advance()
        val items = ArrayList<JsonElement>()
        var c = skipWhitespace()
        if (c == ']'.code) {
            advance()
            return JsonArray(items)
        }
        while (true) {
            items.add(element(depth + 1))
            c = skipWhitespace()
            if (c == ']'.code) {
                advance()
                return JsonArray(items)
            }
            if (c != ','.code) malformed(c, "',' or ']'")
            advance()
        }
    }

    /** Decodes the string whose opening quote is the next character. */
    private fun string(): String {
        advance()
        val out = text
        out.setLength(0)
        while (true) {
            if (position == limit && !fill()) malformed(-1, "'\"'")
            // Copy the run of plain characters in the buffer in one go.
            var end = position
            while (end < limit && buffer[end] != '"' && buffer[end] != '\\') end++
            if (end > position) {
                charge(end - position)
                out.append(buffer, position, end - position)
                position = end
                if (end == limit) continue
            }
            val c = buffer[position]
            advance()
            if (c == '"') break
            val escape = peek()
            if (escape < 0) malformed(escape, "an escape")
            advance()
            when (escape.toChar()) {
                '"', '\\', '/' -> out.append(escape.toChar())
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
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
                    out.append(code.toChar())
                }
                else -> malformed(escape, "a JSON escape after '\\'")
            }
        }
        return takeText()
    }

    /** Decodes `true`, `false`, `null` or a number. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun scalar(): JsonElement {
        val out = text
        out.setLength(0)
        while (true) {
            val c = peek()
            if (c < 0 || c.toChar() in ",]}" || c.toChar().isWhitespace()) break
            out.append(c.toChar())
            advance()
        }
        val scalar = takeText()
        return when {
            scalar == "null" -> JsonNull
            // Keeps the text as written (a number is not converted and back).
            scalar == "true" || scalar == "false" || NUMBER.matches(scalar) -> JsonUnquotedLiteral(scalar)
            else -> malformed(if (scalar.isEmpty()) peek() else 0, "a value")
        }
    }

    private fun takeText(): String {
        val result = text.toString()
        // One unusually large value must not keep a large buffer alive for the rest of the stream.
        if (text.capacity() > RETAINED_TEXT_CHARS) text = StringBuilder()
        return result
    }

    /**
     * Enters the object that starts next and skips its members up to [name], leaving the reader in
     * front of that member's value.
     */
    private fun member(name: String) {
        expect('{')
        while (true) {
            val c = skipWhitespace()
            check(c == '"'.code) { if (c < 0) "Truncated SPARQL JSON" else "SPARQL JSON missing $name" }
            val key = key()
            expect(':')
            if (key == name) return
            value()
            val next = skipWhitespace()
            check(next == ','.code) { if (next < 0) "Truncated SPARQL JSON" else "SPARQL JSON missing $name" }
            advance()
        }
    }

    fun rows(): Sequence<JsonObject> = sequence {
        member("results")
        member("bindings")
        expect('[')
        var c = skipWhitespace()
        if (c == ']'.code) {
            advance()
        } else {
            while (true) {
                if (c != '{'.code) malformed(c, "a result row (an object)")
                yield(value() as JsonObject)
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
        finishObject()
        finishObject()
        check(skipWhitespace() == -1) { "Trailing content in SPARQL JSON" }
    }.constrainOnce()

    /** Skips the remaining members of the current object and its closing brace. */
    private fun finishObject() {
        var c = skipWhitespace()
        while (c == ','.code) {
            advance()
            val next = skipWhitespace()
            if (next != '"'.code) malformed(next, "a field name")
            key()
            expect(':')
            value()
            c = skipWhitespace()
        }
        if (c != '}'.code) malformed(c, "',' or '}'")
        advance()
    }

    private companion object {
        /** Far deeper than any SPARQL result (RDF 1.2 triple terms nest a few levels); bounds the decoder's recursion. */
        const val MAX_DEPTH = 128
        const val RETAINED_TEXT_CHARS = 64 * 1024
        val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}
