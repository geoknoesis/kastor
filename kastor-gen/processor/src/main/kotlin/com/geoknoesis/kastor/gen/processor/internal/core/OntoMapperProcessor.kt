package com.geoknoesis.kastor.gen.processor.internal.core

import com.geoknoesis.kastor.gen.annotations.RDF_ANNOTATION_FQN
import com.geoknoesis.kastor.gen.annotations.RDF_MATERIALIZABLE_ANNOTATION_FQN
import com.geoknoesis.kastor.gen.processor.internal.model.ClassModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyType
import com.geoknoesis.kastor.gen.processor.internal.model.RdfEnumKind
import com.geoknoesis.kastor.gen.processor.internal.model.RdfMemberTypes
import com.geoknoesis.kastor.gen.processor.internal.codegen.WrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.utils.QNameResolver
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate

/**
 * KSP processor for generating RDF-backed domain object wrappers from `@Rdf` interfaces.
 *
 * ## Diagnostics
 * Everything the generator cannot handle is reported through KSP, naming the type or member, and no wrapper is
 * generated for the affected interface:
 * - **error**: two `@Rdf` types map to one wrapper class (a nested `Outer.Inner` and a top-level `Outer_Inner` both
 *   give `Outer_InnerWrapper`), or to two wrapper classes whose source files would collide on a case-insensitive
 *   file system (`Foo` and `FOO` give `FooWrapper.kt` and `FOOWrapper.kt`: distinct classes, but the build would
 *   only work on a case-sensitive file system). The message says which of the two it is;
 * - **error**: an abstract property or function without `@Rdf` (the wrapper could not implement it); members with a
 *   default implementation are left alone, and so is `rdf` of `RdfBacked`, which every wrapper implements itself
 *   (`@Rdf interface X : RdfBacked` is supported);
 * - **error**: a member type without a reader: anything but the literal types of [RdfMemberTypes] (`String`, `Int`,
 *   `Long`, `Float`, `Double`, `Boolean`, `BigInteger`, `BigDecimal`, `LocalDate`, `LangString`), enums, `Iri`,
 *   `RdfResource`, types that are `@Rdf`-annotated (or have a `<Type>Wrapper` / `<Type>Factory`), types that opt in
 *   because the application registers their factory by hand (`@RdfMaterializable` on the type, or its qualified name
 *   in the KSP option [MATERIALIZABLE_TYPES_OPTION]), and `List`s of these. Type aliases are resolved first.
 *   `Short`, `Instant` and classes without `@Rdf` are rejected instead of failing at the first read with "No wrapper
 *   factory registered";
 * - **warning**: a class named `<Type>Wrapper` exists that is not an `RdfBacked` implementation of the interface, so
 *   no wrapper is generated. (An existing `RdfBacked` implementation - generated from SHACL, or written by hand on
 *   purpose - is used silently.)
 */
