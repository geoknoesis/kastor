package com.geoknoesis.kastor.rdf.sparql

import java.io.InputStream
import java.io.InputStreamReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Incrementally frames JSON values. Only one binding row is materialized at a time. */
internal class JsonBindingRows(input: InputStream) {
    // Decoding in 8K chunks into a private buffer: no per-character lock (PushbackReader and the
    // buffered reader it wrapped synchronize every read call).
    private val source = InputStreamReader(input, Charsets.UTF_8)
    private val buffer = CharArray(8192)
    private var position = 0
    private var limit = 0
    private var pushedBack = -1

    private fun read(): Int {
        if (pushedBack >= 0) {
            val c = pushedBack
            pushedBack = -1
            return c
        }
        if (position == limit) {
            val count = source.read(buffer, 0, buffer.size)
            if (count <= 0) return -1
            position = 0
            limit = count
        }
        return buffer[position++].code
    }

    private fun unread(c: Int) {
        check(pushedBack < 0) { "Only one character of push-back is supported" }
        pushedBack = c
    }

    private fun next(): Int { var c: Int; do { c = read() } while (c >= 0 && c.toChar().isWhitespace()); return c }
    private fun expect(c: Char) { check(next() == c.code) { "Malformed SPARQL JSON: expected $c" } }
    private fun value(): String {
        val first = next()
        check(first >= 0) { "Truncated SPARQL JSON" }
        val out = StringBuilder().append(first.toChar())
        var quoted = first == '"'.code
        var escaped = false
        var depth = if (first == '{'.code || first == '['.code) 1 else 0
        if (!quoted && depth == 0) {
            while (true) {
                val c = read()
                if (c < 0) break
                if (c.toChar() in ",]}" || c.toChar().isWhitespace()) { unread(c); break }
                out.append(c.toChar())
            }
            return out.toString()
        }
        while (true) {
            val c = read()
            check(c >= 0) { "Truncated SPARQL JSON" }
            val ch = c.toChar(); out.append(ch)
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') { quoted = false; if (depth == 0) break }
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) break }
            }
        }
        return out.toString()
    }
    private fun field(name: String) {
        expect('{')
        while (true) {
            val key = Json.parseToJsonElement(value()).jsonPrimitive.content
            expect(':')
            if (key == name) return
            Json.parseToJsonElement(value())
            check(next() == ','.code) { "SPARQL JSON missing $name" }
        }
    }
    fun rows(): Sequence<JsonObject> = sequence {
        field("results"); field("bindings"); expect('[')
        var c = next()
        if (c != ']'.code) {
            unread(c)
            while (true) {
                yield(Json.parseToJsonElement(value()).jsonObject)
                c = next()
                if (c == ']'.code) break
                check(c == ','.code) { "Malformed bindings array" }
            }
        }
        finishObject()
        finishObject()
        check(next() == -1) { "Trailing content in SPARQL JSON" }
    }.constrainOnce()
    private fun finishObject() {
        var c = next()
        while (c == ','.code) {
            check(Json.parseToJsonElement(value()).jsonPrimitive.isString) { "Expected JSON field name" }
            expect(':'); Json.parseToJsonElement(value()); c = next()
        }
        check(c == '}'.code) { "Truncated SPARQL JSON object" }
    }
}
