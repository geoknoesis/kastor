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
    
    /**
     * Converts a name to a valid Kotlin identifier.
     * Defaults to camelCase conversion.
     */
    fun toValidKotlinIdentifier(name: String): String = toCamelCase(name)

    /** Converts a raw value/local-name to an UPPER_SNAKE Kotlin enum constant. */
    fun toEnumConstant(raw: String): String {
        val spaced = raw.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        val parts = spaced.split('-', '_', ' ', '.').filter { it.isNotBlank() }
        val joined = parts.joinToString("_") { it.uppercase() }
        val safe = joined.ifEmpty { "VALUE" }
        return if (safe.first().isDigit()) "_$safe" else safe
    }
}


