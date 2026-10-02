package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The streaming decoder for SPARQL JSON results: one pass per row, bounded row size, nothing stored that a binding does not need. */
class JsonBindingRowsTest {

    private fun rows(
        json: String,
        maxChars: Int = SparqlEndpointConfig.DEFAULT_MAX_RESULT_ROW_CHARS,
        policy: MalformedTermPolicy = MalformedTermPolicy.FAIL,
    ): List<Map<String, RdfTerm>> =
        JsonBindingRows(json.byteInputStream(Charsets.UTF_8), maxChars, policy).rows().map { it.asMap() }.toList()

    private fun ask(text: String): Boolean = JsonBindingRows(text.byteInputStream(Charsets.UTF_8)).ask()

    /** A result that declares `x` and `s`; the head is shorter than any row, so a row limit never falls on it. */
    private fun document(vararg rows: String) = """{"head":{"vars":["x","s"]},"results":{"bindings":[${rows.joinToString(",")}]}}"""

    private fun document(vars: List<String>, vararg rows: String) =
        """{"head":{"vars":[${vars.joinToString(",") { "\"$it\"" }}]},"results":{"bindings":[${rows.joinToString(",")}]}}"""

    /** A row that binds `x` to a term with the members [body]. */
    private fun binding(body: String) = "{\"x\":{$body}}"

    /** The JSON escape of the UTF-16 code unit [code]. */
    private fun escape(code: Int): String = "%cu%04X".format(92.toChar(), code)

    private fun assertRejected(document: String, vararg expected: String, policy: MalformedTermPolicy = MalformedTermPolicy.FAIL) {
        val e = assertThrows(IllegalStateException::class.java, { rows(document, policy = policy) }, document)
        for (part in expected) assertTrue(e.message!!.contains(part), "'$part' is not in the message '${e.message}' for $document")
    }

    private fun literalRow(value: String) = "{\"x\":{\"type\":\"literal\",\"value\":\"$value\"}}"

    private fun value(row: Map<String, RdfTerm>): String = (row.getValue("x") as Literal).lexical

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

        // Members that are skipped count like those that are kept.
        val padded = "{\"x\":{\"type\":\"literal\",\"value\":\"v\",\"note\":[${"1,".repeat(40)}1]}}"
        assertEquals("v", value(rows(document(padded), padded.length).single()))
        assertThrows(IllegalStateException::class.java) { rows(document(padded), padded.length - 1) }

