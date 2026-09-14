package com.geoknoesis.kastor.gen.processor.internal.core

import com.geoknoesis.kastor.gen.annotations.RDF_ANNOTATION_FQN
import com.geoknoesis.kastor.gen.processor.internal.model.ClassModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyModel
import com.geoknoesis.kastor.gen.processor.internal.model.RdfEnumKind
import com.geoknoesis.kastor.gen.processor.internal.model.RdfMemberTypes
import com.geoknoesis.kastor.gen.processor.internal.codegen.WrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.utils.QNameResolver
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate

/**
 * KSP processor for generating RDF-backed domain object wrappers from `@Rdf` interfaces.
 */
public class OntoMapperProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  private val options: Map<String, String>,
) : SymbolProcessor {

  private val wrapperGenerator = WrapperGenerator(logger)
  private val processedClasses = mutableSetOf<String>()

  override fun process(resolver: Resolver): List<KSAnnotated> {
    logger.info("OntoMapper processor starting…")
    val symbols = resolver.getSymbolsWithAnnotation(RDF_ANNOTATION_FQN).toList()

    logger.info("Found ${symbols.iterator().asSequence().count()} symbols with @Rdf")

    if (!symbols.iterator().hasNext()) {
      logger.info("No symbols found, returning empty list")
      return emptyList()
    }

    val classModels = mutableListOf<Pair<ClassModel, KSClassDeclaration>>()

    symbols.forEach { symbol ->
      if (!symbol.validate() || symbol !is KSClassDeclaration) {
        return@forEach
      }
      val rdfAnn = symbol.annotations.find { it.shortName.asString() == "Rdf" } ?: return@forEach
      val iriRaw = rdfAnn.arguments.find { it.name?.asString() == "iri" }?.value as? String ?: ""
      val shaclRaw = rdfAnn.arguments.find { it.name?.asString() == "shacl" }?.value as? String ?: ""
      if (iriRaw.isBlank() || shaclRaw.isNotBlank()) {
        return@forEach
      }
      // Interfaces generated from SHACL by OntologyProcessor also carry @Rdf(iri) and come with their own
      // wrapper; generating a second one would fail with FileAlreadyExistsException in the next round.
      val qualified = symbol.qualifiedName?.asString()
      if (qualified != null && resolver.getClassDeclarationByName(resolver.getKSNameFromString(qualified + "Wrapper")) != null) {
        logger.info("Skipping $qualified: a wrapper already exists")
        return@forEach
      }

      val prefixMappings = prefixMappingsFor(symbol)
      logger.info("Resolved ${prefixMappings.size} prefix mappings for ${symbol.qualifiedName?.asString()}")

      val classModel = analyzeClass(symbol, prefixMappings, rdfAnn)
      if (classModel != null) {
        classModels.add(classModel to symbol)
      }
    }

    // Each wrapper depends only on its interface's file and its supertypes' files (whose properties it implements),
    // so editing an unrelated source does not invalidate every wrapper.
    classModels.forEach { (model, declaration) -> generateWrapper(model, originatingFiles(declaration).toTypedArray()) }

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

    // Literal member types follow the SHACL generators (XsdLiterals codecs): String, Int, Long, Float, Double,
    // Boolean, BigInteger, BigDecimal, LocalDate, LangString and enums. Iri / RdfResource members read RDF terms.
    val returnType = property.type.resolve()
    val isList = returnType.declaration.qualifiedName?.asString() == "kotlin.collections.List"
    val elementDeclaration = (if (isList) returnType.arguments.firstOrNull()?.type?.resolve() else returnType)?.declaration
    val elementName = RdfMemberTypes.normalize(elementDeclaration?.qualifiedName?.asString())
    val kotlinType = if (isList) "List<$elementName>" else elementName
    val enumKind = (elementDeclaration as? KSClassDeclaration)?.let(::enumKindOf)
    val propertyType = RdfMemberTypes.propertyType(kotlinType, enumKind)

    val wantsMutable = property.isMutable
    val effectiveMutable = wantsMutable && RdfMemberTypes.supportsMutation(kotlinType, propertyType, enumKind)
    if (wantsMutable && !effectiveMutable) {
      logger.warn(
        "var property '${property.simpleName.asString()}' is not supported for generated mutation " +
          "(lists, enums and RDF term members are read-only in wrappers); generating a read-only accessor.",
        property,
      )
    }

    return PropertyModel(
      name = property.simpleName.asString(),
      kotlinType = kotlinType,
      predicateIri = predicateIri,
      type = propertyType,
      mutable = effectiveMutable,
      nullable = returnType.isMarkedNullable,
      enumKind = enumKind,
      typePackage = elementDeclaration?.packageName?.asString()?.takeIf { it.isNotEmpty() && elementName.startsWith("$it.") },
    )
  }

  /**
   * [RdfEnumKind.NAME] for a Kotlin `enum class`; for a sealed type with a companion `from(code: String)` or
   * `from(iri: Iri)` (enums generated from `sh:in`), [RdfEnumKind.CODE] or [RdfEnumKind.IRI]; otherwise `null`.
   */
  private fun enumKindOf(declaration: KSClassDeclaration): RdfEnumKind? {
    if (declaration.classKind == ClassKind.ENUM_CLASS) return RdfEnumKind.NAME
    if (Modifier.SEALED !in declaration.modifiers) return null
    val from = declaration.declarations.filterIsInstance<KSClassDeclaration>().firstOrNull { it.isCompanionObject }
      ?.getDeclaredFunctions()
      ?.firstOrNull { it.simpleName.asString() == "from" && it.parameters.size == 1 }
      ?: return null
    return when (from.parameters.single().type.resolve().declaration.qualifiedName?.asString()) {
      "kotlin.String" -> RdfEnumKind.CODE
      "com.geoknoesis.kastor.rdf.Iri" -> RdfEnumKind.IRI
      else -> null
    }
  }

  private fun generateWrapper(classModel: ClassModel, sources: Array<KSFile>) {
    val fileSpec = wrapperGenerator.generateWrapper(classModel)
    val file = codeGenerator.createNewFile(
      dependencies = Dependencies(false, *sources),
      packageName = classModel.packageName,
      fileName = fileSpec.name.removeSuffix(".kt"),
    )

    val writer = file.bufferedWriter(Charsets.UTF_8)
    fileSpec.writeTo(writer)
    writer.close()
    file.close()
    processedClasses.add(classModel.qualifiedName)
  }
}

/**
 * Source files a wrapper for [declaration] is generated from: the declaration's own file and, transitively, the
 * files of its supertypes (library supertypes without a source file are skipped). Order: declaration first, then
 * supertypes depth-first, without duplicates.
 */
internal fun originatingFiles(declaration: KSClassDeclaration): List<KSFile> {
  val files = LinkedHashSet<KSFile>()
  val seen = HashSet<KSClassDeclaration>()
  fun visit(current: KSClassDeclaration) {
    if (!seen.add(current)) return
    current.containingFile?.let(files::add)
    current.superTypes.forEach { reference ->
      (reference.resolve().declaration as? KSClassDeclaration)?.let(::visit)
    }
  }
  visit(declaration)
  return files.toList()
}

public class OntoMapperProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
    OntoMapperProcessor(
      codeGenerator = environment.codeGenerator,
      logger = environment.logger,
      options = environment.options,
    )
}
