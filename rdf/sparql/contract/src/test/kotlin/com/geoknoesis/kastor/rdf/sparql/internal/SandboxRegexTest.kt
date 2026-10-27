package com.geoknoesis.kastor.rdf.sparql.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * [SandboxRegex] matches and replaces with `fn:matches` / `fn:replace` semantics on a linear-time engine, and bounds
 * what a hostile pattern, input or replacement can cost: the work per input character (no backtracking), the deadline
 * check while it reads, and the output size.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SandboxRegexTest {

    private val none = Runnable { }

    private fun compile(regex: String, flags: String? = null) = SandboxRegex.compile(regex, flags)

    private fun replaceAll(regex: String, text: String, replacement: String, check: Runnable = none) =
        SandboxRegex.replace(compile(regex), text, replacement, skipEmptyAfterFirst = false, check = check)

    @Test
    fun `replace gives what Java gives where the syntaxes agree`() {
        val cases = listOf(
            Triple("b", "abcb", "x"), Triple("(b)", "abc", "[$1]"), Triple("b", "abc", "$0$0"), Triple("b", "ab", "\\\\"),
            Triple("b", "ab", "\\$"), Triple("x*", "abc", "-"), Triple("", "abc", "x"), Triple("(a)(b)?", "ac", "<$2>"),
            Triple("(a)", "aa", "$10"), Triple("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)", "abcdefghijk", "$11$1"),
            Triple("[a-c]+", "xabcyab", "($0)"), Triple("(?:a|b)+?", "abab", "-"),
        )
        for ((regex, text, replacement) in cases) {
            val expected = Pattern.compile(regex).matcher(text).replaceAll(replacement)
            val actual = replaceAll(regex, text, replacement) ?: text
            assertEquals(expected, actual, "$regex / $text / $replacement")
        }
        assertNull(replaceAll("z", "abc", "x"))
    }

    /** The expressions of the W3C SPARQL 1.1 function tests on `REGEX` / `REPLACE` (functions/replace0x, regex/). */
    @Test
    fun `the W3C SPARQL regex and replace cases give their expected results`() {
        assertEquals("[1=ab][2=]cd", replaceAll("(ab)|(a)", "abcd", "[1=$1][2=$2]"))
        assertEquals("aZcd", replaceAll("b", "abcd", "Z"))
        assertEquals("abc-def", replaceAll("[^a-z0-9]", "abc DEF".lowercase(), "-"))
        assertEquals("bbb", replaceAll("a", "aaa", "b"))
        assertTrue(SandboxRegex.find(compile("^mailto:example\\.com$"), "mailto:example.com", none))
        assertTrue(SandboxRegex.find(compile("example\\.COM", "i"), "mailto:Example.com", none))
        assertFalse(SandboxRegex.find(compile("^example"), "mailto:example.com", none))
        assertTrue(SandboxRegex.find(compile("^\\w+$"), "abc_1", none))
    }

    @Test
    fun `replacement references follow fn replace`() {
        // A missing group up to 9 is empty (Java would throw); beyond 9, trailing digits are literal.
        assertEquals("a<>c", replaceAll("(b)", "abc", "<$5>"))
        assertEquals("a<b0>c", replaceAll("(b)", "abc", "<$10>"))
        assertEquals("a<b99>c", replaceAll("(b)", "abc", "<$199>"))
        // A group that did not take part is empty.
        assertEquals("<a>", replaceAll("(a)|(b)", "a", "<$1$2>"))
    }

    @Test
    fun `malformed replacements are errors`() {
        assertThrows(XPathRegexException::class.java) { compile("(a") }
        for (bad in listOf("$", "a$", "\${n}", "\\x", "\\", "$-1")) {
            assertThrows(XPathRegexException::class.java, { replaceAll("b", "abc", bad) }, bad)
        }
    }

    @Test
    fun `jena's rule skips empty matches after the first`() {
        assertEquals("xabc", SandboxRegex.replace(compile(""), "abc", "x", skipEmptyAfterFirst = true, check = none))
        assertEquals("-abc", SandboxRegex.replace(compile("x*"), "abc", "-", skipEmptyAfterFirst = true, check = none))
        // A non-empty match after the first is still replaced.
        assertEquals("xbx", SandboxRegex.replace(compile("a|"), "aba", "x", skipEmptyAfterFirst = true, check = none))
    }

    @Test
    fun `find follows fn matches`() {
        assertTrue(SandboxRegex.find(compile("b"), "abc", none))
        assertFalse(SandboxRegex.find(compile("^b"), "abc", none))
        assertTrue(SandboxRegex.find(compile("B", "i"), "abc", none))
        // `$` is the end of the input, not before a final line feed (Java's `$`).
        assertFalse(SandboxRegex.find(compile("^\\d+$"), "123\n", none))
    }

    /**
     * Patterns that backtrack catastrophically in java.util.regex read a linear number of characters here: the work
     * is measured deterministically, through the reads the deadline check counts.
     */
    @Test
    fun `catastrophic patterns read their input a linear number of times`() {
        for (regex in listOf("((a+)+)+$", "(a|aa)+$", "(a*)*b", "(a|a?)+c", "^(\\w+\\s?)*$")) {
            val text = "a".repeat(5000) + "!"
            val reads = AtomicLong()
            val pattern = compile(regex)
            val interval = minOf(4096L, maxOf(16L, 4096L * 64 / pattern.programSize))
            assertFalse(SandboxRegex.find(pattern, text, Runnable { reads.addAndGet(interval) }), regex)
            assertTrue(reads.get() <= 8L * text.length, "$regex read ${reads.get()} characters of ${text.length}")
        }
    }

    /**
     * Round thirteen's bypasses of the shape analyzer (core P1-1, providers P2-1): choices that read nothing, hidden
     * behind `\Q[\E` in a class or a comments-mode prefix. On java.util.regex `[\Q[\E]?` + `(|)` x 26 + `(?!)` took
     * 2.2 s with no deadline check, doubling per group. Now lookarounds and flag groups are refused, and what is
     * accepted is linear.
     */
    @Test
    fun `round thirteen's analyzer bypasses are refused or linear`() {
        for (refused in listOf(
            "[\\Q[\\E]?" + "(|)".repeat(40) + "(?!)", "(?x)[a#[\n]?" + "(|)".repeat(40) + "(?!)", "(?-x)#" + "(?:|)".repeat(20) + "x",
            "(?x:a)#" + "(?:|)".repeat(20) + "x", "(?-x)#" + "(?:a?|b?)".repeat(20) + "x",
        )) {
            val error = assertThrows(XPathRegexException::class.java, { XPathRegex.compile(refused, null) }, refused)
            assertTrue(error.unsupported, refused)
            // In a sandboxed query, a valid pattern the engine cannot honour fails the query (fail closed).
            assertThrows(SandboxLimitException::class.java, { compile(refused) }, refused)
        }
        for (accepted in listOf("[\\Q[\\E]" + "(?:|)".repeat(40) + "x", "[\\Q[\\E]?" + "(|)".repeat(60) + "\\z.", "(?:a?|b?)".repeat(40) + "c")) {
            assertFalse(SandboxRegex.find(compile(accepted), "", none), accepted)
            assertNull(SandboxRegex.replace(compile(accepted), "", "x", skipEmptyAfterFirst = false, check = none), accepted)
        }
    }

    @Test
    fun `the check runs while the matcher reads, so a deadline stops a long input`() {
        val checks = AtomicInteger()
        class Stop : RuntimeException()
        assertThrows(Stop::class.java) {
            SandboxRegex.find(compile("(a|b)*c"), "ab".repeat(200_000), Runnable { if (checks.incrementAndGet() == 3) throw Stop() })
        }
        assertEquals(3, checks.get())
    }

    @Test
    fun `a long alternation under a star is no stack overflow`() {
        // java.util.regex recursed per repetition and overflowed the stack on this input.
        assertFalse(SandboxRegex.find(compile("(a|b)*c"), "ab".repeat(200_000), none))
        assertTrue(SandboxRegex.find(compile("^(a|b)*$"), "ab".repeat(200_000), none))
    }

    @Test
    fun `a replacement that would outgrow the string limit is refused before it is built`() {
        val text = "a".repeat(1 shl 16)
        // 2^16 matches, each expanded to 512 copies of itself plus 512 more chars: 2^16 * 1024 = 64 Mi chars.
        val error = assertThrows(SandboxLimitException::class.java) { replaceAll("a", text, "$0".repeat(512) + "x".repeat(512)) }
        assertTrue(error.message!!.contains("REPLACE"), error.message)
        // One match of the whole input, copied 300 times: 300 * 2^16 chars > 16 Mi, refused at the first match.
        assertThrows(SandboxLimitException::class.java) { replaceAll("^.*$", text, "$0".repeat(300)) }
        // Within the limit, the same shape succeeds.
        assertEquals(2 * text.length, replaceAll("^.*$", text, "$0$0")!!.length)
    }

    /** Group lengths are taken from offsets: a replacement that refers to a huge group many times is refused at once. */
    @Test
    fun `a replacement is measured from group offsets before anything is copied`() {
        val text = "a".repeat(1 shl 20)
        val error = assertThrows(SandboxLimitException::class.java) { replaceAll("(a+)", text, "$1".repeat(64)) }
        assertTrue(error.message!!.contains("REPLACE"), error.message)
    }

    /** A call site compiles a constant pattern once, and a few alternating ones once each; the cache is bounded. */
    @Test
    fun `the pattern cache compiles each pattern once and is bounded`() {
        val cache = SandboxRegex.PatternCache()
        val first = cache.get("^a+$", null, none)
        assertTrue(first === cache.get("^a+$", null, none))
        assertFalse(first === cache.get("^a+$", "i", none))
        val alternating = listOf("x", "y", "z").map { cache.get(it, null, none) }
        repeat(10) { listOf("x", "y", "z").forEachIndexed { k, p -> assertTrue(alternating[k] === cache.get(p, null, none)) } }
        repeat(10 * SandboxRegex.PatternCache.CAPACITY) { cache.get("p$it", null, none) }
        assertTrue(cache.size <= SandboxRegex.PatternCache.CAPACITY, "the cache holds ${cache.size} patterns")
        // Errors are not cached as patterns, and keep their kind.
        assertThrows(XPathRegexException::class.java) { cache.get("(", null, none) }
        assertThrows(SandboxLimitException::class.java) { cache.get("(a)\\1", null, none) }
        assertThrows(SandboxLimitException::class.java) { cache.get("\\p{L}".repeat(80), null, none) }
    }

    /** Many empty matches of a long replacement cost its length each time, so the check runs while they expand. */
    @Test
    fun `the check runs while replacements expand`() {
        val checks = AtomicInteger()
        replaceAll("x*", "a".repeat(1000), "$0".repeat(SandboxLimits.CHECK_INTERVAL), Runnable { checks.incrementAndGet() })
        assertTrue(checks.get() >= 1000, "checks: ${checks.get()}")
    }
}
