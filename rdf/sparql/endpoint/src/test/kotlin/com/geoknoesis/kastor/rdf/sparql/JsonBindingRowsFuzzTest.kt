package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.random.Random

/**
 * Seeded property tests for the SPARQL JSON result decoder. Every run generates the same documents;
 * a failure names the document, so it can be replayed.
 *
 * - Valid results (every term kind, every way of escaping a character, surrogate pairs, white
 *   space, unknown members, any member order) decode to exactly the terms they were generated from,
 *   and to what a general JSON parser reads.
 * - A mutated document is either rejected with the documented [IllegalStateException], or accepted
 *   with the result a general JSON parser gives for it. Nothing else may be thrown.
 * - A valid result into which rows with one defect each are inserted is rejected; when the defects
 *   are terms the RDF model refuses and such rows are to be skipped, exactly those rows are missing.
 */
class JsonBindingRowsFuzzTest {

    /** Hands out the bytes in chunks of random size, so buffer boundaries fall everywhere. */
    private class Chunked(private val bytes: ByteArray, private val random: Random) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(len, bytes.size - position, 1 + random.nextInt(if (random.nextInt(4) == 0) 7 else 5000))
            System.arraycopy(bytes, position, b, off, count)
            position += count
            return count
        }
    }

    private fun decode(bytes: ByteArray, random: Random): List<Map<String, RdfTerm>> =
        JsonBindingRows(Chunked(bytes, random)).rows().map { it.asMap() }.toList()

    /** One way a row can be wrong: [members] are inserted into it. A [skippable] defect is a term the RDF model refuses. */
    private class Defect(val name: String, val skippable: Boolean, val members: String)

    private fun escape(code: Int): String = "%cu%04X".format(92.toChar(), code)

    private val sound = """{"type":"uri","value":"urn:sound"}"""

    private val defects = listOf(
        Defect("relative IRI", true, """"bad":{"type":"uri","value":"relative/path"}"""),
        Defect("IRI with a space", true, """"bad":{"value":"http://example.org/a b","type":"uri"}"""),
        Defect("empty IRI", true, """"bad":{"type":"uri","value":""}"""),
        Defect("invalid datatype IRI", true, """"bad":{"type":"literal","value":"v","datatype":"{dt}"}"""),
        Defect("invalid language tag", true, """"bad":{"type":"literal","xml:lang":"en_GB","value":"v"}"""),
        Defect("blank node without a label", true, """"bad":{"type":"bnode","value":""}"""),
        Defect("unpaired high surrogate", true, """"bad":{"type":"literal","value":"cut ${escape(0xD83D)}"}"""),
        Defect("unpaired low surrogate", true, """"bad":{"type":"uri","value":"urn:x:${escape(0xDE00)}:y"}"""),
        Defect("repeated member", false, """"bad":{"type":"uri","value":"urn:x","type":"uri"}"""),
        Defect("repeated value", false, """"bad":{"value":"urn:x","type":"uri","value":"urn:x"}"""),
        Defect("repeated variable", false, """"bad":$sound,"bad":$sound"""),
        Defect("undeclared variable", false, """"undeclared":$sound"""),
        Defect("number as value", false, """"bad":{"type":"literal","value":7}"""),
        Defect("null as datatype", false, """"bad":{"type":"literal","value":"v","datatype":null}"""),
        Defect("boolean as language", false, """"bad":{"type":"literal","value":"v","xml:lang":false}"""),
        Defect("null as type", false, """"bad":{"type":null,"value":"v"}"""),
        Defect("language tag and datatype", false, """"bad":{"type":"literal","value":"v","xml:lang":"en","datatype":"urn:dt"}"""),
        Defect("raw control character", false, """"bad":{"type":"literal","value":"a${0x0B.toChar()}b"}"""),
        Defect("unknown type", false, """"bad":{"type":"thing","value":"v"}"""),
        Defect("triple term", false, """"bad":{"type":"triple","value":{"subject":$sound}}"""),
        Defect("base direction", false, """"bad":{"type":"literal","value":"v","xml:lang":"ar","its:dir":"rtl"}"""),
    )

    private class Generator(private val random: Random) {
        private val datatypes = listOf(XSD.integer.value, XSD.date.value, "http://www.w3.org/1999/02/22-rdf-syntax-ns#JSON", "urn:dt:\u00E9")
        private val languages = listOf("en", "en-GB", "FR", "zh-Hant-TW", "de-1996")

        fun chance(percent: Int) = random.nextInt(100) < percent

        fun space(): String = if (chance(70)) "" else buildString { repeat(1 + random.nextInt(3)) { append(" \t\n\r"[random.nextInt(4)]) } }

        /** Any text: ASCII, what JSON must escape, control characters, Latin-1, odd spaces, CJK and characters beyond the BMP. */
        fun text(max: Int = 12): String = buildString {
            repeat(random.nextInt(max + 1)) {
                when (random.nextInt(12)) {
                    0 -> append("\"\\/"[random.nextInt(3)])
                    1 -> append("\b\u000C\n\r\t\u0000\u0001\u001F\u007F"[random.nextInt(9)])
                    2 -> append((0xA0 + random.nextInt(0x60)).toChar())
                    3 -> append(listOf(0x2028, 0x2029, 0xFEFF, 0x3000, 0xFFFD, 0x200B).random(random).toChar())
                    4 -> append((0x4E00 + random.nextInt(0x500)).toChar())
                    5 -> appendCodePoint(0x1F600 + random.nextInt(0x40))
                    6 -> appendCodePoint(0x10000 + random.nextInt(0xFFFFF))
                    7 -> append("{}[],:"[random.nextInt(6)])
                    else -> append(('a'.code + random.nextInt(26)).toChar())
                }
            }
        }

        private fun hex(c: Char): String = "\\u" + "%04x".format(c.code).let { if (chance(50)) it.uppercase() else it }

        /** [value] as a JSON string, each character written in one of the ways JSON allows. */
        fun string(value: String): String = buildString {
            append('"')
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (Character.isHighSurrogate(c)) {
                    // A pair is written raw or as two escapes, never half of each.
                    val low = value[i + 1]
                    if (chance(30)) append(hex(c)).append(hex(low)) else append(c).append(low)
                    i += 2
                    continue
                }
                val short = when (c) {
                    '"' -> "\\\""
                    '\\' -> "\\\\"
                    '/' -> "\\/"
                    '\b' -> "\\b"
                    '\u000C' -> "\\f"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> null
                }
                when {
                    c == '"' || c == '\\' || c.code < 0x20 -> append(if (short != null && chance(70)) short else hex(c))
                    c == '/' -> append(if (chance(50)) "/" else short)
                    chance(10) -> append(hex(c))
                    else -> append(c)
                }
                i++
            }
            append('"')
        }

        /** Any JSON value; nothing in it is a result. */
        fun junk(depth: Int = 0): String = when (random.nextInt(if (depth > 3) 5 else 7)) {
            0 -> listOf("0", "-0", "7", "-12.5", "3e2", "4E+2", "5.25e-1", "1234567890123456789012345678901234567890", "0.0", "1E-0").random(random)
            1 -> "true"
            2 -> "false"
            3 -> "null"
            4 -> string(text())
            5 -> (0 until random.nextInt(4)).joinToString(",", "[" + space(), space() + "]") { space() + junk(depth + 1) + space() }
            else -> (0 until random.nextInt(4)).joinToString(",", "{" + space(), space() + "}") {
                space() + string(listOf("type", "value", "results", "bindings", "boolean", text(4)).random(random)) + space() + ":" + space() + junk(depth + 1)
            }
        }

        private fun name() = ('a'.code + random.nextInt(26)).toChar().toString() + random.nextInt(1000)

        /** A term and one way to write its binding. */
        fun binding(): Pair<RdfTerm, String> {
            val members = ArrayList<String>()
            fun member(name: String, value: String) = members.add(space() + string(name) + space() + ":" + space() + value + space())
            val term: RdfTerm = when (random.nextInt(6)) {
                0 -> Iri(if (chance(50)) "urn:x:${name()}" else "http://example.org/${name()}/caf\u00E9#${name()}").also {
                    member("type", string("uri"))
                    member("value", string(it.value))
                }
                1 -> BlankNode(if (chance(50)) name() else "nodeID://${name()}").also {
                    member("type", string("bnode"))
                    member("value", string(it.id))
                }
                2 -> {
                    val literal = LangString(text(), languages.random(random))
                    member("type", string("literal"))
                    member("value", string(literal.lexical))
                    member("xml:lang", string(literal.lang))
                    if (chance(20)) member("datatype", string("http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"))
                    literal
                }
                3 -> {
                    val datatype = datatypes.random(random)
                    val lexical = text()
                    member("type", string(if (chance(50)) "literal" else "typed-literal"))
                    member("value", string(lexical))
                    member("datatype", string(datatype))
                    if (chance(20)) member("xml:lang", "\"\"")
                    Literal(lexical, Iri(datatype))
                }
                else -> {
                    val lexical = text(if (chance(5)) 20_000 else 12)
                    member("type", string("literal"))
                    member("value", string(lexical))
                    if (chance(20)) member("datatype", string(XSD.string.value))
                    Literal(lexical, XSD.string)
                }
            }
            repeat(if (chance(30)) 1 + random.nextInt(2) else 0) { member("x-${name()}", junk()) }
            members.shuffle(random)
            return term to members.joinToString(",", "{", "}")
        }

        fun row(): Pair<Map<String, RdfTerm>, String> {
            val terms = LinkedHashMap<String, RdfTerm>()
            val members = ArrayList<String>()
            repeat(random.nextInt(5)) {
                val variable = if (chance(80)) name() else text(6)
                if (variable !in terms) {
                    val (term, json) = binding()
                    terms[variable] = term
                    members.add(space() + string(variable) + space() + ":" + space() + json + space())
                }
            }
            return terms to members.joinToString(",", "{", "}")
        }

        /** A SELECT result: the rows it holds and one way to write it. */
        fun document(maxRows: Int = 6): Pair<List<Map<String, RdfTerm>>, String> {
            val rows = (0 until random.nextInt(maxRows + 1)).map { row() }
            fun members(required: String, others: List<String>): String {
                val all = ArrayList<String>()
                all.add(required)
                others.forEach { if (chance(50)) all.add(space() + it + space()) }
                repeat(if (chance(30)) 1 + random.nextInt(2) else 0) { all.add(space() + string("j-${name()}") + ":" + space() + junk() + space()) }
                all.shuffle(random)
                return all.joinToString(",", space() + "{", "}" + space())
            }
            val bindings = rows.joinToString(",", "[" + space(), space() + "]") { space() + it.second + space() }
            val results = members(space() + "\"bindings\"" + space() + ":" + space() + bindings, listOf("\"distinct\":false", "\"ordered\":true"))
            val head = "\"head\":{\"vars\":[${rows.flatMap { it.first.keys }.distinct().joinToString(",") { string(it) }}]}"
            return rows.map { it.first } to members(space() + "\"results\"" + space() + ":" + results, listOf(head))
        }
    }

    @Test
    fun `generated results decode to the terms they were written from`() {
        val random = Random(SEED)
        val generator = Generator(random)
        var rows = 0
        repeat(400) { index ->
            val (expected, text) = generator.document()
            val bytes = (if (generator.chance(10)) "\uFEFF" else "").toByteArray(Charsets.UTF_8) + text.toByteArray(Charsets.UTF_8)
            val decoded = try {
                decode(bytes, random)
            } catch (e: Throwable) {
                fail("document #$index was not decoded: $text", e)
            }
            assertEquals(expected, decoded, "document #$index: $text")
            assertEquals(ReferenceJsonResults.rows(text), decoded, "document #$index against the reference parser: $text")
            rows += expected.size
        }
        assertTrue(rows > 800, "only $rows rows were generated")
    }

    @Test
    fun `rows with a defect fail the result or are skipped when the defect is a malformed term`() {
        val random = Random(SEED + 3)
        val generator = Generator(random)
        var failed = 0
        var skipped = 0L
        var intact = 0
        repeat(600) { index ->
            val rows = (0 until 1 + random.nextInt(5)).map { generator.row() }
            // Most documents get a defect in one or two rows; the others show that the scaffolding itself is sound.
            val broken = rows.indices.associateWith { if (generator.chance(35)) defects.random(random) else null }.filterValues { it != null }
            val written = rows.mapIndexed { i, (_, json) ->
                val defect = broken[i] ?: return@mapIndexed json
                "{" + defect.members + (if (json == "{}") "" else ",") + json.substring(1)
            }
            val vars = (rows.flatMap { it.first.keys } + "bad").distinct().joinToString(",") { generator.string(it) }
            val text = """{"head":{"vars":[$vars]},"results":{"bindings":[${written.joinToString(",")}]}}"""
            val label = "document #$index (${broken.values.joinToString { it!!.name }}): $text"
            val bytes = text.toByteArray(Charsets.UTF_8)
            fun decode(policy: MalformedTermPolicy): Pair<List<Map<String, RdfTerm>>, Long>? = try {
                val decoder = JsonBindingRows(Chunked(bytes, random), malformedTerms = policy)
                decoder.rows().map { it.asMap() }.toList() to decoder.skippedRows
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.length < 400, "an error message of ${e.message!!.length} characters for $label")
                null
            } catch (e: Throwable) {
                fail("${e.javaClass.name} instead of the documented IllegalStateException for $label", e)
            }

            val strict = decode(MalformedTermPolicy.FAIL)
            val lenient = decode(MalformedTermPolicy.SKIP_ROW)
            if (broken.isEmpty()) {
                intact++
                assertEquals(rows.map { it.first } to 0L, strict, label)
                assertEquals(strict, lenient, label)
            } else {
                assertEquals(null, strict, "a defect was accepted: $label")
                if (broken.values.all { it!!.skippable }) {
                    assertEquals(rows.filterIndexed { i, _ -> i !in broken }.map { it.first } to broken.size.toLong(), lenient, label)
                    skipped += broken.size
                } else {
                    assertEquals(null, lenient, "a defect that is not a malformed term was skipped: $label")
                    failed++
                }
            }
        }
        // All three outcomes occur, or the test shows nothing.
        assertTrue(intact > 50, "only $intact documents without a defect")
        assertTrue(failed > 100, "only $failed documents with a defect that always fails")
        assertTrue(skipped > 100, "only $skipped rows were skipped")
    }

    private val interesting = "{}[]\",:\\/ \t\n\rtfnu0123456789.eE+-\u00A0\u2028\uFEFF\u0000\u001F\u00E9\uD83D\uDE00"

    private fun mutate(text: String, random: Random): String {
        val out = StringBuilder(text)
        repeat(1 + random.nextInt(3)) {
            if (out.isEmpty()) return@repeat
            val at = random.nextInt(out.length)
            when (random.nextInt(8)) {
                0 -> out.deleteCharAt(at)
                1 -> out.insert(at, interesting[random.nextInt(interesting.length)])
                2 -> out.setCharAt(at, interesting[random.nextInt(interesting.length)])
                3 -> out.setLength(at)
                4 -> out.insert(at, out.substring(at, minOf(out.length, at + 1 + random.nextInt(20))))
                5 -> out.delete(at, minOf(out.length, at + 1 + random.nextInt(10)))
                6 -> if (at + 1 < out.length) {
                    val c = out[at]
                    out.setCharAt(at, out[at + 1])
                    out.setCharAt(at + 1, c)
                }
                // Two members swap their names or values: valid JSON with another meaning.
                else -> {
                    val words = listOf("\"type\"", "\"value\"", "\"xml:lang\"", "\"datatype\"", "\"results\"", "\"bindings\"", "\"head\"", "\"literal\"", "\"uri\"", "null", "true", "{}", "[]")
                    val from = words.random(random)
                    val found = out.indexOf(from)
                    if (found >= 0) out.replace(found, found + from.length, words.random(random))
                }
            }
        }
        return out.toString()
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    @Test
    fun `mutated results are rejected or read like a general JSON parser reads them`() {
        val random = Random(SEED + 1)
        val generator = Generator(random)
        var accepted = 0
        var rejected = 0
        var invalidUtf8 = 0
        repeat(120) { base ->
            val (_, original) = generator.document(maxRows = 3)
            repeat(40) { variant ->
                // A lone surrogate left by a mutation is encoded as '?', so the bytes are valid UTF-8 unless a byte is changed below.
                val bytes = mutate(original, random).toByteArray(Charsets.UTF_8)
                if (bytes.isNotEmpty() && random.nextInt(5) == 0) bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
                val text = strictUtf8(bytes)
                val label = "mutation $base/$variant of: $original\nas: ${text ?: bytes.joinToString(" ") { "%02x".format(it) }}"
                val decoded = try {
                    decode(bytes, random)
                } catch (e: IllegalStateException) {
                    assertTrue(e.message!!.length < 400, "an error message of ${e.message!!.length} characters for $label")
                    null
                } catch (e: Throwable) {
                    fail("${e.javaClass.name} instead of the documented IllegalStateException for $label", e)
                }
                if (text == null) {
                    invalidUtf8++
                    assertEquals(null, decoded, "malformed UTF-8 was accepted: $label")
                }
                if (decoded == null) {
                    rejected++
                } else {
                    accepted++
                    val reference = try {
                        ReferenceJsonResults.rows(text!!.removePrefix("\uFEFF"))
                    } catch (e: Exception) {
                        fail("accepted what the reference parser rejects (${e.message?.take(200)}): $label", e)
                    }
                    assertEquals(reference, decoded, label)
                }
            }
        }
        // The mutations are of both kinds, or the test shows nothing.
        assertTrue(accepted > 200, "only $accepted mutations were accepted")
        assertTrue(rejected > 1000, "only $rejected mutations were rejected")
        assertTrue(invalidUtf8 > 50, "only $invalidUtf8 mutations were malformed UTF-8")
    }

    @Test
    fun `mutated ASK results are rejected or read like a general JSON parser reads them`() {
        val random = Random(SEED + 2)
        val generator = Generator(random)
        var accepted = 0
        var rejected = 0
        repeat(3000) { index ->
            val answer = random.nextBoolean()
            val members = mutableListOf("\"boolean\"${generator.space()}:${generator.space()}$answer")
            if (generator.chance(60)) members.add("\"head\":" + generator.junk())
            if (generator.chance(30)) members.add("\"extra\":" + generator.junk())
            members.shuffle(random)
            val original = members.joinToString(",", generator.space() + "{", "}" + generator.space())
            val text = if (index % 4 == 0) original else String(mutate(original, random).toByteArray(Charsets.UTF_8), Charsets.UTF_8)
            val decoded = try {
                JsonBindingRows(Chunked(text.toByteArray(Charsets.UTF_8), random)).ask()
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.length < 400, e.message)
                null
            } catch (e: Throwable) {
                fail("${e.javaClass.name} instead of the documented IllegalStateException for ASK #$index: $text", e)
            }
            if (index % 4 == 0) assertEquals(answer, decoded, "ASK #$index: $text")
            if (decoded == null) {
                rejected++
            } else {
                accepted++
                val plain = text.removePrefix("\uFEFF").trim(' ', '\t', '\n', '\r')
                val reference = when {
                    plain.equals("true", ignoreCase = true) -> true
                    plain.equals("false", ignoreCase = true) -> false
                    else -> try {
                        ReferenceJsonResults.ask(text.removePrefix("\uFEFF"))
                    } catch (e: Exception) {
                        fail("accepted what the reference parser rejects (${e.message?.take(200)}): ASK #$index: $text", e)
                    }
                }
                assertEquals(reference, decoded, "ASK #$index: $text")
            }
        }
        assertTrue(accepted > 800, "only $accepted ASK results were accepted")
        assertTrue(rejected > 800, "only $rejected ASK results were rejected")
    }

    private companion object {
        const val SEED = 20261001L
    }
}
