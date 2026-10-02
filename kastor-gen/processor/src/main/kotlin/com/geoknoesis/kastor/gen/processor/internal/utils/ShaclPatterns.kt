package com.geoknoesis.kastor.gen.processor.internal.utils

import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import java.security.MessageDigest
import java.util.regex.PatternSyntaxException

/**
 * `sh:pattern` values are XPath (`fn:matches`) / XML Schema regular expressions. Generated code runs them with
 * `kotlin.text.Regex` (java.util.regex), so they are translated at generation time - **with the same rules as the
 * Kastor SHACL validator** (`rdf-shacl-validation`, `ShaclPatternCompiler`), so that generated validation and SHACL
 * validation accept and reject the same values, and the same patterns.
 *
 * **The rule**: a pattern is read as an XPath / XML Schema regular expression. XML Schema syntax is translated to its
 * `java.util.regex` equivalent; `java.util.regex` syntax that XPath does not have is passed through when it means the
 * same after translation, a few common idioms of other regular expression dialects are translated with the meaning
 * their author intends (see "Compatibility"), and everything else that would not mean the same is **rejected** at
 * generation time, with an error that names the shape and the property ([problems]) - a pattern is never silently
 * given another meaning.
 *
 * Flags (`sh:flags`): `i` (case-insensitive, Unicode-aware), `m` (multi-line), `s` (dot matches all), `x` (whitespace
 * outside character classes is removed from the pattern) and `q` (the pattern is matched literally; only `i` combines
 * with it). Any other flag is rejected.
 *
 * Translated:
 * - `$` matches at the end of input only, or with `m` before a line feed. Java's `$` also matches before a final
 *   line terminator, so `^\d+$` would accept "123" followed by a newline.
 * - `^` with `m` matches at the start of input and after a line feed. Only U+000A ends a line in XPath; Java's
 *   multi-line anchors also treat carriage return, U+0085, U+2028 and U+2029 as line terminators (Java's `MULTILINE`
 *   mode is therefore never used).
 * - `.` without `s` matches every character except line feed and carriage return (Java also excludes U+0085, U+2028
 *   and U+2029).
 * - `\d` / `\D` are Unicode decimal digits (`\p{Nd}`); `\s` / `\S` are exactly space, tab, line feed and carriage
 *   return. Java's defaults are ASCII-only for `\d` and wider for `\s`.
 * - `\w` is `[_\p{L}\p{M}\p{N}\p{S}]`: the XML Schema definition ("everything but punctuation, separators and other
 *   characters", so symbols such as `+` are word characters) **plus the underscore**, which XML Schema leaves out but
 *   every engine that evaluates `sh:pattern` with `java.util.regex` counts as a word character. `\W` is the exact
 *   complement, so it does not match `_`.
 * - `\i` / `\I` and `\c` / `\C` are the XML `NameStartChar` and `NameChar` productions (XML 1.0 fifth edition) and
 *   their complements.
 * - Character-class subtraction `[base-[excluded]]` (it must end the class; it nests) becomes
 *   `[[base]&&[^[excluded]]]`. A single literal `&` inside a class is escaped (`&&` is an operator in Java classes).
 * - Unicode block escapes `\p{IsBlock}` become `\p{InBlock}`.
 *
 * Character classes are parsed as XML Schema `charClassExpr`:
 * - a class cannot be empty and `]` is never a member unless escaped: `[]`, `[^]`, `[].]` and `[^].]` are rejected
 *   (`java.util.regex` and POSIX read a `]` right after the opening bracket as a member; write `\]`);
 * - `-` is a literal at the start or at the end of a class, after a range (`[a-c-e]`) and next to a multi-character
 *   escape (`[\w-.]`, `[+-\w]`); between two single characters it makes a range, whose endpoints may be escapes but
 *   never an unescaped hyphen (`[a--b]`, `[+--]`, `[--a]` are rejected; write `\-`);
 * - `-[` always starts a subtraction, which needs a non-empty base (`[-[a]]` is rejected);
 * - an unterminated class is rejected.
 *
 * The `i` flag follows XPath F&O 5.6.2: characters, character ranges (also in a negated class or a subtraction) and
 * back-references match case-insensitively; `\p` / `\P`, `\i`, `\I`, `\c`, `\C` and POSIX classes stay case-sensitive
 * (`java.util.regex` would make `\p{Lu}` match every cased letter): they are compiled outside the scope of the flag
 * (`(?-i:...)`), also as members of a class.
 *
 * Passed through, because the meaning is the same: lazy and possessive quantifiers, back-references, non-capturing
 * and named groups, lookarounds, atomic groups, general-category escapes (`\p{Lu}`) and `\Q...\E` quoting (copied
 * verbatim, so `$`, `^` and `.` inside it stay literal).
 *
 * **Compatibility** (unconditional, there is no switch):
 * - A **leading inline flag group**: one or more groups `(?flags)` at the very start of the pattern, with flags among
 *   `i`, `m`, `s`, `x`, are removed from the pattern and their flags are added to those of `sh:flags` (ignored under
 *   `q`). After a leading `(?x)`, the two things Java's comments mode reads differently are rejected: an unescaped
 *   `#` and whitespace inside a character class.
 * - **Script names**: `\p{IsX}` is a block when `X` is a block name (as in XML Schema); otherwise, when `X` is a
 *   Unicode script name that the JVM knows (`\p{IsLatin}`, `\p{IsHan}`), it is that script. Names that are both
 *   (`Greek`, `Cyrillic`) are blocks.
 * - **POSIX bracket expressions** inside a character class: `[:alpha:]`, `[:digit:]`, `[:alnum:]`, `[:upper:]`,
 *   `[:lower:]`, `[:space:]`, `[:punct:]`, `[:xdigit:]`, `[:blank:]`, `[:cntrl:]`, `[:graph:]` and `[:print:]`
 *   become the `java.util.regex` classes `\p{Alpha}`, `\p{Digit}`... (US-ASCII). Other names are rejected.
 *
 * Rejected: `&&` inside a character class; an unescaped `[` inside a character class other than a POSIX bracket
 * expression; inline flag groups anywhere but at the start, scoped (`(?i:...)`), negated (`(?-i)`) or with flags other
 * than `i`, `m`, `s`, `x`; `\p{IsX}` where `X` is neither a Unicode block nor a Unicode script.
 */