public class OntoMapperProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  private val options: Map<String, String>,
) : SymbolProcessor {

  public companion object {
    /**
     * KSP option listing the qualified names (comma separated) of member types that are materialized by a factory
     * the application registers by hand with `OntoMapper.register`, for types that cannot carry `@RdfMaterializable`
     * (library types).
     */
    public const val MATERIALIZABLE_TYPES_OPTION: String = "kastor.gen.materializableTypes"
  }

  private val wrapperGenerator = WrapperGenerator(logger)
  private val processedClasses = mutableSetOf<String>()
  /**
   * Path of a generated wrapper file as a case-insensitive file system sees it (the qualified wrapper name in lower
   * case) to the type the wrapper was generated for.
   */
  private val wrapperOwners = HashMap<String, WrapperOwner>()

  /** An `@Rdf` type (qualified name) and the qualified name of its wrapper class. */
  private class WrapperOwner(val type: String, val wrapper: String)
  /** Member types whose factory the application registers by hand ([MATERIALIZABLE_TYPES_OPTION]). */
  private val materializableTypes: Set<String> =
    options[MATERIALIZABLE_TYPES_OPTION].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

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
      val rdfAnn = symbol.annotations.find { it.isKastorRdf() } ?: return@forEach
      val iriRaw = rdfAnn.arguments.find { it.name?.asString() == "iri" }?.value as? String ?: ""
      val shaclRaw = rdfAnn.arguments.find { it.name?.asString() == "shacl" }?.value as? String ?: ""
      if (iriRaw.isBlank() || shaclRaw.isNotBlank()) {
        return@forEach
      }
      val qualified = symbol.qualifiedName?.asString() ?: return@forEach
      if (qualified in processedClasses) return@forEach
      // Interfaces generated from SHACL by OntologyProcessor also carry @Rdf(iri) and come with their own
      // wrapper; generating a second one would fail with FileAlreadyExistsException in the next round.
      val wrapperName = "${symbol.packageName.asString()}.${WrapperGenerator.wrapperName(qualified, symbol.packageName.asString())}"
        .removePrefix(".")
      val existing = resolver.getClassDeclarationByName(resolver.getKSNameFromString(wrapperName))
      if (existing != null) {
        if (isWrapperOf(existing, qualified)) {
          logger.info("Skipping $qualified: the wrapper $wrapperName already exists")
        } else {
          logger.warn(
            "@Rdf type $qualified: no wrapper is generated because a class named $wrapperName already exists and it is " +
              "not an RdfBacked implementation of $qualified. Materializing $qualified will fail with " +
              "\"No wrapper factory registered\" unless a factory is registered with OntoMapper.register; rename " +
              "that class to get the generated wrapper.",
            symbol,
          )
        }
        return@forEach
      }

      val prefixMappings = prefixMappingsFor(symbol)
      logger.info("Resolved ${prefixMappings.size} prefix mappings for ${symbol.qualifiedName?.asString()}")

      val classModel = analyzeClass(symbol, prefixMappings, rdfAnn, resolver)
      if (classModel != null) {
        classModels.add(classModel to symbol)
      }
    }

    // Two types whose wrappers would be written to the same file, as a case-insensitive file system sees it: report
    // both instead of failing with FileAlreadyExistsException on the second one (or, for names that differ only in
    // case, producing a build that only works on a case-sensitive file system).
    val byWrapper = classModels.groupBy { (model, _) -> wrapperFileKey(model) }
    val generatable = byWrapper.flatMap { (key, group) ->
      val rivals = (group.map { (model, _) -> WrapperOwner(model.qualifiedName, wrapperQualifiedName(model)) } + listOfNotNull(wrapperOwners[key]))
        .distinctBy { it.type }
        .sortedBy { it.type }
      if (rivals.size > 1) {
        val names = rivals.joinToString(" and ") { it.type }
        val wrappers = rivals.map { it.wrapper }.distinct().sorted()
        val message = if (wrappers.size == 1) {
          "@Rdf types $names map to the same wrapper class ${wrappers.single().substringAfterLast('.')}; rename one of them"
        } else {
          "@Rdf types $names get wrapper classes whose names differ only in case " +
            "(${wrappers.joinToString(" and ") { it.substringAfterLast('.') + ".kt" }}): the classes are distinct, but " +
            "their source and class files are one file on a case-insensitive file system (Windows, macOS), so the " +
            "build would only work on a case-sensitive one; rename one of the types"
        }
        logger.error(message, group.first().second)
        emptyList()
      } else {
        group
      }
    }

    // Each wrapper depends only on its interface's file and its supertypes' files (whose properties it implements),
    // so editing an unrelated source does not invalidate every wrapper.
    generatable.forEach { (model, declaration) ->
      wrapperOwners[wrapperFileKey(model)] = WrapperOwner(model.qualifiedName, wrapperQualifiedName(model))
      generateWrapper(model, originatingFiles(declaration).toTypedArray())
    }

    return symbols.filterNot { it.validate() }.toList()
  }

  private fun wrapperQualifiedName(model: ClassModel): String =
    "${model.packageName}.${WrapperGenerator.wrapperName(model.qualifiedName, model.packageName)}"

  /** The generated wrapper file of a type as a case-insensitive file system identifies it. */
  private fun wrapperFileKey(model: ClassModel): String = wrapperQualifiedName(model).lowercase()

  /** Whether [candidate] is a wrapper of the interface [qualified]: it implements the interface and `RdfBacked`. */
  private fun isWrapperOf(candidate: KSClassDeclaration, qualified: String): Boolean {
    val supertypes = HashSet<String>()
    val pending = ArrayDeque(listOf(candidate))
    val seen = HashSet<KSClassDeclaration>()
    while (pending.isNotEmpty()) {
      val current = pending.removeFirst()
      if (!seen.add(current)) continue
      current.superTypes.forEach { reference ->
        (reference.resolve().declaration as? KSClassDeclaration)?.let { parent ->
          parent.qualifiedName?.asString()?.let(supertypes::add)
          pending += parent
        }
      }
    }
    return qualified in supertypes && RDF_BACKED_FQN in supertypes
  }

  /**
   * Prefixes visible when resolving QNames for `@Rdf(iri = …)` on [classDecl] and its properties:
   * 1) `@file:Rdf(prefixes = …)` on the same source file (if any)
   * 2) `prefixes = …` on the class/interface `@Rdf` (later entries override earlier on name clash)
   */
  private fun prefixMappingsFor(classDecl: KSClassDeclaration): Map<String, String> {
    val into = LinkedHashMap<String, String>()
    classDecl.findContainingKsFile()?.annotations
      ?.filter { it.isKastorRdf() }
      ?.forEach { mergePrefixesFromRdfAnnotation(it, into) }
    classDecl.annotations.filter { it.isKastorRdf() }.forEach { ann ->
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
    resolver: Resolver,
  ): ClassModel? {
    val qualifiedName = classDecl.qualifiedName?.asString() ?: return null

    if (qualifiedName in processedClasses) {
      return null
    }

    var complete = true
    val properties = mutableListOf<PropertyModel>()
    classDecl.getAllProperties().forEach { property ->
      if (rdfAnnotationOf(property) == null) {
        // A member with a default implementation needs nothing from the wrapper, and `rdf` of RdfBacked is implemented
        // by every wrapper; any other abstract member cannot be implemented, and the generated wrapper would fail to
        // compile with an error far from the cause.
        if (property.isAbstract() && !isImplementedByWrapper(property)) {
          logger.error(
            "Abstract property '${property.simpleName.asString()}' of ${property.parentDeclaration?.qualifiedName?.asString()} " +
              "has no @Rdf(iri = ...) annotation, so the wrapper of $qualifiedName cannot implement it. Annotate it with " +
              "@Rdf or give it a default getter.",
            property,
          )
          complete = false
        }
        return@forEach
      }
      val propertyModel = analyzeProperty(property, prefixMappings, resolver)
      if (propertyModel != null) {
        properties.add(propertyModel)
      } else {
        complete = false
      }
    }
    classDecl.getAllFunctions().filter { it.isAbstract }.forEach { function ->
      logger.error(
        "Abstract function '${function.simpleName.asString()}' of ${function.parentDeclaration?.qualifiedName?.asString()} " +
          "cannot be implemented by the wrapper of $qualifiedName. Give it a default implementation (or move it to an " +
          "extension function).",
        function,
      )
      complete = false
    }
    // Every problem was reported as a KSP error: do not emit a wrapper that cannot compile.
    if (!complete) return null

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

  /**
   * Whether the generated wrapper implements [property] itself, without an `@Rdf` mapping: the members of the runtime
   * interfaces every wrapper implements. That is `RdfBacked.rdf`, inherited (`@Rdf interface X : RdfBacked`) or
   * restated by the interface or one of its supertypes (`override val rdf: RdfHandle`).
   */
  private fun isImplementedByWrapper(property: KSPropertyDeclaration): Boolean {
    var current: KSPropertyDeclaration? = property
    val seen = HashSet<KSPropertyDeclaration>()
    while (current != null && seen.add(current)) {
      val owner = (current.parentDeclaration as? KSClassDeclaration)?.qualifiedName?.asString()
      if (owner != null && WRAPPER_IMPLEMENTED_MEMBERS[owner]?.contains(current.simpleName.asString()) == true) return true
      current = current.findOverridee()
    }
    return false
  }

  /** The `@Rdf` annotation of [property]: on the property, its getter or setter, or on a property it overrides. */
  private fun rdfAnnotationOf(property: KSPropertyDeclaration): KSAnnotation? {
    var current: KSPropertyDeclaration? = property
    val seen = HashSet<KSPropertyDeclaration>()
    while (current != null && seen.add(current)) {
      val annotation = current.annotations.find { it.isKastorRdf() }
        ?: current.getter?.annotations?.find { it.isKastorRdf() }
        ?: current.setter?.annotations?.find { it.isKastorRdf() }
      if (annotation != null) return annotation
      current = current.findOverridee()
    }
    return null
  }

  /** [type] with type aliases replaced by the type they stand for (a nullable alias or use stays nullable). */
  private fun expandAliases(type: KSType): KSType {
    var current = type
    var nullable = type.isMarkedNullable
    val seen = HashSet<KSDeclaration>()
    while (true) {
      val alias = current.declaration as? KSTypeAlias ?: break
      if (!seen.add(alias)) break
      current = alias.type.resolve()
      nullable = nullable || current.isMarkedNullable
    }
    return if (nullable) current.makeNullable() else current
  }

  /**
   * Whether a member of type [declaration] can be materialized: it is `@Rdf`-annotated, has a wrapper / factory, or
   * opted in as registered by hand (`@RdfMaterializable`, or listed in [MATERIALIZABLE_TYPES_OPTION]).
   */
  private fun isMaterializable(declaration: KSClassDeclaration, resolver: Resolver): Boolean {
    if (declaration.annotations.any { it.isKastorRdf() }) return true
    val qualified = declaration.qualifiedName?.asString() ?: return false
    if (qualified in materializableTypes) return true
    val optedIn = declaration.annotations.any {
      it.shortName.asString() == "RdfMaterializable" &&
        it.annotationType.resolve().declaration.qualifiedName?.asString() == RDF_MATERIALIZABLE_ANNOTATION_FQN
    }
    if (optedIn) return true
    val packageName = declaration.packageName.asString()
    val wrapper = "$packageName.${WrapperGenerator.wrapperName(qualified, packageName)}".removePrefix(".")
    return listOf(wrapper, "${qualified}Wrapper", "${qualified}Factory")
      .any { resolver.getClassDeclarationByName(resolver.getKSNameFromString(it)) != null }
  }

  private fun analyzeProperty(property: KSPropertyDeclaration, prefixMappings: Map<String, String>, resolver: Resolver): PropertyModel? {
    val rdfPropertyAnnotation = rdfAnnotationOf(property) ?: return null

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
    // Type aliases (of the member type and of a List element) are resolved to the types they stand for.
    val returnType = expandAliases(property.type.resolve())
    val isList = returnType.declaration.qualifiedName?.asString() == "kotlin.collections.List"
    val elementType = if (isList) returnType.arguments.firstOrNull()?.type?.resolve()?.let(::expandAliases) else returnType
    val elementDeclaration = elementType?.declaration
    val container = listOf(returnType.declaration, elementDeclaration)
      .firstOrNull { RdfMemberTypes.isUnsupportedContainer(it?.qualifiedName?.asString()) }
    if (container != null) {
      logger.error(
        "@Rdf member '${property.simpleName.asString()}' of ${property.parentDeclaration?.qualifiedName?.asString()} has type " +
          "$returnType; generated wrappers support multi-valued members only as List<…> of a literal, enum, RDF term " +
          "or @Rdf type. Declare it as List<…> (use .toSet() at call sites if set semantics are needed).",
        property,
      )
      return null
    }
    val elementName = RdfMemberTypes.normalize(elementDeclaration?.qualifiedName?.asString())
    val kotlinType = if (isList) "List<$elementName>" else elementName
    val enumKind = (elementDeclaration as? KSClassDeclaration)?.let(::enumKindOf)
    val propertyType = RdfMemberTypes.propertyType(kotlinType, enumKind)
    if (propertyType == PropertyType.OBJECT || propertyType == PropertyType.OBJECT_LIST) {
      // Neither a literal, an enum nor an RDF term: the value is materialized through OntoMapper, which needs a
      // wrapper or factory for the type. Reject anything else now instead of failing at the first read.
      val elementClass = elementDeclaration as? KSClassDeclaration
      if (elementType != null && elementType.arguments.isNotEmpty()) {
        // Wrappers are generated for the declaration, not for an instantiation: Foo<Bar> would be written as raw Foo
        // and the generated code would not compile.
        logger.error(
          "@Rdf member '${property.simpleName.asString()}' of ${property.parentDeclaration?.qualifiedName?.asString()} has " +
            "type $elementType, which generated wrappers cannot read: generic types are not supported (the wrapper " +
            "would refer to the raw type). Declare a non-generic @Rdf interface for it.",
          property,
        )
        return null
      }
      if (elementClass == null || !isMaterializable(elementClass, resolver)) {
        val typeName = elementDeclaration?.qualifiedName?.asString() ?: property.type.toString()
        logger.error(
          "@Rdf member '${property.simpleName.asString()}' of ${property.parentDeclaration?.qualifiedName?.asString()} has " +
            "type $typeName, which generated wrappers cannot read: it is not a supported literal type " +
            "(${RdfMemberTypes.supportedLiteralTypes()}), an enum, Iri or RdfResource, and it is not an @Rdf type " +
            "(reading it would fail with \"No wrapper factory registered\"). Use a supported type, or annotate " +
            "$typeName with @Rdf. If the application registers a factory for $typeName itself (OntoMapper.register), " +
            "say so: annotate the type with @RdfMaterializable, or list its qualified name in the KSP option " +
            "$MATERIALIZABLE_TYPES_OPTION.",
          property,
        )
        return null
      }
    }

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

private const val RDF_BACKED_FQN = "com.geoknoesis.kastor.gen.runtime.RdfBacked"

/** Members (by declaring runtime interface) that every generated wrapper implements itself; see WrapperGenerator. */
private val WRAPPER_IMPLEMENTED_MEMBERS: Map<String, Set<String>> = mapOf(RDF_BACKED_FQN to setOf("rdf"))

public class OntoMapperProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
    OntoMapperProcessor(
      codeGenerator = environment.codeGenerator,
      logger = environment.logger,
      options = environment.options,
    )
}
