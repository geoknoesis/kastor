package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.model.PropertyBuilderModel
import com.geoknoesis.kastor.gen.processor.utils.CodegenConstants
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.*
import com.squareup.kotlinpoet.CodeBlock

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
        val strategy = PropertyTypeStrategy.from(property.kotlinType)
        return strategy.generateMethods(property, propertyIri, options)
    }
}

/**
 * Strategy for generating property methods based on type.
 */
sealed class PropertyTypeStrategy {
    abstract fun generateMethods(
        property: PropertyBuilderModel,
        propertyIri: CodeBlock,
        options: DslGenerationOptions
    ): List<FunSpec>
    
    object StringStrategy : PropertyTypeStrategy() {
        override fun generateMethods(
            property: PropertyBuilderModel,
            propertyIri: CodeBlock,
            options: DslGenerationOptions
        ): List<FunSpec> {
            val methods = mutableListOf<FunSpec>()
            
            val functionBuilder = FunSpec.builder(property.propertyName)
                .addKdoc(buildKdoc(property))
                .addParameter("value", String::class)
            
            addImmediateValidation(functionBuilder, property, "value")
            
            if (options.output.supportLanguageTags) {
                functionBuilder.addParameter(
                    ParameterSpec.builder("lang", String::class.asTypeName().copy(nullable = true))
                        .defaultValue(CodeBlock.of("null"))
                        .build()
                )
                functionBuilder.addStatement("val literal = if (lang != null) {")
                functionBuilder.addStatement("    %T(value, lang)", ClassName(CodegenConstants.RDF_PACKAGE, "LangString"))
                functionBuilder.addStatement("} else {")
                functionBuilder.addStatement("    %T(value, %T.string)", 
                    ClassName(CodegenConstants.RDF_PACKAGE, "Literal"),
                    ClassName(CodegenConstants.VOCAB_PACKAGE, "XSD"))
                functionBuilder.addStatement("}")
                functionBuilder.addStatement("graph.addTriple(resource, %L, literal)", propertyIri)
            } else {
                functionBuilder.addStatement("graph.addTriple(resource, %L, %T(value, %T.string))",
                    propertyIri,
                    ClassName(CodegenConstants.RDF_PACKAGE, "Literal"),
                    ClassName(CodegenConstants.VOCAB_PACKAGE, "XSD"))
            }
            
            methods.add(functionBuilder.build())
            
            // Add list variant if property is a list
            if (property.isList) {
                methods.add(generateStringListMethod(property, propertyIri, options))
            }
            
            return methods
        }
        
        private fun generateStringListMethod(
            property: PropertyBuilderModel,
            propertyIri: CodeBlock,
            options: DslGenerationOptions
        ): FunSpec {
            val functionBuilder = FunSpec.builder(property.propertyName)
                .addKdoc(buildKdoc(property))
                .addParameter("values", String::class, VARARG)
            
            if (options.output.supportLanguageTags) {
                functionBuilder.addParameter(
                    ParameterSpec.builder("lang", String::class.asTypeName().copy(nullable = true))
                        .defaultValue(CodeBlock.of("null"))
                        .build()
                )
                functionBuilder.addStatement("values.forEach { value ->")
                addImmediateValidation(functionBuilder, property, "value", indent = "    ")
                functionBuilder.addStatement("    val literal = if (lang != null) {")
                functionBuilder.addStatement("        %T(value, lang)", ClassName(CodegenConstants.RDF_PACKAGE, "LangString"))
                functionBuilder.addStatement("    } else {")
                functionBuilder.addStatement("        %T(value, %T.string)",
                    ClassName(CodegenConstants.RDF_PACKAGE, "Literal"),
                    ClassName(CodegenConstants.VOCAB_PACKAGE, "XSD"))
                functionBuilder.addStatement("    }")
                functionBuilder.addStatement("    graph.addTriple(resource, %L, literal)", propertyIri)
                functionBuilder.addStatement("}")
            } else {
                functionBuilder.addStatement("values.forEach { value ->")
                addImmediateValidation(functionBuilder, property, "value", indent = "    ")
                functionBuilder.addStatement("    graph.addTriple(resource, %L, %T(value, %T.string))",
                    propertyIri,
                    ClassName(CodegenConstants.RDF_PACKAGE, "Literal"),
                    ClassName(CodegenConstants.VOCAB_PACKAGE, "XSD"))
                functionBuilder.addStatement("}")
            }
            
            return functionBuilder.build()
        }
    }
    