internal object ShaclPatterns {

    /** A pattern that is not an XPath regular expression this translation can honour, or an unknown `sh:flags` flag. */
    class PatternRejectedException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

    /** A translated pattern: the `java.util.regex` [source] and the names of the [RegexOption]s to compile it with. */
    class Translated(val source: String, val options: List<String>) {
        fun toRegex(): Regex = Regex(source, options.map { RegexOption.valueOf(it) }.toSet())
    }

    /** Flags a leading inline flag group may set. */
    private const val LEADING_INLINE_FLAGS = "imsx"

    /**
     * Translates [pattern] with [flags] (see the class documentation); the result is not compiled.
     *
     * @throws PatternRejectedException for an unknown flag or a construct that is rejected
     */
    fun translate(pattern: String, flags: String?): Translated {
        var ignoreCase = false
        var quote = false
        var extended = false
        var multiLine = false
        var dotAll = false
        flags?.forEach { c ->
            when (c) {
                'i' -> ignoreCase = true
                'm' -> multiLine = true
                's' -> dotAll = true
                'x' -> extended = true
                'q' -> quote = true
                else -> throw PatternRejectedException("Unsupported sh:flags character '$c' in \"$flags\"")
            }
        }
        if (quote) {
            // The pattern is literal text (a leading flag group included); only i still applies.
            return Translated(pattern, listOfNotNull("IGNORE_CASE".takeIf { ignoreCase }, "LITERAL"))
        }
        // Leading inline flag groups: "(?" + one or more of i, m, s, x + ")", repeated, at the very start.
        var start = 0
        var inlineExtended = false
        while (pattern.startsWith("(?", start)) {
            var end = start + 2
            while (end < pattern.length && pattern[end] in LEADING_INLINE_FLAGS) end++
            if (end == start + 2 || end >= pattern.length || pattern[end] != ')') break
            for (k in start + 2 until end) {
                when (pattern[k]) {
                    'i' -> ignoreCase = true
                    'm' -> multiLine = true
                    's' -> dotAll = true
                    else -> inlineExtended = true
                }
            }
            start = end + 1
        }
        val source = XPathRegexTranslator(
            p = pattern,
            start = start,
            extended = extended || inlineExtended,
            javaComments = inlineExtended,
            multiLine = multiLine,
            dotAll = dotAll,
            ignoreCase = ignoreCase,
        ).translate()
        // The anchors are translated: Java's MULTILINE mode (other line terminators) is never used.
        return Translated(source, listOfNotNull("IGNORE_CASE".takeIf { ignoreCase }, "DOT_MATCHES_ALL".takeIf { dotAll }))
    }

