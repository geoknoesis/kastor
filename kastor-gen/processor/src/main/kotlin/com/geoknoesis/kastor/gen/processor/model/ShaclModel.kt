package com.geoknoesis.kastor.gen.processor.model

import com.geoknoesis.kastor.rdf.Iri as RdfIri

/**
 * Model representing a SHACL NodeShape.
 *
 * A SHACL shape defines the structure and constraints for instances of a particular class.
 * This model captures the essential information needed for code generation.
 *
 * @param shapeIri The IRI of the SHACL shape
 * @param targetClass The IRI of the target class this shape applies to
 * @param properties List of property constraints defined in this shape
 *
 * @sample com.example.CreateShaclShape
 */
data class ShaclShape(
    val shapeIri: String,
    val targetClass: String,
    val properties: List<ShaclProperty>
)

/**
 * Model representing a SHACL property constraint.
 *
 * This model captures all SHACL constraints that can be applied to a property,
 * including cardinality, datatype, value constraints, and more.
 *
 * @param path The IRI of the property path
 * @param name Human-readable name for the property
 * @param description Description of the property
 * @param datatype The datatype constraint (e.g., xsd:string, xsd:integer)
 * @param targetClass The class constraint for object properties
 * @param minCount Minimum cardinality (sh:minCount)
 * @param maxCount Maximum cardinality (sh:maxCount)
 * @param minLength Minimum string length (sh:minLength)
 * @param maxLength Maximum string length (sh:maxLength)
 * @param pattern Regular expression pattern (sh:pattern)
 * @param minInclusive Minimum inclusive numeric value (sh:minInclusive)
 * @param maxInclusive Maximum inclusive numeric value (sh:maxInclusive)
 * @param minExclusive Minimum exclusive numeric value (sh:minExclusive)
 * @param maxExclusive Maximum exclusive numeric value (sh:maxExclusive)
 * @param inValues List of allowed values (sh:in)
 * @param hasValue Required value (sh:hasValue)
 * @param nodeKind Node kind constraint (sh:nodeKind)
 * @param qualifiedValueShape Qualified value shape (sh:qualifiedValueShape)
 * @param qualifiedMinCount Qualified minimum count (sh:qualifiedMinCount)
 * @param qualifiedMaxCount Qualified maximum count (sh:qualifiedMaxCount)
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
 *
 * A JSON-LD context provides mappings between compact terms and full IRIs,
 * type information, and container specifications.
 *
 * @param prefixes Map of prefix names to namespace IRIs
 * @param baseIri Base IRI for resolving relative IRIs
 * @param vocabIri Vocabulary IRI for default vocabulary terms
 * @param typeMappings Map of type names to their IRIs
 * @param propertyMappings Map of property names to their definitions
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
 *
 * This model combines SHACL shapes (structure and constraints) with JSON-LD context
 * (type mappings and property definitions) to provide all information needed for
 * code generation.
 *
 * @param shapes List of SHACL shapes defining class structures
 * @param context JSON-LD context providing type and property mappings
 *
 * @sample com.example.CreateOntologyModel
 */
data class OntologyModel(
    val shapes: List<ShaclShape>,
    val context: JsonLdContext
)












