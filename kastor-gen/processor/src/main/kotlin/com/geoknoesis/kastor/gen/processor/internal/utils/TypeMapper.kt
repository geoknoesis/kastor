package com.geoknoesis.kastor.gen.processor.internal.utils

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.joinToCode

/**
 * How a literal datatype is represented in generated Kotlin.
 *
 * @property type element type used in interfaces, data classes and DSL setters
 * @property decoder name of the `XsdLiterals` function that decodes a `Literal` into [type]
 * @property writeDatatype datatype IRI written back (always the declared datatype; `xsd:string` when none)
 */
internal data class LiteralMapping(
    val type: ClassName,
    val decoder: String,
    val writeDatatype: String,
) {
    val isNumeric: Boolean get() = type in NUMERIC_TYPES
    val isPrimitiveNumber: Boolean get() = type in PRIMITIVE_NUMBER_TYPES
    val isString: Boolean get() = type == STRING

    /** `XsdLiterals.encode(<valueExpr>, Iri("<datatype>"))`. */
    fun encode(valueExpr: CodeBlock): CodeBlock =
        CodeBlock.of("%T.encode(%L, %T(%S))", XSD_LITERALS, valueExpr, IRI, writeDatatype)

    /** `XsdLiterals.<decoder>(<literalExpr>)` (nullable result). */
    fun decode(literalExpr: CodeBlock): CodeBlock = CodeBlock.of("%T.%N(%L)", XSD_LITERALS, decoder, literalExpr)

    companion object {
        internal val STRING = String::class.asTypeName()
        private val NUMERIC_TYPES = setOf(
            Int::class.asTypeName(), Long::class.asTypeName(), Float::class.asTypeName(), Double::class.asTypeName(),
            ClassName("java.math", "BigInteger"), ClassName("java.math", "BigDecimal"),
        )
        private val PRIMITIVE_NUMBER_TYPES = setOf(
            Int::class.asTypeName(), Long::class.asTypeName(), Float::class.asTypeName(), Double::class.asTypeName(),
        )
        internal val XSD_LITERALS = ClassName(CodegenConstants.RUNTIME_PACKAGE, "XsdLiterals")
        internal val IRI = ClassName(CodegenConstants.RDF_PACKAGE, "Iri")
    }
}

/** What a property's values are, decided once for every generator. */
internal enum class ValueKind {
    /** `sh:in`-derived enum. */
    ENUM,
    /** Nested object materialized as a generated type. */
    OBJECT,
    /** IRI exposed as a String: `sh:nodeKind sh:IRI`-only properties, unshaped `sh:class` targets, `NestedMode.IRI_ONLY`. */
    IRI,
    /** Literal value (see [TypeMapper.literalMapping]). */
    LITERAL,
}

/** `Regex("pattern")` / `Regex("pattern", setOf(RegexOption.X))` honouring SHACL `sh:flags` (i, m, s, x, q). */
internal fun regexCode(pattern: String, flags: String?): CodeBlock {
    val options = flags.orEmpty().mapNotNull {
        when (it) {
            'i' -> "IGNORE_CASE"
            'm' -> "MULTILINE"
            's' -> "DOT_MATCHES_ALL"
            'x' -> "COMMENTS"
            'q' -> "LITERAL"
            else -> null
        }
    }.distinct()
    return if (options.isEmpty()) {
        CodeBlock.of("%T(%S)", Regex::class, pattern)
    } else {
        CodeBlock.of(
            "%T(%S, setOf(%L))", Regex::class, pattern,
            options.map { CodeBlock.of("%T.%L", RegexOption::class, it) }.joinToCode(", "),
        )
    }
}

/**
 * Unified type mapper for converting SHACL properties to Kotlin types.
 * Single source of truth for type mapping logic.
 */
internal object TypeMapper {

    private const val XSD = "http://www.w3.org/2001/XMLSchema#"
    private const val SH = "http://www.w3.org/ns/shacl#"
    private const val RDF_LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"

    /**
     * Classifies [property]'s values. [knownTypes] (when non-null) turns `sh:class` targets without a shape
     * into [ValueKind.IRI]; so does [NestedMode.IRI_ONLY].
     */
    fun valueKind(
        property: ShaclProperty,
        context: JsonLdContext,
        knownTypes: Set<String>?,
        nestedMode: NestedMode = NestedMode.INTERFACE,
    ): ValueKind = when {
        property.enumName != null -> ValueKind.ENUM
        property.targetClass != null ->
            if (nestedMode == NestedMode.IRI_ONLY || isUnshapedTarget(property, context, knownTypes)) ValueKind.IRI
            else ValueKind.OBJECT
        isIriReference(property) -> ValueKind.IRI
        else -> ValueKind.LITERAL
    }

    /** `sh:nodeKind sh:IRI` / `sh:BlankNodeOrIRI` / `sh:BlankNode` without `sh:datatype` or `sh:class`. */
    fun isIriReference(property: ShaclProperty): Boolean =
        property.targetClass == null && property.datatype == null &&
            property.nodeKind in setOf("${SH}IRI", "${SH}BlankNodeOrIRI", "${SH}BlankNode")

