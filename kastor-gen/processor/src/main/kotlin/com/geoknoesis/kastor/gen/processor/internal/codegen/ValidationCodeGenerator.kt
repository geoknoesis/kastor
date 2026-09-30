package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.processor.api.model.ClassBuilderModel
import com.geoknoesis.kastor.gen.processor.internal.utils.CodegenConstants
import com.geoknoesis.kastor.gen.processor.internal.utils.ShaclPatterns
import com.geoknoesis.kastor.gen.processor.internal.utils.kdocText
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*

/**
 * Generator for validation methods in builder classes using KotlinPoet.
 *
 * Every ontology-derived value (IRIs, patterns, `sh:in` members, names in messages) is passed as a
 * KotlinPoet argument (`%S`, `%N`) so quotes, `$` and `%` can never break or inject into generated code.
 */
internal class ValidationCodeGenerator(
    private val logger: KSPLogger
) {

    private val literalClass = ClassName(CodegenConstants.RDF_PACKAGE, "Literal")
    private val iriClass = ClassName(CodegenConstants.RDF_PACKAGE, "Iri")
    private val xsdLiterals = ClassName(CodegenConstants.RUNTIME_PACKAGE, "XsdLiterals")

    /**
     * Generates a validate() method for a builder class.
     */
    fun generateValidationMethod(classBuilder: ClassBuilderModel): FunSpec {
        val functionBuilder = FunSpec.builder("validate")
            .addKdoc("%L", kdocText("Validate the ${classBuilder.className.lowercase()} against SHACL constraints."))
            .addStatement("val violations = mutableListOf<%T>()", String::class)

        // Check required properties - sort by propertyIri for deterministic output
        classBuilder.properties
            .sortedBy { it.propertyIri }
            .forEach { property ->
                if (property.isRequired) {
                    val iri = CodegenConstants.iriConstant(property.propertyIri)
                    functionBuilder.addComment("Check required %L", property.propertyName)
                    val count = "${property.propertyName}Count"
                    functionBuilder.addStatement("val %N = graph.find(resource, %L).size", count, iri)
                    functionBuilder.beginControlFlow("if (%N < 1)", count)
                    functionBuilder.addStatement("violations.add(%S)", "${property.propertyName} is required (minCount=1)")
                    functionBuilder.endControlFlow()
                }
            }

        // Check value constraints on existing values - sort by propertyIri for deterministic output
        classBuilder.properties
            .sortedBy { it.propertyIri }
            .forEach { property ->
                val iri = CodegenConstants.iriConstant(property.propertyIri)
                val c = property.constraints
                val name = property.propertyName

                val hasConstraints = c.minLength != null || c.maxLength != null || c.pattern != null ||
                    !c.inValues.isNullOrEmpty()

                if (hasConstraints) {
                    functionBuilder.addComment("Validate %L constraints", name)
                    // Like embedded validation (SHACL): string constraints use the lexical form of a literal or the
                    // string of an IRI; a blank node has no string and violates them. sh:in compares the same string.
                    functionBuilder.beginControlFlow("graph.find(resource, %L).forEach { triple ->", iri)
                    functionBuilder.addStatement(
                        "val value: String? = when (val obj = triple.obj) { is %T -> obj.lexical; is %T -> obj.value; else -> null }",
                        literalClass, iriClass,
                    )
                    c.minLength?.let {
                        functionBuilder.beginControlFlow("if (value == null || value.codePointCount(0, value.length) < %L)", it)
                        functionBuilder.addStatement("violations.add(%S)", "$name must have minLength >= $it")
                        functionBuilder.endControlFlow()
                    }
                    c.maxLength?.let {
                        functionBuilder.beginControlFlow("if (value == null || value.codePointCount(0, value.length) > %L)", it)
                        functionBuilder.addStatement("violations.add(%S)", "$name must have maxLength <= $it")
                        functionBuilder.endControlFlow()
                    }
                    c.pattern?.let {
                        functionBuilder.beginControlFlow("if (value == null || !%N.containsMatchIn(value))", ShaclPatterns.constantName(it, c.patternFlags))
                        functionBuilder.addStatement("violations.add(%S)", "$name must match pattern: $it")
                        functionBuilder.endControlFlow()
                    }
                    c.inValues?.takeIf { it.isNotEmpty() }?.let { values ->
                        functionBuilder.beginControlFlow(
                            "if (value !in listOf(%L))", values.map { CodeBlock.of("%S", it) }.joinToCode(", ")
                        )
                        functionBuilder.addStatement("violations.add(%S)", "$name must be one of: ${values.joinToString()}")
                        functionBuilder.endControlFlow()
                    }
                    functionBuilder.endControlFlow()
                }

                val hasNumericConstraints = c.minInclusive != null || c.maxInclusive != null ||
                    c.minExclusive != null || c.maxExclusive != null

                if (hasNumericConstraints) {
                    functionBuilder.addComment("Validate %L numeric constraints", name)
                    // Exact comparison of XSD numeric literals with the bound's decimal lexical form; a value that is not
                    // a well-formed numeric literal cannot be compared, which SHACL (and embedded validation) reports.
                    functionBuilder.beginControlFlow("graph.find(resource, %L).forEach { triple ->", iri)
                    fun bound(bound: java.math.BigDecimal?, violatedWhen: String, text: String) {
                        if (bound == null) return
                        val lexical = bound.toPlainString()
                        functionBuilder.beginControlFlow(
                            "if (%T.compareNumeric(triple.obj, %S).let { it == null || %L })", xsdLiterals, lexical, violatedWhen,
                        )
                        functionBuilder.addStatement("violations.add(%S)", "$name must be $text $lexical")
                        functionBuilder.endControlFlow()
                    }
                    bound(c.minInclusive, "it < 0", ">=")
                    bound(c.maxInclusive, "it > 0", "<=")
                    bound(c.minExclusive, "it <= 0", ">")
                    bound(c.maxExclusive, "it >= 0", "<")
                    functionBuilder.endControlFlow()
                }
            }

        functionBuilder.beginControlFlow("if (violations.isNotEmpty())")
        // `violations` is a List<String>; the runtime ValidationException takes the message (and optional
        // structured violations), so the strings are folded into the message.
        functionBuilder.addStatement(
            "throw %T(%S + resource + %S + violations.joinToString(%S))",
            ClassName(CodegenConstants.RUNTIME_PACKAGE, "ValidationException"),
            "${classBuilder.className} ",
            " validation failed: ",
            ", ",
        )
        functionBuilder.endControlFlow()

        return functionBuilder.build()
    }
}
