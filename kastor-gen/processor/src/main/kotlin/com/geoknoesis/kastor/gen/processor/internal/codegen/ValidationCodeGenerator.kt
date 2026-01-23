package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.processor.api.model.ClassBuilderModel
import com.geoknoesis.kastor.gen.processor.internal.utils.VocabularyMapper
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.CodeBlock

/**
 * Generator for validation methods in builder classes using KotlinPoet.
 */
internal class ValidationCodeGenerator(
    private val logger: KSPLogger
) {

    /**
     * Generates a validate() method for a builder class.
     */
    fun generateValidationMethod(classBuilder: ClassBuilderModel): FunSpec {
        val functionBuilder = FunSpec.builder("validate")
            .addKdoc("Validate the %L against SHACL constraints.", classBuilder.className.lowercase())
            .addStatement("val violations = mutableListOf<%T>()", String::class)
        
        // Check required properties
        classBuilder.properties.forEach { property ->
            if (property.isRequired) {
                val propertyIriConstant = VocabularyMapper.getVocabularyConstant(property.propertyIri)
                    ?: "Iri(\"${property.propertyIri}\")"
                val propertyIriCodeBlock = CodeBlock.of("%L", propertyIriConstant)
                
                functionBuilder.addCode("\n")
                functionBuilder.addStatement("// Check required %L", property.propertyName)
                functionBuilder.addStatement("val %LCount = graph.getTriples(resource, %L, null).size",
                    property.propertyName, propertyIriCodeBlock)
                functionBuilder.addStatement("if (%LCount < 1) {", property.propertyName)
                functionBuilder.addStatement("    violations.add(\"%L is required (minCount=1)\")", property.propertyName)
                functionBuilder.addStatement("}")
            }
        }
        
        // Check value constraints on existing values
        classBuilder.properties.forEach { property ->
            val propertyIriConstant = VocabularyMapper.getVocabularyConstant(property.propertyIri)
                ?: "Iri(\"${property.propertyIri}\")"
            val propertyIriCodeBlock = CodeBlock.of("%L", propertyIriConstant)
            
            val hasConstraints = property.constraints.minLength != null ||
                    property.constraints.maxLength != null ||
                    property.constraints.pattern != null ||
                    property.constraints.inValues != null
            
            if (hasConstraints) {
                functionBuilder.addCode("\n")
                functionBuilder.addStatement("// Validate %L constraints", property.propertyName)
                functionBuilder.addStatement("graph.getTriples(resource, %L, null).forEach { triple ->", propertyIriCodeBlock)
                functionBuilder.addStatement("    val literal = triple.obj as? %T", 
                    ClassName("com.geoknoesis.kastor.rdf", "Literal"))
                functionBuilder.addStatement("    if (literal != null) {")
                functionBuilder.addStatement("        val value = literal.lexical")
                
                // MinLength
                if (property.constraints.minLength != null) {
                    functionBuilder.addStatement("        if (value.length < %L) {", property.constraints.minLength)
                    functionBuilder.addStatement("            violations.add(\"%L must have minLength >= %L\")",
                        property.propertyName, property.constraints.minLength)
                    functionBuilder.addStatement("        }")
                }
                
                // MaxLength
                if (property.constraints.maxLength != null) {
                    functionBuilder.addStatement("        if (value.length > %L) {", property.constraints.maxLength)
                    functionBuilder.addStatement("            violations.add(\"%L must have maxLength <= %L\")",
                        property.propertyName, property.constraints.maxLength)
                    functionBuilder.addStatement("        }")
                }
                
                // Pattern
                if (property.constraints.pattern != null) {
                    functionBuilder.addStatement("        if (!%T(\"%S\").matches(value)) {",
                        Regex::class, property.constraints.pattern)
                    functionBuilder.addStatement("            violations.add(\"%L must match pattern: %S\")",
                        property.propertyName, property.constraints.pattern)
                    functionBuilder.addStatement("        }")
                }
                
                // In constraint
                if (property.constraints.inValues != null && property.constraints.inValues.isNotEmpty()) {
                    val valuesList = property.constraints.inValues.joinToString(", ") { "\"$it\"" }
                    functionBuilder.addStatement("        if (value !in listOf($valuesList)) {")
                    functionBuilder.addStatement("            violations.add(\"%L must be one of: %S\")",
                        property.propertyName, property.constraints.inValues.joinToString())
                    functionBuilder.addStatement("        }")
                }
                
                functionBuilder.addStatement("    }")
                functionBuilder.addStatement("}")
            }
            
            // Numeric constraints
            val hasNumericConstraints = property.constraints.minInclusive != null ||
                    property.constraints.maxInclusive != null ||
                    property.constraints.minExclusive != null ||
                    property.constraints.maxExclusive != null
            
            if (hasNumericConstraints) {
                functionBuilder.addCode("\n")
                functionBuilder.addStatement("// Validate %L numeric constraints", property.propertyName)
                functionBuilder.addStatement("graph.getTriples(resource, %L, null).forEach { triple ->", propertyIriCodeBlock)
                functionBuilder.addStatement("    val literal = triple.obj as? %T",
                    ClassName("com.geoknoesis.kastor.rdf", "Literal"))
                functionBuilder.addStatement("    if (literal != null) {")
                functionBuilder.addStatement("        val value = literal.lexical.toDoubleOrNull()")
                functionBuilder.addStatement("        if (value != null) {")
                
                if (property.constraints.minInclusive != null) {
                    functionBuilder.addStatement("            if (value < %L) {", property.constraints.minInclusive)
                    functionBuilder.addStatement("                violations.add(\"%L must be >= %L\")",
                        property.propertyName, property.constraints.minInclusive)
                    functionBuilder.addStatement("            }")
                }
                if (property.constraints.maxInclusive != null) {
                    functionBuilder.addStatement("            if (value > %L) {", property.constraints.maxInclusive)
                    functionBuilder.addStatement("                violations.add(\"%L must be <= %L\")",
                        property.propertyName, property.constraints.maxInclusive)
                    functionBuilder.addStatement("            }")
                }
                if (property.constraints.minExclusive != null) {
                    functionBuilder.addStatement("            if (value <= %L) {", property.constraints.minExclusive)
                    functionBuilder.addStatement("                violations.add(\"%L must be > %L\")",
                        property.propertyName, property.constraints.minExclusive)
                    functionBuilder.addStatement("            }")
                }
                if (property.constraints.maxExclusive != null) {
                    functionBuilder.addStatement("            if (value >= %L) {", property.constraints.maxExclusive)
                    functionBuilder.addStatement("                violations.add(\"%L must be < %L\")",
                        property.propertyName, property.constraints.maxExclusive)
                    functionBuilder.addStatement("            }")
                }
                
                functionBuilder.addStatement("        }")
                functionBuilder.addStatement("    }")
                functionBuilder.addStatement("}")
            }
        }
        
        // Check maxCount constraints
        // Note: maxCount validation would go here if needed
        // Currently maxCount is enforced by the type system (List vs single value)
        
        functionBuilder.addCode("\n")
        functionBuilder.addStatement("if (violations.isNotEmpty()) {")
        functionBuilder.addStatement("    throw %T(", ClassName("com.geoknoesis.kastor.gen.runtime", "ValidationException"))
        functionBuilder.addStatement("        \"%L \${resource} validation failed: \${violations.joinToString(\", \")}\",",
            classBuilder.className)
        functionBuilder.addStatement("        violations")
        functionBuilder.addStatement("    )")
        functionBuilder.addStatement("}")
        
        return functionBuilder.build()
    }
}