    /**
     * Maps a SHACL property to a Kotlin TypeName.
     *
     * [nestedMode] and [dataClassSuffix] only affect object properties and are ignored for literals.
     *   - [NestedMode.INTERFACE]  → property type is the generated interface (default, no suffix applied)
     *   - [NestedMode.DATA_CLASS] → property type is the generated data class (interface name + [dataClassSuffix])
     *   - [NestedMode.IRI_ONLY]   → property type is String (IRI value, no sub-object materialisation)
     *
     * [knownTypes] (when non-null) is the set of interface names that will actually be generated. An
     * `sh:class` target that is NOT in this set has no generated type (e.g. `sbe:Hypothesis`/`sbe:Analysis`,
     * which SBEO references but does not shape), so the property falls back to the IRI (String) instead of
     * emitting a dangling, unresolvable reference. `null` disables the check (legacy callers).
     */
    fun toKotlinType(
        property: ShaclProperty,
        context: JsonLdContext,
        nestedMode: NestedMode = NestedMode.INTERFACE,
        dataClassSuffix: String = "",
        objectPackage: String = "",
        knownTypes: Set<String>? = null,
    ): TypeName {
        return when {
            // Referenced enum/interface/data-class types are generated into [objectPackage]; qualify them
            // so KotlinPoet does not emit an invalid default-package import (e.g. `import InformationItem`).
            property.enumName != null -> applyCardinality(ClassName(objectPackage, property.enumName), property)
            property.targetClass != null -> {
                if (isUnshapedTarget(property, context, knownTypes) && nestedMode != NestedMode.IRI_ONLY) {
                    applyCardinality(String::class.asTypeName(), property) // unshaped sh:class target -> IRI
                } else {
                    mapObjectProperty(property, nestedMode, dataClassSuffix, objectPackage, context)
                }
            }
            isIriReference(property) -> applyCardinality(String::class.asTypeName(), property)
            else -> applyCardinality(literalMapping(property.datatype).type, property)
        }
    }

    /** True when [property] references a class that has no generated type in [knownTypes]. */
    fun isUnshapedTarget(property: ShaclProperty, context: JsonLdContext, knownTypes: Set<String>?): Boolean =
        property.targetClass != null && knownTypes != null &&
            NamingUtils.domainName(property.targetClass, context) !in knownTypes

    private fun mapObjectProperty(
        property: ShaclProperty,
        nestedMode: NestedMode,
        dataClassSuffix: String,
        objectPackage: String,
        context: JsonLdContext,
    ): TypeName {
        val baseName = NamingUtils.domainName(property.targetClass!!, context)
        val targetType: TypeName = when (nestedMode) {
            NestedMode.INTERFACE  -> ClassName(objectPackage, baseName)
            NestedMode.DATA_CLASS -> ClassName(objectPackage, "$baseName$dataClassSuffix")
            NestedMode.IRI_ONLY   -> String::class.asTypeName()
        }

        return applyCardinality(targetType, property)
    }

    private fun applyCardinality(baseType: TypeName, property: ShaclProperty): TypeName =
        when {
            Cardinality.isList(property) -> KotlinPoetUtils.listOf(baseType.copy(nullable = false))
            Cardinality.isRequiredSingle(property) -> baseType.copy(nullable = false)
            else -> baseType.copy(nullable = true)
        }

    /** Element type for a literal datatype (without cardinality). */
    internal fun mapDatatype(datatype: String?): TypeName = literalMapping(datatype).type

    /**
     * Datatype → Kotlin representation. Integers are exact (`xsd:integer` and unbounded derived types map
     * to [java.math.BigInteger]; `xsd:long`/`xsd:unsignedInt` to [Long]; `xsd:int`/`short`/`byte` and the
     * small unsigned types to [Int]), `xsd:decimal` to [java.math.BigDecimal], `xsd:float` to [Float],
     * `xsd:date` to [java.time.LocalDate] and `rdf:langString` to the core `LangString` term. Every other
     * datatype (including `xsd:dateTime`, `xsd:time`, `xsd:duration` whose value spaces carry optional
     * timezones that no single `java.time` type represents losslessly) keeps its lexical form as [String];
     * writers always emit the declared datatype so the value round-trips.
     */
    fun literalMapping(datatype: String?): LiteralMapping {
        fun m(type: ClassName, decoder: String) = LiteralMapping(type, decoder, datatype ?: "${XSD}string")
        return when (datatype) {
            "${XSD}boolean" -> m(Boolean::class.asTypeName(), "boolean")
            "${XSD}int", "${XSD}short", "${XSD}byte", "${XSD}unsignedShort", "${XSD}unsignedByte" ->
                m(Int::class.asTypeName(), "int")
            "${XSD}long", "${XSD}unsignedInt" -> m(Long::class.asTypeName(), "long")
            "${XSD}integer", "${XSD}nonNegativeInteger", "${XSD}positiveInteger", "${XSD}nonPositiveInteger",
            "${XSD}negativeInteger", "${XSD}unsignedLong" -> m(ClassName("java.math", "BigInteger"), "bigInteger")
            "${XSD}decimal" -> m(ClassName("java.math", "BigDecimal"), "bigDecimal")
            "${XSD}float" -> m(Float::class.asTypeName(), "float")
            "${XSD}double" -> m(Double::class.asTypeName(), "double")
            "${XSD}date" -> m(ClassName("java.time", "LocalDate"), "localDate")
            RDF_LANG_STRING -> m(ClassName(CodegenConstants.RDF_PACKAGE, "LangString"), "langString")
            else -> m(String::class.asTypeName(), "string")
        }
    }
}