    object IntStrategy : PropertyTypeStrategy() {
        override fun generateMethods(
            property: PropertyBuilderModel,
            propertyIri: CodeBlock,
            options: DslGenerationOptions
        ): List<FunSpec> {
            return if (property.isList) {
                listOf(generateIntListMethod(property, propertyIri, options))
            } else {
                listOf(generateIntMethod(property, propertyIri, options))
            }
        }
        
        private fun generateIntMethod(
            property: PropertyBuilderModel,
            propertyIri: CodeBlock,
            options: DslGenerationOptions
        ): FunSpec {
            val functionBuilder = FunSpec.builder(property.propertyName)
                .addKdoc(buildKdoc(property))
                .addParameter("value", Int::class)
            
            addImmediateValidation(functionBuilder, property, "value")
            functionBuilder.addStatement("graph.addTriple(resource, %L, %T(value.toString(), %T.integer))",
                propertyIri,
                ClassName(CodegenConstants.RDF_PACKAGE, "Literal"),
                ClassName(CodegenConstants.VOCAB_PACKAGE, "XSD"))
            
            return functionBuilder.build()
        }
        
        private fun generateIntListMethod(
            property: PropertyBuilderModel,
            propertyIri: CodeBlock,
            options: DslGenerationOptions
        ): FunSpec {
            val functionBuilder = FunSpec.builder(property.propertyName)
                .addKdoc(buildKdoc(property))
                .addParameter("values", Int::class, VARARG)
            
            functionBuilder.addStatement("values.forEach { value ->")
            addImmediateValidation(functionBuilder, property, "value", indent = "    ")
            functionBuilder.addStatement("    graph.addTriple(resource, %L, %T(value.toString(), %T.integer))",
                propertyIri,
                ClassName(CodegenConstants.RDF_PACKAGE, "Literal"),
                ClassName(CodegenConstants.VOCAB_PACKAGE, "XSD"))
            functionBuilder.addStatement("}")
            
            return functionBuilder.build()
        }
    }
    
    object DoubleStrategy : PropertyTypeStrategy() {
        override fun generateMethods(
            property: PropertyBuilderModel,
            propertyIri: CodeBlock,
            options: DslGenerationOptions
        ): List<FunSpec> {
            return if (property.isList) {
                listOf(generateDoubleListMethod(property, propertyIri, options))
            } else {
                listOf(generateDoubleMethod(property, propertyIri, options))
            }
        }
        
        private fun generateDoubleMethod(
            property: PropertyBuilderModel,
            propertyIri: CodeBlock,
            options: DslGenerationOptions
        ): FunSpec {
            val functionBuilder = FunSpec.builder(property.propertyName)
                .addKdoc(buildKdoc(property))
                .addParameter("value", Double::class)
            
            addImmediateValidation(functionBuilder, property, "value")
            functionBuilder.addStatement("graph.addTriple(resource, %L, %T(value.toString(), %T.double))",
                propertyIri,
                ClassName(CodegenConstants.RDF_PACKAGE, "Literal"),
                ClassName(CodegenConstants.VOCAB_PACKAGE, "XSD"))
            
            return functionBuilder.build()
        }
        
