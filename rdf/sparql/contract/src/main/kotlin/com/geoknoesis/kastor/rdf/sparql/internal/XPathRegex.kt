package com.geoknoesis.kastor.rdf.sparql.internal

/**
 * **Not public API.** XPath / XML Schema regular expressions (`fn:matches`, SPARQL `REGEX` and `REPLACE`, SHACL
 * `sh:pattern`) compiled for **RE2/J**, a linear-time engine: matching costs at most the size of the compiled pattern
 * times the length of the input, whatever the pattern, so an untrusted pattern cannot backtrack catastrophically. It
 * is public only because Kotlin `internal` cannot be shared across modules; it may change without notice.
 *
 * **The rule**: a pattern is read as an XPath regular expression (XPath and XQuery Functions and Operators 3.1,
 * 5.6.1). It is translated into RE2 syntax with the same meaning; java.util.regex idioms that shapes written for Java
 * engines use are translated with the meaning their author intends; everything else that would not mean the same,
 * or that RE2 cannot evaluate in linear time, is **refused** with [XPathRegexException]. Nothing ever falls back to
 * `java.util.regex`.
 *
 * Flags: `i` (case-insensitive: characters and ranges match their case variants; `\p{Lu}` and the other escapes do
 * not change), `m` (`^` and `$` also match after and before a line feed), `s` (`.` matches every character), `x`
 * (whitespace outside character classes is removed) and `q` (the pattern is literal text; only `i` combines with
 * it). Any other flag character is refused.
 *
 * Translated: `$` matches at the end of the input only (not before a final line feed); `.` matches everything but
 * line feed and carriage return; `\d` is `\p{Nd}`; `\s` is space, tab, line feed and carriage return; `\w` is the
 * categories L, M, N and S **plus the underscore** (XML Schema excludes `_`; it is kept for patterns written against
 * the Java engines); `\i` and `\c` are the XML name characters; class subtraction `[a-z-[aeiou]]`; `\p{IsBlock}`
 * (Unicode blocks; a Unicode script name is also accepted); the general categories, `Cn` included. Every character
 * class is computed from `java.lang.Character` (the JVM's Unicode version) and handed to RE2 as explicit ranges.
 *
 * java.util.regex idioms that are accepted: reluctant quantifiers (also XPath), a **leading** inline flag group of
 * `i`, `m`, `s`, `x` (merged with the flags; after a leading `(?x)` an unescaped `#` and whitespace inside a class,
 * which Java's comments mode would skip, are refused), `\Q...\E`, `\A`, `\z`, `\xHH`, `\x{h}`, `\uHHHH`, `\0ooo`, `\t`,
 * `\n`, `\r`, `\f`, `\a`, `\e`, named groups (as plain capturing groups), POSIX bracket expressions inside a class
 * (`[[:alpha:]]`, US-ASCII), and any escaped non-alphanumeric character.
 *
 * **Refused** ([XPathRegexException]), because RE2 cannot evaluate them in linear time or because they would not
 * mean the same: back-references (`\1`; XPath has them), lookahead and lookbehind, atomic groups, possessive
 * quantifiers, word boundaries (`\b`, `\B`: RE2's are ASCII-only), inline flag groups other than leading ones,
 * class intersection (`&&`) and nested classes, repetition counts above 1000, groups nested more than 64 deep, class
 * subtractions nested more than 64 deep, and patterns whose compiled program would pass 50,000 instructions.
 *
 * **Compile cost.** No deadline can interrupt RE2/J while it parses and compiles a pattern, and its parser takes time
 * quadratic in the pattern's text. So a pattern is refused (as unsupported) when it is longer than
 * [MAX_PATTERN_LENGTH] characters, or when its translation's structural cost passes the bounds of
 * [XPathRegexTranslator]: syntax elements times text length ([XPathRegexTranslator.MAX_PARSE_COST]; about eight
 * classes the size of `\p{L}`, or 16,000 literal characters), and the estimated program. These are counts, so
 * whether a pattern is accepted does not depend on the machine. The translation also runs the caller's `check`.
 * Counts are written to RE2 in canonical form (`{01}` is `{1}`), and `\uHHHH\uHHHH` of a surrogate pair is the one
 * character it encodes.
 */
