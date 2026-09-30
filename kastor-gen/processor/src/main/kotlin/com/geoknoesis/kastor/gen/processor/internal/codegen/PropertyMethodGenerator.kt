package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind
import com.geoknoesis.kastor.gen.processor.api.model.PropertyBuilderModel
import com.geoknoesis.kastor.gen.processor.internal.utils.CodegenConstants
import com.geoknoesis.kastor.gen.processor.internal.utils.ShaclPatterns
import com.geoknoesis.kastor.gen.processor.internal.utils.kdocText
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.*

/**
 * Generator for property setter methods in builder classes using KotlinPoet.
 * Uses strategy pattern for type-specific method generation.
 */
internal class PropertyMethodGenerator(
    private val logger: KSPLogger
) {

    /**
     * Generates property methods for a builder class.
     */
    fun generatePropertyMethods(
        property: PropertyBuilderModel,
        options: DslGenerationOptions
    ): List<FunSpec> {
        val propertyIri = CodegenConstants.iriConstant(property.propertyIri)
        // Enum-typed properties take a dedicated type-safe path before the generic strategy dispatch.
        if (property.enumName != null && property.enumMemberKind != null) {
            return PropertyTypeStrategy.EnumStrategy.generateMethods(property, propertyIri, options)
        }
        if (property.isIriValued) {
            return PropertyTypeStrategy.IriStrategy.generateMethods(property, propertyIri, options)
        }
        val strategy = PropertyTypeStrategy.from(property.kotlinType)
        return strategy.generateMethods(property, propertyIri, options)
    }
}

private val LITERAL = ClassName(CodegenConstants.RDF_PACKAGE, "Literal")
private val IRI = ClassName(CodegenConstants.RDF_PACKAGE, "Iri")
private val XSD = ClassName(CodegenConstants.VOCAB_PACKAGE, "XSD")
private val XSD_LITERALS = ClassName(CodegenConstants.RUNTIME_PACKAGE, "XsdLiterals")

/**
 * Strategy for generating property methods based on type.
 *
 * `sh:pattern` checks reference a file-level lazily compiled constant named by the pattern; the enclosing DSL
 * file (see [InstanceDslGenerator]) declares it.
 */
public sealed class PropertyTypeStrategy {
    public abstract fun generateMethods(
        property: PropertyBuilderModel,
        propertyIri: CodeBlock,
        options: DslGenerationOptions
    ): List<FunSpec>

    public object StringStrategy : PropertyTypeStrategy() {
        override fun generateMethods(
            property: PropertyBuilderModel,
            propertyIri: CodeBlock,
            options: DslGenerationOptions
        ): List<FunSpec> {
            val methods = mutableListOf<FunSpec>()
            val langTags = options.output.supportLanguageTags

            val functionBuilder = FunSpec.builder(property.propertyName)
                .addKdoc("%L", buildKdoc(property))
                .addParameter("value", String::class)
            if (langTags) addLanguageParameters(functionBuilder)
            addImmediateValidation(functionBuilder, property, "value", STRING)
            addStringWrite(functionBuilder, property, propertyIri, langTags)
            methods.add(functionBuilder.build())

            // Add list variant if property is a list
            if (property.isList) {
                val listBuilder = FunSpec.builder(property.propertyName)
                    .addKdoc("%L", buildKdoc(property))
                    .addParameter("values", String::class, VARARG)
                if (langTags) addLanguageParameters(listBuilder)
                listBuilder.beginControlFlow("values.forEach { value ->")
                addImmediateValidation(listBuilder, property, "value", STRING)
                addStringWrite(listBuilder, property, propertyIri, langTags)
                listBuilder.endControlFlow()
                methods.add(listBuilder.build())
            }

            return methods
        }

        private fun addLanguageParameters(builder: FunSpec.Builder) {
            builder.addParameter(
                ParameterSpec.builder("lang", String::class.asTypeName().copy(nullable = true))
                    .defaultValue(CodeBlock.of("null"))
                    .build()
            )
            // RDF 1.2: an optional base direction. When supplied, the
            // resulting literal carries the rdf:dirLangString datatype.
            builder.addParameter(
                ParameterSpec.builder("direction", ClassName(CodegenConstants.RDF_PACKAGE, "Direction").copy(nullable = true))
                    .defaultValue(CodeBlock.of("null"))
                    .build()
            )
        }