    /**
     * Translates and compiles [pattern]: what generated code will run.
     *
     * @throws PatternRejectedException for an unknown flag, a rejected construct or a pattern the JVM cannot compile
     */
    fun compile(pattern: String, flags: String?): Regex {
        val translated = translate(pattern, flags)
        return try {
            translated.toRegex()
        } catch (e: PatternSyntaxException) {
            throw PatternRejectedException("Invalid sh:pattern \"$pattern\": ${e.description}", e)
        }
    }

    /** The `java.util.regex` source of [pattern] (see [translate]). */
    fun toJava(pattern: String, flags: String?): String = translate(pattern, flags).source

    /**
     * The [RegexOption] names that `sh:flags` alone asks for: `i` and `s`, and `LITERAL` for `q` (with which only `i`
     * still applies). `m` and `x` are applied by the translation. A leading inline flag group of a pattern adds to
     * them: use [translate] for the options of a pattern.
     */
    fun options(flags: String?): List<String> {
        val f = flags.orEmpty()
        return if ('q' in f) {
            listOfNotNull("IGNORE_CASE".takeIf { 'i' in f }, "LITERAL")
        } else {
            listOfNotNull("IGNORE_CASE".takeIf { 'i' in f }, "DOT_MATCHES_ALL".takeIf { 's' in f })
        }
    }

    /** Why [pattern] with [flags] cannot be used (rejected by the translation or by the JVM), otherwise null. */
    fun error(pattern: String, flags: String?): String? = try {
        compile(pattern, flags)
        null
    } catch (e: PatternRejectedException) {
        (e.message ?: "invalid regular expression").removePrefix("Invalid sh:pattern \"$pattern\": ")
    }

    /** Patterns in [model] that are rejected or invalid, one message per shape, path and pattern. */
    fun problems(model: OntologyModel): List<String> =
        model.shapes.sortedBy { it.targetClass }.flatMap { shape ->
            shape.properties.sortedBy { it.path }.flatMap { property ->
                property.patterns.mapNotNull { (pattern, flags) ->
                    error(pattern, flags)?.let {
                        "shape <${shape.shapeIri}>, path <${property.path}>: sh:pattern \"$pattern\" is not a valid regular expression ($it)"
                    }
                }
            }
        }.distinct()

    /** Deterministic name of a lazily compiled pattern constant, shared by every generator emitting one file. */
    fun constantName(pattern: String, flags: String?): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${flags.orEmpty()}\n$pattern".toByteArray(Charsets.UTF_8))
        return "PATTERN_" + digest.take(6).joinToString("") { "%02x".format(it) }
    }

    /** `private val <constantName>: Regex by lazy { Regex(...) }`: compiled on first use, never during class init. */
    fun lazyProperty(pattern: String, flags: String?, name: String = constantName(pattern, flags)): PropertySpec =
        PropertySpec.builder(name, Regex::class)
            .addModifiers(KModifier.PRIVATE)
            .delegate(CodeBlock.of("lazy { %L }", regexCode(pattern, flags)))
            .build()
}

