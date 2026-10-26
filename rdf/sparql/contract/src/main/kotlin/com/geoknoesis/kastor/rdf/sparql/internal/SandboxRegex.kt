package com.geoknoesis.kastor.rdf.sparql.internal

/**
 * **Not public API.** Regular-expression evaluation for sandboxed queries (see [SandboxLimits]) and SHACL shapes:
 * `REGEX` / `fn:matches` ([find]) and `REPLACE` / `fn:replace` ([replace]) with an [XPathPattern], which runs on RE2/J,
 * a linear-time engine. No pattern can backtrack: a match costs at most the size of the compiled program per input
 * character, so the bound of a regular expression is the bound of its input. The input is read through a counter
 * that runs `check` (the caller's deadline and cancellation) at an interval that shrinks as the program grows, so a
 * long input stops at the deadline too.
 *
 * [replace] follows `fn:replace`: `$N` refers to group N (the longest number of digits that names a group; `$N` of a
 * group that did not take part, or of a missing group below 10, is empty), `\$` and `\\` are a literal `$` and `\`,
 * and any other `$` or `\` is an error ([XPathRegexException]). It also bounds its output: the size of each
 * replacement is computed from group offsets before anything is appended, so no text beyond
 * [SandboxLimits.MAX_STRING_LENGTH] is ever built.
 */
object SandboxRegex {

    /**
     * [pattern] with [flags], compiled for [find] and [replace] (see [XPathRegex.compile]).
     *
     * @throws XPathRegexException when the pattern or the flags are invalid: an error of the expression.
     * @throws SandboxLimitException when the pattern is valid but cannot be evaluated as meant on the linear-time engine
     *   (a back-reference, a lookaround, a structural limit, ...): the sandboxed query fails, fail closed.
     */
    @JvmStatic
    @Throws(XPathRegexException::class, SandboxLimitException::class)
    fun compile(pattern: String, flags: String?): XPathPattern = try {
        XPathRegex.compile(pattern, flags)
    } catch (e: XPathRegexException) {
        if (e.unsupported) throw SandboxLimitException(e.message.orEmpty()) else throw e
    }

    /** Whether [pattern] matches somewhere in [text] (SPARQL `REGEX`, XPath `fn:matches`). */
    @JvmStatic
    fun find(pattern: XPathPattern, text: String, check: Runnable): Boolean =
        pattern.re2.matcher(Budgeted(text, check, pattern.programSize)).find()

    /**
     * [text] with every match of [pattern] replaced by [replacement] (`fn:replace` syntax), or null when nothing
     * matched. With [skipEmptyAfterFirst] (Jena's `REPLACE`), an empty match after the first match is not replaced;
     * otherwise (RDF4J's) every match is.
     *
     * @throws XPathRegexException when [replacement] is malformed (`fn:replace` error FORX0004).
     */
    @JvmStatic
    @Throws(XPathRegexException::class)
    fun replace(pattern: XPathPattern, text: String, replacement: String, skipEmptyAfterFirst: Boolean, check: Runnable): String? {
        val parts = Replacement.parse(replacement, pattern.groupCount)
        val matcher = pattern.re2.matcher(Budgeted(text, check, pattern.programSize))
        var out: StringBuilder? = null
        var copied = 0
        var work = 0L
        while (matcher.find()) {
            // Expanding a replacement costs its parts, whatever the length of the match (an empty one costs as much).
            work += parts.size
            if (work >= SandboxLimits.CHECK_INTERVAL) {
                work = 0
                check.run()
            }
            if (out == null) out = StringBuilder() else if (skipEmptyAfterFirst && matcher.start() == matcher.end()) continue
            // The text before the match, the expansion, and the rest of the text: measured before anything is copied.
            val before = out.length + (matcher.start() - copied).toLong() + (text.length - matcher.end())
            SandboxLimits.checkStringLength(before + parts.expandedLength(matcher), "REPLACE")
            out.append(text, copied, matcher.start())
            parts.appendTo(out, text, matcher)
            copied = matcher.end()
        }
        return out?.append(text, copied, text.length)?.toString()
    }

