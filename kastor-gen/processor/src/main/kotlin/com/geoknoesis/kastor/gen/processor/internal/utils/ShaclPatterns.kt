package com.geoknoesis.kastor.gen.processor.internal.utils

import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.rdf.sparql.internal.XPathRegex
import com.geoknoesis.kastor.rdf.sparql.internal.XPathRegexException
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import java.security.MessageDigest

/**
 * `sh:pattern` values are XPath (`fn:matches`) / XML Schema regular expressions. Generated code checks them with
 * `com.geoknoesis.kastor.gen.runtime.ShaclPattern`, which runs them on RE2/J, a linear-time engine, through the very
 * translation the Kastor SHACL validator uses ([XPathRegex]): generated validation and SHACL validation accept and
 * reject the same values, and the same patterns, and no value can make a pattern backtrack catastrophically.
 *
 * A pattern that is invalid, or that the engine refuses (back-references, lookarounds, atomic groups, possessive
 * quantifiers, word boundaries, inline flag groups that are not leading, class intersection, repetition counts above
 * 1000, ...; [XPathRegex] has the full rule), fails generation with an error that names the shape and the property
 * ([problems]): a pattern is never silently given another meaning, and nothing falls back to `java.util.regex`.
 */
internal object ShaclPatterns {

    /** Why [pattern] with [flags] cannot be used (invalid, or refused by the linear-time engine), otherwise null. */
    fun error(pattern: String, flags: String?): String? = try {
        XPathRegex.compile(pattern, flags)
        null
    } catch (e: XPathRegexException) {
        e.reason
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

    /**
     * `private val <constantName>: ShaclPattern by lazy { ShaclPattern(...) }`: compiled on first use, never during
     * class initialisation. Generated code matches values with its `containsMatchIn`.
     */
    fun lazyProperty(pattern: String, flags: String?, name: String = constantName(pattern, flags)): PropertySpec {
        val type = ClassName("com.geoknoesis.kastor.gen.runtime", "ShaclPattern")
        val create = if (flags == null) CodeBlock.of("%T(%S)", type, pattern) else CodeBlock.of("%T(%S, %S)", type, pattern, flags)
        return PropertySpec.builder(name, type)
            .addModifiers(KModifier.PRIVATE)
            .delegate(CodeBlock.of("lazy { %L }", create))
            .build()
    }
}
