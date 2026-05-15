package com.geoknoesis.kastor.gen.processor.internal.core

import com.geoknoesis.kastor.gen.annotations.RDF_ANNOTATION_FQN
import com.geoknoesis.kastor.gen.processor.internal.model.ClassModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyType
import com.geoknoesis.kastor.gen.processor.internal.codegen.WrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.utils.QNameResolver
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate

/**
 * KSP processor for generating RDF-backed domain object wrappers from `@Rdf` interfaces.
 */
class OntoMapperProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  private val options: Map<String, String>,
) : SymbolProcessor {

  private val wrapperGenerator = WrapperGenerator(logger)
  private val processedClasses = mutableSetOf<String>()

  override fun process(resolver: Resolver): List<KSAnnotated> {
    logger.info("OntoMapper processor starting…")
    val symbols = resolver.getSymbolsWithAnnotation(RDF_ANNOTATION_FQN)

    logger.info("Found ${symbols.iterator().asSequence().count()} symbols with @Rdf")

    if (!symbols.iterator().hasNext()) {
      logger.info("No symbols found, returning empty list")
      return emptyList()
    }

    val classModels = mutableListOf<ClassModel>()

    symbols.forEach { symbol ->
      if (symbol !is KSClassDeclaration) {
        return@forEach
      }
      val rdfAnn = symbol.annotations.find { it.shortName.asString() == "Rdf" } ?: return@forEach
      val iriRaw = rdfAnn.arguments.find { it.name?.asString() == "iri" }?.value as? String ?: ""
      val shaclRaw = rdfAnn.arguments.find { it.name?.asString() == "shacl" }?.value as? String ?: ""
      if (iriRaw.isBlank() || shaclRaw.isNotBlank()) {
        return@forEach
      }

      val prefixMappings = prefixMappingsFor(symbol)
      logger.info("Resolved ${prefixMappings.size} prefix mappings for ${symbol.qualifiedName?.asString()}")

      val classModel = analyzeClass(symbol, prefixMappings, rdfAnn)
      if (classModel != null) {
        classModels.add(classModel)
      }
    }

    classModels.forEach { generateWrapper(it) }

    return symbols.filterNot { it.validate() }.toList()
  }

  /**
   * Prefixes visible when resolving QNames for `@Rdf(iri = …)` on [classDecl] and its properties:
   * 1) `@file:Rdf(prefixes = …)` on the same source file (if any)
   * 2) `prefixes = …` on the class/interface `@Rdf` (later entries override earlier on name clash)
   */
  private fun prefixMappingsFor(classDecl: KSClassDeclaration): Map<String, String> {
    val into = LinkedHashMap<String, String>()
    classDecl.findContainingKsFile()?.annotations
      ?.filter { it.shortName.asString() == "Rdf" }
      ?.forEach { mergePrefixesFromRdfAnnotation(it, into) }
    classDecl.annotations.filter { it.shortName.asString() == "Rdf" }.forEach { ann ->
      mergePrefixesFromRdfAnnotation(ann, into)
    }
    return into
  }

  private fun KSDeclaration.findContainingKsFile(): KSFile? {
    var node: KSNode? = this
    while (node != null) {
      if (node is KSFile) return node
      node = node.parent
    }
    return null
  }

  private fun mergePrefixesFromRdfAnnotation(annotation: KSAnnotation, into: MutableMap<String, String>) {
    val prefixesArgument = annotation.arguments.find { it.name?.asString() == "prefixes" }
    val prefixesArray = prefixesArgument?.value as? List<*> ?: emptyList<Any>()
    prefixesArray.forEach { prefixElement ->
      when (prefixElement) {
        is KSAnnotation -> {
          val name = prefixElement.arguments.find { it.name?.asString() == "name" }?.value as? String
          val namespace = prefixElement.arguments.find { it.name?.asString() == "namespace" }?.value as? String
          if (!name.isNullOrBlank() && !namespace.isNullOrBlank()) {
            into[name] = namespace
            logger.info("Registered prefix: $name -> $namespace")
          }
        }
        is KSType -> {
          val declAnn = prefixElement.declaration.annotations.firstOrNull()
          val name = declAnn?.arguments?.find { it.name?.asString() == "name" }?.value as? String
          val namespace = declAnn?.arguments?.find { it.name?.asString() == "namespace" }?.value as? String
          if (!name.isNullOrBlank() && !namespace.isNullOrBlank()) {
            into[name] = namespace
            logger.info("Registered prefix: $name -> $namespace")
          }
        }
      }
    }
  }

  private fun analyzeClass(
    classDecl: KSClassDeclaration,
    prefixMappings: Map<String, String>,
    rdfClassAnnotation: KSAnnotation,
  ): ClassModel? {
    val qualifiedName = classDecl.qualifiedName?.asString() ?: return null

    if (qualifiedName in processedClasses) {
      return null
    }
    processedClasses.add(qualifiedName)

    val properties = mutableListOf<PropertyModel>()
    classDecl.getAllProperties().forEach { property ->
      val propertyModel = analyzeProperty(property, prefixMappings)
      if (propertyModel != null) {
        properties.add(propertyModel)
      }
    }

    val classIriRaw = rdfClassAnnotation.arguments
      .find { it.name?.asString() == "iri" }
      ?.value as? String
      ?: ""

    val classIri = if (classIriRaw.isNotEmpty() && QNameResolver.isQName(classIriRaw)) {
      try {
        QNameResolver.resolveQName(classIriRaw, prefixMappings)
      } catch (e: IllegalArgumentException) {
        logger.error("Failed to resolve QName '$classIriRaw': ${e.message}", classDecl)
        classIriRaw
      }
    } else {
      classIriRaw
    }

    return ClassModel(
      qualifiedName = qualifiedName,
      simpleName = classDecl.simpleName.asString(),
      packageName = classDecl.packageName.asString(),
      classIri = classIri,
      properties = properties,
    )
  }

  private fun analyzeProperty(property: KSPropertyDeclaration, prefixMappings: Map<String, String>): PropertyModel? {
    val rdfPropertyAnnotation = property.annotations.find { it.shortName.asString() == "Rdf" }
      ?: property.getter?.annotations?.find { it.shortName.asString() == "Rdf" }
      ?: property.setter?.annotations?.find { it.shortName.asString() == "Rdf" }
      ?: return null

    val predicateIriRaw = rdfPropertyAnnotation.arguments
      .find { it.name?.asString() == "iri" }
      ?.value as? String
      ?: return null

    val predicateIri = if (QNameResolver.isQName(predicateIriRaw)) {
      try {
        QNameResolver.resolveQName(predicateIriRaw, prefixMappings)
      } catch (e: IllegalArgumentException) {
        logger.error("Failed to resolve QName '$predicateIriRaw': ${e.message}", property)
        return null
      }
    } else {
      predicateIriRaw
    }

    val returnType = property.type.resolve()
    val kotlinType = when {
      returnType.declaration.qualifiedName?.asString() == "kotlin.String" -> "String"
      returnType.declaration.qualifiedName?.asString() == "kotlin.Int" -> "Int"
      returnType.declaration.qualifiedName?.asString() == "kotlin.Boolean" -> "Boolean"
      returnType.declaration.qualifiedName?.asString() == "kotlin.Double" -> "Double"
      returnType.declaration.qualifiedName?.asString() == "kotlin.collections.List" -> {
        val typeArg = returnType.arguments.firstOrNull()?.type?.resolve()
        val elementType = typeArg?.declaration?.qualifiedName?.asString()
        "List<${normalizeKotlinType(elementType)}>"
      }
      else -> returnType.declaration.qualifiedName?.asString() ?: "Any"
    }

    val propertyType = when {
      kotlinType == "List<String>" || kotlinType == "List<Int>" || kotlinType == "List<Double>" || kotlinType == "List<Boolean>" ->
        PropertyType.LITERAL
      kotlinType.startsWith("List<") -> PropertyType.OBJECT_LIST
      kotlinType == "String" || kotlinType == "Int" || kotlinType == "Double" || kotlinType == "Boolean" ->
        PropertyType.LITERAL
      else -> PropertyType.OBJECT
    }

    val wantsMutable = property.isMutable
    val effectiveMutable =
      wantsMutable &&
        when (propertyType) {
          PropertyType.LITERAL ->
            kotlinType == "String" ||
              kotlinType == "Int" ||
              kotlinType == "Double" ||
              kotlinType == "Boolean"
          PropertyType.OBJECT -> true
          PropertyType.OBJECT_LIST -> false
        }
    if (wantsMutable && !effectiveMutable) {
      logger.warn(
        "var property '${property.simpleName.asString()}' is not supported for generated mutation " +
          "(lists and literal collections are read-only in wrappers); generating a read-only accessor.",
        property,
      )
    }

    return PropertyModel(
      name = property.simpleName.asString(),
      kotlinType = kotlinType,
      predicateIri = predicateIri,
      type = propertyType,
      mutable = effectiveMutable,
    )
  }

  private fun generateWrapper(classModel: ClassModel) {
    val fileSpec = wrapperGenerator.generateWrapper(classModel)
    val file = codeGenerator.createNewFile(
      dependencies = Dependencies(false),
      packageName = classModel.packageName,
      fileName = fileSpec.name.removeSuffix(".kt"),
    )

    val writer = file.bufferedWriter(Charsets.UTF_8)
    fileSpec.writeTo(writer)
    writer.close()
    file.close()
  }

  private fun normalizeKotlinType(typeName: String?): String {
    return when (typeName) {
      "kotlin.String" -> "String"
      "kotlin.Int" -> "Int"
      "kotlin.Boolean" -> "Boolean"
      "kotlin.Double" -> "Double"
      null -> "Any"
      else -> typeName
    }
  }
}

class OntoMapperProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
    OntoMapperProcessor(
      codeGenerator = environment.codeGenerator,
      logger = environment.logger,
      options = environment.options,
    )
}
