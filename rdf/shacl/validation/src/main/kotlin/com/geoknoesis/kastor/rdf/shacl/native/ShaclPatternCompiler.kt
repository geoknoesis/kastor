package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException

/**
 * Compiles `sh:pattern` with `sh:flags` into a JVM [Regex] that follows XPath `fn:matches` (XML Schema regular
 * expressions), which is what SHACL prescribes through SPARQL `REGEX`.
 *
 * Flags: `i` (case-insensitive, Unicode-aware), `m` (multi-line), `s` (dot matches all), `x` (whitespace outside
 * character classes is removed from the pattern) and `q` (the pattern is matched literally; only `i` combines with
 * it). Unknown flags or syntactically invalid patterns raise [ShapeCompileException].
 *
 * Patterns are evaluated with `java.util.regex`. **The rule**: a pattern is read as an XPath / XML Schema regular
 * expression. XML Schema syntax is translated to its `java.util.regex` equivalent; `java.util.regex` syntax that
 * XPath does not have is passed through when it means the same after translation, and **rejected** with a
 * [ShapeCompileException] when it would not — a pattern is never silently given another meaning.
 *
 * Translated ([XPathRegexTranslator]):
 * - `$` matches at the end of input only, or with `m` before a line feed. Java's `$` also matches before a final
 *   line terminator, so `^\d+$` would accept `"123\n"`.
 * - `^` with `m` matches at the start of input and after a line feed. Only U+000A ends a line in XPath; Java's
 *   multi-line anchors also treat carriage return, U+0085, U+2028 and U+2029 as line terminators (Java's `MULTILINE`
 *   mode is therefore never used).
 * - `.` without `s` matches every character except line feed and carriage return (Java also excludes U+0085, U+2028
 *   and U+2029).
 * - `\d` / `\D` are Unicode decimal digits (`\p{Nd}`); `\s` / `\S` are exactly space, tab, line feed and carriage
 *   return. Java's defaults are ASCII-only for `\d` and wider for `\s`.
 * - `\w` is `[_\p{L}\p{M}\p{N}\p{S}]`: the XML Schema definition ("everything but punctuation, separators and other
 *   characters", so `José` is a word and symbols such as `+` are word characters) **plus the underscore** U+005F.
 *   XML Schema excludes `_` (connector punctuation); it is accepted because shapes are usually written against
 *   engines that evaluate `sh:pattern` with `java.util.regex` (Jena, RDF4J, TopBraid), where `\w` matches `_`.
 *   `\W` is the exact complement, so it does not match `_`.
 * - `\i` / `\I` and `\c` / `\C` are the XML `NameStartChar` and `NameChar` productions (XML 1.0 fifth edition) and
 *   their complements.
 * - Character-class subtraction `[base-[excluded]]` (it must end the class; it nests) becomes
 *   `[[base]&&[^[excluded]]]`. A single literal `&` inside a class is escaped (`&&` is an operator in Java classes).
 * - Unicode block escapes `\p{IsBlock}` become `\p{InBlock}`. The block must be a Unicode block known to the JVM
 *   under its XML Schema name (the Unicode name without spaces; `PrivateUse` is mapped to `PrivateUseArea`).
 *
 * Passed through, because the meaning is the same: lazy and possessive quantifiers, back-references, non-capturing
 * and named groups, lookarounds, atomic groups, general-category escapes (`\p{Lu}`) and `\Q…\E` quoting (a quoted
 * section is copied verbatim, so `$`, `^` and `.` inside it stay literal). `$`, `^`, `.` and the class escapes
 * inside such groups are still translated. Portable shapes should stick to the XML Schema subset.
 *
 * Rejected, because `java.util.regex` would read them differently from what the author of a Java-style pattern
 * means, or because the translation cannot honour them:
 * - `&&` inside a character class (Java class intersection; use `[base-[excluded]]`, or `\&` for a literal);
 * - an unescaped `[` inside a character class (Java nested classes and unions such as `[a-c[x-z]]`, POSIX
 *   `[[:alpha:]]`; use `\[` for a literal bracket);
 * - inline flag groups `(?i)`, `(?m)`, `(?s)`, `(?x)`, `(?i:…)`, `(?-i)`…: `$`, `^`, `.` and whitespace are
 *   translated according to `sh:flags`, which an inline flag would bypass (use `sh:flags`);
 * - `\p{IsX}` where `X` is not a Unicode block (Java reads `\p{IsLatin}` as a script and `\p{IsAlphabetic}` as a
 *   binary property; XML Schema has neither).
 */
