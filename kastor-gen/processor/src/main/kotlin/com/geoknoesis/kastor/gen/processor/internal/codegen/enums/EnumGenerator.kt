package com.geoknoesis.kastor.gen.processor.internal.codegen.enums

import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind
import com.geoknoesis.kastor.gen.processor.api.model.EnumModel
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

private val IRI_CLASS = ClassName("com.geoknoesis.kastor.rdf", "Iri")

internal class EnumGenerator(private val logger: KSPLogger) {

    public fun generateEnums(model: OntologyModel, packageName: String): Map<String, FileSpec> =
        model.enums.sortedBy { it.name }.associate { it.name to fileFor(it, packageName) }

    private fun fileFor(e: EnumModel, packageName: String): FileSpec {
        val sealedName = ClassName(packageName, e.name)
        val iriKind = e.memberKind == EnumMemberKind.IRI
        val valueProp = if (iriKind) "iri" else "code"
        val valueType = if (iriKind) IRI_CLASS else ClassName("kotlin", "String")

        // Build enum class Known(override val <valueProp>: <valueType>) : <SealedName>
        val knownBuilder = TypeSpec.enumBuilder("Known")
            .addSuperinterface(sealedName)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(ParameterSpec.builder(valueProp, valueType).build())
                    .build()
            )
            .addProperty(
                PropertySpec.builder(valueProp, valueType)
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer(valueProp)
                    .build()
            )

        e.members.forEach { m ->
            val argLiteral: String = if (iriKind) m.iri ?: "" else m.code ?: ""
            val constructorArgs = if (iriKind) "Iri(%S)" else "%S"
            knownBuilder.addEnumConstant(
                m.constantName,
                TypeSpec.anonymousClassBuilder()
                    .addSuperclassConstructorParameter(constructorArgs, argLiteral)
                    .build()
            )
        }

        // Build data class Unknown(override val <valueProp>: <valueType>) : <SealedName>
        val unknownClass = TypeSpec.classBuilder("Unknown")
            .addModifiers(KModifier.DATA)
            .addSuperinterface(sealedName)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(ParameterSpec.builder(valueProp, valueType).build())
                    .build()
            )
            .addProperty(
                PropertySpec.builder(valueProp, valueType)
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer(valueProp)
                    .build()
            )
            .build()

        // Build companion object with from() factory
        val companionObject = TypeSpec.companionObjectBuilder()
            .addFunction(
                FunSpec.builder("from")
                    .addParameter(ParameterSpec.builder(valueProp, valueType).build())
                    .returns(sealedName)
                    .addStatement(
                        "return Known.entries.firstOrNull { it.%L == %L } ?: Unknown(%L)",
                        valueProp, valueProp, valueProp
                    )
                    .build()
            )
            .build()

        // Build the sealed interface with val <valueProp>: <valueType> + nested types
        val sealedInterface = TypeSpec.interfaceBuilder(e.name)
            .addModifiers(KModifier.SEALED)
            .addProperty(
                PropertySpec.builder(valueProp, valueType).build()
            )
            .addType(knownBuilder.build())
            .addType(unknownClass)
            .addType(companionObject)
            .build()

        return FileSpec.builder(packageName, e.name)
            .addImport("com.geoknoesis.kastor.rdf", "Iri")
            .addType(sealedInterface)
            .build()
    }
}