        private fun generateDoubleListMethod(
            property: PropertyBuilderModel,
            propertyIri: CodeBlock,
            options: DslGenerationOptions
        ): FunSpec {
            val functionBuilder = FunSpec.builder(property.propertyName)
                .addKdoc(buildKdoc(property))
                .addParameter("values", Double::class, VARARG)
            
            functionBuilder.addStatement("values.forEach { value ->")
            addImmediateValidation(functionBuilder, property, "value", indent = "    ")
            functionBuilder.addStatement("    graph.addTriple(resource, %L, %T(value.toString(), %T.double))",
                propertyIri,
                ClassName(CodegenConstants.RDF_PACKAGE, "Literal"),
                ClassName(CodegenConstants.VOCAB_PACKAGE, "XSD"))
            functionBuilder.addStatement("}")
            
            return functionBuilder.build()
        }
    }
    
    object BooleanStrategy : PropertyTypeStrategy() {
        override fun generateMethods(
            property: PropertyBuilderModel,
            propertyIri: CodeBlock,
            options: DslGenerationOptions
        ): List<FunSpec> {
            return if (property.isList) {
                listOf(generateBooleanListMethod(property, propertyIri, options))
            } else {
                listOf(generateBooleanMethod(property, propertyIri, options))
            }
        }
        
        private fun generateBooleanMethod(
            property: PropertyBuilderModel,
            propertyIri: CodeBlock,
            options: DslGenerationOptions
        ): FunSpec {
            val functionBuilder = FunSpec.builder(property.propertyName)
                .addKdoc(buildKdoc(property))
                .addParameter("value", Boolean::class)
            
            addImmediateValidation(functionBuilder, property, "value")
            functionBuilder.addStatement("graph.addTriple(resource, %L, %T(value.toString(), %T.boolean))",
                propertyIri,
                ClassName(CodegenConstants.RDF_PACKAGE, "Literal"),
                ClassName(CodegenConstants.VOCAB_PACKAGE, "XSD"))
            
            return functionBuilder.build()
        }
        
        private fun generateBooleanListMethod(
            property: PropertyBuilderModel,
            propertyIri: CodeBlock,
            options: DslGenerationOptions
        ): FunSpec {
            val functionBuilder = FunSpec.builder(property.propertyName)
                .addKdoc(buildKdoc(property))
                .addParameter("values", Boolean::class, VARARG)
            
            functionBuilder.addStatement("values.forEach { value ->")
            addImmediateValidation(functionBuilder, property, "value", indent = "    ")
            functionBuilder.addStatement("    graph.addTriple(resource, %L, %T(value.toString(), %T.boolean))",
                propertyIri,
                ClassName(CodegenConstants.RDF_PACKAGE, "Literal"),
                ClassName(CodegenConstants.VOCAB_PACKAGE, "XSD"))
            functionBuilder.addStatement("}")
            
            return functionBuilder.build()
        }
    }
    
    companion object {
        fun from(type: TypeName): PropertyTypeStrategy {
            return when {
                type.isStringType() -> StringStrategy
                type.isIntType() -> IntStrategy
                type.isDoubleType() -> DoubleStrategy
                type.isBooleanType() -> BooleanStrategy
                else -> StringStrategy // Default fallback
            }
        }
    }
}

// Extension functions for type checking
private fun TypeName.isStringType(): Boolean {
    return when {
        this == String::class.asTypeName() -> true
        this == String::class.asTypeName().copy(nullable = true) -> true
        this is ParameterizedTypeName && this.rawType.simpleName == "List" -> {
            val elementType = this.typeArguments.firstOrNull()
            elementType == String::class.asTypeName()
        }
        else -> false
    }
}

private fun TypeName.isIntType(): Boolean {
    return when {
        this == Int::class.asTypeName() -> true
        this == Int::class.asTypeName().copy(nullable = true) -> true
        this is ParameterizedTypeName && this.rawType.simpleName == "List" -> {
            val elementType = this.typeArguments.firstOrNull()
            elementType == Int::class.asTypeName()
        }
        else -> false
    }
}

