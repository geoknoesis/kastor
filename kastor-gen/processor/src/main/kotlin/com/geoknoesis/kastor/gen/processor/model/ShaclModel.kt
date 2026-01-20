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












