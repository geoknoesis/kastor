package com.geoknoesis.kastor.gen.processor.model

import com.geoknoesis.kastor.gen.annotations.ValidationMode
import com.squareup.kotlinpoet.TypeName

/**
 * Model representing a class to generate a builder DSL for.
 *
 * This model encapsulates all information needed to generate a builder class
 * for creating instances of a particular RDF class.
 *
 * @param className The Kotlin class name (e.g., "Concept", "ConceptScheme")
 * @param classIri The full IRI of the RDF class
 * @param builderName The name for the builder function (e.g., "concept", "conceptScheme")
 * @param properties List of properties that can be set in the builder
 * @param shapeIri Optional IRI of the associated SHACL shape
 *
 * @sample com.example.GenerateSkosDsl
 */
data class ClassBuilderModel(
    val className: String,            // e.g., "Concept", "ConceptScheme"
    val classIri: String,             // Full IRI
    val builderName: String,           // e.g., "concept", "conceptScheme"
    val properties: List<PropertyBuilderModel>,
    val shapeIri: String? = null      // Associated SHACL shape IRI
)

/**
 * Model representing a property in a builder DSL.
 *
 * This model contains all information needed to generate setter methods
 * for a property in a builder class.
 *
 * @param propertyName The Kotlin property name (camelCase)
 * @param propertyIri The full IRI of the RDF property
 * @param kotlinType The Kotlin type representation (e.g., String, List<String>, String?)
 * @param isRequired Whether the property is required (from sh:minCount >= 1)
 * @param isList Whether the property accepts multiple values (from sh:maxCount > 1)
 * @param constraints SHACL constraints for the property
 */
data class PropertyBuilderModel(
    val propertyName: String,         // Kotlin property name (camelCase)
    val propertyIri: String,          // Full IRI
    val kotlinType: TypeName,         // Kotlin type (e.g., String, List<String>, String?)
    val isRequired: Boolean,           // From sh:minCount >= 1
    val isList: Boolean,               // From sh:maxCount > 1 or null
    val constraints: PropertyConstraints
)

/**
 * All SHACL constraint values for a property.
 *
 * This data class encapsulates all SHACL constraint types that can be applied
 * to a property, including string, numeric, and value constraints.
 *
 * @param minLength Minimum string length (sh:minLength)
 * @param maxLength Maximum string length (sh:maxLength)
 * @param pattern Regular expression pattern (sh:pattern)
 * @param minInclusive Minimum inclusive value (sh:minInclusive)
 * @param maxInclusive Maximum inclusive value (sh:maxInclusive)
 * @param minExclusive Minimum exclusive value (sh:minExclusive)
 * @param maxExclusive Maximum exclusive value (sh:maxExclusive)
 * @param inValues List of allowed values (sh:in)
 * @param hasValue Required value (sh:hasValue)
 * @param nodeKind Node kind constraint (sh:nodeKind)
 * @param qualifiedValueShape Qualified value shape (sh:qualifiedValueShape)
 * @param qualifiedMinCount Qualified minimum count (sh:qualifiedMinCount)
 * @param qualifiedMaxCount Qualified maximum count (sh:qualifiedMaxCount)
 */
data class PropertyConstraints(
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val minInclusive: Double? = null,
    val maxInclusive: Double? = null,
    val minExclusive: Double? = null,
    val maxExclusive: Double? = null,
    val inValues: List<String>? = null,
    val hasValue: String? = null,
    val nodeKind: String? = null,
    val qualifiedValueShape: String? = null,
    val qualifiedMinCount: Int? = null,
    val qualifiedMaxCount: Int? = null
) {
    companion object {
        /**
         * Creates PropertyConstraints from a ShaclProperty.
         */
        fun from(property: ShaclProperty): PropertyConstraints {
            return PropertyConstraints(
                minLength = property.minLength,
                maxLength = property.maxLength,
                pattern = property.pattern,
                minInclusive = property.minInclusive,
                maxInclusive = property.maxInclusive,
                minExclusive = property.minExclusive,
                maxExclusive = property.maxExclusive,
                inValues = property.inValues,
                hasValue = property.hasValue,
                nodeKind = property.nodeKind,
                qualifiedValueShape = property.qualifiedValueShape,
                qualifiedMinCount = property.qualifiedMinCount,
                qualifiedMaxCount = property.qualifiedMaxCount
            )
        }
    }
}

