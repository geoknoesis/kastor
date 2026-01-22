package com.geoknoesis.kastor.gen.processor.model

import com.geoknoesis.kastor.rdf.Iri as RdfIri

/**
 * Model representing a SHACL NodeShape.
 */
data class ShaclShape(
    val shapeIri: String,
    val targetClass: String,
    val properties: List<ShaclProperty>
)

/**
 * Model representing a SHACL property constraint.
 * 
 * This model captures the essential SHACL constraints for code generation.
 * Additional constraints (e.g., sh:pattern, sh:in) can be added as needed.
 */
data class ShaclProperty(
    val path: String,
    val name: String,
    val description: String,
    val datatype: String?,
    val targetClass: String?,
    val minCount: Int?,
    val maxCount: Int?,
    // String constraints
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    // Numeric constraints
    val minInclusive: Double? = null,
    val maxInclusive: Double? = null,
    val minExclusive: Double? = null,
    val maxExclusive: Double? = null,
    // Value constraints
    val inValues: List<String>? = null,
    val hasValue: String? = null,
    // Node constraints
    val nodeKind: String? = null,
    val qualifiedValueShape: String? = null,
    val qualifiedMinCount: Int? = null,
    val qualifiedMaxCount: Int? = null
)

/**
 * Model representing a JSON-LD context.
 */
data class JsonLdContext(
    val prefixes: Map<String, String>,
    val baseIri: RdfIri? = null,
    val vocabIri: RdfIri? = null,
    val typeMappings: Map<String, RdfIri>,
    val propertyMappings: Map<String, JsonLdProperty>
)

/**
 * Model representing a JSON-LD property definition.
 */
data class JsonLdProperty(
    val id: RdfIri,
    val type: JsonLdType?,
    val container: JsonLdContainer? = null
)

sealed interface JsonLdType {
    data object Id : JsonLdType
    data class Iri(val iri: RdfIri) : JsonLdType
}

sealed interface JsonLdContainer {
    data object List : JsonLdContainer
    data object Set : JsonLdContainer
    data object Index : JsonLdContainer
    data object Language : JsonLdContainer
    data class Unknown(val value: String) : JsonLdContainer
}

/**
 * Combined model for code generation from SHACL + JSON-LD.
 */
data class OntologyModel(
    val shapes: List<ShaclShape>,
    val context: JsonLdContext
)












