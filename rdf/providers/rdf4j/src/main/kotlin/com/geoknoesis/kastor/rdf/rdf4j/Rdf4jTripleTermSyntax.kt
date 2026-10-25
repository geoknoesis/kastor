package com.geoknoesis.kastor.rdf.rdf4j

import java.io.InputStream
import java.io.PushbackInputStream

/**
 * Translates between the RDF 1.2 triple-term tokens of Turtle and TriG (`<<(` and `)>>`) and the RDF-star tokens
 * (`<<` and `>>`) that RDF4J 5.x's Rio reads and writes for the same triple term.
 *
 * Rio 5.x implements RDF-star, not RDF 1.2: its parsers reject `<<( s p o )>>`, and its RDF 1.1 writers encode a
 * triple term as an `urn:rdf4j:triple:` IRI, which every other RDF 1.2 reader takes for a plain IRI (the triple term
 * is lost). Rio's Turtle-star and TriG-star readers and writers, however, treat `<< s p o >>` in object position as
 * exactly the RDF 1.2 triple term `<<( s p o )>>`. So:
 * - [toRdf12] rewrites the output of Rio's TURTLESTAR / TRIGSTAR writers into RDF 1.2 syntax;
 * - [fromRdf12] rewrites an RDF 1.2 document's triple-term tokens into RDF-star tokens before Rio parses it. Other
 *   RDF 1.2 constructs (reified triples `<< s p o >>`, annotations) pass unchanged, as they did before. A `<<(` where
 *   a subject or a predicate comes next (at the start of a statement, after `;`, `[`, `<<`, ...) is left as it is, so
 *   Rio rejects it: a triple term is an object only, and as `<<` Rio would read it as a reified triple.
 *
 * N-Triples and N-Quads, in which Rio 5.x reads no RDF-star, have their own translation ([Rdf4jLineSyntax]).
 *
 * Both work on the tokens of the document, not on its text: IRIs (`<...>`), string literals (all four quote forms,
 * with escapes; a long string ends at the first three quotes, as in the grammar), comments and escaped characters of
 * prefixed names are copied as they are, so a `<<(` or `)>>` inside a literal, an IRI or a comment is never touched.
 * They work on bytes: every token involved is ASCII, and a UTF-8 multi-byte sequence never contains an ASCII byte.
 * Each byte is looked at a bounded number of times (at most three bytes of look-ahead), and the state is a few
 * fields, so the cost is linear in the input and the memory constant.
 */
internal object Rdf4jTripleTermSyntax {

    /** [rdfStar], the text of a Rio RDF-star writer, with its quoted-triple tokens written as RDF 1.2 triple terms. */
    fun toRdf12(rdfStar: String): String {
        if (!rdfStar.contains("<<")) return rdfStar
        return Translator(rdfStar.byteInputStream(Charsets.UTF_8), toRdf12 = true).readBytes().toString(Charsets.UTF_8)
    }

    /** [input], an RDF 1.2 Turtle or TriG document, with its triple terms as RDF-star tokens. */
    fun fromRdf12(input: InputStream): InputStream = Translator(input, toRdf12 = false)

    /** A streaming token translator; see the object documentation. Closing it closes the source. */
    private class Translator(source: InputStream, private val toRdf12: Boolean) : InputStream() {
        private val input = PushbackInputStream(java.io.BufferedInputStream(source), 4)
        private val out = ByteArray(8)
        private var outStart = 0
        private var outEnd = 0
        private var state = State.NORMAL
        private var quote = 0

        private enum class State { NORMAL, IRI, COMMENT, SHORT_STRING, LONG_STRING }

