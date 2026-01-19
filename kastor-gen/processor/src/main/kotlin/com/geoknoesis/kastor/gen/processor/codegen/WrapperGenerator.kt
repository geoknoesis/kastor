package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.model.ClassModel
import com.geoknoesis.kastor.gen.processor.model.PropertyModel
import com.geoknoesis.kastor.gen.processor.model.PropertyType
import com.google.devtools.ksp.processing.KSPLogger

class WrapperGenerator(private val logger: KSPLogger) {
    
    fun generateWrapper(classModel: ClassModel): String {
        return buildString {
            appendLine("// GENERATED FILE - DO NOT EDIT")
            appendLine("package ${classModel.packageName}")
            appendLine()
            appendLine("import com.geoknoesis.kastor.gen.runtime.*")
            appendLine("import com.geoknoesis.kastor.rdf.*")
            appendLine()
            appendLine("internal class ${classModel.simpleName}Wrapper(")
            appendLine("  private val input: RdfHandle")
            appendLine(") : ${classModel.simpleName}, RdfBacked {")
            appendLine()
            appendLine("  private val known: Set<Iri> = setOf(")
            appendLine(classModel.properties.joinToString(",\n") { "    Iri(\"${it.predicateIri}\")" })
            appendLine("  )")
            appendLine()
            appendLine("  override val rdf: RdfHandle by lazy(LazyThreadSafetyMode.PUBLICATION) {")
            appendLine("    if (input is DefaultRdfHandle) DefaultRdfHandle(input.node, input.graph, known) else input")
            appendLine("  }")
            appendLine()
            
            // Generate property implementations
            classModel.properties.forEach { property ->
                appendLine(generatePropertyImplementation(property))
                appendLine()
            }
            
            // Generate companion object with registry
            appendLine("  companion object {")
            appendLine("    init {")
            appendLine("      OntoMapper.registry[${classModel.simpleName}::class.java] = { handle -> ${classModel.simpleName}Wrapper(handle) }")
            appendLine("    }")
            appendLine("  }")
            appendLine("}")
        }
    }
    
    private fun generatePropertyImplementation(property: PropertyModel): String {
        return when (property.type) {
            PropertyType.LITERAL -> generateLiteralProperty(property)
            PropertyType.OBJECT -> generateObjectProperty(property)
            PropertyType.OBJECT_LIST -> generateObjectListProperty(property)
        }
    }
    
    private fun generateLiteralProperty(property: PropertyModel): String {
        return buildString {
            appendLine("  override val ${property.name}: ${property.kotlinType} by lazy {")
            if (property.kotlinType == "List<String>") {
                appendLine("    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"${property.predicateIri}\")).map { it.lexical }")
            } else if (property.kotlinType == "String") {
                appendLine("    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"${property.predicateIri}\")).map { it.lexical }.firstOrNull() ?: \"\"")
            } else {
                appendLine("    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"${property.predicateIri}\")).map { it.lexical }${getConversionMethod(property.kotlinType)}")
            }
            appendLine("  }")
        }
    }
    
    private fun generateObjectProperty(property: PropertyModel): String {
        val elementType = property.kotlinType
        return buildString {
            appendLine("  override val ${property.name}: ${elementType} by lazy {")
            appendLine("    KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(\"${property.predicateIri}\")) { child ->")
            appendLine("      OntoMapper.materialize(RdfRef(child, rdf.graph), ${elementType}::class.java)")
            appendLine("    }.firstOrNull() ?: error(\"Required object ${property.name} missing\")")
            appendLine("  }")
        }
    }
    
    private fun generateObjectListProperty(property: PropertyModel): String {
        val elementType = property.kotlinType.removePrefix("List<").removeSuffix(">")
        return buildString {
            appendLine("  override val ${property.name}: ${property.kotlinType} by lazy {")
            appendLine("    KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(\"${property.predicateIri}\")) { child ->")
            appendLine("      OntoMapper.materialize(RdfRef(child, rdf.graph), ${elementType}::class.java)")
            appendLine("    }")
            appendLine("  }")
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












