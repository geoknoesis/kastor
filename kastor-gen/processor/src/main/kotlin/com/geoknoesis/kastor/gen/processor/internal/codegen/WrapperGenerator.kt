package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.processor.internal.model.ClassModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyType
import com.geoknoesis.kastor.gen.processor.internal.utils.KotlinPoetUtils
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.*

internal class WrapperGenerator(private val logger: KSPLogger) {
    
    fun generateWrapper(classModel: ClassModel): FileSpec {
        val wrapperName = "${classModel.simpleName}Wrapper"
        
        val fileBuilder = FileSpec.builder(classModel.packageName, wrapperName)
            .addFileComment("GENERATED FILE - DO NOT EDIT")
        
        // Add imports
        fileBuilder.addImport("com.geoknoesis.kastor.gen.runtime", "RdfBacked", "OntoMapper", "KastorGraphOps", "RdfRef", "RdfHandle", "DefaultRdfHandle")
        fileBuilder.addImport("com.geoknoesis.kastor.rdf", "Iri", "RdfHandle", "RdfResource")
        
        // Build wrapper class
        val classBuilder = TypeSpec.classBuilder(wrapperName)
            .addModifiers(INTERNAL)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("input", ClassName("com.geoknoesis.kastor.rdf", "RdfHandle"))
                    .addModifiers(PRIVATE)
                    .build()
            )
            .addSuperinterface(ClassName("", classModel.simpleName))
            .addSuperinterface(ClassName("com.geoknoesis.kastor.gen.runtime", "RdfBacked"))
        
        // Known predicates set
        val knownIris = classModel.properties.map { 
            CodeBlock.of("Iri(%S)", it.predicateIri)
        }
        val setType = KotlinPoetUtils.setOf(
            ClassName("com.geoknoesis.kastor.rdf", "Iri")
        )
        val knownIrisCode = knownIris.joinToString(", ") { it.toString() }
        classBuilder.addProperty(
            PropertySpec.builder("known", setType)
                .addModifiers(PRIVATE)
                .initializer("setOf(%L)", CodeBlock.of(knownIrisCode))
                .build()
        )
        
        // RDF handle property
        classBuilder.addProperty(
            PropertySpec.builder("rdf", ClassName("com.geoknoesis.kastor.rdf", "RdfHandle"))
                .addModifiers(OVERRIDE)
                .delegate(
                    CodeBlock.of(
                        "lazy(LazyThreadSafetyMode.PUBLICATION) {\n" +
                        "  if (input is DefaultRdfHandle) DefaultRdfHandle(input.node, input.graph, known) else input\n" +
                        "}"
                    )
                )
                .build()
        )
        
        // Generate property implementations
        classModel.properties.forEach { property ->
            classBuilder.addProperty(generatePropertyImplementation(property))
        }
        
        // Companion object with registry
        val companionBuilder = TypeSpec.companionObjectBuilder()
        companionBuilder.addInitializerBlock(
            CodeBlock.of(
                "OntoMapper.registry[%T::class.java] = { handle -> %T(handle) }",
                ClassName("", classModel.simpleName),
                ClassName("", wrapperName)
            )
        )
        classBuilder.addType(companionBuilder.build())
        