        private fun addStringWrite(builder: FunSpec.Builder, property: PropertyBuilderModel, propertyIri: CodeBlock, langTags: Boolean) {
            val typed = typedLiteral(property, CodeBlock.of("value")) ?: CodeBlock.of("%T(value, %T.string)", LITERAL, XSD)
            if (langTags) {
                builder.addStatement(
                    "val literal = if (lang != null) %T(value, lang, direction) else %L",
                    ClassName(CodegenConstants.RDF_PACKAGE, "LangString"), typed,
                )
                builder.addStatement("graph.addTriple(resource, %L, literal)", propertyIri)
            } else {
                builder.addStatement("graph.addTriple(resource, %L, %L)", propertyIri, typed)
            }
        }
    }

    public object IntStrategy : PropertyTypeStrategy() {
        override fun generateMethods(property: PropertyBuilderModel, propertyIri: CodeBlock, options: DslGenerationOptions): List<FunSpec> =
            valueMethods(property, propertyIri, Int::class.asTypeName(), CodeBlock.of("%T(value.toString(), %T.integer)", LITERAL, XSD))
    }

    public object DoubleStrategy : PropertyTypeStrategy() {
        override fun generateMethods(property: PropertyBuilderModel, propertyIri: CodeBlock, options: DslGenerationOptions): List<FunSpec> =
            valueMethods(property, propertyIri, Double::class.asTypeName(), CodeBlock.of("%T.encode(value, %T.double)", XSD_LITERALS, XSD))
    }

    public object BooleanStrategy : PropertyTypeStrategy() {
        override fun generateMethods(property: PropertyBuilderModel, propertyIri: CodeBlock, options: DslGenerationOptions): List<FunSpec> =
            valueMethods(property, propertyIri, Boolean::class.asTypeName(), CodeBlock.of("%T(value.toString(), %T.boolean)", LITERAL, XSD))
    }

    /**
     * Strategy for any other literal type (Long, Float, BigInteger, BigDecimal, LocalDate, LangString, …):
     * the setter takes the Kotlin type and writes it with the property's declared datatype.
     */
    public object TypedLiteralStrategy : PropertyTypeStrategy() {
        override fun generateMethods(property: PropertyBuilderModel, propertyIri: CodeBlock, options: DslGenerationOptions): List<FunSpec> {
            val element = elementType(property.kotlinType)
            val fallback = CodeBlock.of("%T.encode(value, %T.string)", XSD_LITERALS, XSD)
            return valueMethods(property, propertyIri, element, fallback)
        }
    }

    /** Strategy for IRI-valued properties (`sh:class`, `sh:nodeKind sh:IRI`): setters take the IRI string. */
    public object IriStrategy : PropertyTypeStrategy() {
        override fun generateMethods(property: PropertyBuilderModel, propertyIri: CodeBlock, options: DslGenerationOptions): List<FunSpec> {
            val methods = mutableListOf<FunSpec>()
            methods += FunSpec.builder(property.propertyName)
                .addKdoc("%L", buildKdoc(property))
                .addParameter("value", String::class)
                .addStatement("graph.addTriple(resource, %L, %T(value))", propertyIri, IRI)
                .build()
            if (property.isList) {
                methods += FunSpec.builder(property.propertyName)
                    .addKdoc("%L", buildKdoc(property))
                    .addParameter("values", String::class, VARARG)
                    .addStatement("values.forEach { value -> graph.addTriple(resource, %L, %T(value)) }", propertyIri, IRI)
                    .build()
            }
            return methods
        }
    }

