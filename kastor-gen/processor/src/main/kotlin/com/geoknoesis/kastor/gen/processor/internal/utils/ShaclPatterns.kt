package com.geoknoesis.kastor.gen.processor.internal.utils

import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import java.security.MessageDigest
import java.util.regex.PatternSyntaxException

/**
 * `sh:pattern` values are XPath (`fn:matches`) regular expressions. Generated code runs them with
 * `kotlin.text.Regex` (java.util.regex), so XPath syntax whose meaning differs is translated first:
 * - `\d` / `\D` are Unicode decimal digits (`\p{Nd}`); `\w` is "everything but punctuation, separators and other
 *   characters" (`[^\p{P}\p{Z}\p{C}]`, the XML Schema definition, so symbols such as `$` are word characters) **plus
 *   the underscore** `_`, which XML Schema leaves out but the Kastor SHACL validator and every mainstream regex
 *   dialect count as a word character; `\W` is its complement (so it does not match `_`); and `\s` / `\S` are
 *   exactly space, tab, newline and carriage return. Java's defaults are ASCII-only or wider;
 * - `\i` / `\I` (XML name-start characters) and `\c` / `\C` (XML name characters), approximated with Unicode
 *   letter/digit classes plus `_`, `:`, `.`, `-` and U+00B7;
 * - character-class subtraction `[base-[excluded]]` becomes `[[base]&&[^[excluded]]]`, with every operand a
 *   self-contained class so a negated base (`[^a-z-[0-9]]` = "not a-z, minus digits") keeps XPath precedence;
 * - Unicode block escapes `\p{IsBlock}` become `\p{InBlock}`;
 * - the anchor `$` matches only at the very end of the input (`\z`): Java's `$` also matches before a final line
 *   terminator, so `^\d+$` would accept "123" followed by a newline. With the `m` flag `$` matches before a newline
 *   or at the end, and `^` at the start or after a newline: only a newline (U+000A) ends a line in XPath, whereas
 *   Java's line anchors also treat carriage return, U+0085, U+2028 and U+2029 as line terminators;
 * - without the `s` flag `.` matches every character except newline (U+000A) and carriage return (U+000D); Java's
 *   `.` also excludes U+0085, U+2028 and U+2029. With `s` it matches every character (`DOT_MATCHES_ALL`);
 * - a `\Q...\E` quotation (a java.util.regex extension that XPath does not define) is copied verbatim, so the
 *   characters inside it stay literal instead of being rewritten as anchors, dots or classes;
 * - a literal `&` or `[` inside a class is escaped (they are operators in Java classes);
 * - the `x` flag removes whitespace outside character classes during translation. It is not mapped to Java's
 *   `COMMENTS` mode, which would also treat `#` as a comment start and drop whitespace inside classes.
 *
 * The `q` flag (literal pattern) disables translation, and `m`, `s` and `x` have no effect with it (as in XPath).
 * Patterns that are still invalid after translation are reported at generation time by [problems].
 */
internal object ShaclPatterns {

    private const val NAME_START = "\\p{L}_:"
    private const val NAME_CHAR = "\\p{L}\\p{Nd}._:\\-\\u00B7"
    private const val SPACE = " \\t\\n\\r"
    /** XML Schema's non-word characters; the underscore (a `\p{P}` character) is taken out of them, see [WORD]. */
    private const val XSD_NOT_WORD = "\\p{P}\\p{Z}\\p{C}"
    /** `\w` as a self-contained class (a union member when nested in a class): XML Schema word characters and `_`. */
    private const val WORD = "[_[^$XSD_NOT_WORD]]"
    /** `\W` as a self-contained class: the complement of [WORD]. */
    private const val NOT_WORD = "[[$XSD_NOT_WORD]&&[^_]]"

    fun toJava(pattern: String, flags: String?): String {
        val f = flags.orEmpty()
        if ('q' in f) return pattern
        return Translator(pattern, extended = 'x' in f, multiline = 'm' in f, dotAll = 's' in f).translate()
    }