    /** A parsed `fn:replace` replacement: literal text and group references. */
    private class Replacement private constructor(private val literals: List<String>, private val groupRefs: IntArray) {
        // literals[k] precedes groupRefs[k]; the last literal follows the last reference.

        /** How many parts expanding the replacement walks (at least 1). */
        val size: Int get() = literals.size + groupRefs.size

        fun expandedLength(m: com.google.re2j.Matcher): Long {
            var length = literals.sumOf { it.length.toLong() }
            for (g in groupRefs) length += groupLength(m, g)
            return length
        }

        fun appendTo(out: StringBuilder, text: String, m: com.google.re2j.Matcher) {
            for (k in groupRefs.indices) {
                out.append(literals[k])
                val g = groupRefs[k]
                if (g >= 0 && m.start(g) >= 0) out.append(text, m.start(g), m.end(g))
            }
            out.append(literals.last())
        }

        private fun groupLength(m: com.google.re2j.Matcher, g: Int): Long =
            if (g < 0 || m.start(g) < 0) 0L else (m.end(g) - m.start(g)).toLong()

        companion object {
            /** [replacement] for a pattern of [groups] capturing groups; a reference to nothing is -1 (empty). */
            fun parse(replacement: String, groups: Int): Replacement {
                val literals = mutableListOf<String>()
                val refs = mutableListOf<Int>()
                val current = StringBuilder()
                var i = 0
                while (i < replacement.length) {
                    val c = replacement[i]
                    when {
                        c == '\\' && i + 1 < replacement.length && (replacement[i + 1] == '\\' || replacement[i + 1] == '$') -> {
                            current.append(replacement[i + 1])
                            i += 2
                        }
                        c == '\\' -> throw XPathRegexException(replacement, "a '\\' in a replacement must be followed by '\\' or '$' (write \\\\ for a backslash)")
                        c == '$' && i + 1 < replacement.length && replacement[i + 1] in '0'..'9' -> {
                            val (group, end) = reference(replacement, i + 1, groups)
                            literals.add(current.toString())
                            current.setLength(0)
                            refs.add(group)
                            // fn:replace: digits that would name a group beyond the last are literal text.
                            current.append(replacement, end.first, end.last + 1)
                            i = end.last + 1
                        }
                        c == '$' -> throw XPathRegexException(replacement, "a '$' in a replacement must be followed by a digit (write \\$ for a dollar sign)")
                        else -> { current.append(c); i++ }
                    }
                }
                literals.add(current.toString())
                return Replacement(literals, refs.toIntArray())
            }

            /**
             * The group of the reference whose digits start at [from], and the range of its trailing digits that stay
             * literal (empty when none): `fn:replace` takes all the digits, then drops the last one while the number
             * is above both 9 and [groups]. A number at most 9 that names no group is an empty reference (-1).
             */
            private fun reference(replacement: String, from: Int, groups: Int): Pair<Int, IntRange> {
                var end = from
                while (end < replacement.length && replacement[end] in '0'..'9') end++
                var digits = end - from
                while (digits > 1 && (digits > 10 || replacement.substring(from, from + digits).toInt().let { it > 9 && it > groups })) digits--
                val number = replacement.substring(from, from + digits).toInt()
                val group = if (number <= groups) number else -1
                return group to (from + digits until end)
            }
        }
    }

    /**
     * [text], read through a counter: [check] runs every `max(16, CHECK_INTERVAL * 64 / programSize)` characters read
     * (at most every [SandboxLimits.CHECK_INTERVAL]), so that the work between two checks stays about the same however
     * large the compiled program. Sub-sequences are plain strings.
     */
    private class Budgeted(private val text: String, private val check: Runnable, programSize: Int) : CharSequence {
        private val interval = (SandboxLimits.CHECK_INTERVAL * 64L / programSize.coerceAtLeast(1))
            .coerceIn(16L, SandboxLimits.CHECK_INTERVAL.toLong()).toInt()
        private var untilCheck = interval

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (--untilCheck == 0) {
                untilCheck = interval
                check.run()
            }
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.substring(startIndex, endIndex)

        override fun toString(): String = text
    }
}