    /**
     * Strategy for enum-typed properties.
     *
     * IRI-membered enums: setter writes `graph.addTriple(resource, pred, value.iri)`.
     * LITERAL-membered enums: setter writes `graph.addTriple(resource, pred, Literal(value.code, XSD.string))`.
     * List variants use vararg + forEach.
     *
     * The property's [PropertyBuilderModel.enumName] supplies the Kotlin parameter type and
     * [PropertyBuilderModel.enumMemberKind] selects between .iri and .code.
     */
    public object EnumStrategy : PropertyTypeStrategy() {
        override fun generateMethods(
            property: PropertyBuilderModel,
            propertyIri: CodeBlock,
            options: DslGenerationOptions
        ): List<FunSpec> {
            val enumName = requireNotNull(property.enumName) { "EnumStrategy requires enumName" }
            val memberKind = requireNotNull(property.enumMemberKind) { "EnumStrategy requires enumMemberKind" }
            // InstanceDslGenerator passes "<package>.<EnumName>" for a top-level generated enum; a simple name is taken
            // as-is. Split at the last dot rather than ClassName.bestGuess, which reads a package segment that starts
            // with an upper-case letter (e.g. `com.Acme.model`) as a class name.
            val enumType = if ('.' in enumName) {
                ClassName(enumName.substringBeforeLast('.'), enumName.substringAfterLast('.'))
            } else {
                ClassName("", enumName)
            }
            val methods = mutableListOf<FunSpec>()

            fun term(v: String) = when (memberKind) {
                EnumMemberKind.IRI -> CodeBlock.of("%L.iri", v)
                EnumMemberKind.LITERAL ->
                    typedLiteral(property, CodeBlock.of("%L.code", v)) ?: CodeBlock.of("%T(%L.code, %T.string)", LITERAL, v, XSD)
            }

            // Scalar setter
            methods.add(
                FunSpec.builder(property.propertyName)
                    .addKdoc("%L", buildKdoc(property))
                    .addParameter("value", enumType)
                    .addStatement("graph.addTriple(resource, %L, %L)", propertyIri, term("value"))
                    .build()
            )

            // List (vararg) setter when the property can hold multiple values
            if (property.isList) {
                methods.add(
                    FunSpec.builder(property.propertyName)
                        .addKdoc("%L", buildKdoc(property))
                        .addParameter("values", enumType, VARARG)
                        .beginControlFlow("values.forEach")
                        .addStatement("graph.addTriple(resource, %L, %L)", propertyIri, term("it"))
                        .endControlFlow()
                        .build()
                )
            }

            return methods
        }
    }

    public companion object {
        public fun from(type: TypeName): PropertyTypeStrategy {
            return when (elementType(type)) {
                STRING -> StringStrategy
                Int::class.asTypeName() -> IntStrategy
                Double::class.asTypeName() -> DoubleStrategy
                Boolean::class.asTypeName() -> BooleanStrategy
                else -> if (elementType(type) is ClassName) TypedLiteralStrategy else StringStrategy
            }
        }
    }
}

private val STRING = String::class.asTypeName()

/** Element type of `T`, `T?` or `List<T>` (non-null). */
private fun elementType(type: TypeName): TypeName {
    val base = if (type is ParameterizedTypeName && type.rawType.simpleName == "List") type.typeArguments.first() else type
    return base.copy(nullable = false)
}

/** `XsdLiterals.encode(value, Iri("<datatype>"))` when the property declares a datatype. */
private fun typedLiteral(property: PropertyBuilderModel, valueExpr: CodeBlock): CodeBlock? =
    property.datatype?.let { CodeBlock.of("%T.encode(%L, %T(%S))", XSD_LITERALS, valueExpr, IRI, it) }

/** Scalar setter (+ vararg setter for lists) for a non-String literal type. */
private fun valueMethods(
    property: PropertyBuilderModel,
    propertyIri: CodeBlock,
    valueType: TypeName,
    untypedWrite: CodeBlock,
): List<FunSpec> {
    val write = typedLiteral(property, CodeBlock.of("value")) ?: untypedWrite
    return if (property.isList) {
        val builder = FunSpec.builder(property.propertyName)
            .addKdoc("%L", buildKdoc(property))
            .addParameter("values", valueType, VARARG)
            .beginControlFlow("values.forEach { value ->")
        addImmediateValidation(builder, property, "value", valueType)
        builder.addStatement("graph.addTriple(resource, %L, %L)", propertyIri, write)
        builder.endControlFlow()
        listOf(builder.build())
    } else {
        val builder = FunSpec.builder(property.propertyName)
            .addKdoc("%L", buildKdoc(property))
            .addParameter("value", valueType)
        addImmediateValidation(builder, property, "value", valueType)
        builder.addStatement("graph.addTriple(resource, %L, %L)", propertyIri, write)
        listOf(builder.build())
    }
}