object XPathRegex {

    /**
     * [pattern] compiled with the XPath [flags] (null for none).
     *
     * @throws XPathRegexException when the pattern or the flags are invalid or not supported (see [XPathRegex]).
     */
    @JvmStatic
    @Throws(XPathRegexException::class)
    fun compile(pattern: String, flags: String?): XPathPattern = compile(pattern, flags, NO_CHECK)

    /**
     * [pattern] compiled with the XPath [flags], running [check] (a deadline, a cancellation) while it is translated.
     *
     * @throws XPathRegexException when the pattern or the flags are invalid or not supported (see [XPathRegex]).
     */
    @JvmStatic
    @Throws(XPathRegexException::class)
    fun compile(pattern: String, flags: String?, check: Runnable): XPathPattern {
        // Checked before anything is built from the pattern: translating it costs (and allocates) about its length.
        if (pattern.length > MAX_PATTERN_LENGTH) {
            throw XPathRegexException(pattern, "the pattern is longer than $MAX_PATTERN_LENGTH characters (${pattern.length})", unsupported = true)
        }
        var ignoreCase = false
        var multiLine = false
        var dotAll = false
        var extended = false
        var literal = false
        flags?.forEach { c ->
            when (c) {
                'i' -> ignoreCase = true
                'm' -> multiLine = true
                's' -> dotAll = true
                'x' -> extended = true
                'q' -> literal = true
                else -> throw XPathRegexException(pattern, "unsupported flag character '$c' in \"$flags\" (the flags are s, m, i, x and q)")
            }
        }
        if (literal) return compileTranslation(pattern, flags, literalTranslation(pattern, ignoreCase, check))
        var start = 0
        var leadingExtended = false
        // Leading inline flag groups: "(?" + one or more of i, m, s, x + ")", repeated, at the very start.
        while (pattern.startsWith("(?", start)) {
            var end = start + 2
            while (end < pattern.length && pattern[end] in LEADING_INLINE_FLAGS) end++
            if (end == start + 2 || end >= pattern.length || pattern[end] != ')') break
            for (k in start + 2 until end) {
                when (pattern[k]) {
                    'i' -> ignoreCase = true
                    'm' -> multiLine = true
                    's' -> dotAll = true
                    else -> leadingExtended = true
                }
            }
            start = end + 1
        }
        val translation = XPathRegexTranslator(
            pattern, start, ignoreCase, multiLine, dotAll, extended || leadingExtended, javaComments = leadingExtended, check,
        ).translate()
        return compileTranslation(pattern, flags, translation)
    }

    /** The `q` flag: every character of [pattern] is literal (under `i`, the class of its case variants). */
    private fun literalTranslation(pattern: String, ignoreCase: Boolean, check: Runnable): XPathRegexTranslator.Translation {
        val quoted = "\\Q" + pattern.replace("\\E", "\\E\\\\E\\Q")
        return XPathRegexTranslator(quoted, 0, ignoreCase, multiLine = false, dotAll = false, extended = false, javaComments = false, check).translate()
    }

    private fun compileTranslation(pattern: String, flags: String?, translation: XPathRegexTranslator.Translation): XPathPattern {
        val compiled = try {
            com.google.re2j.Pattern.compile(translation.re2)
        } catch (e: com.google.re2j.PatternSyntaxException) {
            throw XPathRegexException(pattern, "not supported by the linear-time engine (${e.description})", unsupported = true)
        } catch (e: StackOverflowError) {
            throw XPathRegexException(pattern, "too complex to compile", unsupported = true)
        }
        if (compiled.programSize() > MAX_COMPILED_PROGRAM) {
            throw XPathRegexException(pattern, "the pattern is too large: it compiles to ${compiled.programSize()} instructions", unsupported = true)
        }
        return XPathPattern(pattern, flags, compiled, translation.groups)
    }

