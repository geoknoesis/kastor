package com.geoknoesis.kastor.gen.processor.extensions

import com.geoknoesis.kastor.gen.processor.model.ClassBuilderModel
import com.geoknoesis.kastor.gen.processor.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.model.NamingStrategy
import com.geoknoesis.kastor.gen.processor.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.utils.VocabularyMapper

/**
 * Extension functions for collections and models.
 */

/**
 * Checks if a shape contains a property with the given IRI.
 *
 * This operator allows using the `in` keyword to check property existence:
 * ```kotlin
 * if ("http://example.org/property" in shape) {
 *     // Property exists
 * }
 * ```
 *
 * @param propertyIri The IRI of the property to check
 * @return true if the shape contains a property with the given IRI
 *
 * @sample com.example.CheckPropertyInShape
 */
operator fun ShaclShape.contains(propertyIri: String): Boolean {
    return properties.any { it.path == propertyIri }
}

/**
 * Gets a property from a shape by its IRI.
 *
 * This operator allows using bracket notation to access properties:
 * ```kotlin
 * val property = shape["http://example.org/property"]
 * ```
 *
 * @param propertyIri The IRI of the property to retrieve
 * @return The property if found, null otherwise
 */
operator fun ShaclShape.get(propertyIri: String): ShaclProperty? {
    return properties.find { it.path == propertyIri }
}

/**
 * Finds properties of a specific datatype in a shape.
 *
 * @param datatype The datatype IRI to filter by
 * @return List of properties with the given datatype
 */
fun ShaclShape.propertiesOfType(datatype: String): List<ShaclProperty> {
    return properties.filter { it.datatype == datatype }
}

/**
 * Finds a shape for a given class IRI in an ontology model.
 *
 * @param classIri The class IRI to find a shape for
 * @return The shape if found, null otherwise
 */
fun OntologyModel.findShapeForClass(classIri: String): ShaclShape? {
    return shapes.find { it.targetClass == classIri }
}

/**
 * Creates a new DslGenerationOptions with updated validation settings.
 *
 * @param enabled Whether validation should be enabled
 * @return A new DslGenerationOptions with updated validation config
 */
fun DslGenerationOptions.withValidation(enabled: Boolean): DslGenerationOptions {
    return copy(validation = validation.copy(enabled = enabled))
}

/**
 * Creates a new DslGenerationOptions with updated output settings.
 *
 * @param supportLanguageTags Whether to support language tags
 * @return A new DslGenerationOptions with updated output config
 */
fun DslGenerationOptions.withLanguageTags(supportLanguageTags: Boolean): DslGenerationOptions {
    return copy(output = output.copy(supportLanguageTags = supportLanguageTags))
}

/**
 * Creates a new DslGenerationOptions with updated naming strategy.
 *
 * @param strategy The naming strategy to use
 * @return A new DslGenerationOptions with updated naming config
 */
fun DslGenerationOptions.withNamingStrategy(strategy: NamingStrategy): DslGenerationOptions {
    return copy(naming = naming.copy(strategy = strategy))
}

/**
 * Creates a new DslGenerationOptions with updated default language.
 *
 * @param defaultLanguage The default language tag to use
 * @return A new DslGenerationOptions with updated output config
 */
fun DslGenerationOptions.withDefaultLanguage(defaultLanguage: String?): DslGenerationOptions {
    return copy(output = output.copy(defaultLanguage = defaultLanguage))
}

/**
 * Checks if a property is required (minCount >= 1).
 */
fun ShaclProperty.isRequired(): Boolean = minCount != null && minCount >= 1

/**
 * Checks if a property accepts multiple values (maxCount > 1 or null).
 */
fun ShaclProperty.isList(): Boolean = maxCount == null || maxCount > 1

/**
 * Gets all required properties from a shape.
 */
fun ShaclShape.requiredProperties(): List<ShaclProperty> {
    return properties.filter { it.isRequired() }
}

/**
 * Gets all optional properties from a shape.
 */
fun ShaclShape.optionalProperties(): List<ShaclProperty> {
    return properties.filter { !it.isRequired() }
}

/**
 * Collects all required imports from class builders.
 */
internal fun List<ClassBuilderModel>.collectRequiredImports(): Set<String> {
    return flatMap { builder ->
        setOf(builder.classIri) + builder.properties.map { it.propertyIri }
    }.let { VocabularyMapper.getRequiredImports(it) }
}

/**
 * Groups shapes by their target class IRI.
 */
internal fun List<ShaclShape>.groupByTargetClass(): Map<String, ShaclShape> {
    return associateBy { it.targetClass }
}

/**
 * Finds a shape for a given class IRI.
 */
internal fun List<ShaclShape>.findShapeForClass(classIri: String): ShaclShape? {
    return find { it.targetClass == classIri }
}