        override fun read(): Int {
            while (outStart == outEnd) {
                outStart = 0
                outEnd = 0
                if (!step()) return -1
            }
            return out[outStart++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var n = 0
            while (n < len) {
                if (outStart == outEnd && n > 0 && input.available() == 0) break
                val c = read()
                if (c < 0) break
                b[off + n++] = c.toByte()
            }
            return if (n == 0) -1 else n
        }

        override fun close() = input.close()

        private fun emit(c: Int) {
            out[outEnd++] = c.toByte()
        }

        private fun emit(text: String) = text.forEach { emit(it.code) }

        /** True when [expected] comes next (and is then consumed); otherwise nothing is consumed. */
        private fun next(expected: String): Boolean {
            val read = IntArray(expected.length)
            for ((count, ch) in expected.withIndex()) {
                val c = input.read()
                if (c != ch.code) {
                    if (c >= 0) input.unread(c)
                    for (i in count - 1 downTo 0) input.unread(read[i])
                    return false
                }
                read[count] = c
            }
            return true
        }

        /** Translates the next token or byte into [out]; false at the end of the input. */
        private fun step(): Boolean {
            val c = input.read()
            if (c < 0) return false
            when (state) {
                State.NORMAL -> normal(c)
                State.IRI -> { emit(c); if (c == '>'.code) endTerm() }
                State.COMMENT -> { emit(c); if (c == '\n'.code || c == '\r'.code) state = State.NORMAL }
                State.SHORT_STRING -> string(c, long = false)
                State.LONG_STRING -> string(c, long = true)
            }
            return true
        }

        /**
         * True where a subject or a predicate comes next: at the start, after `.`, `{`, `}`, `;`, `[`, `{|`, `<<` or
         * `<<(`, and after a `PREFIX` / `BASE` / `VERSION` directive. A triple term is an object only, so [fromRdf12]
         * leaves a `<<(` there as it is, and Rio rejects it, instead of reading it as a reified triple.
         */
        private var subjectNext = true
        private var directive = false
        private var previous = 0
        private val word = StringBuilder()

        private fun normal(c: Int) {
            if (!nameChar(c) && c != '.'.code) endWord()
            when (c) {
                '<'.code -> when {
                    next("<") -> {
                        emit("<<")
                        when {
                            toRdf12 -> emit('('.code)
                            // A triple term where only a subject or a predicate can be: left for Rio to reject.
                            next("(") -> if (subjectNext) emit('('.code)
                        }
                        subjectNext = true
                    }
                    else -> { emit(c); state = State.IRI }
                }
                ')'.code -> { if (!toRdf12 && next(">>")) emit(">>") else emit(c); subjectNext = false }
                '>'.code -> { if (toRdf12 && next(">")) emit(")>>") else emit(c); subjectNext = false }
                '#'.code -> { emit(c); state = State.COMMENT }
                '"'.code, '\''.code -> {
                    emit(c)
                    quote = c
                    state = if (next("${c.toChar()}${c.toChar()}")) { emit(c); emit(c); State.LONG_STRING } else State.SHORT_STRING
                }
                '\\'.code -> { emit(c); input.read().let { if (it >= 0) emit(it) }; subjectNext = false }
                '.'.code -> {
                    emit(c)
                    val following = input.read().also { if (it >= 0) input.unread(it) }
                    val inWord = word.isNotEmpty() || following in '0'.code..'9'.code
                    if (inWord && nameChar(following)) { if (word.length < MAX_WORD) word.append('.') } else { endWord(); subjectNext = true; directive = false }
                }
                '{'.code, '}'.code, ';'.code, '['.code -> { emit(c); subjectNext = true }
                ' '.code, '\t'.code, '\n'.code, '\r'.code -> emit(c)
                else -> {
                    emit(c)
                    if (!nameChar(c)) subjectNext = false
                    else if (word.length < MAX_WORD) { if (word.isEmpty()) wordStartPrevious = previous; word.append(c.toChar()) }
                }
            }
            previous = c
        }

        /** Letters, digits and the other characters of prefixed names, blank node labels, numbers and keywords. */
        private fun nameChar(c: Int): Boolean =
            c >= 0x80 || c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '_'.code || c == '-'.code || c == ':'.code || c == '%'.code

        /** Ends the keyword, prefixed name, blank node label or number being read. */
        private fun endWord() {
            if (word.isEmpty()) return
            val keyword = word.toString().uppercase()
            val atSign = wordStartPrevious == '@'.code
            word.setLength(0)
            if (!atSign && (keyword == "PREFIX" || keyword == "BASE" || keyword == "VERSION")) {
                directive = true
                subjectNext = true
            } else if (!directive) {
                subjectNext = false
            }
        }

        /** The byte before the word being read (`@` makes it a language tag or an `@prefix` / `@base` keyword). */
        private var wordStartPrevious = 0

        /** An IRI or a string ended: an object or a directive's argument. */
        private fun endTerm() {
            state = State.NORMAL
            subjectNext = directive
            directive = false
        }

        private fun string(c: Int, long: Boolean) {
            emit(c)
            when {
                c == '\\'.code -> input.read().let { if (it >= 0) emit(it) }
                c != quote -> Unit
                !long -> endTerm()
                // A long string ends at the first three quotes (as in the grammar, and in Rio).
                next("${quote.toChar()}${quote.toChar()}") -> { emit(quote); emit(quote); endTerm() }
            }
        }

        private companion object {
            const val MAX_WORD = 8
        }
    }
}
