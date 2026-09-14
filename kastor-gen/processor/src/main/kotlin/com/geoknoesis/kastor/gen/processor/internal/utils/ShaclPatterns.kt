package com.geoknoesis.kastor.gen.processor.internal.utils

import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import java.security.MessageDigest
import java.util.regex.PatternSyntaxException

/**
 * `sh:pattern` values are XPath (`fn:matches`) regular expressions. Generated code runs them with
 * `kotlin.text.Regex` (java.util.regex), so the XPath-only syntax is translated first:
 * - `\i` / `\I` (XML name-start characters) and `\c` / `\C` (XML name characters), approximated with Unicode
 *   letter/digit classes plus `_`, `:`, `.`, `-` and U+00B7;
 * - character-class subtraction `[base-[excluded]]` becomes `[base&&[^excluded]]`;
 * - Unicode block escapes `\p{IsBlock}` become `\p{InBlock}`;
 * - a literal `&&` or `[` inside a class is escaped (they are operators in Java classes).
 *
 * The `q` flag (literal pattern) disables translation. Patterns that are still invalid after translation are
 * reported at generation time by [problems].
 */
internal object ShaclPatterns {

    private const val NAME_START = "\\p{L}_:"
    private const val NAME_CHAR = "\\p{L}\\p{Nd}._:\\-\\u00B7"

    fun toJava(pattern: String, flags: String?): String {
        if (flags.orEmpty().contains('q')) return pattern
        val out = StringBuilder(pattern.length + 16)
        var depth = 0
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    when (val n = pattern[i + 1]) {
                        'i' -> out.append(if (depth > 0) NAME_START else "[$NAME_START]")
                        'I' -> out.append("[^$NAME_START]")
                        'c' -> out.append(if (depth > 0) NAME_CHAR else "[$NAME_CHAR]")
                        'C' -> out.append("[^$NAME_CHAR]")
                        'p', 'P' -> {
                            if (pattern.startsWith("{Is", i + 2)) {
                                out.append('\\').append(n).append("{In")
                                i += 5
                                continue
                            }
                            out.append('\\').append(n)
                        }
                        else -> out.append('\\').append(n)
                    }
                    i += 2
                }
                depth > 0 && c == '-' && i + 1 < pattern.length && pattern[i + 1] == '[' -> {
                    val negated = i + 2 < pattern.length && pattern[i + 2] == '^'
                    out.append(if (negated) "&&[" else "&&[^")
                    depth++
                    i += if (negated) 3 else 2
                }
                depth > 0 && c == '[' -> { out.append("\\["); i++ }
                depth > 0 && c == '&' -> { out.append("\\&"); i++ }
                c == '[' -> {
                    depth++
                    out.append('[')
                    i++
                    if (i < pattern.length && pattern[i] == '^') { out.append('^'); i++ }
                }
                depth > 0 && c == ']' -> { depth--; out.append(']'); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** The [RegexOption] names for SHACL `sh:flags` (i, m, s, x, q). */
    fun options(flags: String?): List<String> = flags.orEmpty().mapNotNull {
        when (it) {
            'i' -> "IGNORE_CASE"
            'm' -> "MULTILINE"
            's' -> "DOT_MATCHES_ALL"
            'x' -> "COMMENTS"
            'q' -> "LITERAL"
            else -> null
        }
    }.distinct()

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
