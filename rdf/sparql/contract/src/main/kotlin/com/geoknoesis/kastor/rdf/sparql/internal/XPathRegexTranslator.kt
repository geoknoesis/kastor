package com.geoknoesis.kastor.rdf.sparql.internal

/**
 * Translates one XPath / XML Schema regular expression (see [XPathRegex]) from index `start` of `pattern` into RE2
 * syntax. Every character class is computed into a [CodePointSet] and written out as explicit ranges, so the RE2
 * pattern is compiled without flags: `i`, `m`, `s` and `x` are applied here.
 *
 * Besides the text, the translation measures what RE2 will build, so that a hostile pattern is refused before RE2
 * sees it (the structural cost model; every bound is a count, never a duration):
 * - **parsing**: RE2/J's parser copies the rest of the pattern text for most syntax elements it reads, so parsing
 *   costs about (elements x length of the text). The translation counts the elements it writes (each character,
 *   range endpoint, group, alternative, quantifier and assertion) and refuses the pattern as soon as elements x length
 *   passes [MAX_PARSE_COST], or the text passes [MAX_TRANSLATED_LENGTH] characters;
 * - **the program**: [Translation.size] estimates it (repetitions multiply) and refuses it above [MAX_PROGRAM_SIZE];
 * - **recursion**: the group nesting and the class subtraction nesting are bounded.
 *
 * [check] (a deadline, a cancellation) runs every [CHECK_ELEMENTS] elements, and in long character classes.
 */