    private class Translator(
        private val p: String,
        private val extended: Boolean,
        private val multiline: Boolean,
        private val dotAll: Boolean,
    ) {
        private var i = 0

        /** Whether a `\Q` quotation starts at [i]. */
        private fun atQuotation(): Boolean = p.startsWith("\\Q", i)

        /** Copies the quotation starting at [i] up to and including its `\E` (or to the end of the pattern). */
        private fun quotation(): String {
            val end = p.indexOf("\\E", i + 2)
            val stop = if (end < 0) p.length else end + 2
            return p.substring(i, stop).also { i = stop }
        }

        fun translate(): String {
            val out = StringBuilder(p.length + 16)
            while (i < p.length) {
                val c = p[i]
                when {
                    atQuotation() -> out.append(quotation())
                    c == '\\' && i + 1 < p.length -> out.append(escape(inClass = false))
                    c == '[' -> out.append(charClass())
                    c == '.' && !dotAll -> {
                        out.append("[^\\n\\r]")
                        i++
                    }
                    c == '$' -> {
                        out.append(if (multiline) "(?=\\n|\\z)" else "\\z")
                        i++
                    }
                    c == '^' && multiline -> {
                        out.append("(?:\\A|(?<=\\n))")
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

        /** Translates the escape starting at [i] (a backslash with a following character) and moves past it. */
        private fun escape(inClass: Boolean): String {
            val n = p[i + 1]
            if ((n == 'p' || n == 'P') && p.startsWith("{Is", i + 2)) {
                i += 5 // the block name and `}` are copied as ordinary characters
                return "\\" + n + "{In"
            }
            i += 2
            return when (n) {
                'd' -> "\\p{Nd}"
                'D' -> "\\P{Nd}"
                // Nested classes are unions inside a Java class, so the self-contained forms work in both contexts.
                'w' -> WORD
                'W' -> NOT_WORD
                's' -> if (inClass) SPACE else "[$SPACE]"
                'S' -> "[^$SPACE]"
                'i' -> if (inClass) NAME_START else "[$NAME_START]"
                'I' -> "[^$NAME_START]"
                'c' -> if (inClass) NAME_CHAR else "[$NAME_CHAR]"
                'C' -> "[^$NAME_CHAR]"
                else -> "\\" + n
            }
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
            var subtraction: String? = null
            while (i < p.length) {
                val c = p[i]
                when {
                    c == ']' -> {
                        i++
                        val base = if (negated) "[^$group]" else "[$group]"
                        return if (subtraction == null) base else "[$base&&[^$subtraction]]"
                    }
                    atQuotation() -> group.append(quotation())
                    c == '\\' && i + 1 < p.length -> group.append(escape(inClass = true))
                    c == '-' && i + 1 < p.length && p[i + 1] == '[' -> {
                        i++
                        subtraction = charClass()
                    }
                    c == '[' -> {
                        group.append("\\[")
                        i++
                    }
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
    }

    /**
     * The [RegexOption] names for SHACL `sh:flags`: `i`, `m`, `s` and `q`. `x` is applied by [toJava]. With `q` only
     * `i` still applies. (Kotlin's `IGNORE_CASE` is Unicode-aware, like XPath's `i`.)
     */
    fun options(flags: String?): List<String> {
        val f = flags.orEmpty()
        val applicable = if ('q' in f) f.filter { it == 'i' || it == 'q' } else f
        return applicable.mapNotNull {
            when (it) {
                'i' -> "IGNORE_CASE"
                'm' -> "MULTILINE"
                's' -> "DOT_MATCHES_ALL"
                'q' -> "LITERAL"
                else -> null
            }
        }.distinct()
    }

    /** Error text when [pattern] cannot be compiled after translation, otherwise null. */
    fun error(pattern: String, flags: String?): String? = try {
        Regex(toJava(pattern, flags), options(flags).map { RegexOption.valueOf(it) }.toSet())
        null
    } catch (e: PatternSyntaxException) {
        e.description ?: e.message ?: "invalid regular expression"
    }

    /** Invalid patterns in [model], one message per shape and path. */
    fun problems(model: OntologyModel): List<String> =
        model.shapes.sortedBy { it.targetClass }.flatMap { shape ->
            shape.properties.sortedBy { it.path }.mapNotNull { property ->
                property.pattern?.let { pattern ->
                    error(pattern, property.patternFlags)?.let {
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
