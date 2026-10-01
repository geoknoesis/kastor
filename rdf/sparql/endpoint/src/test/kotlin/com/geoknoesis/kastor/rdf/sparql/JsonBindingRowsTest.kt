package com.geoknoesis.kastor.rdf.sparql

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The streaming decoder for SPARQL JSON result rows: one pass per row, bounded row size. */
class JsonBindingRowsTest {

    private fun rows(json: String, maxChars: Int = SparqlEndpointConfig.DEFAULT_MAX_RESULT_ROW_CHARS): List<JsonObject> =
        JsonBindingRows(json.byteInputStream(Charsets.UTF_8), maxChars).rows().toList()

    private fun document(vararg rows: String) = "{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[${rows.joinToString(",")}]}}"

    private fun literalRow(value: String) = "{\"x\":{\"type\":\"literal\",\"value\":\"$value\"}}"

    /** What a general-purpose JSON parser makes of the same document. */
    private fun reference(json: String): List<JsonObject> =
        Json.parseToJsonElement(json).jsonObject.getValue("results").jsonObject.getValue("bindings").jsonArray.map { it.jsonObject }

    private fun value(row: JsonObject): String = row.getValue("x").jsonObject.getValue("value").jsonPrimitive.content

    @Test
    fun `a row of exactly the limit is accepted and one character more is not`() {
        val row = literalRow("y".repeat(100))
        assertEquals(1, rows(document(row), row.length).size)
        val e = assertThrows(IllegalStateException::class.java) { rows(document(row), row.length - 1) }
        assertTrue(e.message!!.contains("maxResultRowChars"), e.message)
        assertTrue(e.message!!.contains("${row.length - 1} characters"), e.message)

        // White space around a row is not part of it; white space inside is.
        assertEquals(1, rows(document(" \n\t$row\r\n "), row.length).size)
        val spaced = row.replace(":", " : ")
        assertEquals(1, rows(document(spaced), spaced.length).size)
        assertThrows(IllegalStateException::class.java) { rows(document(spaced), spaced.length - 1) }

        // Escapes count as written: the limit is on the JSON text, not on the decoded value.
        val escaped = literalRow("\\u0041".repeat(10))
        assertEquals("A".repeat(10), value(rows(document(escaped), escaped.length).single()))
        assertThrows(IllegalStateException::class.java) { rows(document(escaped), escaped.length - 1) }

        // Rows before an oversized one are delivered; the limit also covers skipped values such as `head`.
        val small = literalRow("ok")
        val iterator = JsonBindingRows(document(small, row, small).byteInputStream(), row.length - 1).rows().iterator()
        assertEquals("ok", value(iterator.next()))
        assertThrows(IllegalStateException::class.java) { iterator.hasNext() }
        val head = "{\"head\":{\"vars\":[\"${"v".repeat(200)}\"]},\"results\":{\"bindings\":[$small]}}"
        assertThrows(IllegalStateException::class.java) { rows(head, 100) }
        assertEquals(1, rows(head, 300).size)
    }

    @Test
    fun `the default limit is 4 Mi characters and is exact`() {
        val limit = SparqlEndpointConfig.DEFAULT_MAX_RESULT_ROW_CHARS
        assertEquals(4 * 1024 * 1024, limit)
        assertEquals(limit, SparqlEndpointConfig("http://example.org/sparql").maxResultRowChars)
        val overhead = literalRow("").length
        val largest = literalRow("y".repeat(limit - overhead))
        assertEquals(limit, largest.length)
        assertEquals(limit - overhead, value(rows(document(largest)).single()).length)
        assertThrows(IllegalStateException::class.java) { rows(document(literalRow("y".repeat(limit - overhead + 1)))) }
    }

    @Test
    fun `surrogate pairs are decoded and counted as two characters`() {
        val emoji = "\uD83D\uDE00"
        val raw = literalRow("a${emoji}b")
        assertEquals("a${emoji}b", value(rows(document(raw), raw.length).single()))
        assertThrows(IllegalStateException::class.java) { rows(document(raw), raw.length - 1) }

        val escaped = literalRow("a\\uD83D\\uDE00b\\ud83d\\ude00")
        assertEquals("a${emoji}b$emoji", value(rows(document(escaped)).single()))

        // A pair is never split by the decoder's buffer: try every alignment around the 8K boundary.
        val valueStart = document(literalRow("")).indexOf("\"\"}}")
        for (offset in 8_192 - 20..8_192 + 20) {
            val text = "y".repeat(offset - valueStart) + emoji + "z"
            val document = document(literalRow(text), literalRow(emoji))
            assertEquals(listOf(text, emoji), rows(document).map(::value), "pair at character $offset")
        }
    }

