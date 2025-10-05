package com.example.ontomapper.processor.model

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
 */
data class ShaclProperty(
    val path: String,
    val name: String,
    val description: String,
    val datatype: String?,
    val targetClass: String?,
    val minCount: Int?,
    val maxCount: Int?
)

/**
 * Model representing a JSON-LD context.
 */
data class JsonLdContext(
    val prefixes: Map<String, String>,
    val typeMappings: Map<String, String>,
    val propertyMappings: Map<String, JsonLdProperty>
)

/**
 * Model representing a JSON-LD property definition.
 */
data class JsonLdProperty(
    val id: String,
    val type: String?
)

/**
 * Combined model for code generation from SHACL + JSON-LD.
 */
data class OntologyModel(
    val shapes: List<ShaclShape>,
    val context: JsonLdContext
)
