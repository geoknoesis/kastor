package com.geoknoesis.kastor.gen.processor.internal.utils

import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty

/**
 * Unified naming utilities for code generation.
 *
 * Single source of truth for every Kotlin name derived from ontology data: type names (interfaces,
 * wrappers, data classes, factories, enums) come from [domainName]; property/member names come from
 * [propertyName]. All generators MUST use these so that e.g. a wrapper overrides exactly the property the
 * interface declares.
 *
 * Returned identifiers are *unescaped* (e.g. `class`, not `` `class` ``); emit them through KotlinPoet
 * (`PropertySpec.builder(name, …)`, `%N`) which backtick-escapes keywords where needed. Derive local
 * variable names from the unescaped form (e.g. `_classList`).
 */
internal object NamingUtils {

    /** Kotlin type name for [iri]: the JSON-LD context term mapped to it, or the IRI's local name. */
    fun domainName(iri: String, context: JsonLdContext): String =
        context.typeMappings.entries
            .filter { it.value.value == iri }
            .minByOrNull { it.key }
            ?.key
            ?.let { toTypeIdentifier(it) }
            ?: extractInterfaceName(iri)

    /** Kotlin member name for a SHACL property (from `sh:name`, falling back to the path local name). */
    fun propertyName(property: ShaclProperty): String = toMemberIdentifier(property.name)

    /**
     * Members generated alongside ontology properties; an ontology property whose name would clash with
     * one of them gets a `Value` suffix (consistently in every generator).
     */
    private val RESERVED_MEMBER_NAMES = setOf(
        "rdf", "known", "validate", "writeToGraph", "propertyMappings",
        "equals", "hashCode", "toString", "copy",
    )

    /**
     * Converts a name to camelCase.
     * Handles hyphens, underscores, and spaces.
     */
    fun toCamelCase(name: String): String {
        val parts = name.split('-', '_', ' ')
        return parts.mapIndexed { index, part ->
            if (index == 0) part.replaceFirstChar { it.lowercaseChar() }
            else part.replaceFirstChar { it.uppercaseChar() }
        }.joinToString("")
    }

    /**
     * Converts a name to PascalCase.
     * Handles hyphens, underscores, and spaces.
     */
    fun toPascalCase(name: String): String {
        val parts = name.split('-', '_', ' ')
        return parts.joinToString("") { part ->
            part.replaceFirstChar { it.uppercaseChar() }
        }
    }

    /**
     * Extracts interface name from an IRI.
     * Returns PascalCase interface name.
     */
    fun extractInterfaceName(classIri: String): String =
        toTypeIdentifier(classIri.substringAfterLast('/').substringAfterLast('#'))

    /** Kotlin hard keywords that cannot be used as identifiers unless backtick-escaped. */
    private val KOTLIN_HARD_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    fun isKeyword(name: String): Boolean = name in KOTLIN_HARD_KEYWORDS

    private fun words(raw: String): List<String> =
        raw.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotEmpty() }

    /** PascalCase type identifier: non-identifier characters split words; a leading digit gets `_`. */
    fun toTypeIdentifier(raw: String): String {
        val joined = words(raw).joinToString("") { w -> w.replaceFirstChar { it.uppercaseChar() } }
        val safe = when {
            joined.isEmpty() -> "Type"
            joined.first().isDigit() -> "_$joined"
            else -> joined
        }
        return safe
    }

    /**
     * camelCase member identifier (unescaped). Non-identifier characters split words
     * (`date-issued`, `Date issued` → `dateIssued`), a leading digit gets `_`, and names that clash with
     * generated members get a `Value` suffix.
     */
    fun toMemberIdentifier(raw: String): String {
        val joined = words(raw).mapIndexed { i, w ->
            if (i == 0) w.replaceFirstChar { it.lowercaseChar() } else w.replaceFirstChar { it.uppercaseChar() }
        }.joinToString("")
        val safe = when {
            joined.isEmpty() -> "value"
            joined.first().isDigit() -> "_$joined"
            else -> joined
        }
        return if (safe in RESERVED_MEMBER_NAMES) "${safe}Value" else safe
    }

    /**
     * Converts a name to a valid Kotlin identifier in camelCase, backtick-escaping hard keywords.
     * Prefer [propertyName]/[toMemberIdentifier] + KotlinPoet `%N`, which escape at emission time.
     */
    fun toValidKotlinIdentifier(name: String): String {
        val safe = toMemberIdentifier(name)
        return if (isKeyword(safe)) "`$safe`" else safe
    }

    /** Converts a raw value/local-name to an UPPER_SNAKE Kotlin enum constant. */
    fun toEnumConstant(raw: String): String {
        val spaced = raw.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        val parts = spaced.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }
        val joined = parts.joinToString("_") { it.uppercase() }
        val safe = joined.ifEmpty { "VALUE" }
        return if (safe.first().isDigit()) "_$safe" else safe
    }
}

/** Escapes text for use inside a KDoc block: comment delimiters are neutralised. Pass via `addKdoc("%L", …)`. */
internal fun kdocText(text: String): String =
    text.replace("/*", "&#47;*").replace("*/", "*&#47;")

/** Single cardinality interpretation shared by every generator. */
internal object Cardinality {
    /** Multi-valued unless `sh:maxCount` is 0 or 1 (`maxCount 0` = always absent → nullable single). */
    fun isList(property: ShaclProperty): Boolean = property.maxCount == null || property.maxCount > 1

    /** Single-valued and `sh:minCount >= 1` (a deactivated property shape imposes no cardinality). */
    fun isRequiredSingle(property: ShaclProperty): Boolean = !isList(property) && isRequired(property)

    /** Any cardinality with `sh:minCount >= 1`, unless the property shape is `sh:deactivated`. */
    fun isRequired(property: ShaclProperty): Boolean = !property.deactivated && (property.minCount ?: 0) > 0
}