    @Test
    fun `nested values and every JSON form are decoded like a general JSON parser does`() {
        val triple = "{\"t\":{\"type\":\"triple\",\"value\":{\"subject\":{\"type\":\"uri\",\"value\":\"urn:s\"}," +
            "\"predicate\":{\"type\":\"uri\",\"value\":\"urn:p\"}," +
            "\"object\":{\"type\":\"triple\",\"value\":{\"subject\":{\"type\":\"bnode\",\"value\":\"b0\"}," +
            "\"predicate\":{\"type\":\"uri\",\"value\":\"urn:q\"},\"object\":{\"type\":\"literal\",\"value\":\"o\",\"xml:lang\":\"en\"}}}}}," +
            "\"n\":{\"type\":\"literal\",\"value\":\"1\",\"datatype\":\"http://www.w3.org/2001/XMLSchema#integer\"}}"
        val forms = "{\"x\":{\"type\":\"literal\",\"value\":\"q\\\" b\\\\ s\\/ \\b\\f\\n\\r\\t \\u00e9\\u20AC \u00e9\u20ac\"," +
            "\"extra\":[0,-0,1,-2.5,3e2,4E+2,5.25e-1,true,false,null,[],{},[[1],[{\"a\":[]}]],\"\",\"s\"],\"\":{\"k\\u0041\":null}}}"
        val spaced = " {\t\"x\" :\n{ \"type\"\r\n: \"uri\" , \"value\" : \"urn:x\" }\n} "
        val documents = listOf(
            document(triple, forms, spaced),
            document(),
            " { \"head\" : { \"vars\" : [ ] , \"link\" : [ \"urn:l\" ] } , \"results\" : { \"bindings\" : [ ] } } \n",
            "{\"a\":1,\"b\":[{\"results\":0}],\"c\":\"results\",\"results\":{\"distinct\":false,\"bindings\":[$triple,$forms],\"ordered\":true},\"z\":null}",
            "{\"results\":{\"bindings\":[$spaced]},\"head\":{\"vars\":[\"x\"]}}",
        )
        for (document in documents) {
            assertEquals(reference(document), rows(document), document)
        }
    }

    @Test
    fun `malformed or truncated results fail instead of being guessed at`() {
        val row = literalRow("v")
        val malformed = listOf(
            "",
            "[]",
            "{}",
            "{\"results\":{}}",
            "{\"results\":[]}",
            "{\"results\":{\"bindings\":{}}}",
            document("1"),
            document("\"row\""),
            document("[$row]"),
            document("null"),
            document("$row,"),
            document(",$row"),
            document("$row $row"),
            document("{\"x\":{\"type\":\"uri\",\"value\":\"urn:x\"},}"),
            document("{\"x\":{\"type\":\"uri\" \"value\":\"urn:x\"}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\"}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":}}"),
            document("{x:{\"type\":\"uri\",\"value\":\"urn:x\"}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":'urn:x'}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":\"bad \\q escape\"}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":\"bad \\u12G4 escape\"}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":\"short \\u12\"}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":[1,]}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":[,1]}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":[1 2]}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":[1}}}"),
        ) + listOf("01", "1.", "-", "+1", ".5", "1e", "1e+", "0x1", "NaN", "Infinity", "tru", "nul", "True", "truefalse", "1a").map {
            document("{\"x\":{\"type\":\"literal\",\"value\":$it}}")
        } + listOf(
            document(row) + "x",
            document(row) + "{}",
            document(row).dropLast(1),
            document(row).dropLast(2),
            document(row).dropLast(3),
            document(row).dropLast(4),
            document(row).substringBefore("\"v\"") + "\"v",
            "{\"results\":{\"bindings\":[$row]},\"head\":",
            "{\"results\":{\"bindings\":[$row]},\"head\"}",
            "{\"results\":{\"bindings\":[$row]} \"head\":{}}",
            "{\"head\":{\"vars\":[\"x\"]} \"results\":{\"bindings\":[$row]}}",
        )
        for (document in malformed) {
            assertThrows(IllegalStateException::class.java, { rows(document) }, document)
        }
        // Rows are delivered as they are read: an error only surfaces when its position is reached.
        val iterator = JsonBindingRows(document(row, "{\"x\":}").byteInputStream()).rows().iterator()
        assertEquals("v", value(iterator.next()))
        assertThrows(IllegalStateException::class.java) { iterator.hasNext() }
    }

    @Test
    fun `deeply nested values fail with an error instead of exhausting the stack`() {
        val deep = "[".repeat(200_000) + "]".repeat(200_000)
        val e = assertThrows(IllegalStateException::class.java) { rows(document("{\"x\":{\"type\":\"literal\",\"value\":$deep}}")) }
        assertTrue(e.message!!.contains("nested"), e.message)
        assertThrows(IllegalStateException::class.java) { rows("{\"head\":$deep,\"results\":{\"bindings\":[]}}") }
        // Ordinary nesting (RDF 1.2 triple terms inside triple terms) is far below the limit.
        val nested = "[".repeat(50) + "]".repeat(50)
        assertEquals(1, rows(document("{\"x\":{\"type\":\"literal\",\"value\":$nested}}")).size)
    }
}
