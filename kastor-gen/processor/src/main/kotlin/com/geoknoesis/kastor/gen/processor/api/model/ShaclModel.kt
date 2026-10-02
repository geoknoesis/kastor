package com.geoknoesis.kastor.gen.processor.api.model

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
 */
public data class ShaclShape(
    val shapeIri: String,
    val targetClass: String,
    val properties: List<ShaclProperty>,
    /** Target classes of shapes this shape inherits from (via `sh:node` on the node shape or `rdfs:subClassOf`). */
    val parentClasses: List<String> = emptyList(),
    /** `sh:deactivated true`: the shape's constraints are not validated (types are still generated). */
    val deactivated: Boolean = false,
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
public data class ShaclProperty(
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
    /** `sh:flags` for [pattern] (e.g. "i"). */
    val patternFlags: String? = null,
    // Numeric constraints: exact decimal values (a Double would round bounds such as 9223372036854775807)
    val minInclusive: java.math.BigDecimal? = null,
    val maxInclusive: java.math.BigDecimal? = null,
    val minExclusive: java.math.BigDecimal? = null,
    val maxExclusive: java.math.BigDecimal? = null,
    // Value constraints
    val inValues: List<String>? = null,
    val inValuesTyped: List<ShaclInValue>? = null,
    val enumName: String? = null,
    val hasValue: String? = null,
    // Node constraints
    val nodeKind: String? = null,
    val qualifiedValueShape: String? = null,
    val qualifiedMinCount: Int? = null,
    val qualifiedMaxCount: Int? = null,
    // Shape-level parameters of the property shape
    /** `sh:severity` IRI (e.g. `sh:Warning`); `null` means `sh:Violation`. */
    val severity: String? = null,
    /** `sh:message` used for this property shape's validation results instead of the generated message. */
    val message: String? = null,
    /** `sh:deactivated true`: the property shape's constraints are not validated and do not make the member required. */
    val deactivated: Boolean = false,
    /**
     * Further `sh:pattern`s that apply to the same path (declared by another property shape of the node shape, or
     * inherited): SHACL constraints are a conjunction, so a value must match [pattern] **and** each of these.
     */
    val additionalPatterns: List<ShaclPattern> = emptyList(),
    /** Further `sh:hasValue`s that apply to the same path: each must be among the values, like [hasValue]. */
    val additionalHasValues: List<String> = emptyList(),
    /**
     * True when the `sh:nodeKind`s that apply to the path (its own and the inherited ones) have no kind in common:
     * no value satisfies them, so validation rejects every value. [nodeKind] then keeps the first declaration's kind.
     */
    val nodeKindUnsatisfiable: Boolean = false,
) {
    /** Every `sh:pattern` that applies to the path: [pattern] (with [patternFlags]) and [additionalPatterns]. */
    val patterns: List<ShaclPattern>
        get() = listOfNotNull(pattern?.let { ShaclPattern(it, patternFlags) }) + additionalPatterns

    /** Every `sh:hasValue` that applies to the path: [hasValue] and [additionalHasValues]. */
    val hasValues: List<String>
        get() = listOfNotNull(hasValue) + additionalHasValues
}

/** One `sh:pattern` with its `sh:flags`. */
public data class ShaclPattern(val pattern: String, val flags: String? = null)

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
 * @param keywordAliases Terms that stand for a JSON-LD keyword (`"id": "@id"`, `"type": "@type"`), with the keyword
 */
public data class JsonLdContext(
    val prefixes: Map<String, String>,
    val baseIri: RdfIri? = null,
    val vocabIri: RdfIri? = null,
    val typeMappings: Map<String, RdfIri>,
    val propertyMappings: Map<String, JsonLdProperty>,
    val keywordAliases: Map<String, String> = emptyMap(),
)

/**
 * Model representing a JSON-LD property definition (an expanded term definition).
 *
 * @param id the IRI of the property; for a [reverse] property the IRI named by `@reverse`
 * @param type the type coercion (`@type`), if any
 * @param container the container that shapes the values: the first of [containers] that is not `@set` (`@set` only
 *   says "always an array"), else `@set`, else null
 * @param containers every `@container` value, in the order written (`["@set", "@language"]`)
 * @param reverse true for a reverse property (`@reverse`): the values are the subjects, the node is the object
 * @param scopedContext the context that applies to the values of this term (`@context` in the term definition),
 *   resolved against the context it is declared in
 */
public data class JsonLdProperty(
    val id: RdfIri,
    val type: JsonLdType?,
    val container: JsonLdContainer? = null,
    val containers: kotlin.collections.List<JsonLdContainer> = listOfNotNull(container),
    val reverse: Boolean = false,
    val scopedContext: JsonLdContext? = null,
)

public sealed interface JsonLdType {
    /** `"@type": "@id"`: the values are IRIs, resolved against the document base. */
    public data object Id : JsonLdType
    /** `"@type": "@vocab"`: the values are IRIs, resolved against the vocabulary mapping (terms first). */
    public data object Vocab : JsonLdType
    /** `"@type": "@json"`: the values are JSON literals (`rdf:JSON`). */
    public data object Json : JsonLdType
    /** `"@type": "@none"`: the values are not coerced. */
    public data object None : JsonLdType
    public data class Iri(val iri: RdfIri) : JsonLdType
}

public sealed interface JsonLdContainer {
    public data object List : JsonLdContainer
    public data object Set : JsonLdContainer
    public data object Index : JsonLdContainer
    public data object Language : JsonLdContainer
    public data object Id : JsonLdContainer
    public data object Type : JsonLdContainer
    public data object Graph : JsonLdContainer
    public data class Unknown(val value: String) : JsonLdContainer
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
 * @param enums List of generated enum types derived from sh:in constraints
 *
 */
public data class OntologyModel(
    val shapes: List<ShaclShape>,
    val context: JsonLdContext,
    val enums: List<EnumModel> = emptyList(),
)


