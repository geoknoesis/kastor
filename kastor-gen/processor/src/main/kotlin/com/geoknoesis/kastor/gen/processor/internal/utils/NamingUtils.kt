package com.geoknoesis.kastor.gen.processor.internal.utils

/**
 * Unified naming utilities for code generation.
 * Single source of truth for naming conventions.
 */
internal object NamingUtils {
    
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
    fun extractInterfaceName(classIri: String): String {
        val localName = classIri.substringAfterLast('/').substringAfterLast('#')
        return toPascalCase(localName)
    }
    
    /** Kotlin hard keywords that cannot be used as identifiers unless backtick-escaped. */
    private val KOTLIN_HARD_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    /**
     * Converts a name to a valid Kotlin identifier in camelCase. Ontology terms whose
     * local name collides with a Kotlin keyword (e.g. `class`, `object`, `in`) are
     * backtick-escaped, and names starting with a digit are prefixed with `_`, so the
     * generated code always compiles.
     */
    fun toValidKotlinIdentifier(name: String): String {
        val camel = toCamelCase(name)
        val safe = when {
            camel.isEmpty() -> "value"
            camel.first().isDigit() -> "_$camel"
            else -> camel
        }
        return if (safe in KOTLIN_HARD_KEYWORDS) "`$safe`" else safe
    }

    /** Converts a raw value/local-name to an UPPER_SNAKE Kotlin enum constant. */
    fun toEnumConstant(raw: String): String {
        val spaced = raw.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        val parts = spaced.split('-', '_', ' ', '.').filter { it.isNotBlank() }
        val joined = parts.joinToString("_") { it.uppercase() }
        val safe = joined.ifEmpty { "VALUE" }
        return if (safe.first().isDigit()) "_$safe" else safe
    }
}