        // Rows before an oversized one are delivered; the limit also covers skipped values such as `head`.
        val small = literalRow("ok")
        val iterator = JsonBindingRows(document(small, row, small).byteInputStream(), row.length - 1).rows().iterator()
        assertEquals("ok", value(iterator.next().asMap()))
        assertThrows(IllegalStateException::class.java) { iterator.hasNext() }
        val head = "{\"head\":{\"vars\":[\"x\",\"${"v".repeat(200)}\"]},\"results\":{\"bindings\":[$small]}}"
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
    fun `every term kind and every JSON form is decoded like a general JSON parser does`() {
        val terms = "{\"s\":{\"type\":\"uri\",\"value\":\"urn:s\"},\"b\":{\"type\":\"bnode\",\"value\":\"b0\"}," +
            "\"l\":{\"type\":\"literal\",\"value\":\"o\",\"xml:lang\":\"en-GB\"}," +
            "\"n\":{\"type\":\"literal\",\"value\":\"1\",\"datatype\":\"http://www.w3.org/2001/XMLSchema#integer\"}," +
            "\"t\":{\"type\":\"typed-literal\",\"datatype\":\"http://www.w3.org/2001/XMLSchema#date\",\"value\":\"2026-10-01\"}," +
            "\"p\":{\"value\":\"plain\",\"type\":\"literal\"},\"e\":{\"type\":\"literal\",\"value\":\"\",\"xml:lang\":\"\"}}"
        val forms = "{\"x\":{\"type\":\"literal\",\"value\":\"q\\\" b\\\\ s\\/ \\b\\f\\n\\r\\t \\u00e9\\u20AC \u00e9\u20ac\"," +
            "\"extra\":[0,-0,1,-2.5,3e2,4E+2,5.25e-1,true,false,null,[],{},[[1],[{\"a\":[]}]],\"\",\"s\"],\"\":{\"k\\u0041\":null}}}"
        val spaced = " {\t\"x\" :\n{ \"type\"\r\n: \"uri\" , \"value\" : \"urn:x\" }\n} "
        val documents = listOf(
            document(listOf("x", "s", "b", "l", "n", "t", "p", "e"), terms, forms, spaced, "{}"),
            document(),
            " { \"head\" : { \"vars\" : [ ] , \"link\" : [ \"urn:l\" ] } , \"results\" : { \"bindings\" : [ ] } } \n",
            "{\"a\":1,\"b\":[{\"results\":0}],\"c\":\"results\",\"results\":{\"distinct\":false,\"bindings\":[$terms,$forms],\"ordered\":true},\"z\":null}",
            "{\"results\":{\"bindings\":[$spaced]},\"head\":{\"vars\":[\"x\"]}}",
        )
        for (document in documents) {
            assertEquals(ReferenceJsonResults.rows(document), rows(document), document)
        }
        val row = rows(document(listOf("e", "p", "t", "n", "l", "b", "s"), terms)).single()
        assertEquals(Iri("urn:s"), row["s"])
        assertEquals(BlankNode("b0"), row["b"])
        assertEquals(LangString("o", "en-GB"), row["l"])
        assertEquals(Literal("1", XSD.integer), row["n"])
        assertEquals(Literal("2026-10-01", XSD.date), row["t"])
        assertEquals(Literal("plain", XSD.string), row["p"])
        assertEquals(Literal("", XSD.string), row["e"])
        assertEquals(listOf("s", "b", "l", "n", "t", "p", "e"), row.keys.toList(), "variables keep the order of the row")
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
            // A binding that is not an object, or lacks what a term needs.
            document("{\"x\":\"urn:x\"}"),
            document("{\"x\":[{\"type\":\"uri\",\"value\":\"urn:x\"}]}"),
            document("{\"x\":null}"),
            document("{\"x\":{}}"),
            document("{\"x\":{\"type\":\"uri\"}}"),
            document("{\"x\":{\"value\":\"urn:x\"}}"),
            document("{\"x\":{\"type\":null,\"value\":\"urn:x\"}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":null}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":{}}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":[\"urn:x\"]}}"),
            document("{\"x\":{\"type\":[\"uri\"],\"value\":\"urn:x\"}}"),
            document("{\"x\":{\"type\":\"literal\",\"value\":\"v\",\"xml:lang\":{}}}"),
            document("{\"x\":{\"type\":\"literal\",\"value\":\"v\",\"datatype\":[]}}"),
            document("{\"x\":{\"type\":\"resource\",\"value\":\"urn:x\"}}"),
            // Terms the RDF model refuses.
            document("{\"x\":{\"type\":\"uri\",\"value\":\"not an iri\"}}"),
            document("{\"x\":{\"type\":\"bnode\",\"value\":\"\"}}"),
            document("{\"x\":{\"type\":\"literal\",\"value\":\"v\",\"xml:lang\":\"not a tag\"}}"),
            document("{\"x\":{\"type\":\"literal\",\"value\":\"v\",\"datatype\":\"not an iri\"}}"),
            // Skipped content is still checked.
            document("{\"x\":{\"type\":\"uri\",\"value\":\"urn:x\",\"extra\":[1,]}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":\"urn:x\",\"extra\":{\"a\":01}}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":\"urn:x\",\"extra\":\"bad \\q\"}}"),
            document("{\"x\":{\"type\":\"uri\",\"value\":\"urn:x\",\"extra\":{\"a\" 1}}}"),
            "{\"head\":{\"vars\":[\"x\",]},\"results\":{\"bindings\":[$row]}}",
            "{\"head\":{\"vars\":[\"x\"],\"n\":1e},\"results\":{\"bindings\":[$row]}}",
            "{\"head\":{\"vars\":[\"x\"],\"n\":nul},\"results\":{\"bindings\":[$row]}}",
            "{\"results\":{\"bindings\":[$row]},\"tail\":{\"a\":tru}}",
            "{\"results\":{\"bindings\":[$row],\"more\":[1,,2]}}",
            // A repeated member that was already read cannot be honoured by a streaming reader.
            "{\"results\":{\"bindings\":[$row]},\"results\":{\"bindings\":[]}}",
            "{\"results\":{\"bindings\":[$row],\"bindings\":[]}}",
        ) + listOf("01", "1.", "-", "+1", ".5", "1e", "1e+", "0x1", "NaN", "Infinity", "tru", "nul", "True", "truefalse", "1a", "--1", "1.e1", "1e1.5").map {
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
        assertEquals("v", value(iterator.next().asMap()))
        assertThrows(IllegalStateException::class.java) { iterator.hasNext() }
    }