internal class XPathRegexTranslator(
    pattern: String,
    start: Int,
    ignoreCase: Boolean,
    private val multiLine: Boolean,
    private val dotAll: Boolean,
    private val extended: Boolean,
    javaComments: Boolean,
    check: Runnable = XPathRegex.NO_CHECK,
) : XPathRegexLexer(pattern, start, ignoreCase, javaComments, check) {

    /** The RE2 text, its capturing groups and the estimated size of its program. */
    class Translation(val re2: String, val groups: Int, val size: Long)

    private val out = StringBuilder(minOf(pattern.length, MAX_TRANSLATED_LENGTH) + 16)
    private var groups = 0
    private var depth = 0

    /** The syntax elements written to [out] (what RE2/J's parser pays the length of the rest of the text for). */
    private var elements = 0L

    fun translate(): Translation {
        val size = regExp()
        if (i < p.length) reject("unbalanced ')' at index $i")
        return Translation(out.toString(), groups, size)
    }

    /** One syntax element was just written to [out]: the parse cost is checked, and [check] runs now and then. */
    private fun element() {
        elements++
        if (out.length > MAX_TRANSLATED_LENGTH || elements * out.length > MAX_PARSE_COST) {
            refuse(
                "the pattern is too complex to compile: its translation for the linear-time engine has $elements syntax " +
                    "elements and ${out.length} characters so far, and parsing costs their product, which may be at most " +
                    "$MAX_PARSE_COST (large character classes such as \\p{L} or \\w count each of their ranges; " +
                    "simplify the pattern or bound it with fewer classes)",
            )
        }
        if (elements % CHECK_ELEMENTS == 0L) check.run()
    }

    private fun emit(text: String) {
        out.append(text)
        element()
    }

    private fun emit(c: Char) {
        out.append(c)
        element()
    }

    /** `regExp ::= branch ( '|' branch )*`. */
    private fun regExp(): Long {
        var size = branch()
        while (i < p.length && p[i] == '|') {
            emit('|')
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
            '^' -> { i++; emit(if (multiLine) "(?m:^)" else "(?:\\A)") }
            '$' -> { i++; emit(if (multiLine) "(?m:$)" else "(?:\\z)") }
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
        is Escape.Assertion -> { emit(e.re2); 1 }
        // As in java.util.regex, a quantifier after `\Q...\E` applies to its last character.
        is Escape.Quoted -> if (e.codePoints.isEmpty()) {
            emit("(?:)")
            1
        } else {
            e.codePoints.forEach(::literal)
            e.codePoints.size.toLong()
        }
    }

    /** A group at [i]: capturing, non-capturing, or a java.util.regex construct RE2 lacks (refused). */
    private fun group(): Long {
        val open = i
        if (p.startsWith("(?", i)) groupPrefix(open) else { emit('('); groups++; i++ }
        if (++depth > MAX_GROUP_DEPTH) refuse("groups are nested more than $MAX_GROUP_DEPTH levels deep at index $open")
        val size = regExp()
        if (i >= p.length || p[i] != ')') reject("the group opened at index $open is not closed: ')' expected")
        i++
        depth--
        emit(')')
        return bounded(size + 1)
    }

    /** The `(?...` prefix of a group at [open]: `(?:` and java.util.regex named groups; everything else is refused. */
    private fun groupPrefix(open: Int) {
        val rest = p.substring(open + 2, minOf(p.length, open + 4))
        when {
            rest.startsWith(":") -> { emit("(?:"); i = open + 3 }
            rest.startsWith("=") || rest.startsWith("!") || rest.startsWith("<=") || rest.startsWith("<!") -> refuse(
                "the lookaround at index $open is not supported: patterns run on a linear-time engine (RE2), which has " +
                    "no lookahead or lookbehind (XPath regular expressions have none either)",
            )
            rest.startsWith(">") -> refuse("the atomic group at index $open is not supported (java.util.regex syntax that the linear-time engine does not have)")
            rest.startsWith("<") || rest.startsWith("P<") -> {
                // A java.util.regex named group is an ordinary capturing group (XPath has no names).
                val close = p.indexOf('>', open)
                if (close < 0) reject("the named group at index $open is not terminated: '>' expected")
                emit('(')
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
        // Counts in canonical form: RE2 reads a count with a leading zero ({01}) as literal text.
        emit(
            when {
                p[at] != '{' -> p.substring(at, i)
                min == max -> "{$min}"
                max < 0 -> "{$min,}"
                else -> "{$min,$max}"
            },
        )
        if (i < p.length && p[i] == '?') { emit('?'); i++ }
        if (i < p.length && p[i] == '+') {
            refuse("the possessive quantifier at index $at is not supported (java.util.regex syntax that the linear-time engine does not have)")
        }
        val copies = if (max < 0) maxOf(min, 1) + 1L else maxOf(max, 1).toLong()
        return bounded(atom * copies + 1)
    }

    /** `{n}`, `{n,}` or `{n,m}` at [i] (XML Schema: `[0-9]+`, leading zeros allowed); read in one linear scan. */
    private fun counts(): Pair<Int, Int> {
        val at = i
        val minEnd = digitsEnd(at + 1)
        val comma = minEnd < p.length && p[minEnd] == ','
        val maxEnd = if (comma) digitsEnd(minEnd + 1) else minEnd
        val close = if (comma) maxEnd else minEnd
        if (minEnd == at + 1 || close >= p.length || p[close] != '}') {
            reject("'{' at index $at does not start a quantifier ({n}, {n,} or {n,m}): write \\{ for a literal brace")
        }
        val min = count(at + 1, minEnd, at)
        val max = when {
            !comma -> min
            maxEnd == minEnd + 1 -> -1
            else -> count(minEnd + 1, maxEnd, at)
        }
        if (max in 0 until min) reject("the quantifier at index $at has a maximum below its minimum")
        i = close + 1
        return min to max
    }

    private fun digitsEnd(from: Int): Int {
        var k = from
        while (k < p.length && p[k] in '0'..'9') k++
        return k
    }

    /** The count written by the digits from [from] to [end]: compared by its significant digits, never parsed whole. */
    private fun count(from: Int, end: Int, at: Int): Int {
        var significant = from
        while (significant < end - 1 && p[significant] == '0') significant++
        val value = if (end - significant > MAX_REPEAT_DIGITS) Int.MAX_VALUE else p.substring(significant, end).toInt()
        if (value > MAX_REPEAT) {
            refuse("the quantifier at index $at repeats more than $MAX_REPEAT times, the most the linear-time engine supports")
        }
        return value
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
            emit("[^\\x{0}-\\x{10ffff}]")
        } else if (complement.isEmpty) {
            emit("[\\x{0}-\\x{10ffff}]")
        } else if (complement.rangeCount < set.rangeCount) {
            emit("[^")
            appendRanges(complement)
            emit(']')
        } else {
            emit('[')
            appendRanges(set)
            emit(']')
        }
    }

    private fun appendRanges(set: CodePointSet) = set.forEachRange { a, b ->
        appendChar(a)
        if (b > a) {
            if (b > a + 1) emit('-')
            appendChar(b)
        }
    }

    /**
     * One code point as RE2 text, one syntax element: ASCII letters and digits, and the visible characters outside
     * ASCII (never RE2 syntax), as they are, which keeps the text of a large class short; everything else (ASCII
     * punctuation, controls, separators, format characters, surrogates, private use and unassigned code points) as
     * `\x{h}`.
     */
    private fun appendChar(cp: Int) {
        if ((cp < 0x80 && Character.isLetterOrDigit(cp)) || (cp >= 0x80 && Character.getType(cp) in VISIBLE_TYPES)) {
            out.appendCodePoint(cp)
        } else {
            out.append("\\x{").append(Integer.toHexString(cp)).append('}')
        }
        element()
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
        const val MAX_TRANSLATED_LENGTH = 64 * 1024

        /**
         * The largest parse cost of a translation: its syntax elements times its length in characters. RE2/J parses
         * a text of this cost in a few tens of milliseconds; the cost of a pattern is a count, so whether it is
         * accepted does not depend on the machine. About eight `\p{L}` classes, or 16,000 literal characters, fit.
         */
        const val MAX_PARSE_COST = 1L shl 28

        /** [check] runs every this many syntax elements. */
        const val CHECK_ELEMENTS = 256L

        /** A count of more significant digits than this is above [MAX_REPEAT]. */
        private const val MAX_REPEAT_DIGITS = 4

        /** `java.util.regex` inline flag letters (`(?idmsuxU-idmsuxU)`). */
        private const val INLINE_FLAG_CHARACTERS = "idmsuxU-"

        /** The general categories whose characters outside ASCII are written to RE2 as they are (visible ones). */
        private val VISIBLE_TYPES: Set<Int> = setOf(
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
            Character.OTHER_LETTER, Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER, Character.CONNECTOR_PUNCTUATION,
            Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
            Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        ).mapTo(HashSet()) { it.toInt() }
    }
}
