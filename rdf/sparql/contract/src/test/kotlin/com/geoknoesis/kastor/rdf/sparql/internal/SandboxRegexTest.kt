package com.geoknoesis.kastor.rdf.sparql.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * [SandboxRegex] matches and replaces as Java does (with Jena's rule for empty matches when asked), and bounds what a
 * hostile pattern or replacement can cost: the characters read (deterministically, without a clock), the output size,
 * and the matcher's recursion.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SandboxRegexTest {

    private val none = Runnable { }

    /** Still exponential on Java 21's matcher, whose memoization defuses simpler shapes such as `(a+)+$`. */
    private val catastrophic = "((a+)+)+$"

    private fun replaceAll(regex: String, text: String, replacement: String) =
        SandboxRegex.replace(Pattern.compile(regex), text, replacement, skipEmptyAfterFirst = false, check = none)

    @Test
    fun `replace gives what Java gives`() {
        val cases = listOf(
            Triple("b", "abcb", "x"), Triple("(b)", "abc", "[$1]"), Triple("b", "abc", "$0$0"),
            Triple("(?<n>b)", "abcd", "\${n}"), Triple("b", "ab", "\\\\"), Triple("b", "ab", "\\$"),
            Triple("x*", "abc", "-"), Triple("", "abc", "x"), Triple("(a)(b)?", "ac", "<$2>"),
            Triple("(a)", "aa", "$10"), Triple("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)", "abcdefghijk", "$11$1"),
        )
        for ((regex, text, replacement) in cases) {
            val expected = Pattern.compile(regex).matcher(text).replaceAll(replacement)
            val actual = replaceAll(regex, text, replacement) ?: text
            assertEquals(expected, actual, "$regex / $text / $replacement")
        }
        assertNull(replaceAll("z", "abc", "x"))
    }

    @Test
    fun `jena's rule skips empty matches after the first`() {
        assertEquals("xabc", SandboxRegex.replace(Pattern.compile(""), "abc", "x", skipEmptyAfterFirst = true, check = none))
        assertEquals("-abc", SandboxRegex.replace(Pattern.compile("x*"), "abc", "-", skipEmptyAfterFirst = true, check = none))
        // A non-empty match after the first is still replaced.
        assertEquals("xbx", SandboxRegex.replace(Pattern.compile("a|"), "aba", "x", skipEmptyAfterFirst = true, check = none))
    }

    @Test
    fun `malformed replacements fail as in Java`() {
        assertThrows(IllegalArgumentException::class.java) { replaceAll("b", "abc", "$") }
        assertThrows(IndexOutOfBoundsException::class.java) { replaceAll("b", "abc", "$2") }
        assertThrows(IllegalArgumentException::class.java) { replaceAll("b", "abc", "\${missing}") }
    }

    @Test
    fun `find gives what Java gives`() {
        assertTrue(SandboxRegex.find(Pattern.compile("b"), "abc", none))
        assertFalse(SandboxRegex.find(Pattern.compile("^b"), "abc", none))
        assertTrue(SandboxRegex.find(Pattern.compile("B", Pattern.CASE_INSENSITIVE), "abc", none))
    }

    @Test
    fun `a catastrophic pattern ends at the step limit without a clock`() {
        val text = "a".repeat(40) + "!"
        val error = assertThrows(SandboxLimitException::class.java) { SandboxRegex.find(Pattern.compile(catastrophic), text, none) }
        assertTrue(error.message!!.contains("characters"), error.message)
        assertThrows(SandboxLimitException::class.java) { replaceAll(catastrophic, text, "x") }
    }

    @Test
    fun `the check runs while the matcher works, so a deadline stops it`() {
        val checks = AtomicInteger()
        class Stop : RuntimeException()
        assertThrows(Stop::class.java) {
            SandboxRegex.find(Pattern.compile(catastrophic), "a".repeat(40) + "!", Runnable { if (checks.incrementAndGet() == 3) throw Stop() })
        }
        assertEquals(3, checks.get())
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

    @Test
    fun `a pattern too deep for the matcher's recursion is a limit, not a stack overflow`() {
        val text = "ab".repeat(200_000)
        assertThrows(SandboxLimitException::class.java) { SandboxRegex.find(Pattern.compile("(a|b)*c"), text, none) }
    }

    /** Group lengths are taken from offsets: a replacement that refers to a huge group many times is refused at once. */
    @Test
    fun `a replacement is measured from group offsets before anything is copied`() {
        val text = "a".repeat(1 shl 20)
        val error = assertThrows(SandboxLimitException::class.java) { replaceAll("(a+)", text, "$1".repeat(64)) }
        assertTrue(error.message!!.contains("REPLACE"), error.message)
        // The check runs while a long replacement is read.
        val checks = AtomicInteger()
        replaceAll("b", "abc", "x".repeat(3 * SandboxLimits.CHECK_INTERVAL), Runnable { checks.incrementAndGet() })
        assertTrue(checks.get() >= 3, "checks: ${checks.get()}")
    }

    private fun replaceAll(regex: String, text: String, replacement: String, check: Runnable) =
        SandboxRegex.replace(Pattern.compile(regex), text, replacement, skipEmptyAfterFirst = false, check = check)

    /**
     * Choices that match the empty string are explored without reading a character, so the step budget cannot see
     * them: 24 groups `(?:|)` take Java's matcher 0.4 s with zero reads, and every further group doubles that. Such
     * patterns are refused before they run.
     */
    @Test
    fun `patterns whose choices read nothing are refused before they run`() {
        val zeroWidth = "(?:|)".repeat(40) + "\\z."
        val started = System.nanoTime()
        assertThrows(SandboxLimitException::class.java) { SandboxRegex.find(Pattern.compile(zeroWidth), "b", none) }
        assertThrows(SandboxLimitException::class.java) { replaceAll(zeroWidth, "b", "x") }
        assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started) < java.time.Duration.ofMillis(500))
        for (refused in listOf(
            "(|){30}x", "(?:a?|b?){40}c", "(a*)*b", "(a?)+", "(?:)*", "(?=a)b", "(?!a)b", "(?<=a)b", "(?<!a)b", "(?x) a",
            "(a|b?|c?)", "(\\b)*", "(\\1)*", "(?:^)+", "(a{0,3}){2}",
        )) {
            assertThrows(SandboxLimitException::class.java, { SandboxRegexPatterns.requireBounded(Pattern.compile(refused), "t") }, refused)
        }
    }

    @Test
    fun `ordinary patterns are allowed`() {
        for (allowed in listOf(
            "", "b", "^b$", "^AB", "a|", "x*", "^.*$", "[a-z]+@[a-z]+\\.com", "^(\\d{3})-(\\d{4})$", "(a|b)*c", "\\p{L}+",
            "(?i)abc", "a{1000}", "(?<n>b)", "((a+)+)+$", "(?:ab)?", "([a-z]+ ?)+$", "[(|)]*", "\\Q(|)*\\E", "(a|b?)",
            "(?i:x)+", "[a-z&&[^b]]+", "\\(a\\|\\)*",
        )) {
            SandboxRegexPatterns.requireBounded(Pattern.compile(allowed), "t")
        }
        // A literal pattern has no choice at all.
        SandboxRegexPatterns.requireBounded(Pattern.compile("(|)*", Pattern.LITERAL), "t")
        // In comments mode, whitespace is not an atom.
        SandboxRegexPatterns.requireBounded(Pattern.compile("a + b", Pattern.COMMENTS), "t")
        assertThrows(SandboxLimitException::class.java) { SandboxRegexPatterns.requireBounded(Pattern.compile("( a ? ) +", Pattern.COMMENTS), "t") }
    }
}