/**
 * Translates one XPath / XML Schema regular expression to `java.util.regex` syntax (see [ShaclPatterns]),
 * from index [start] of [p]. [extended] removes whitespace outside character classes (the `x` flag); [javaComments]
 * says that the flag came from a leading `(?x)`, where what Java's comments mode reads differently is rejected.
 */
private class XPathRegexTranslator(
    private val p: String,
    start: Int,
    private val extended: Boolean,
    private val javaComments: Boolean,
    private val multiLine: Boolean,
    private val dotAll: Boolean,
    private val ignoreCase: Boolean,
) {
    private var i = start

    /** What the last [escape] was (it sets this as a second result). */
    private var escapeKind = EscapeKind.SINGLE

    private enum class EscapeKind {
        /** One character: `\n`, `\\`, `\]`, `\-`, `\x41`... It can be the endpoint of a range. */
        SINGLE,

        /** A set of characters that the `i` flag cannot change: `\d`, `\s`, `\w` and their complements, `\Q...\E`. */
        SET,

        /** A set of characters that `java.util.regex` changes under `CASE_INSENSITIVE` although XPath does not. */
        CASE_SENSITIVE_SET,
    }

    /** A translated character class: [text] matches exactly one character; [plain] says it is a Java class `[...]`. */
    private class ClassExpr(val text: String, val plain: Boolean)

    private fun reject(reason: String): Nothing = throw ShaclPatterns.PatternRejectedException("Invalid sh:pattern \"$p\": $reason")

    fun translate(): String {
        val out = StringBuilder(p.length + 16)
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' && i + 1 < p.length -> {
                    val text = escape()
                    // XPath F&O 5.6.2: only characters, ranges and back-references are affected by the i flag.
                    if (ignoreCase && escapeKind == EscapeKind.CASE_SENSITIVE_SET) out.append("(?-i:").append(text).append(')') else out.append(text)
                }
                c == '[' -> out.append(charClass().text)
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
                extended && isPatternWhitespace(c) -> i++
                javaComments && c == '#' -> reject(
                    "unescaped '#' at index $i after a leading (?x): java.util.regex reads it as the start of a comment, " +
                        "XPath regular expressions as a literal; write \\# for a literal, or remove the comment",
                )
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun isPatternWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    /**
     * Rejects the inline flag group starting at [i] (`(`), if it is one: `(?` followed by at least one of the
     * `java.util.regex` flag letters or `-`, then `)` or `:`. `(?:`, lookarounds and named groups are not flag groups.
     * Leading groups of `i`, `m`, `s`, `x` never get here: [ShaclPatterns.translate] consumes them.
     */
    private fun rejectInlineFlags() {
        if (!p.startsWith("(?", i)) return
        var end = i + 2
        while (end < p.length && p[end] in INLINE_FLAG_CHARACTERS) end++
        if (end == i + 2 || end >= p.length || (p[end] != ')' && p[end] != ':')) return
        reject(
            "inline flag group \"${p.substring(i, end + 1)}\" at index $i is java.util.regex syntax that XPath regular " +
                "expressions do not have, and the anchors and the dot are translated according to the flags of the whole " +
                "pattern. Only groups of the flags i, m, s, x at the very start of the pattern are supported, e.g. " +
                "(?i)^abc\$ (they are merged with sh:flags); otherwise set sh:flags (i, m, s, x) on the shape instead",
        )
    }

    /**
     * Translates the escape starting at [i] (a backslash with a following character), moves past it and sets
     * [escapeKind].
     */
    private fun escape(): String {
        val n = p[i + 1]
        if (n == 'Q') {
            // Java literal quoting (not XPath syntax): copied verbatim up to and including `\E`, or to the end.
            val end = p.indexOf("\\E", i + 2)
            val stop = if (end < 0) p.length else end + 2
            escapeKind = EscapeKind.SET
            return p.substring(i, stop).also { i = stop }
        }
        if (n == 'p' || n == 'P') {
            escapeKind = EscapeKind.CASE_SENSITIVE_SET
            if (p.startsWith("{Is", i + 2)) return blockOrScriptEscape(n)
            // `\p{Name}` is copied whole, so that the name is never read as pattern text; Java also has `\pL`.
            val from = i
            i += 2
            if (i < p.length && p[i] == '{') {
                val close = p.indexOf('}', i)
                if (close < 0) reject("the category escape at index $from is not terminated: '}' expected")
                i = close + 1
            } else if (i < p.length) {
                i++
            }
            return p.substring(from, i)
        }
        val from = i
        i += 2
        // Multi-character escapes are self-contained classes: inside a character class they are union members, so a
        // neighbouring hyphen (`[\w-.]`, `[+-\w]`) is a literal and never forms a range with their first or last member.
        escapeKind = EscapeKind.SET
        when (n) {
            'd' -> return "\\p{Nd}"
            'D' -> return "\\P{Nd}"
            'w' -> return "[$WORD]"
            'W' -> return "[^$WORD]"
            's' -> return "[$SPACE]"
            'S' -> return "[^$SPACE]"
        }
        // The name classes are code point ranges: Java would add the case variants of their members under `i`.
        escapeKind = EscapeKind.CASE_SENSITIVE_SET
        when (n) {
            'i' -> return "[$NAME_START]"
            'I' -> return "[^$NAME_START]"
            'c' -> return "[$NAME_CHAR]"
            'C' -> return "[^$NAME_CHAR]"
        }
        escapeKind = EscapeKind.SINGLE
        // Java's numeric character escapes are copied with their digits, so that the digits are not read as members.
        when (n) {
            'x' ->
                if (i < p.length && p[i] == '{') {
                    val close = p.indexOf('}', i)
                    if (close >= 0) i = close + 1
                } else {
                    skipWhile(2) { it.isHexDigit() }
                }
            'u' -> skipWhile(4) { it.isHexDigit() }
            '0' -> skipWhile(3) { it in '0'..'7' }
        }
        return p.substring(from, i)
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private inline fun skipWhile(max: Int, accept: (Char) -> Boolean) {
        var n = 0
        while (n < max && i < p.length && accept(p[i])) {
            i++
            n++
        }
    }

    /**
     * Translates `\p{IsName}` / `\P{IsName}` starting at [i] and moves past it: a block when `Name` is a block name
     * (XML Schema), otherwise a script when it is a script name (`java.util.regex`).
     */
    private fun blockOrScriptEscape(kind: Char): String {
        val close = p.indexOf('}', i + 5)
        if (close < 0) reject("the block escape at index $i is not terminated: '}' expected")
        val name = p.substring(i + 5, close)
        val block = XSD_BLOCK_ALIASES[name] ?: name
        val isBlock = try {
            Character.UnicodeBlock.forName(block)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
        val isScript = !isBlock && try {
            Character.UnicodeScript.forName(name)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
        if (!isBlock && !isScript) {
            reject(
                "\\$kind{Is$name} at index $i: \"$name\" is neither a Unicode block nor a Unicode script. In XML Schema " +
                    "regular expressions \\p{IsX} names a block (the Unicode block name without spaces, e.g. " +
                    "\\p{IsBasicLatin}, \\p{IsGreek}, \\p{IsLatin-1Supplement}); a java.util.regex script name is also " +
                    "accepted (\\p{IsLatin}, \\p{IsHan}); java.util.regex binary properties (\\p{IsAlphabetic}) are not " +
                    "supported",
            )
        }
        i = close + 1
        return if (isBlock) "\\$kind{In$block}" else "\\$kind{Is$name}"
    }

    /**
     * Translates the POSIX bracket expression `[:name:]` starting at [i] (inside a character class) and moves past it.
     */
    private fun posixClass(): String {
        val close = p.indexOf(":]", i + 2)
        val name = if (close < 0) null else p.substring(i + 2, close)
        val java = name?.let { POSIX_CLASSES[it] } ?: reject(
            "\"${if (close < 0) p.substring(i) else p.substring(i, close + 2)}\" at index $i is not a POSIX bracket " +
                "expression that can be translated: the supported names are ${POSIX_CLASSES.keys.joinToString(", ") { "[:$it:]" }} " +
                "(inside a character class, e.g. [[:alpha:]_]); write \\[ for a literal bracket",
        )
        i = close + 2
        return "\\p{$java}"
    }

    /**
     * Translates the character class expression starting at [i] (`[`) into an expression that matches one character
     * and moves past its closing `]`.
     */
    private fun charClass(): ClassExpr {
        val open = i
        i++ // [
        val negated = i < p.length && p[i] == '^'
        if (negated) i++
        // Members that follow the i flag (characters and ranges; sets it cannot change) and, under the i flag only,
        // the members it must not change (see EscapeKind.CASE_SENSITIVE_SET).
        val members = StringBuilder()
        val caseSensitive = StringBuilder()
        var count = 0
        // Whether the previous member is a single character (it can start a range), and an unescaped hyphen.
        var single = false
        var hyphen = false
        fun member(text: CharSequence, isSingle: Boolean, sensitive: Boolean = false) {
            (if (sensitive && ignoreCase) caseSensitive else members).append(text)
            count++
            single = isSingle
            hyphen = false
        }
        fun close(): ClassExpr {
            val not = if (negated) "^" else ""
            return when {
                caseSensitive.isEmpty() -> ClassExpr("[$not$members]", plain = true)
                members.isEmpty() -> ClassExpr("(?-i:[$not$caseSensitive])", plain = false)
                // Not a case-sensitive member and not one of the others.
                negated -> ClassExpr("(?:(?!(?-i:[$caseSensitive]))[^$members])", plain = false)
                else -> ClassExpr("(?:[$members]|(?-i:[$caseSensitive]))", plain = false)
            }
        }
        while (i < p.length) {
            val c = p[i]
            val next = if (i + 1 < p.length) p[i + 1] else null
            when {
                c == ']' -> {
                    if (count == 0) {
                        reject(
                            "']' at index $i right after \"${p.substring(open, i)}\": an XML Schema character class cannot " +
                                "be empty and an unescaped ']' is never a member (java.util.regex and POSIX would read it " +
                                "as a member here); write \\] for a literal bracket",
                        )
                    }
                    i++
                    return close()
                }
                c == '\\' && next != null -> {
                    val text = escape()
                    member(text, isSingle = escapeKind == EscapeKind.SINGLE, sensitive = escapeKind == EscapeKind.CASE_SENSITIVE_SET)
                }
                c == '-' && next == '[' -> {
                    // XML Schema: charClassSub ::= ( posCharGroup | negCharGroup ) '-' charClassExpr, then ']'.
                    val at = i
                    if (count == 0) {
                        reject(
                            "the character class subtraction at index $at has nothing to subtract from (XML Schema: " +
                                "[base-[excluded]]); write \\- for a literal hyphen and \\[ for a literal bracket",
                        )
                    }
                    i++
                    val excluded = charClass()
                    if (i >= p.length || p[i] != ']') {
                        reject(
                            "the character class subtraction at index $at must end its class: ']' expected after " +
                                "\"-[...]\" (XML Schema: [base-[excluded]])",
                        )
                    }
                    i++
                    val base = close()
                    return if (base.plain && excluded.plain) {
                        ClassExpr("[${base.text}&&[^${excluded.text}]]", plain = true)
                    } else {
                        // One character that the subtrahend does not match and the base does.
                        ClassExpr("(?:(?!${excluded.text})${base.text})", plain = false)
                    }
                }
                c == '[' && next == ':' -> member(posixClass(), isSingle = false, sensitive = true)
                c == '[' -> reject(
                    "unescaped '[' inside a character class at index $i: nested classes and unions ([a-c[x-z]]) are " +
                        "java.util.regex syntax that XPath regular expressions do not have; write \\[ for a literal " +
                        "bracket, [base-[excluded]] for a class subtraction, or [[:alpha:]] for a POSIX class",
                )
                c == '&' && next == '&' -> reject(
                    "'&&' inside a character class at index $i: class intersection is java.util.regex syntax that " +
                        "XPath regular expressions do not have; write [base-[excluded]] for a class subtraction, or " +
                        "\\& for a literal ampersand",
                )
                c == '&' -> {
                    member("\\&", isSingle = true)
                    i++
                }
                c == '-' -> {
                    val literal = when {
                        // At the start or at the end of the class, and after a range or a multi-character escape.
                        count == 0 || next == ']' || next == null || !single -> true
                        // Before a multi-character escape: `[+-\w]` (it cannot end a range).
                        next == '\\' && i + 2 < p.length && p[i + 2] in SET_ESCAPE_LETTERS -> true
                        else -> false
                    }
                    if (literal) {
                        val first = count == 0
                        member("\\-", isSingle = first)
                        hyphen = first
                        i++
                    } else {
                        if (hyphen || next == '-') {
                            reject(
                                "unescaped '-' as an endpoint of the character range at index $i: XML Schema does not " +
                                    "allow it and java.util.regex would read a range from or to the hyphen; write \\- " +
                                    "for a literal hyphen",
                            )
                        }
                        // A range: the operator and its second endpoint, a character or a single-character escape.
                        members.append('-')
                        i++
                        if (p[i] == '\\' && i + 1 < p.length) {
                            members.append(escape())
                        } else {
                            rejectClassWhitespace(p[i])
                            members.append(if (p[i] == '&') "\\&" else p[i].toString())
                            i++
                        }
                        single = false
                        hyphen = false
                    }
                }
                else -> {
                    rejectClassWhitespace(c)
                    member(c.toString(), isSingle = true)
                    i++
                }
            }
        }
        reject("the character class starting at index $open is not terminated: ']' expected")
    }

    private fun rejectClassWhitespace(c: Char) {
        if (javaComments && isPatternWhitespace(c)) {
            reject(
                "whitespace inside a character class at index $i after a leading (?x): java.util.regex ignores it, XPath " +
                    "regular expressions keep it as a member; escape it (\\x20, \\t) or remove it",
            )
        }
    }

    private companion object {
        /**
         * Members of `\w`: XML Schema's "every character except punctuation (P), separators (Z) and other (C)" -
         * every code point has exactly one general category, so that is L, M, N and S - plus the underscore (see
         * [ShaclPatterns]). `\W` is the complement `[^...]` of the same members.
         */
        const val WORD = "_\\p{L}\\p{M}\\p{N}\\p{S}"
        const val SPACE = " \\t\\n\\r"

        /** `java.util.regex` inline flag letters (`(?idmsuxU-idmsuxU)`). */
        const val INLINE_FLAG_CHARACTERS = "idmsuxU-"

        /** The letters of the escapes that stand for a set of characters (they cannot be the endpoint of a range). */
        const val SET_ESCAPE_LETTERS = "dDwWsSiIcCpPQ"

        /** POSIX bracket expression names and the `java.util.regex` POSIX character classes (US-ASCII) they become. */
        val POSIX_CLASSES: Map<String, String> = linkedMapOf(
            "alpha" to "Alpha", "digit" to "Digit", "alnum" to "Alnum", "upper" to "Upper", "lower" to "Lower",
            "space" to "Space", "punct" to "Punct", "xdigit" to "XDigit", "blank" to "Blank", "cntrl" to "Cntrl",
            "graph" to "Graph", "print" to "Print",
        )

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