internal fun compileShaclPattern(pattern: String, flags: String?): Regex {
    val options = mutableSetOf<RegexOption>()
    var quote = false
    var extended = false
    var multiLine = false
    flags?.forEach { c ->
        when (c) {
            'i' -> options.add(RegexOption.IGNORE_CASE)
            'm' -> multiLine = true
            's' -> options.add(RegexOption.DOT_MATCHES_ALL)
            'x' -> extended = true
            'q' -> quote = true
            else -> throw ShapeCompileException("Unsupported sh:flags character '$c' in \"$flags\"")
        }
    }
    val source =
        if (quote) {
            Regex.escape(pattern)
        } else {
            XPathRegexTranslator(pattern, extended, multiLine, dotAll = RegexOption.DOT_MATCHES_ALL in options).translate()
        }
    return try {
        Regex(source, options)
    } catch (e: java.util.regex.PatternSyntaxException) {
        throw ShapeCompileException("Invalid sh:pattern \"$pattern\": ${e.description}", e)
    }
}

/** Translates one XPath / XML Schema regular expression to `java.util.regex` syntax (see [compileShaclPattern]). */
private class XPathRegexTranslator(
    private val p: String,
    private val extended: Boolean,
    private val multiLine: Boolean,
    private val dotAll: Boolean,
) {
    private var i = 0

    private fun reject(reason: String): Nothing = throw ShapeCompileException("Invalid sh:pattern \"$p\": $reason")

    fun translate(): String {
        val out = StringBuilder(p.length + 16)
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' && i + 1 < p.length -> out.append(escape())
                c == '[' -> out.append(charClass())
                c == '(' -> {
                    rejectInlineFlags()
                    out.append(c)
                    i++
                }
                c == '$' -> {
                    out.append(if (multiLine) "(?=\\n|\\z)" else "\\z")
                    i++
                }
                c == '^' && multiLine -> {
                    out.append("(?:\\A|(?<=\\n))")
                    i++
                }
                c == '.' && !dotAll -> {
                    out.append("[^\\n\\r]")
                    i++
                }
                extended && (c == ' ' || c == '\t' || c == '\n' || c == '\r') -> i++
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * Rejects the inline flag group starting at [i] (`(`), if it is one: `(?` followed by at least one of the
     * `java.util.regex` flag letters or `-`, then `)` or `:`. `(?:`, lookarounds and named groups are not flag groups.
     */
    private fun rejectInlineFlags() {
        if (!p.startsWith("(?", i)) return
        var end = i + 2
        while (end < p.length && p[end] in INLINE_FLAG_CHARACTERS) end++
        if (end == i + 2 || end >= p.length || (p[end] != ')' && p[end] != ':')) return
        reject(
            "inline flag group \"${p.substring(i, end + 1)}\" at index $i is java.util.regex syntax that XPath regular " +
                "expressions do not have, and the anchors and the dot are translated according to sh:flags; set sh:flags " +
                "(i, m, s, x) on the shape instead",
        )
    }

    /** Translates the escape starting at [i] (a backslash with a following character) and moves past it. */
    private fun escape(): String {
        val n = p[i + 1]
        if (n == 'Q') {
            // Java literal quoting (not XPath syntax): copied verbatim up to and including `\E`, or to the end.
            val end = p.indexOf("\\E", i + 2)
            val stop = if (end < 0) p.length else end + 2
            return p.substring(i, stop).also { i = stop }
        }
        if ((n == 'p' || n == 'P') && p.startsWith("{Is", i + 2)) return blockEscape(n)
        i += 2
        // Multi-character escapes are self-contained classes: inside a character class they are union members, so a
        // neighbouring hyphen (`[\w-.]`, `[+-\w]`) is a literal and never forms a range with their first or last member.
        return when (n) {
            'd' -> "\\p{Nd}"
            'D' -> "\\P{Nd}"
            'w' -> "[$WORD]"
            'W' -> "[^$WORD]"
            's' -> "[$SPACE]"
            'S' -> "[^$SPACE]"
            'i' -> "[$NAME_START]"
            'I' -> "[^$NAME_START]"
            'c' -> "[$NAME_CHAR]"
            'C' -> "[^$NAME_CHAR]"
            else -> "\\" + n
        }
    }

    /** Translates the block escape `\p{IsName}` / `\P{IsName}` starting at [i] and moves past it. */
    private fun blockEscape(kind: Char): String {
        val close = p.indexOf('}', i + 5)
        if (close < 0) reject("the block escape at index $i is not terminated: '}' expected")
        val name = p.substring(i + 5, close)
        val block = XSD_BLOCK_ALIASES[name] ?: name
        val known = try {
            Character.UnicodeBlock.forName(block)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
        if (!known) {
            reject(
                "\\$kind{Is$name} at index $i: \"$name\" is not a Unicode block. In XML Schema regular expressions " +
                    "\\p{IsX} names a block (the Unicode block name without spaces, e.g. \\p{IsBasicLatin}, " +
                    "\\p{IsGreek}, \\p{IsLatin-1Supplement}); java.util.regex scripts and binary properties " +
                    "(\\p{IsLatin}, \\p{IsAlphabetic}) are not supported",
            )
        }
        i = close + 1
        return "\\$kind{In$block}"
    }

    /**
     * Translates the character class expression starting at [i] (`[`) into a self-contained Java class and moves
     * past its closing `]`. An unterminated class is returned unterminated so that compiling it fails.
     */
    private fun charClass(): String {
        i++ // [
        val negated = i < p.length && p[i] == '^'
        if (negated) i++
        val group = StringBuilder()
        while (i < p.length) {
            val c = p[i]
            when {
                c == ']' -> {
                    i++
                    return if (negated) "[^$group]" else "[$group]"
                }
                c == '\\' && i + 1 < p.length -> group.append(escape())
                c == '-' && i + 1 < p.length && p[i + 1] == '[' -> {
                    // XML Schema: charClassSub ::= ( posCharGroup | negCharGroup ) '-' charClassExpr, then ']'.
                    val at = i
                    i++
                    val excluded = charClass()
                    if (i >= p.length || p[i] != ']') {
                        reject(
                            "the character class subtraction at index $at must end its class: ']' expected after " +
                                "\"-[...]\" (XML Schema: [base-[excluded]])",
                        )
                    }
                    i++
                    val base = if (negated) "[^$group]" else "[$group]"
                    return "[$base&&[^$excluded]]"
                }
                c == '[' -> reject(
                    "unescaped '[' inside a character class at index $i: nested classes and unions ([a-c[x-z]], " +
                        "[[:alpha:]]) are java.util.regex syntax that XPath regular expressions do not have; write \\[ " +
                        "for a literal bracket, or [base-[excluded]] for a class subtraction",
                )
                c == '&' && i + 1 < p.length && p[i + 1] == '&' -> reject(
                    "'&&' inside a character class at index $i: class intersection is java.util.regex syntax that " +
                        "XPath regular expressions do not have; write [base-[excluded]] for a class subtraction, or " +
                        "\\& for a literal ampersand",
                )
                c == '&' -> {
                    group.append("\\&")
                    i++
                }
                else -> {
                    group.append(c)
                    i++
                }
            }
        }
        return (if (negated) "[^" else "[") + group
    }

    private companion object {
        /**
         * Members of `\w`: XML Schema's "every character except punctuation (P), separators (Z) and other (C)" —
         * every code point has exactly one general category, so that is L, M, N and S — plus the underscore (see
         * [compileShaclPattern]). `\W` is the complement `[^…]` of the same members.
         */
        const val WORD = "_\\p{L}\\p{M}\\p{N}\\p{S}"
        const val SPACE = " \\t\\n\\r"

        /** `java.util.regex` inline flag letters (`(?idmsuxU-idmsuxU)`). */
        const val INLINE_FLAG_CHARACTERS = "idmsuxU-"

        /** Members of a Java character class for the code point [ranges] (`\x{h}` notation, no raw characters). */
        private fun members(vararg ranges: IntRange): String =
            ranges.joinToString("") { r ->
                val first = "\\x{" + r.first.toString(16) + "}"
                if (r.first == r.last) first else first + "-\\x{" + r.last.toString(16) + "}"
            }

        /** XML 1.0 (fifth edition) `NameStartChar`: `\i`. */
        val NAME_START: String = members(
            ':'.code..':'.code, 'A'.code..'Z'.code, '_'.code..'_'.code, 'a'.code..'z'.code,
            0xC0..0xD6, 0xD8..0xF6, 0xF8..0x2FF, 0x370..0x37D, 0x37F..0x1FFF, 0x200C..0x200D, 0x2070..0x218F,
            0x2C00..0x2FEF, 0x3001..0xD7FF, 0xF900..0xFDCF, 0xFDF0..0xFFFD, 0x10000..0xEFFFF,
        )

        /** XML 1.0 (fifth edition) `NameChar`: `\c`. */
        val NAME_CHAR: String = NAME_START + members(
            '-'.code..'-'.code, '.'.code..'.'.code, '0'.code..'9'.code, 0xB7..0xB7, 0x300..0x36F, 0x203F..0x2040,
        )

        /** XML Schema 1.0 block names that are not names or aliases of the block in `java.lang.Character.UnicodeBlock`. */
        val XSD_BLOCK_ALIASES: Map<String, String> = mapOf("PrivateUse" to "PrivateUseArea")
    }
}