// Shared helper functions
private fun buildKdoc(property: PropertyBuilderModel): String {
    return kdocText(buildString {
        append("Set ${property.propertyName}.")
        if (property.isRequired) {
            append("\nRequired property.")
        }
        if (property.constraints.minLength != null) {
            append("\nMin length: ${property.constraints.minLength}")
        }
        if (property.constraints.pattern != null) {
            append("\nPattern: ${property.constraints.pattern}")
        }
    })
}

/**
 * Emits `require(...)` checks for the SHACL constraints that can be evaluated on the setter argument.
 * Checks are datatype-aware: length/pattern checks only for text, numeric bounds only for numeric types
 * (BigInteger/BigDecimal compare exactly), `sh:in`/`sh:hasValue` against the lexical form. Constraints that
 * do not apply to [valueType] are left to `validate()` / SHACL validation.
 */
private fun addImmediateValidation(
    functionBuilder: FunSpec.Builder,
    property: PropertyBuilderModel,
    valueVar: String,
    valueType: TypeName,
) {
    val c = property.constraints
    val name = property.propertyName
    val langString = ClassName(CodegenConstants.RDF_PACKAGE, "LangString")
    val text: CodeBlock? = when (valueType) {
        STRING -> CodeBlock.of("%L", valueVar)
        langString -> CodeBlock.of("%L.lexical", valueVar)
        else -> null
    }
    val lexical: CodeBlock = text ?: CodeBlock.of("%L.toString()", valueVar)

    if (text != null) {
        c.minLength?.let {
            functionBuilder.addStatement("require(%L.let { it.codePointCount(0, it.length) } >= %L) { %S }", text, it, "$name must have minLength >= $it")
        }
        c.maxLength?.let {
            functionBuilder.addStatement("require(%L.let { it.codePointCount(0, it.length) } <= %L) { %S }", text, it, "$name must have maxLength <= $it")
        }
        c.pattern?.let {
            // The lazily compiled constant is emitted once per file by InstanceDslGenerator.
            functionBuilder.addStatement(
                "require(%N.containsMatchIn(%L)) { %S }", ShaclPatterns.constantName(it, c.patternFlags), text, "$name must match pattern: $it"
            )
        }
    }

    c.inValues?.takeIf { it.isNotEmpty() }?.let { values ->
        functionBuilder.addStatement(
            "require(%L in listOf(%L)) { %S }",
            lexical, values.map { CodeBlock.of("%S", it) }.joinToCode(", "), "$name must be one of: ${values.joinToString()}"
        )
    }

    c.hasValue?.let {
        functionBuilder.addStatement("require(%L == %S) { %S }", lexical, it, "$name must equal: $it")
    }

    // Numeric bounds compare exactly: the value is encoded as an XSD numeric literal and compared with the bound's
    // decimal lexical form (NaN is never within bounds; infinities lie beyond every bound).
    val numericDatatype: String? = when (valueType) {
        Int::class.asTypeName(), Long::class.asTypeName(), ClassName("java.math", "BigInteger") -> "integer"
        ClassName("java.math", "BigDecimal") -> "decimal"
        Float::class.asTypeName() -> "float"
        Double::class.asTypeName() -> "double"
        else -> null
    }
    if (numericDatatype != null) {
        fun bound(value: java.math.BigDecimal?, op: String, text: String) {
            if (value == null) return
            val lexical = value.toPlainString()
            functionBuilder.addStatement(
                "require(%T.compareNumeric(%T.encode(%L, %T(%S)), %S).let { it != null && it %L 0 }) { %S }",
                XSD_LITERALS, XSD_LITERALS, valueVar, IRI, "http://www.w3.org/2001/XMLSchema#$numericDatatype", lexical, op,
                "$name must be $text $lexical",
            )
        }
        bound(c.minInclusive, ">=", ">=")
        bound(c.maxInclusive, "<=", "<=")
        bound(c.minExclusive, ">", ">")
        bound(c.maxExclusive, "<", "<")
    }
}