    /**
     * The longest pattern compiled, in UTF-16 units: a longer one is refused before it is read. (What a pattern costs
     * to compile is bounded by the translation's structural cost, [XPathRegexTranslator.MAX_PARSE_COST]; this bound
     * keeps the work and the allocation of reading it small too.)
     */
    const val MAX_PATTERN_LENGTH = 64 * 1024

    /** A check that does nothing (a pattern of a trusted source, or one compiled without a deadline). */
    @JvmField
    val NO_CHECK: Runnable = Runnable { }

    /** Flags a leading inline flag group may set. */
    private const val LEADING_INLINE_FLAGS = "imsx"

    /** The largest compiled RE2 program accepted (the translator's estimate is checked first). */
    private const val MAX_COMPILED_PROGRAM = 2 * XPathRegexTranslator.MAX_PROGRAM_SIZE.toInt()
}

/**
 * **Not public API.** A compiled XPath regular expression ([XPathRegex.compile]); thread-safe. Match it with
 * [SandboxRegex] (bounded by a deadline check) or [containsMatchIn] / [firstMatch] (unbounded, for trusted inputs).
 */
class XPathPattern internal constructor(
    /** The XPath pattern this was compiled from. */
    val pattern: String,
    /** The XPath flags it was compiled with. */
    val flags: String?,
    internal val re2: com.google.re2j.Pattern,
    /** The number of capturing groups (what `$N` in a replacement can refer to). */
    val groupCount: Int,
) {
    /** Whether the pattern matches somewhere in [text] (`fn:matches`). */
    fun containsMatchIn(text: CharSequence): Boolean = re2.matcher(text).find()

    /** The UTF-16 offsets of the first match in [text], or null. */
    fun firstMatch(text: CharSequence): IntRange? {
        val m = re2.matcher(text)
        return if (m.find()) m.start() until m.end() else null
    }

    /** The size of the compiled RE2 program (the per-character cost of a match is at most proportional to it). */
    val programSize: Int get() = re2.programSize()

    override fun toString(): String = "XPathPattern($pattern, flags=$flags)"
}

/**
 * An XPath regular expression (or an `fn:replace` replacement) that is invalid or not supported by the linear-time
 * engine: [reason] says why. An [IllegalArgumentException].
 *
 * Providers report an invalid one (a syntax error, an unknown flag, a malformed replacement) as an error of the
 * expression, as SPARQL prescribes; an [unsupported] one fails the sandboxed query (fail closed), because a pattern
 * the engine cannot honour must never turn into a silent non-match.
 */
class XPathRegexException(
    /** The pattern (or replacement) refused. */
    val pattern: String,
    /** Why, without the pattern. */
    val reason: String,
    /** Whether the pattern is refused because the engine cannot evaluate it as meant (not because it is invalid). */
    val unsupported: Boolean = false,
) : IllegalArgumentException("${if (unsupported) "Unsupported" else "Invalid"} regular expression \"${abbreviated(pattern)}\": $reason")

/** [pattern] for a message: a long one is cut (a refused pattern may be megabytes long). */
private fun abbreviated(pattern: String): String =
    if (pattern.length <= MESSAGE_PATTERN_LENGTH) {
        pattern
    } else {
        val cut = if (Character.isHighSurrogate(pattern[MESSAGE_PATTERN_LENGTH - 1])) MESSAGE_PATTERN_LENGTH - 1 else MESSAGE_PATTERN_LENGTH
        pattern.take(cut) + "... (${pattern.length} characters)"
    }

private const val MESSAGE_PATTERN_LENGTH = 200
