package com.geoknoesis.kastor.rdf.sparql.internal

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * **Not public API.** Regular-expression evaluation for sandboxed queries (see [SandboxLimits]): `REGEX` / `fn:matches`
 * ([find]) and `REPLACE` / `fn:replace` ([replace]) with the provider's own compiled [Pattern] (its flags and syntax),
 * but over an input that counts the characters the matcher reads. Java's matcher backtracks, so a catastrophic
 * pattern such as `((a+)+)+$` reads an input exponentially often: every [SandboxLimits.CHECK_INTERVAL] reads `check`
 * runs (the caller's deadline and cancellation), and more than [SandboxLimits.MAX_REGEX_STEPS] reads fail with
 * [SandboxLimitException], so the evaluation ends whatever the timeout. A pattern too deep for the matcher's
 * recursion (a `StackOverflowError`) fails the same way instead of unwinding the thread.
 *
 * [replace] also bounds its output: the size of each replacement is computed (from the offsets of the groups it refers
 * to) before it is appended, so no replacement text beyond [SandboxLimits.MAX_STRING_LENGTH] is ever built.
 *
 * Reads are not the only work of a backtracking matcher: alternatives and repetitions that match the empty string can
 * be explored exponentially often without reading a character (`(|){40}x` on an empty input). Every pattern is
 * therefore first checked by [SandboxRegexPatterns.requireBounded], which refuses the shapes that allow such work, so
 * that every choice the matcher explores costs at least one counted read.
 */
object SandboxRegex {

    /** Whether [pattern] matches somewhere in [text] (SPARQL `REGEX`, XPath `fn:matches`). */
    @JvmStatic
    fun find(pattern: Pattern, text: String, check: Runnable): Boolean = bounded("REGEX") {
        SandboxRegexPatterns.requireBounded(pattern, "REGEX")
        pattern.matcher(Budgeted(text, check)).find()
    }

    /**
     * [text] with every match of [pattern] replaced by [replacement] (Java's replacement syntax: `$n`, `${name}`, `\`
     * escapes), or null when nothing matched. With [skipEmptyAfterFirst] (Jena's `REPLACE`), an empty match after the
     * first match is not replaced; otherwise (RDF4J's, Java's `replaceAll`) every match is. A replacement that refers
     * to a group the pattern does not have is an [IllegalArgumentException] or [IndexOutOfBoundsException], as in
     * Java; the provider turns those into SPARQL errors.
     */
    @JvmStatic
    fun replace(pattern: Pattern, text: String, replacement: String, skipEmptyAfterFirst: Boolean, check: Runnable): String? =
        bounded("REPLACE") {
            SandboxRegexPatterns.requireBounded(pattern, "REPLACE")
            val matcher = pattern.matcher(Budgeted(text, check))
            var out: StringBuilder? = null
            var copied = 0
            while (matcher.find()) {
                if (out == null) out = StringBuilder() else if (skipEmptyAfterFirst && matcher.start() == matcher.end()) continue
                // What appendReplacement adds: the text before the match, then the expanded replacement. Both are
                // measured from the match's offsets (nothing is copied) and checked before anything is appended.
                val before = out.length + (matcher.start() - copied).toLong() + (text.length - matcher.end())
                SandboxLimits.checkStringLength(before + expandedLength(matcher, replacement, before, check), "REPLACE")
                matcher.appendReplacement(out, replacement)
                copied = matcher.end()
            }
            out?.let { matcher.appendTail(it).toString() }
        }

    /**
     * An upper bound of the length of [replacement] expanded for the current match of [matcher], read as
     * `Matcher.appendReplacement` reads it; a malformed reference counts as its own text (`appendReplacement` then
     * fails on it). A group's length is taken from its offsets, never by copying it, and the sum fails with
     * [SandboxLimitException] as soon as [base] plus it passes [SandboxLimits.MAX_STRING_LENGTH]; [check] runs every
     * [SandboxLimits.CHECK_INTERVAL] characters of [replacement] read.
     */
    private fun expandedLength(matcher: Matcher, replacement: String, base: Long, check: Runnable): Long {
        var length = 0L
        var i = 0
        var sinceCheck = 0
        fun add(n: Long) {
            length += n
            SandboxLimits.checkStringLength(base + length, "REPLACE")
        }
        while (i < replacement.length) {
            val start = i
            val c = replacement[i]
            when {
                c == '\\' -> { add(1); i += 2 }
                c == '$' && i + 1 < replacement.length && replacement[i + 1] == '{' -> {
                    val close = replacement.indexOf('}', i + 2)
                    if (close < 0) {
                        add((replacement.length - i).toLong())
                        return length
                    }
                    add(groupLength { matcher.end(replacement.substring(i + 2, close)) - matcher.start(replacement.substring(i + 2, close)) })
                    i = close + 1
                }
                c == '$' && i + 1 < replacement.length && replacement[i + 1].isAsciiDigit() -> {
                    // As appendReplacement: the first digit, then more digits while the group number stays valid.
                    var group = replacement[i + 1] - '0'
                    var j = i + 2
                    while (j < replacement.length && replacement[j].isAsciiDigit()) {
                        val next = group * 10 + (replacement[j] - '0')
                        if (next > matcher.groupCount()) break
                        group = next
                        j++
                    }
                    add(groupLength { matcher.end(group) - matcher.start(group) })
                    i = j
                }
                else -> { add(1); i++ }
            }
            sinceCheck += i - start
            if (sinceCheck >= SandboxLimits.CHECK_INTERVAL) {
                sinceCheck = 0
                check.run()
            }
        }
        return length
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /**
     * The length of a group from its offsets ([span], `end - start`); 0 for a group that did not take part in the
     * match (offsets -1) or does not exist.
     */
    private inline fun groupLength(span: () -> Int): Long = try {
        span().coerceAtLeast(0).toLong()
    } catch (e: IllegalArgumentException) {
        0L
    } catch (e: IndexOutOfBoundsException) {
        0L
    }

    private inline fun <T> bounded(what: String, block: () -> T): T = try {
        block()
    } catch (e: StackOverflowError) {
        throw SandboxLimitException("$what: the regular expression is too complex for its input")
    }

    /**
     * [text], read through a counter: every [SandboxLimits.CHECK_INTERVAL] characters read run [check], and more than
     * [SandboxLimits.MAX_REGEX_STEPS] fail with [SandboxLimitException]. Sub-sequences (the groups a matcher extracts)
     * are plain strings: copying one reads it once.
     */
    private class Budgeted(private val text: String, private val check: Runnable) : CharSequence {
        private var reads = 0L

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (++reads % SandboxLimits.CHECK_INTERVAL == 0L) {
                if (reads > SandboxLimits.MAX_REGEX_STEPS) {
                    throw SandboxLimitException(
                        "The regular expression read more than ${SandboxLimits.MAX_REGEX_STEPS} characters (a catastrophic pattern?)",
                    )
                }
                check.run()
            }
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.substring(startIndex, endIndex)

        override fun toString(): String = text
    }
}