    @Test
    fun `deeply nested values fail with an error instead of exhausting the stack`() {
        val deep = "[".repeat(200_000) + "]".repeat(200_000)
        for (document in listOf(
            document("{\"x\":{\"type\":\"literal\",\"value\":$deep}}"),
            document("{\"x\":{\"type\":\"literal\",\"value\":\"v\",\"extra\":$deep}}"),
            "{\"head\":{\"link\":$deep},\"results\":{\"bindings\":[]}}",
            "{\"results\":{\"bindings\":[]},\"tail\":${deep.replace("[", "{\"a\":").replace("]", "}")}}",
        )) {
            val e = assertThrows(IllegalStateException::class.java) { rows(document) }
            assertTrue(e.message!!.contains("nested more than 128 levels"), e.message)
        }
        // The limit is exact: a skipped value may open 128 containers, counted from the value itself.
        fun nested(levels: Int) = "[".repeat(levels) + "]".repeat(levels)
        assertEquals(0, rows("{\"tail\":${nested(128)},\"results\":{\"bindings\":[]}}").size)
        assertThrows(IllegalStateException::class.java) { rows("{\"tail\":${nested(129)},\"results\":{\"bindings\":[]}}") }
        // Inside `head` one level is taken by `head` itself.
        assertEquals(0, rows("{\"head\":{\"link\":${nested(127)}},\"results\":{\"bindings\":[]}}").size)
        assertThrows(IllegalStateException::class.java) { rows("{\"head\":{\"link\":${nested(128)}},\"results\":{\"bindings\":[]}}") }
        // Inside a binding two levels are taken by the row and the binding.
        assertEquals(1, rows(document("{\"x\":{\"type\":\"literal\",\"value\":\"v\",\"extra\":${nested(126)}}}")).size)
        assertThrows(IllegalStateException::class.java) { rows(document("{\"x\":{\"type\":\"literal\",\"value\":\"v\",\"extra\":${nested(127)}}}")) }
    }

    @Test
    fun `content a binding does not need is skipped without being stored`() {
        // About 4 Mi characters of junk in one row: a 2 M element array, objects with many members, long strings.
        val limit = SparqlEndpointConfig.DEFAULT_MAX_RESULT_ROW_CHARS
        val array = "[" + "1,".repeat(1_000_000) + "1]"
        val objects = "{" + (0 until 50_000).joinToString(",") { "\"k$it\":{\"a\":[\"s\",null]}" } + "}"
        val row = "{\"x\":{\"junk\":$array,\"type\":\"literal\",\"more\":$objects,\"value\":\"kept\",\"text\":\"${"t".repeat(100_000)}\"}}"
        assertTrue(row.length > limit * 3 / 4 && row.length <= limit, "row of ${row.length} characters")
        val head = "{\"link\":$array,\"vars\":[\"x\"],\"more\":$objects}"
        val document = "{\"head\":$head,\"results\":{\"bindings\":[$row,${literalRow("second")}],\"tail\":$objects},\"after\":$array}"

        // The decoder reports every object it creates for decoded content: strings, terms and rows.
        var created = 0L
        val decoder = JsonBindingRows(document.byteInputStream(), created = { created++ })
        val iterator = decoder.rows().iterator()
        assertTrue(iterator.hasNext())
        assertEquals(HEAD_OBJECTS + ROW_OBJECTS, created, "objects created for `head` and the first row, junk included")
        assertEquals("kept", value(iterator.next().asMap()))
        assertEquals("second", value(iterator.next().asMap()))
        assertFalse(iterator.hasNext())
        // Two rows of one binding each; millions of skipped values added nothing.
        assertEquals(HEAD_OBJECTS + 2 * ROW_OBJECTS, created)

        // ASK: only the answer is read.
        var createdForAsk = 0L
        val ask = JsonBindingRows("{\"head\":$head,\"boolean\":true,\"junk\":$array}".byteInputStream(), created = { createdForAsk++ })
        assertTrue(ask.ask())
        assertEquals(0, createdForAsk)
    }

    @Test
    fun `an ASK result is read from JSON or plain text`() {
        assertTrue(ask("{\"head\":{},\"boolean\":true}"))
        assertFalse(ask(" { \"boolean\" : false } "))
        assertTrue(ask("{\"boolean\":\"true\"}"), "some endpoints send the answer as a string")
        assertFalse(ask("{\"a\":[{\"boolean\":true}],\"boolean\":false,\"z\":{\"boolean\":true}}"))
        assertTrue(ask("true"))
        assertTrue(ask(" TRUE\r\n"))
        assertFalse(ask("False"))
        for (document in listOf("{\"head\":{},\"boolean\":true}", "{\"boolean\":false}", "{\"boolean\":\"false\",\"x\":[1,2,{\"y\":null}]}")) {
            assertEquals(ReferenceJsonResults.ask(document), ask(document), document)
        }
        val malformed = listOf(
            "", " ", "{}", "{\"head\":{}}", "{\"boolean\":null}", "{\"boolean\":1}", "{\"boolean\":\"yes\"}", "{\"boolean\":\"TRUE\"}",
            "{\"boolean\":[true]}", "{\"boolean\":{}}", "{\"boolean\":True}", "{\"boolean\":true", "{\"boolean\":true}x", "{\"boolean\":true,}",
            "{\"boolean\":true,\"boolean\":false}", "{\"boolean\":true,\"x\":[1,]}", "{\"x\":01,\"boolean\":true}", "[true]", "\"true\"",
            "truee", "tru", "true false", "yes", "1", "null", "<html>true</html>",
        )
        for (document in malformed) {
            assertThrows(IllegalStateException::class.java, { ask(document) }, document)
        }
        // The size and nesting limits apply to ASK as well.
        assertThrows(IllegalStateException::class.java) { JsonBindingRows("{\"head\":\"${"h".repeat(200)}\",\"boolean\":true}".byteInputStream(), 100).ask() }
        assertThrows(IllegalStateException::class.java) { ask("{\"head\":${"[".repeat(500)}${"]".repeat(500)},\"boolean\":true}") }
    }

    // ------------------------------------------------------------------ what JSON and the results format require

    @Test
    fun `control characters inside a string must be escaped`() {
        for (code in 0 until 0x20) {
            val raw = code.toChar()
            for (document in listOf(
                document(literalRow("a${raw}b")),
                document(binding(""""type":"literal","value":"v","note":"a${raw}b"""")),
                document(binding(""""type":"literal","value":"v","no${raw}te":1""")),
                document("""{"${raw}x":{"type":"literal","value":"v"}}"""),
                """{"head":{"link":["a${raw}b"]},"results":{"bindings":[]}}""",
                """{"results":{"bindings":[]},"tail":"$raw"}""",
            )) {
                assertRejected(document, "control character")
            }
            assertThrows(IllegalStateException::class.java) { ask("""{"note":"$raw","boolean":true}""") }
            // Escaped, it is that character.
            assertEquals("a${raw}b", value(rows(document(literalRow("a${escape(code)}b"))).single()))
        }
        // DEL and the C1 controls are ordinary characters in JSON.
        val others = "a" + 0x7F.toChar() + 0x85.toChar() + 0x9F.toChar()
        assertEquals(others, value(rows(document(literalRow(others))).single()))
    }

    @Test
    fun `unpaired surrogate escapes are rejected`() {
        val high = escape(0xD83D)
        val low = escape(0xDE00)
        val emoji = String(Character.toChars(0x1F600))
        val newline = "%cn".format(92.toChar())
        val unpaired = listOf(
            high, low, "a$high", "${low}a", "$low$high", "${high}x$low", "$high$high$low", "$high$low$low",
            "$high$newline$low", "$high$emoji", "$emoji$low", "$high${escape(0x41)}",
        )
        for (text in unpaired) {
            for (document in listOf(
                document(literalRow(text)),
                document(binding(""""type":"literal","value":"v","note":"$text"""")),
                document(binding(""""type":"literal","value":"v","note $text":1""")),
                document("""{"x$text":{"type":"literal","value":"v"}}"""),
                """{"head":{"link":["$text"]},"results":{"bindings":[]}}""",
                """{"results":{"bindings":[]},"tail":{"$text":null}}""",
            )) {
                assertRejected(document, "surrogate")
            }
            assertThrows(IllegalStateException::class.java) { ask("""{"note":"$text","boolean":true}""") }
        }
        assertEquals("$emoji-$emoji", value(rows(document(literalRow("$high$low-$emoji"))).single()))
    }

    @Test
    fun `the members of a binding must be strings`() {
        for (member in listOf("type", "value", "datatype", "xml:lang")) {
            for (scalar in listOf("1", "-2.5e3", "true", "false", "null", "[]", "{}", "[\"v\"]")) {
                val members = linkedMapOf("type" to "\"literal\"", "value" to "\"v\"")
                members[member] = scalar
                val body = members.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
                assertRejected(document(binding(body)), "'$member' of a binding must be a string")
            }
        }
    }

    @Test
    fun `a repeated member is rejected wherever members are read`() {
        // A reader that keeps the first and one that keeps the last would disagree, so neither is guessed at.
        val uri = """{"type":"uri","value":"urn:x"}"""
        val repeated = listOf(
            document(binding(""""type":"uri","type":"uri","value":"urn:x"""")) to "'type'",
            document(binding(""""type":"uri","value":"urn:x","type":"literal"""")) to "'type'",
            document(binding(""""type":"uri","value":"urn:x","value":"urn:y"""")) to "'value'",
            document(binding(""""value":[1],"type":"uri","value":"urn:y"""")) to "'value'",
            document(binding(""""type":"literal","value":"v","datatype":"urn:a","datatype":"urn:a"""")) to "'datatype'",
            document(binding(""""type":"literal","value":"v","xml:lang":"en","xml:lang":"fr"""")) to "'xml:lang'",
            document("""{"x":$uri,"x":$uri}""") to "'x'",
            document("""{"x":$uri,"s":$uri,"x":{"type":"literal","value":"other"}}""") to "'x'",
            """{"head":{"vars":["x"]},"head":{"vars":["x"]},"results":{"bindings":[]}}""" to "'head'",
            """{"head":{"vars":["x"]},"results":{"bindings":[]},"head":{}}""" to "'head'",
            """{"results":{"bindings":[]},"head":{},"head":{}}""" to "'head'",
            """{"head":{"vars":["x"],"vars":["x"]},"results":{"bindings":[]}}""" to "'vars'",
            """{"results":{"bindings":[]},"results":{"bindings":[]}}""" to "'results'",
            """{"results":{"bindings":[],"bindings":[]}}""" to "'bindings'",
        )
        for ((document, member) in repeated) assertRejected(document, "more than one", member)
    }

    @Test
    fun `rows may only bind the variables that a preceding head declares`() {
        val row = """{"x":{"type":"uri","value":"urn:x"},"y":{"type":"uri","value":"urn:y"}}"""
        fun withHead(head: String) = """{"head":$head,"results":{"bindings":[$row]}}"""
        assertEquals(1, rows(withHead("""{"vars":["y","x","unused"]}""")).size)
        assertEquals(1, rows(withHead("""{"link":[],"vars":["x","y","x"]}""")).size, "a variable listed twice is declared")
        assertRejected(withHead("""{"vars":["x"]}"""), "'y'", "head")
        assertRejected(withHead("""{"vars":[]}"""), "'x'", "head")
        // Rows before the undeclared variable are delivered, like those before any other error.
        val declared = """{"x":{"type":"uri","value":"urn:x"}}"""
        val iterator = JsonBindingRows("""{"head":{"vars":["x"]},"results":{"bindings":[$declared,$row]}}""".byteInputStream()).rows().iterator()
        assertEquals(Iri("urn:x"), iterator.next().asMap()["x"])
        assertThrows(IllegalStateException::class.java) { iterator.hasNext() }

        // Nothing to compare with: no `head`, a `head` without `vars`, or a `head` that only follows the rows.
        assertEquals(1, rows("""{"results":{"bindings":[$row]}}""").size)
        assertEquals(1, rows(withHead("{}")).size)
        assertEquals(1, rows(withHead("""{"link":["urn:l"]}""")).size)
        assertEquals(1, rows("""{"results":{"bindings":[$row]},"head":{"vars":["x"]}}""").size)

        // `head` is an object and `vars` an array of strings, wherever `head` stands.
        val shapes = listOf(
            "[]", "null", "\"x\"", "1", "{\"vars\":{}}", "{\"vars\":\"x\"}", "{\"vars\":null}", "{\"vars\":[1]}",
            "{\"vars\":[\"x\",null]}", "{\"vars\":[[\"x\"]]}", "{\"vars\":[\"x\",]}",
        )
        for (head in shapes) {
            assertRejected("""{"head":$head,"results":{"bindings":[]}}""")
            assertRejected("""{"results":{"bindings":[]},"head":$head}""")
        }
    }

    @Test
    fun `a language tag is only accepted without a datatype or with rdf langString`() {
        val langString = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"
        assertEquals(LangString("v", "en"), rows(document(binding(""""type":"literal","value":"v","xml:lang":"en","datatype":"$langString""""))).single()["x"])
        assertEquals(LangString("v", "en"), rows(document(binding(""""datatype":"$langString","xml:lang":"en","value":"v","type":"literal""""))).single()["x"])
        for (datatype in listOf(XSD.string.value, XSD.integer.value, "urn:dt", "http://www.w3.org/1999/02/22-rdf-syntax-ns#dirLangString")) {
            assertRejected(document(binding(""""type":"literal","value":"v","xml:lang":"en","datatype":"$datatype"""")), "xml:lang", "datatype")
            assertRejected(document(binding(""""type":"typed-literal","datatype":"$datatype","value":"v","xml:lang":"en"""")), "xml:lang", "datatype")
        }
        // An empty tag is no tag.
        val untagged = binding(""""type":"literal","value":"1","xml:lang":"","datatype":"${XSD.integer.value}"""")
        assertEquals(Literal("1", XSD.integer), rows(document(untagged)).single()["x"])
    }

    // ------------------------------------------------------------------ terms the RDF model refuses

    @Test
    fun `a row with a malformed term fails the result or is skipped, as the policy says`() {
        val first = literalRow("first")
        val last = literalRow("last")
        val good = """{"type":"uri","value":"urn:good"}"""
        val malformed = listOf(
            binding(""""type":"uri","value":"relative/path""""),
            binding(""""type":"uri","value":"http://example.org/a b""""),
            binding(""""type":"uri","value":"<urn:x>""""),
            binding(""""type":"uri","value":"""""),
            binding(""""type":"literal","value":"v","datatype":"not an iri""""),
            binding(""""type":"typed-literal","value":"v","datatype":"""""),
            binding(""""type":"literal","value":"v","xml:lang":"not a tag""""),
            binding(""""type":"bnode","value":"""""),
            binding(""""type":"literal","value":"cut ${escape(0xD83D)}""""),
            binding(""""type":"uri","value":"urn:x:${escape(0xDC00)}""""),
            binding(""""type":"literal","value":"v","note":"${escape(0xD800)}""""),
            """{"s":$good,"x":{"type":"uri","value":"no scheme"}}""",
            """{"x":{"type":"uri","value":"no scheme"},"s":$good}""",
        )
        for (bad in malformed) {
            val document = document(first, bad, last)
            // Strict: the rows before it are delivered, then the result fails.
            val strict = JsonBindingRows(document.byteInputStream())
            val iterator = strict.rows().iterator()
            assertEquals("first", value(iterator.next().asMap()), bad)
            assertThrows(IllegalStateException::class.java, { iterator.hasNext() }, bad)
            assertEquals(0, strict.skippedRows)

            val lenient = JsonBindingRows(document.byteInputStream(), malformedTerms = MalformedTermPolicy.SKIP_ROW)
            assertEquals(listOf("first", "last"), lenient.rows().map { value(it.asMap()) }.toList(), bad)
            assertEquals(1, lenient.skippedRows, bad)
            val reason = lenient.firstSkipped!!
            assertTrue(reason.startsWith("row 2: "), reason)
            assertTrue(reason.length < 200 && reason.all { it in ' '..'~' }, reason)
        }
        val all = JsonBindingRows(document(*malformed.toTypedArray(), last).byteInputStream(), malformedTerms = MalformedTermPolicy.SKIP_ROW)
        assertEquals(listOf("last"), all.rows().map { value(it.asMap()) }.toList())
        assertEquals(malformed.size.toLong(), all.skippedRows)
        assertTrue(all.firstSkipped!!.startsWith("row 1: variable 'x': "), all.firstSkipped)
        assertTrue(all.firstSkipped!!.contains("relative/path"), all.firstSkipped)

        val clean = JsonBindingRows(document(first, last).byteInputStream(), malformedTerms = MalformedTermPolicy.SKIP_ROW)
        assertEquals(2, clean.rows().count())
        assertEquals(0, clean.skippedRows)
        assertEquals(null, clean.firstSkipped)
    }

    @Test
    fun `what is not a term problem fails under every policy`() {
        val first = literalRow("first")
        val structural = listOf(
            binding(""""type":"uri","type":"uri","value":"urn:x""""),
            binding(""""type":"uri","value":1"""),
            binding(""""type":"resource","value":"urn:x""""),
            binding(""""type":"uri""""),
            binding(""""type":"triple","value":{"subject":{"type":"uri","value":"urn:s"}}"""),
            binding(""""type":"literal","value":"v","xml:lang":"ar","its:dir":"rtl""""),
            binding(""""type":"literal","value":"v","xml:lang":"en","datatype":"urn:dt""""),
            binding(""""type":"literal","value":"a${1.toChar()}b""""),
            """{"q":{"type":"uri","value":"urn:x"}}""",
            """{"x":}""",
            """{"x":{"type":"uri","value":"no scheme"},"x":{"type":"uri","value":"urn:x"}}""",
            """{"x":{"type":"uri","value":"no scheme"},"s":1}""",
        )
        for (bad in structural) {
            for (policy in MalformedTermPolicy.values()) assertRejected(document(first, bad), policy = policy)
        }
        // Outside the rows there is no row to skip.
        for (policy in MalformedTermPolicy.values()) {
            assertRejected("""{"head":{"link":["${escape(0xD800)}"]},"results":{"bindings":[]}}""", "surrogate", policy = policy)
            assertRejected("""{"results":{"bindings":[]},"tail":"${escape(0xD800)}"}""", "surrogate", policy = policy)
        }
        // The size limit of a row holds for a row that is skipped.
        val long = binding(""""type":"uri","value":"${"no scheme ".repeat(50)}"""")
        assertEquals(0, rows(document(long), long.length, MalformedTermPolicy.SKIP_ROW).size)
        val e = assertThrows(IllegalStateException::class.java) { rows(document(long), long.length - 1, MalformedTermPolicy.SKIP_ROW) }
        assertTrue(e.message!!.contains("maxResultRowChars"), e.message)
    }

    private companion object {
        /** A row with one binding: the row, the variable name, `type`, `value` and the term. */
        const val ROW_OBJECTS = 5L

        /** The one variable name of `head.vars`. */
        const val HEAD_OBJECTS = 1L
    }
}
