package com.geoknoesis.kastor.rdf.sparql.internal

/**
 * Translates one XPath / XML Schema regular expression (see [XPathRegex]) from index `start` of `pattern` into RE2
 * syntax. Every character class is computed into a [CodePointSet] and written out as explicit ranges, so the RE2
 * pattern is compiled without flags: `i`, `m`, `s` and `x` are applied here.
 *
 * Besides the text, the translation measures what RE2 will compile: [Translation.size] estimates its program
 * (repetitions multiply), and the group nesting is bounded, so a hostile pattern is refused before RE2 builds it.
 */
internal class XPathRegexTranslator(
    pattern: String,
    start: Int,
    ignoreCase: Boolean,
    private val multiLine: Boolean,
    private val dotAll: Boolean,
    private val extended: Boolean,
    javaComments: Boolean,
) : XPathRegexLexer(pattern, start, ignoreCase, javaComments) {

    /** The RE2 text, its capturing groups and the estimated size of its program. */
    class Translation(val re2: String, val groups: Int, val size: Long)

    private val out = StringBuilder(pattern.length + 16)
    private var groups = 0
    private var depth = 0

    fun translate(): Translation {
        val size = regExp()
        if (i < p.length) reject("unbalanced ')' at index $i")
        return Translation(out.toString(), groups, size)
    }

    /** `regExp ::= branch ( '|' branch )*`. */
    private fun regExp(): Long {
        var size = branch()
        while (i < p.length && p[i] == '|') {
            out.append('|')
            i++
            size = bounded(size + branch() + 1)
        }
        return size
    }

    /** `branch ::= piece*`, up to `|`, `)` or the end. */
    private fun branch(): Long {
        var size = 0L
        while (true) {
            skipIgnored()
            if (i >= p.length || p[i] == '|' || p[i] == ')') return size
            size = bounded(size + piece())
        }
    }

    /** `piece ::= atom quantifier?`. */
    private fun piece(): Long {
        val atom = atom()
        if (out.length > MAX_TRANSLATED_LENGTH) {
            refuse("the pattern is too large: its translation passes $MAX_TRANSLATED_LENGTH characters")
        }
        skipIgnored()
        return quantifier(atom)
    }

    /** Whitespace under the `x` flag; under a leading `(?x)`, a `#` (a comment in java.util.regex) is refused. */
    private fun skipIgnored() {
        while (i < p.length) {
            val c = p[i]
            when {
                extended && isPatternWhitespace(c.code) -> i++
                javaComments && c == '#' -> refuse(
                    "unescaped '#' at index $i after a leading (?x): java.util.regex reads it as the start of a comment, " +
                        "XPath regular expressions as a literal; write \\# for a literal, or remove the comment",
                )
                else -> return
            }
        }
    }

    /** One atom at [i], written to [out]; its estimated program size. */
    private fun atom(): Long {
        when (val c = p[i]) {
            '(' -> return group()
            '[' -> set(charClass())
            '\\' -> return escapeAtom()
            '.' -> { i++; set(if (dotAll) CodePointSet.ALL else UnicodeSets.NOT_LINE_END) }
            '^' -> { i++; out.append(if (multiLine) "(?m:^)" else "(?:\\A)") }
            '$' -> { i++; out.append(if (multiLine) "(?m:$)" else "(?:\\z)") }
            '*', '+', '?' -> reject("'$c' at index $i has nothing to repeat")
            '{' -> reject("'{' at index $i does not start a quantifier: write \\{ for a literal brace")
            else -> {
                val cp = p.codePointAt(i)
                i += Character.charCount(cp)
                literal(cp)
            }
        }
        return 1
    }

    private fun escapeAtom(): Long = when (val e = escape()) {
        is Escape.Single -> { literal(e.cp); 1 }
        is Escape.Chars -> { set(e.set); 1 }
        is Escape.Assertion -> { out.append(e.re2); 1 }
        // As in java.util.regex, a quantifier after `\Q...\E` applies to its last character.
        is Escape.Quoted -> if (e.codePoints.isEmpty()) {
            out.append("(?:)")
            1
        } else {
            e.codePoints.forEach(::literal)
            e.codePoints.size.toLong()
        }
    }

    /** A group at [i]: capturing, non-capturing, or a java.util.regex construct RE2 lacks (refused). */
    private fun group(): Long {
        val open = i
        if (p.startsWith("(?", i)) groupPrefix(open) else { out.append('('); groups++; i++ }
        if (++depth > MAX_GROUP_DEPTH) refuse("groups are nested more than $MAX_GROUP_DEPTH levels deep at index $open")
        val size = regExp()
        if (i >= p.length || p[i] != ')') reject("the group opened at index $open is not closed: ')' expected")
        i++
        depth--
        out.append(')')
        return bounded(size + 1)
    }

    /** The `(?...` prefix of a group at [open]: `(?:` and java.util.regex named groups; everything else is refused. */
    private fun groupPrefix(open: Int) {
        val rest = p.substring(open + 2, minOf(p.length, open + 4))
        when {
            rest.startsWith(":") -> { out.append("(?:"); i = open + 3 }
            rest.startsWith("=") || rest.startsWith("!") || rest.startsWith("<=") || rest.startsWith("<!") -> refuse(
                "the lookaround at index $open is not supported: patterns run on a linear-time engine (RE2), which has " +
                    "no lookahead or lookbehind (XPath regular expressions have none either)",
            )
            rest.startsWith(">") -> refuse("the atomic group at index $open is not supported (java.util.regex syntax that the linear-time engine does not have)")
            rest.startsWith("<") || rest.startsWith("P<") -> {
                // A java.util.regex named group is an ordinary capturing group (XPath has no names).
                val close = p.indexOf('>', open)
                if (close < 0) reject("the named group at index $open is not terminated: '>' expected")
                out.append('(')
                groups++
                i = close + 1
            }
            else -> rejectFlagGroup(open)
        }
    }

    private fun rejectFlagGroup(open: Int): Nothing {
        var end = open + 2
        while (end < p.length && p[end] in INLINE_FLAG_CHARACTERS) end++
        if (end == open + 2 || end >= p.length || (p[end] != ')' && p[end] != ':')) {
            refuse("the group syntax \"${p.substring(open, minOf(p.length, open + 3))}\" at index $open is not supported")
        }
        refuse(
            "inline flag group \"${p.substring(open, end + 1)}\" at index $open is java.util.regex syntax that XPath regular " +
                "expressions do not have, and the anchors and the dot are translated according to the flags of the whole " +
                "pattern. Only groups of the flags i, m, s, x at the very start of the pattern are supported, e.g. " +
                "(?i)^abc\$ (they are merged with the flags); otherwise set the flags (sh:flags, or the flags argument of " +
                "REGEX / REPLACE) instead",
        )
    }

    /**
     * The quantifier at [i], if any, applied to the atom just written (of estimated size [atom]): `*`, `+`, `?` or
     * `{n}`, `{n,}`, `{n,m}` (counts up to [MAX_REPEAT], RE2's limit), optionally reluctant (`?`). A possessive
     * quantifier (`+` after it) is refused.
     */
    private fun quantifier(atom: Long): Long {
        if (i >= p.length) return atom
        val at = i
        val (min, max) = when (p[i]) {
            '*' -> { i++; 0 to -1 }
            '+' -> { i++; 1 to -1 }
            '?' -> { i++; 0 to 1 }
            '{' -> counts()
            else -> return atom
        }
        out.append(p, at, i)
        if (i < p.length && p[i] == '?') { out.append('?'); i++ }
        if (i < p.length && p[i] == '+') {
            refuse("the possessive quantifier at index $at is not supported (java.util.regex syntax that the linear-time engine does not have)")
        }
        val copies = if (max < 0) maxOf(min, 1) + 1L else maxOf(max, 1).toLong()
        return bounded(atom * copies + 1)
    }

    /** `{n}`, `{n,}` or `{n,m}` at [i]. */
    private fun counts(): Pair<Int, Int> {
        val at = i
        val close = p.indexOf('}', i)
        val body = if (close < 0) null else p.substring(i + 1, close)
        val match = body?.let { QUANTITY.matchEntire(it) }
            ?: reject("'{' at index $at does not start a quantifier ({n}, {n,} or {n,m}): write \\{ for a literal brace")
        val min = count(match.groupValues[1], at)
        val max = when {
            match.groupValues[2].isEmpty() -> min
            match.groupValues[3].isEmpty() -> -1
            else -> count(match.groupValues[3], at)
        }
        if (max in 0 until min) reject("the quantifier at index $at has a maximum below its minimum")
        i = close + 1
        return min to max
    }

    private fun count(digits: String, at: Int): Int {
        val value = digits.toBigInteger()
        if (value > MAX_REPEAT.toBigInteger()) {
            refuse("the quantifier at index $at repeats more than $MAX_REPEAT times, the most the linear-time engine supports")
        }
        return value.toInt()
    }

    /** One literal character; under `i`, the class of its case variants. */
    private fun literal(cp: Int) {
        val orbit = if (ignoreCase) UnicodeSets.caseOrbit(cp) else null
        if (orbit != null) set(CodePointSet.of(*orbit)) else appendChar(cp)
    }

    /** [set] as one RE2 atom: a character, or a (possibly negated) class of explicit ranges. */
    private fun set(set: CodePointSet) {
        val single = set.single()
        if (single >= 0) return appendChar(single)
        val complement = set.complement()
        if (set.isEmpty) {
            out.append("[^\\x{0}-\\x{10ffff}]")
        } else if (complement.isEmpty) {
            out.append("[\\x{0}-\\x{10ffff}]")
        } else if (complement.rangeCount < set.rangeCount) {
            out.append("[^")
            appendRanges(complement)
            out.append(']')
        } else {
            out.append('[')
            appendRanges(set)
            out.append(']')
        }
    }

    private fun appendRanges(set: CodePointSet) = set.forEachRange { a, b ->
        appendChar(a)
        if (b > a) {
            if (b > a + 1) out.append('-')
            appendChar(b)
        }
    }

    /** One code point as RE2 text: ASCII letters and digits as they are, everything else as `\x{h}`. */
    private fun appendChar(cp: Int) {
        if (cp < 0x80 && Character.isLetterOrDigit(cp)) out.append(cp.toChar()) else out.append("\\x{").append(Integer.toHexString(cp)).append('}')
    }

    /** [size] saturated just above [MAX_PROGRAM_SIZE], refusing the pattern once it passes it. */
    private fun bounded(size: Long): Long {
        if (size > MAX_PROGRAM_SIZE) refuse("the pattern is too large: it would compile to more than $MAX_PROGRAM_SIZE instructions (repetitions multiply)")
        return size
    }

    companion object {
        /** The deepest nesting of groups: RE2 compiles a pattern recursively. */
        const val MAX_GROUP_DEPTH = 64

        /** The largest repetition count, as in RE2. */
        const val MAX_REPEAT = 1000

        /** The largest estimated program of a pattern (a literal or a class is one instruction; repetitions multiply). */
        const val MAX_PROGRAM_SIZE = 50_000L

        /** The longest RE2 text a pattern may translate to (classes are written out as ranges). */
        const val MAX_TRANSLATED_LENGTH = 4 * 1024 * 1024

        /** `java.util.regex` inline flag letters (`(?idmsuxU-idmsuxU)`). */
        private const val INLINE_FLAG_CHARACTERS = "idmsuxU-"

        private val QUANTITY = Regex("""(\d+)(,(\d*))?""")
    }
}
