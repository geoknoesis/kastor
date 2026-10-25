package com.geoknoesis.kastor.rdf.rdf4j

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Triple
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler
import org.eclipse.rdf4j.rio.helpers.NTriplesUtil
import org.eclipse.rdf4j.rio.helpers.RDFStarUtil
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.Writer

/**
 * RDF 1.2 triple terms (`<<( s p o )>>`) in N-Triples and N-Quads, which Rio 5.x neither writes nor reads: its
 * writers encode a triple term as an `urn:rdf4j:triple:` IRI, which every other RDF 1.2 reader takes for a plain IRI,
 * and its parsers reject `<<`.
 * - [Writer] writes N-Triples / N-Quads lines itself, with Rio's own term syntax ([NTriplesUtil]) for every term
 *   other than a triple term, and the RDF 1.2 syntax for a triple term.
 * - [fromRdf12] rewrites each triple term of an N-Triples / N-Quads document into the `urn:rdf4j:triple:` IRI that
 *   Rio's parsers decode back into the triple term (`BasicParserSettings.PROCESS_ENCODED_RDF_STAR`, on by default),
 *   so Rio's own strict parser reads everything else. A triple term is an object only: where a subject, a predicate
 *   or a graph name belongs it is left as it is, and Rio rejects it.
 */
internal object Rdf4jLineSyntax {

    /** Writes the statements it is given as N-Triples ([quads] false: graph names are not written) or N-Quads lines. */
    class Writer(private val out: java.io.Writer, private val quads: Boolean) : AbstractRDFHandler() {
        override fun handleStatement(statement: Statement) {
            val line = StringBuilder()
            term(line, statement.subject).append(' ')
            term(line, statement.predicate).append(' ')
            term(line, statement.`object`)
            if (quads) statement.context?.let { term(line.append(' '), it) }
            out.write(line.append(" .\n").toString())
        }

        override fun endRDF() = out.flush()

        private fun term(line: StringBuilder, value: Value): StringBuilder = when (value) {
            is Triple -> {
                line.append("<<( ")
                term(line, value.subject).append(' ')
                term(line, value.predicate).append(' ')
                term(line, value.`object`).append(" )>>")
            }
            else -> line.append(NTriplesUtil.toNTriplesString(value, true))
        }
    }

    /** [input], an N-Triples / N-Quads document, with its RDF 1.2 triple terms as `urn:rdf4j:triple:` IRIs. */
    fun fromRdf12(input: InputStream): InputStream = Translator(input)

    /**
     * The streaming translation of [fromRdf12]. Outside a triple term every byte is copied; a triple term in object
     * position is read (as UTF-8 text: all the tokens involved are ASCII) up to its matching `)>>`, parsed, and
     * replaced by its encoded IRI. The lexer knows IRIs, strings (with escapes) and comments, so `<<(` inside them is
     * never a triple term. The memory a translation holds is one triple term.
     */
    private class Translator(source: InputStream) : InputStream() {
        private val input = PushbackInputStream(java.io.BufferedInputStream(source), 4)
        private var out = ByteArray(0)
        private var outStart = 0

        /** The position of the next term of the statement: 0 subject, 1 predicate, 2 object, 3 graph name. */
        private var position = 0

