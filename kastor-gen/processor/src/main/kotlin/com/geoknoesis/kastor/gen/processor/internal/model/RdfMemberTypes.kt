package com.geoknoesis.kastor.gen.processor.internal.model

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName

/**
 * Member types of hand-written `@Rdf` interfaces, classified with the same literal codecs as the SHACL generators
 * (`XsdLiterals`), so a `Long`, `LocalDate` or `BigDecimal` member is read as a literal instead of an object.
 */
internal object RdfMemberTypes {

    private const val XSD = "http://www.w3.org/2001/XMLSchema#"

    /**
     * A literal member type.
     *
     * @property decoder the `XsdLiterals` function decoding a literal into [typeName]
     * @property writeDatatype datatype written by mutable accessors via `XsdLiterals.encode`; `null` writes `Literal(value)`
     */
    data class LiteralType(val kotlinType: String, val typeName: TypeName, val decoder: String, val writeDatatype: String?)

    private val LITERALS: Map<String, LiteralType> = listOf(
        LiteralType("String", String::class.asTypeName(), "string", null),
        LiteralType("Int", Int::class.asTypeName(), "int", null),
        LiteralType("Double", Double::class.asTypeName(), "double", null),
        LiteralType("Boolean", Boolean::class.asTypeName(), "boolean", null),
        LiteralType("Long", Long::class.asTypeName(), "long", "${XSD}long"),
        LiteralType("Float", Float::class.asTypeName(), "float", "${XSD}float"),
        LiteralType("java.math.BigInteger", ClassName("java.math", "BigInteger"), "bigInteger", "${XSD}integer"),
        LiteralType("java.math.BigDecimal", ClassName("java.math", "BigDecimal"), "bigDecimal", "${XSD}decimal"),
        LiteralType("java.time.LocalDate", ClassName("java.time", "LocalDate"), "localDate", "${XSD}date"),
        // A LangString is a Literal: XsdLiterals.encode writes it unchanged.
        LiteralType("com.geoknoesis.kastor.rdf.LangString", ClassName("com.geoknoesis.kastor.rdf", "LangString"), "langString", "${XSD}string"),
    ).associateBy { it.kotlinType }

    /** RDF term member types and the `KastorGraphOps` reader for each. */
    private val TERMS: Map<String, Pair<ClassName, String>> = mapOf(
        "com.geoknoesis.kastor.rdf.Iri" to (ClassName("com.geoknoesis.kastor.rdf", "Iri") to "getIriValues"),
        "com.geoknoesis.kastor.rdf.RdfResource" to (ClassName("com.geoknoesis.kastor.rdf", "RdfResource") to "getResourceValues"),
    )

    /** `kotlin.Long` -> `Long`; other qualified names are kept; `null` (unresolved) -> `Any`. */
    fun normalize(qualifiedName: String?): String = when {
        qualifiedName == null -> "Any"
        qualifiedName.startsWith("kotlin.") && qualifiedName.count { it == '.' } == 1 -> qualifiedName.removePrefix("kotlin.")
        else -> qualifiedName
    }

    fun isList(kotlinType: String): Boolean = kotlinType.startsWith("List<")

    /**
     * Whether [qualifiedName] is a container other than `List` (`Set`, `Collection`, `Iterable`, `MutableList`, `Map`,
     * arrays, `Sequence`, ...). Wrappers implement multi-valued members as `List` only, so such members are rejected
     * at generation time instead of being treated as a single nested object (which would not compile).
     */
    fun isUnsupportedContainer(qualifiedName: String?): Boolean = qualifiedName != null && qualifiedName != LIST && (
        qualifiedName.startsWith("kotlin.collections.") ||
            qualifiedName == "kotlin.sequences.Sequence" ||
            (qualifiedName.startsWith("kotlin.") && qualifiedName.endsWith("Array") && qualifiedName.count { it == '.' } == 1)
        )

    private const val LIST = "kotlin.collections.List"

    fun element(kotlinType: String): String = kotlinType.removePrefix("List<").removeSuffix(">")

    fun literal(elementType: String): LiteralType? = LITERALS[elementType]

    /** The literal member types, for diagnostics (`String, Int, ..., java.time.LocalDate, ...`). */
    fun supportedLiteralTypes(): String = LITERALS.keys.joinToString(", ")

    fun term(elementType: String): Pair<ClassName, String>? = TERMS[elementType]

    fun propertyType(kotlinType: String, enumKind: RdfEnumKind?): PropertyType {
        val element = element(kotlinType)
        return when {
            enumKind != null || literal(element) != null -> PropertyType.LITERAL
            term(element) != null -> PropertyType.TERM
            isList(kotlinType) -> PropertyType.OBJECT_LIST
            else -> PropertyType.OBJECT
        }
    }

    /** Mutable (`var`) accessors are generated for single-valued literal members (not enums) and single objects. */
    fun supportsMutation(kotlinType: String, type: PropertyType, enumKind: RdfEnumKind?): Boolean =
        !isList(kotlinType) && when (type) {
            PropertyType.LITERAL -> enumKind == null
            PropertyType.OBJECT -> true
            PropertyType.OBJECT_LIST, PropertyType.TERM -> false
        }
}