        fileBuilder.addType(classBuilder.build())
        return fileBuilder.build()
    }
    
    private fun generatePropertyImplementation(property: PropertyModel): PropertySpec {
        return when (property.type) {
            PropertyType.LITERAL -> generateLiteralProperty(property)
            PropertyType.OBJECT -> generateObjectProperty(property)
            PropertyType.OBJECT_LIST -> generateObjectListProperty(property)
        }
    }
    
    private fun generateLiteralProperty(property: PropertyModel): PropertySpec {
        val propertyBuilder = PropertySpec.builder(property.name, determineTypeName(property.kotlinType))
            .addModifiers(OVERRIDE)
        
        val initializer = when {
            property.kotlinType == "List<String>" -> {
                CodeBlock.of("KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(%S)).map { it.lexical }", property.predicateIri)
            }
            property.kotlinType == "String" -> {
                CodeBlock.of("KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(%S)).map { it.lexical }.firstOrNull() ?: \"\"", property.predicateIri)
            }
            else -> {
                CodeBlock.of(
                    "KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(%S)).map { it.lexical }%L",
                    property.predicateIri, getConversionMethod(property.kotlinType)
                )
            }
        }
        
        propertyBuilder.delegate(CodeBlock.builder()
            .add("lazy {\n")
            .add(initializer)
            .add("\n}")
            .build())
        
        return propertyBuilder.build()
    }
    
    private fun generateObjectProperty(property: PropertyModel): PropertySpec {
        val elementType = property.kotlinType
        val elementTypeName = ClassName("", elementType)
        
        val propertyBuilder = PropertySpec.builder(property.name, elementTypeName)
            .addModifiers(OVERRIDE)
        
        val initializer = CodeBlock.of(
            "KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { child ->\n" +
            "  OntoMapper.materialize(RdfRef(child, rdf.graph), %T::class.java)\n" +
            "}.firstOrNull() ?: error(%S)",
            property.predicateIri, elementTypeName, "Required object ${property.name} missing"
        )
        
        propertyBuilder.delegate(CodeBlock.builder()
            .add("lazy {\n")
            .add(initializer)
            .add("\n}")
            .build())
        
        return propertyBuilder.build()
    }
    
    private fun generateObjectListProperty(property: PropertyModel): PropertySpec {
        val elementType = property.kotlinType.removePrefix("List<").removeSuffix(">")
        val elementTypeName = ClassName("", elementType)
        val listType = KotlinPoetUtils.listOf(elementTypeName)
        
        val propertyBuilder = PropertySpec.builder(property.name, listType)
            .addModifiers(OVERRIDE)
        
        val initializer = CodeBlock.of(
            "KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(%S)) { child ->\n" +
            "  OntoMapper.materialize(RdfRef(child, rdf.graph), %T::class.java)\n" +
            "}",
            property.predicateIri, elementTypeName
        )
        
        propertyBuilder.delegate(CodeBlock.builder()
            .add("lazy {\n")
            .add(initializer)
            .add("\n}")
            .build())
        
        return propertyBuilder.build()
    }
    
    private fun determineTypeName(kotlinType: String): TypeName {
        return when {
            kotlinType == "String" -> String::class.asTypeName()
            kotlinType == "Int" -> Int::class.asTypeName()
            kotlinType == "Double" -> Double::class.asTypeName()
            kotlinType == "Boolean" -> Boolean::class.asTypeName()
            kotlinType.startsWith("List<") -> {
                val elementType = kotlinType.removePrefix("List<").removeSuffix(">")
                val elementTypeName = when (elementType) {
                    "String" -> String::class.asTypeName()
                    "Int" -> Int::class.asTypeName()
                    "Double" -> Double::class.asTypeName()
                    "Boolean" -> Boolean::class.asTypeName()
                    else -> ClassName("", elementType)
                }
                KotlinPoetUtils.listOf(elementTypeName)
            }
            else -> ClassName("", kotlinType)
        }
    }
    
    private fun getConversionMethod(kotlinType: String): String {
        return when (kotlinType) {
            "Int" -> ".mapNotNull { it.toIntOrNull() }"
            "Double" -> ".mapNotNull { it.toDoubleOrNull() }"
            "Boolean" -> ".mapNotNull { it.toBooleanStrictOrNull() }"
            "List<Int>" -> ".mapNotNull { it.toIntOrNull() }"
            "List<Double>" -> ".mapNotNull { it.toDoubleOrNull() }"
            "List<Boolean>" -> ".mapNotNull { it.toBooleanStrictOrNull() }"
            else -> ""
        }
    }
}