        override fun read(): Int {
            while (outStart == out.size) if (!step()) return -1
            return out[outStart++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var n = 0
            while (n < len) {
                if (outStart == out.size && n > 0 && input.available() == 0) break
                val c = read()
                if (c < 0) break
                b[off + n++] = c.toByte()
            }
            return if (n == 0) -1 else n
        }

        override fun close() = input.close()

        private fun emit(bytes: ByteArray) {
            out = bytes
            outStart = 0
        }

        private fun emit(c: Int) = emit(byteArrayOf(c.toByte()))

        private fun peek(): Int = input.read().also { if (it >= 0) input.unread(it) }

        /** Translates the next token into [out]; false at the end of the input. */
        private fun step(): Boolean {
            val c = input.read()
            if (c < 0) return false
            when (c) {
                '<'.code -> { if (peek() == '<'.code) tripleTermOrToken() else emit(copyUntil(c, '>'.code)); position++ }
                '"'.code -> { emit(stringToken()); position++ }
                '_'.code -> { emit(blankNodeLabel()); position++ }
                // `^^<datatype>` belongs to the literal before it.
                '^'.code -> emit(datatypeSuffix())
                '#'.code -> emit(copyUntil(c, '\n'.code, '\r'.code))
                '.'.code -> { position = 0; emit(c) }
                else -> emit(c) // white space, a language tag (it belongs to the literal before it), or an error for Rio
            }
            return true
        }

        /** After `<` followed by `<`: a triple term in object position is translated; anything else is copied. */
        private fun tripleTermOrToken() {
            input.read() // the second '<'
            if (peek() != '('.code || position != 2) {
                emit("<<".toByteArray())
                return
            }
            input.read()
            val text = StringBuilder("<<(")
            captureTripleTerm(text)
            val triple = try {
                TripleTermParser(text.toString()).parse()
            } catch (e: IllegalArgumentException) {
                throw IOException("Invalid RDF 1.2 triple term ${text.take(200)}: ${e.message}", e)
            }
            emit("<${RDFStarUtil.toRDFEncodedValue<Value>(triple).stringValue()}>".toByteArray(Charsets.UTF_8))
        }

        /** Reads the rest of a triple term (its `<<(` already read and in [text]) up to its matching `)>>`. */
        private fun captureTripleTerm(text: StringBuilder) {
            val bytes = java.io.ByteArrayOutputStream()
            var depth = 1
            while (depth > 0) {
                val c = input.read()
                if (c < 0) throw IOException("Unterminated RDF 1.2 triple term")
                when {
                    c == '"'.code -> { bytes.write(stringToken()); continue }
                    c == '<'.code && peek() == '<'.code -> { input.read(); bytes.write("<<".toByteArray()); if (peek() == '('.code) depth++; continue }
                    c == '<'.code -> { bytes.write(copyUntil(c, '>'.code)); continue }
                    c == ')'.code && peek() == '>'.code -> {
                        input.read()
                        if (peek() == '>'.code) { input.read(); depth--; bytes.write(")>>".toByteArray()) } else bytes.write(")>".toByteArray())
                        continue
                    }
                }
                bytes.write(c)
            }
            text.append(bytes.toString(Charsets.UTF_8))
        }

        /** After a `^`: `^^<datatype IRI>`, which is part of the literal before it (not a term of its own). */
        private fun datatypeSuffix(): ByteArray {
            if (peek() != '^'.code) return byteArrayOf('^'.code.toByte())
            input.read()
            if (peek() != '<'.code) return "^^".toByteArray()
            input.read()
            return "^^".toByteArray() + copyUntil('<'.code, '>'.code)
        }

        /** A blank node label, its `_` already read; a final `.` is the end of the statement, not part of the label. */
        private fun blankNodeLabel(): ByteArray {
            val bytes = java.io.ByteArrayOutputStream()
            bytes.write('_'.code)
            while (true) {
                val c = input.read()
                if (c < 0) break
                val label = c >= 0x80 || c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                    c == '_'.code || c == '-'.code || c == ':'.code || c == '.'.code
                if (!label) { input.unread(c); break }
                bytes.write(c)
            }
            val label = bytes.toByteArray()
            if (label.size > 1 && label.last() == '.'.code.toByte()) { input.unread('.'.code); return label.copyOf(label.size - 1) }
            return label
        }

        /** The bytes from [first] (already read) up to and including the first of [ends] (or the end of the input). */
        private fun copyUntil(first: Int, vararg ends: Int): ByteArray {
            val bytes = java.io.ByteArrayOutputStream()
            bytes.write(first)
            while (true) {
                val c = input.read()
                if (c < 0) break
                bytes.write(c)
                if (c in ends) break
            }
            return bytes.toByteArray()
        }

        /** A string literal's bytes, its opening quote already read, up to its closing quote, escapes included. */
        private fun stringToken(): ByteArray {
            val bytes = java.io.ByteArrayOutputStream()
            bytes.write('"'.code)
            while (true) {
                val c = input.read()
                if (c < 0) break
                bytes.write(c)
                if (c == '\\'.code) input.read().let { if (it >= 0) bytes.write(it) } else if (c == '"'.code) break
            }
            return bytes.toByteArray()
        }
    }

    /** Parses the text of one RDF 1.2 triple term in N-Triples syntax; malformed text is an [IllegalArgumentException]. */
    private class TripleTermParser(private val text: String) {
        private val values = SimpleValueFactory.getInstance()
        private var i = 0

        fun parse(): Triple = tripleTerm().also {
            skipSpace()
            require(i == text.length) { "unexpected text after the triple term" }
        }

        private fun tripleTerm(): Triple {
            expect("<<(")
            val subject = term() as? Resource ?: throw IllegalArgumentException("the subject must be an IRI or a blank node")
            if (subject is Triple) throw IllegalArgumentException("the subject must be an IRI or a blank node")
            val predicate = term() as? IRI ?: throw IllegalArgumentException("the predicate must be an IRI")
            val obj = term()
            skipSpace()
            expect(")>>")
            return values.createTriple(subject, predicate, obj)
        }

        private fun term(): Value {
            skipSpace()
            require(i < text.length) { "a term is missing" }
            if (text.startsWith("<<(", i)) return tripleTerm()
            val start = i
            when (text[i]) {
                '<' -> i = text.indexOf('>', i).also { require(it > 0) { "unterminated IRI" } } + 1
                '_' -> while (i < text.length && !text[i].isWhitespace() && !text.startsWith(")>>", i)) i++
                '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
                    require(i < text.length) { "unterminated string" }
                    i++
                    if (text.startsWith("^^<", i)) i = text.indexOf('>', i).also { require(it > 0) { "unterminated datatype IRI" } } + 1
                    else if (i < text.length && text[i] == '@') while (i < text.length && (text[i] == '@' || text[i] == '-' || text[i].isLetterOrDigit())) i++
                }
                else -> throw IllegalArgumentException("unexpected '${text[i]}'")
            }
            return NTriplesUtil.parseValue(text.substring(start, i), values)
        }

        private fun skipSpace() {
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        }

        private fun expect(token: String) {
            skipSpace()
            require(text.startsWith(token, i)) { "expected '$token'" }
            i += token.length
        }
    }
}