private fun TypeName.isDoubleType(): Boolean {
    return when {
        this == Double::class.asTypeName() -> true
        this == Double::class.asTypeName().copy(nullable = true) -> true
        this is ParameterizedTypeName && this.rawType.simpleName == "List" -> {
            val elementType = this.typeArguments.firstOrNull()
            elementType == Double::class.asTypeName()
        }
        else -> false
    }
}

private fun TypeName.isBooleanType(): Boolean {
    return when {
        this == Boolean::class.asTypeName() -> true
        this == Boolean::class.asTypeName().copy(nullable = true) -> true
        this is ParameterizedTypeName && this.rawType.simpleName == "List" -> {
            val elementType = this.typeArguments.firstOrNull()
            elementType == Boolean::class.asTypeName()
        }
        else -> false
    }
}

// Shared helper functions
private fun buildKdoc(property: PropertyBuilderModel): String {
    return buildString {
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
    }
}

private fun addImmediateValidation(
    functionBuilder: FunSpec.Builder,
    property: PropertyBuilderModel,
    valueVar: String,
    indent: String = ""
) {
    // MinLength constraint
    if (property.constraints.minLength != null) {
        functionBuilder.addStatement("${indent}require($valueVar.length >= %L) { \"%L must have minLength >= %L\" }",
            property.constraints.minLength, property.propertyName, property.constraints.minLength)
    }
    
    // MaxLength constraint
    if (property.constraints.maxLength != null) {
        functionBuilder.addStatement("${indent}require($valueVar.length <= %L) { \"%L must have maxLength <= %L\" }",
            property.constraints.maxLength, property.propertyName, property.constraints.maxLength)
    }
    
    // Pattern constraint
    if (property.constraints.pattern != null) {
        val errorMessage = "${property.propertyName} must match pattern: ${property.constraints.pattern}"
        val statement = if (indent.isEmpty()) {
            CodeBlock.of("require(%T(%S).matches(%L)) { %S }",
                Regex::class, property.constraints.pattern, valueVar, errorMessage)
        } else {
            CodeBlock.of("%S require(%T(%S).matches(%L)) { %S }",
                indent, Regex::class, property.constraints.pattern, valueVar, errorMessage)
        }
        functionBuilder.addCode(statement)
    }
    
    // In constraint (value set)
    if (property.constraints.inValues != null && property.constraints.inValues.isNotEmpty()) {
        val valuesList = property.constraints.inValues.joinToString(", ") { "\"$it\"" }
        functionBuilder.addStatement("${indent}require($valueVar in listOf($valuesList)) { \"%L must be one of: %S\" }",
            property.propertyName, property.constraints.inValues.joinToString())
    }
    
    // HasValue constraint
    if (property.constraints.hasValue != null) {
        functionBuilder.addStatement("${indent}require($valueVar == \"%S\") { \"%L must equal: %S\" }",
            property.constraints.hasValue, property.propertyName, property.constraints.hasValue)
    }
    
    // Numeric constraints
    if (property.constraints.minInclusive != null) {
        functionBuilder.addStatement("${indent}require($valueVar >= %L) { \"%L must be >= %L\" }",
            property.constraints.minInclusive, property.propertyName, property.constraints.minInclusive)
    }
    if (property.constraints.maxInclusive != null) {
        functionBuilder.addStatement("${indent}require($valueVar <= %L) { \"%L must be <= %L\" }",
            property.constraints.maxInclusive, property.propertyName, property.constraints.maxInclusive)
    }
    if (property.constraints.minExclusive != null) {
        functionBuilder.addStatement("${indent}require($valueVar > %L) { \"%L must be > %L\" }",
            property.constraints.minExclusive, property.propertyName, property.constraints.minExclusive)
    }
    if (property.constraints.maxExclusive != null) {
        functionBuilder.addStatement("${indent}require($valueVar < %L) { \"%L must be < %L\" }",
            property.constraints.maxExclusive, property.propertyName, property.constraints.maxExclusive)
    }
}
