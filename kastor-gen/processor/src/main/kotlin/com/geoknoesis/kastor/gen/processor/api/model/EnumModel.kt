package com.geoknoesis.kastor.gen.processor.api.model

/** Whether an enum's members are IRIs (named individuals) or literal codes. */
public enum class EnumMemberKind { IRI, LITERAL }

/**
 * A single enum member.
 * @param constantName Kotlin identifier (UPPER_SNAKE)
 * @param iri set when the enum's memberKind is IRI
 * @param code set when the enum's memberKind is LITERAL
 * @param datatype literal datatype IRI when kind is LITERAL and not xsd:string
 */
public data class EnumMember(
    val constantName: String,
    val iri: String? = null,
    val code: String? = null,
    val datatype: String? = null,
)

/**
 * A generated enum type derived from a SHACL sh:in closed value set.
 * @param name Kotlin type name (PascalCase)
 * @param classIri sh:class IRI when that was the name source, else null
 */
public data class EnumModel(
    val name: String,
    val classIri: String?,
    val memberKind: EnumMemberKind,
    val members: List<EnumMember>,
)

/**
 * A typed sh:in member captured during parsing: the RDF term a value node must equal.
 *
 * @param value the IRI, or the lexical form of a literal
 * @param isIri whether the member is an IRI (otherwise a literal)
 * @param datatype datatype IRI of a literal member (`rdf:langString` for a language-tagged one); null in hand-built
 *   models means "a literal of the property's `sh:datatype`"
 * @param language language tag of a language-tagged literal member (compared ignoring case), else null
 */
public data class ShaclInValue(
    val value: String,
    val isIri: Boolean,
    val datatype: String? = null,
    val language: String? = null,
)