/**
 * Configuration options for DSL generation.
 *
 * This class provides a structured way to configure all aspects of DSL generation,
 * including validation, naming, and output options. Uses nested configuration classes
 * for better organization and type safety.
 *
 * @param validation Validation configuration (enabled, mode, strictness)
 * @param naming Naming configuration (strategy, property name usage)
 * @param output Output configuration (comments, language tags, etc.)
 *
 * @sample com.example.ConfigureDslGeneration
 */
data class DslGenerationOptions(
    val validation: ValidationConfig = ValidationConfig(),
    val naming: NamingConfig = NamingConfig(),
    val output: OutputConfig = OutputConfig()
) {
    /**
     * Validation configuration for generated DSL.
     *
     * @param enabled Whether validation is enabled
     * @param mode Validation mode (EMBEDDED, EXTERNAL, NONE)
     * @param strict Whether to use strict validation
     * @param validateOnBuild Whether to validate when building instances
     */
    data class ValidationConfig(
        val enabled: Boolean = true,
        val mode: ValidationMode = ValidationMode.EMBEDDED,
        val strict: Boolean = false,
        val validateOnBuild: Boolean = true
    )
    
    /**
     * Naming configuration for generated code.
     *
     * @param strategy Naming strategy (CAMEL_CASE, SNAKE_CASE, PASCAL_CASE)
     * @param usePropertyNames Whether to use property names from SHACL shapes
     */
    data class NamingConfig(
        val strategy: NamingStrategy = NamingStrategy.CAMEL_CASE,
        val usePropertyNames: Boolean = true
    )
    
    /**
     * Output configuration for generated code.
     *
     * @param includeComments Whether to include comments in generated code
     * @param includeKdoc Whether to include KDoc documentation
     * @param supportLanguageTags Whether to support language tags for string properties
     * @param defaultLanguage Default language tag to use (if null, no default)
     */
    data class OutputConfig(
        val includeComments: Boolean = true,
        val includeKdoc: Boolean = true,
        val supportLanguageTags: Boolean = true,
        val defaultLanguage: String? = null
    )
}

/**
 * Naming strategy for property and class names.
 */
enum class NamingStrategy {
    CAMEL_CASE,
    SNAKE_CASE,
    PASCAL_CASE
}

/**
 * What the DSL should return.
 */
enum class ReturnType {
    GRAPH,          // Returns MutableRdfGraph
    RESOURCE,       // Returns RdfResource (last created)
    LIST,           // Returns List<RdfResource> (all created)
    DSL             // Returns DSL instance for chaining
}

/**
 * Model representing an ontology class extracted from OWL/RDFS.
 */
data class OntologyClass(
    val classIri: String,
    val className: String,            // Local name
    val superClasses: List<String> = emptyList()  // rdfs:subClassOf
)

/**
 * Request object for instance DSL generation.
 *
 * This class encapsulates all parameters needed to generate a DSL for creating
 * RDF instances. It combines the ontology model (SHACL shapes + JSON-LD context)
 * with generation options and target package information.
 *
 * @param dslName Name of the DSL to generate (must be a valid Kotlin identifier)
 * @param ontologyModel Combined SHACL shapes and JSON-LD context
 * @param packageName Target package name for generated code
 * @param options Generation configuration options
 *
 * @throws IllegalArgumentException if dslName or packageName are invalid
 *
 * @sample com.example.CreateDslRequest
 */
data class InstanceDslRequest(
    val dslName: String,
    val ontologyModel: OntologyModel,
    val packageName: String,
    val options: DslGenerationOptions = DslGenerationOptions()
) {
    init {
        require(dslName.isNotBlank()) { "dslName cannot be blank" }
        require(dslName.matches(Regex("[a-zA-Z][a-zA-Z0-9]*"))) { 
            "dslName must be a valid identifier" 
        }
        require(packageName.isNotBlank()) { "packageName cannot be blank" }
    }
}

