package com.geoknoesis.kastor.rdf.sparql

import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Incrementally frames JSON values. Only one binding row is materialized at a time. */
internal class JsonBindingRows(input: InputStream) {
    private val reader = java.io.PushbackReader(input.reader(Charsets.UTF_8).buffered(), 1)
    private fun next(): Int { var c: Int; do { c = reader.read() } while (c >= 0 && c.toChar().isWhitespace()); return c }
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
                val c = reader.read()
                if (c < 0) break
                if (c.toChar() in ",]}" || c.toChar().isWhitespace()) { reader.unread(c); break }
                out.append(c.toChar())
            }
            return out.toString()
        }
        while (true) {
            val c = reader.read()
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
            reader.unread(c)
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
