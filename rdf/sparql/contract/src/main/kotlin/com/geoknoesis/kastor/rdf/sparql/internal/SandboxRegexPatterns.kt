package com.geoknoesis.kastor.rdf.sparql.internal

import java.util.regex.Pattern

/**
 * **Not public API.** The shapes of regular expression a sandboxed query may use (see [SandboxRegex]).
 *
 * [SandboxRegex] counts the characters a matcher reads, but a backtracking matcher can also explore choices that read
 * nothing: alternatives and repetitions that match the empty string (`(|){40}x` or `(a?|b?){40}c` on an empty input
 * try 2^40 paths, all of them zero-width). Every such choice point is refused here, before the pattern is used:
 *
 * - a quantifier (`*`, `+`, `?`, `{n,m}`, except `{1}`) on something that can match the empty string;
 * - an alternation with two or more alternatives that can match the empty string;
 * - lookahead and lookbehind (`(?=`, `(?!`, `(?<=`, `(?<!`), which XPath regular expressions do not have;
 * - an inline comments flag (`(?x)`), and groups nested more than [MAX_GROUP_DEPTH] deep.
 *
 * What remains has, between two choices it explores, at least one counted read, so the step budget of
 * [SandboxRegex] bounds all its work. Back-references count as possibly empty (their group may be). A pattern compiled
 * with [Pattern.LITERAL] has no choice at all. A refused pattern fails with [SandboxLimitException].
 */
object SandboxRegexPatterns {

    /** Deepest nesting of groups a sandboxed pattern may have. */
    const val MAX_GROUP_DEPTH: Int = 64

    /** Fails with [SandboxLimitException] when [pattern] has a shape that lets the matcher work without reading. */
    @JvmStatic
    fun requireBounded(pattern: Pattern, what: String) {
        if (pattern.flags() and Pattern.LITERAL != 0) return
        Analysis(pattern.pattern(), pattern.flags() and Pattern.COMMENTS != 0, what).run()
    }

    private class Analysis(private val p: String, private val comments: Boolean, private val what: String) {
        private var i = 0

        fun run() {
            while (i < p.length) {
                alternation(0)
                // An unbalanced ')' is Java's syntax error; the pattern compiled, so skip it and go on.
                if (i < p.length) i++
            }
        }

        private fun refuse(why: String): Nothing =
            throw SandboxLimitException("$what: the regular expression \"${p.take(80)}\" is too complex for a sandboxed query ($why)")

        /** Whether the alternation that starts at [i] can match the empty string. */
        private fun alternation(depth: Int): Boolean {
            var empty = 0
            while (true) {
                if (sequence(depth)) empty++
                if (i < p.length && p[i] == '|') i++ else break
            }
            if (empty >= 2) refuse("alternatives that both match the empty string")
            return empty > 0
        }

        private fun sequence(depth: Int): Boolean {
            var empty = true
            while (true) {
                skipIgnored()
                if (i >= p.length || p[i] == '|' || p[i] == ')') return empty
                val atomEmpty = atom(depth)
                val quantifier = quantifier()
                if (quantifier != null && atomEmpty && !quantifier.once) refuse("a repetition of something that matches the empty string")
                empty = empty && (atomEmpty || quantifier?.optional == true)
            }
        }

        private class Quantifier(val optional: Boolean, val once: Boolean)

        private fun quantifier(): Quantifier? {
            skipIgnored()
            if (i >= p.length) return null
            val q = when (p[i]) {
                '*' -> { i++; Quantifier(optional = true, once = false) }
                '+' -> { i++; Quantifier(optional = false, once = false) }
                '?' -> { i++; Quantifier(optional = true, once = false) }
                '{' -> counted() ?: return null
                else -> return null
            }
            if (i < p.length && (p[i] == '?' || p[i] == '+')) i++ // reluctant or possessive
            return q
        }

        private fun counted(): Quantifier? {
            val close = p.indexOf('}', i)
            if (close < 0) return null
            val bounds = p.substring(i + 1, close).split(',')
            val min = bounds[0].trim().toLongOrNull() ?: return null
            val max = if (bounds.size > 1) bounds[1].trim().let { if (it.isEmpty()) Long.MAX_VALUE else it.toLongOrNull() ?: return null } else min
            i = close + 1
            return Quantifier(optional = min == 0L, once = min == 1L && max == 1L)
        }

        /** Consumes one atom and returns whether it can match the empty string. */
        private fun atom(depth: Int): Boolean {
            val c = p[i]
            return when (c) {
                '(' -> group(depth)
                '[' -> { charClass(); false }
                '\\' -> escape()
                '^', '$' -> { i++; true }
                else -> { i++; false }
            }
        }

        private fun group(depth: Int): Boolean {
            if (depth >= MAX_GROUP_DEPTH) refuse("groups nested more than $MAX_GROUP_DEPTH deep")
            i++ // (
            if (i < p.length && p[i] == '?') {
                i++
                when {
                    p.startsWith("=", i) || p.startsWith("!", i) || p.startsWith("<=", i) || p.startsWith("<!", i) ->
                        refuse("a lookahead or lookbehind")
                    p.startsWith("<", i) -> i = p.indexOf('>', i).let { if (it < 0) p.length else it + 1 } // named group
                    p.startsWith(":", i) || p.startsWith(">", i) -> i++
                    else -> {
                        // Inline flags: (?idmsux-idmsux) or (?idmsux-idmsux:X).
                        val start = i
                        while (i < p.length && p[i] != ')' && p[i] != ':') i++
                        if ('x' in p.substring(start, i)) refuse("an inline comments flag")
                        if (i < p.length && p[i] == ')') {
                            i++
                            return true
                        }
                        i++ // :
                    }
                }
            }
            val empty = alternation(depth + 1)
            if (i < p.length && p[i] == ')') i++
            return empty
        }

        private fun charClass() {
            var depth = 0
            i++ // [
            if (i < p.length && p[i] == '^') i++
            if (i < p.length && p[i] == ']') i++ // a leading ] is a literal
            while (i < p.length) {
                when (p[i]) {
                    '\\' -> i += 2
                    '[' -> { depth++; i++ }
                    ']' -> {
                        i++
                        if (depth == 0) return
                        depth--
                    }
                    else -> i++
                }
            }
        }

        private fun escape(): Boolean {
            i++ // \
            if (i >= p.length) return false
            val c = p[i++]
            return when (c) {
                'Q' -> {
                    val end = p.indexOf("\\E", i)
                    val empty = end == i
                    i = if (end < 0) p.length else end + 2
                    empty
                }
                in '1'..'9' -> {
                    while (i < p.length && p[i].isDigit()) i++
                    true
                }
                'k' -> {
                    i = p.indexOf('>', i).let { if (it < 0) p.length else it + 1 }
                    true
                }
                'p', 'P', 'x', 'N' -> {
                    if (i < p.length && p[i] == '{') i = p.indexOf('}', i).let { if (it < 0) p.length else it + 1 } else if (c != 'N') i++
                    false
                }
                'u' -> { i = minOf(p.length, i + 4); false }
                '0' -> { while (i < p.length && p[i] in '0'..'7') i++; false }
                'c' -> { i++; false }
                'b', 'B', 'A', 'z', 'Z', 'G' -> true
                else -> false
            }
        }

        /** In comments mode, whitespace and `#` comments outside a class are not part of the pattern. */
        private fun skipIgnored() {
            if (!comments) return
            while (i < p.length) {
                when {
                    p[i].isWhitespace() -> i++
                    p[i] == '#' -> while (i < p.length && p[i] != '\n') i++
                    else -> return
                }
            }
        }
    }
}
