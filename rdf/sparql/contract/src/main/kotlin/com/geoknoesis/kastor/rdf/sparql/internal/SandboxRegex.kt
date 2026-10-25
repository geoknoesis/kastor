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
 * [replace] also bounds its output: the size of each replacement is computed (from the groups it refers to) before
 * it is appended, so no replacement text beyond [SandboxLimits.MAX_STRING_LENGTH] is ever built.
 */
object SandboxRegex {

    /** Whether [pattern] matches somewhere in [text] (SPARQL `REGEX`, XPath `fn:matches`). */
    @JvmStatic
    fun find(pattern: Pattern, text: String, check: Runnable): Boolean = bounded("REGEX") {
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
            val matcher = pattern.matcher(Budgeted(text, check))
            var out: StringBuilder? = null
            var copied = 0
            while (matcher.find()) {
                if (out == null) out = StringBuilder() else if (skipEmptyAfterFirst && matcher.start() == matcher.end()) continue
                // What appendReplacement adds: the text before the match, then the expanded replacement.
                val added = (matcher.start() - copied).toLong() + expandedLength(matcher, replacement)
                SandboxLimits.checkStringLength(out.length + added + (text.length - matcher.end()), "REPLACE")
                matcher.appendReplacement(out, replacement)
                copied = matcher.end()
            }
            out?.let { matcher.appendTail(it).toString() }
        }

    /**
     * An upper bound of the length of [replacement] expanded for the current match of [matcher], read as
     * `Matcher.appendReplacement` reads it; a malformed reference counts as its own text (`appendReplacement` then
     * fails on it).
     */
    private fun expandedLength(matcher: Matcher, replacement: String): Long {
        var length = 0L
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            when {
                c == '\\' -> { length++; i += 2 }
                c == '$' && i + 1 < replacement.length && replacement[i + 1] == '{' -> {
                    val close = replacement.indexOf('}', i + 2)
                    if (close < 0) return length + replacement.length - i
                    length += groupLength { matcher.group(replacement.substring(i + 2, close)) }
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
                    length += groupLength { matcher.group(group) }
                    i = j
                }
                else -> { length++; i++ }
            }
        }
        return length
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** The length of a group ([read]); 0 for a group that did not take part in the match or does not exist. */
    private inline fun groupLength(read: () -> String?): Long = try {
        read()?.length?.toLong() ?: 0L
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
