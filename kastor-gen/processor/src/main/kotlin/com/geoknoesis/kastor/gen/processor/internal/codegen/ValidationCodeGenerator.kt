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

                val inMembers = ShaclInCode.members(c.inValuesTyped, c.inValues, iriValued = property.isIriValued)
                val hasConstraints = c.minLength != null || c.maxLength != null || c.patterns.isNotEmpty()

                // sh:hasValue: each required value must be among the values of the property (an IRI or the lexical
                // form of a literal), however many other values there are - and also when there is none.
                c.hasValues.forEach { required ->
                    functionBuilder.addComment("Check %L sh:hasValue", name)
                    functionBuilder.beginControlFlow(
                        "if (graph.find(resource, %L).none { triple -> when (val obj = triple.obj) { is %T -> obj.lexical == %S; is %T -> obj.value == %S; else -> false } })",
                        iri, literalClass, required, iriClass, required,
                    )
                    functionBuilder.addStatement("violations.add(%S)", "$name must have the value: $required")
                    functionBuilder.endControlFlow()
                }

                if (c.nodeKindUnsatisfiable) {
                    functionBuilder.addComment("%L: the sh:nodeKind constraints that apply have no node kind in common", name)
                    functionBuilder.beginControlFlow("if (graph.find(resource, %L).isNotEmpty())", iri)
                    functionBuilder.addStatement("violations.add(%S)", "$name: no value satisfies the sh:nodeKind constraints that apply")
                    functionBuilder.endControlFlow()
                }

                if (hasConstraints) {
                    functionBuilder.addComment("Validate %L constraints", name)
                    // Like embedded validation (SHACL): string constraints use the lexical form of a literal or the
                    // string of an IRI; a blank node has no string and violates them.
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
                    c.patterns.forEach { (pattern, flags) ->
                        functionBuilder.beginControlFlow("if (value == null || !%N.containsMatchIn(value))", ShaclPatterns.constantName(pattern, flags))
                        functionBuilder.addStatement("violations.add(%S)", "$name must match pattern: $pattern")
                        functionBuilder.endControlFlow()
                    }
                    functionBuilder.endControlFlow()
                }

                inMembers?.let { members ->
                    functionBuilder.addComment("Validate %L sh:in", name)
                    // Like embedded validation (SHACL): every value node must equal a member as an RDF term (same IRI,
                    // or same lexical form with the same language tag / datatype), so "chat"@fr is not the member
                    // "chat"@en and the string "5" is not the integer 5.
                    functionBuilder.beginControlFlow("graph.find(resource, %L).forEach { triple ->", iri)
                    functionBuilder.addStatement("val value = triple.obj")
                    functionBuilder.beginControlFlow("if (!(%L))", ShaclInCode.isMember("value", members, property.datatype))
                    functionBuilder.addStatement(
                        "violations.add(%S)",
                        if (members.isEmpty()) "$name has an sh:in without members: no value is allowed"
                        else "$name must be one of: ${members.joinToString { it.value }}",
                    )
                    functionBuilder.endControlFlow()
                    functionBuilder.endControlFlow()
                }

                if (c.numericBounds.isNotEmpty()) {
                    functionBuilder.addComment("Validate %L numeric constraints", name)
                    // A bound's datatype participates in promotion, including inherited constraints.
                    functionBuilder.beginControlFlow("graph.find(resource, %L).forEach { triple ->", iri)
                    c.numericBounds.forEach { bound ->
                        val lexical = bound.value.toPlainString()
                        functionBuilder.beginControlFlow(
                            "if (%T.compareNumeric(triple.obj, %S, %T(%S)).let { it == null || it %L 0 })",
                            xsdLiterals, lexical, iriClass, bound.datatype, bound.kind.violationOperator,
                        )
                        functionBuilder.addStatement("violations.add(%S)", "$name must be ${bound.kind.operator} $lexical")
                        functionBuilder.endControlFlow()
                    }
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
