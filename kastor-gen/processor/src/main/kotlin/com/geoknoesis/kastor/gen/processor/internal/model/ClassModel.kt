package com.geoknoesis.kastor.gen.processor.internal.model

import com.geoknoesis.kastor.rdf.MutableRdfGraph

public data class ClassModel(
    val qualifiedName: String,
    val simpleName: String,
    val packageName: String,
    val classIri: String,
    val properties: List<PropertyModel>
)

public data class PropertyModel(
    val name: String,
    /**
     * Member type: `String`, `Int`, `Long`, `Float`, `Double`, `Boolean` for Kotlin types, the qualified name for
     * other types (e.g. `java.time.LocalDate`, `com.example.Person`), wrapped as `List<…>` for list members.
     */
    val kotlinType: String,
    val predicateIri: String,
    val type: PropertyType,
    /** When true, generated wrapper uses `override var` and writes through a [MutableRdfGraph]. */
    val mutable: Boolean = false,
    /** Whether the declared type is nullable: a missing value reads as `null` (non-null members throw instead). */
    val nullable: Boolean = false,
    /** How the (element) type is decoded when it is an enum; `null` for other types. */
    val enumKind: RdfEnumKind? = null,
    /**
     * Package of the declared (element) type when [kotlinType] is qualified, so nested classes and packages with
     * upper-case segments resolve exactly instead of being guessed from the dotted name.
     */
    val typePackage: String? = null,
)

public enum class PropertyType {
    /** A literal: String, numbers, Boolean, `BigInteger`, `BigDecimal`, `LocalDate`, `LangString`, or an enum. */
    LITERAL,
    OBJECT,
    OBJECT_LIST,
    /** An RDF term read as-is: `Iri` or `RdfResource` (IRI or blank node). */
    TERM,
}

/** How an enum member type of a hand-written `@Rdf` interface is decoded. */
public enum class RdfEnumKind {
    /** Kotlin `enum class`: the literal's lexical form is the constant name. */
    NAME,

    /** Enum generated from a literal `sh:in` (companion `from(code: String)`). */
    CODE,

    /** Enum generated from an IRI `sh:in` (companion `from(iri: Iri)`): values are IRIs. */
    IRI,
}
