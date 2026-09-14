package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.processor.internal.model.ClassModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyType
import com.geoknoesis.kastor.gen.processor.internal.utils.KotlinPoetUtils
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.*

private const val RUNTIME = "com.geoknoesis.kastor.gen.runtime"
private const val DELEGATES = "com.geoknoesis.kastor.gen.runtime.delegates"
private val KASTOR_GRAPH_OPS = ClassName(RUNTIME, "KastorGraphOps")
private val ONTO_MAPPER = ClassName(RUNTIME, "OntoMapper")
private val RDF_REF = ClassName(RUNTIME, "RdfRef")
private val RDF_HANDLE = ClassName(RUNTIME, "RdfHandle")
private val RDF_BACKED = ClassName(RUNTIME, "RdfBacked")
private val XSD_LITERALS = ClassName(RUNTIME, "XsdLiterals")
private val RDF_LITERAL = ClassName("com.geoknoesis.kastor.rdf", "Literal")
private val IRI = ClassName("com.geoknoesis.kastor.rdf", "Iri")
private val WITH_KNOWN_PREDICATES = MemberName(RUNTIME, "withKnownPredicates")
private val AS_RDF = MemberName(RUNTIME, "asRdf")
private val REPLACE_LITERALS = MemberName(RUNTIME, "replacePredicateLiterals")
private val REPLACE_OBJECT = MemberName(RUNTIME, "replacePredicateObjectTerm")
private val CLEAR_LITERALS = MemberName(RUNTIME, "clearPredicateLiterals")
private val CLEAR_OBJECTS = MemberName(RUNTIME, "clearPredicateObjects")

/**
 * Generates RDF-backed wrappers for hand-written `@Rdf` interfaces.
 *
 * Every ontology-derived value (predicate IRIs, names in messages) is passed to KotlinPoet as an argument, never
 * spliced into a format string. Missing values are never replaced by defaults: a non-null member throws
 * [IllegalStateException] when its value is missing or ill-typed, a nullable member returns `null` when missing.
 */
internal class WrapperGenerator(@Suppress("UNUSED_PARAMETER") private val logger: KSPLogger) {

  fun generateWrapper(classModel: ClassModel): FileSpec {
    val wrapperName = "${classModel.simpleName}Wrapper"
    val properties = classModel.properties.sortedBy { it.predicateIri }

    val fileBuilder = FileSpec.builder(classModel.packageName, wrapperName)
      .addFileComment("GENERATED FILE - DO NOT EDIT")

    val knownIris = properties.map { it.predicateIri }.distinct().map { CodeBlock.of("%T(%S)", IRI, it) }.joinToCode(", ")
    val domainInterface = ClassName(classModel.packageName, classModel.simpleName)
    val classBuilder = TypeSpec.classBuilder(wrapperName)
      .addModifiers(INTERNAL)
      .primaryConstructor(
        FunSpec.constructorBuilder()
          .addParameter("input", RDF_HANDLE)
          .addModifiers(PRIVATE)
          .build(),
      )
      .addSuperinterface(domainInterface)
      .addSuperinterface(RDF_BACKED)
      .addProperty(
        PropertySpec.builder("rdf", RDF_HANDLE)
          .addModifiers(OVERRIDE)
          .initializer("input.%M(KNOWN)", WITH_KNOWN_PREDICATES)
          .build(),
      )

    properties.forEach { property ->
      classBuilder.addProperty(generatePropertyImplementation(classModel.packageName, property))
    }

    val companion = TypeSpec.companionObjectBuilder()
      .addProperty(
        PropertySpec.builder("KNOWN", KotlinPoetUtils.setOf(IRI))
          .addModifiers(PRIVATE)
          .initializer("setOf(%L)", knownIris)
          .build(),
      )
      .addInitializerBlock(
        CodeBlock.of(
          "%T.register(%T::class.java) { handle -> %T(handle) }\n",
          ONTO_MAPPER,
          domainInterface,
          ClassName(classModel.packageName, wrapperName),
        ),
      )
    classBuilder.addType(companion.build())

    fileBuilder.addType(classBuilder.build())
    return fileBuilder.build()
  }

  private fun generatePropertyImplementation(domainPackageName: String, property: PropertyModel): PropertySpec {
    val pred = CodeBlock.of("%T(%S)", IRI, property.predicateIri)
    return when (property.type) {
      PropertyType.LITERAL -> literalProperty(domainPackageName, property, pred)
      PropertyType.OBJECT -> objectProperty(domainPackageName, property, pred)
      PropertyType.OBJECT_LIST -> objectListProperty(domainPackageName, property, pred)
    }
  }

  private fun label(property: PropertyModel) = "${property.name} <${property.predicateIri}>"

  private fun decoder(kotlinType: String): String = when (kotlinType.removePrefix("List<").removeSuffix(">")) {
    "Int" -> "int"
    "Double" -> "double"
    "Boolean" -> "boolean"
    else -> "string"
  }

  private fun literalProperty(domainPackageName: String, property: PropertyModel, pred: CodeBlock): PropertySpec {
    val isList = property.kotlinType.startsWith("List<")
    val baseType = determineTypeName(property.kotlinType, domainPackageName)
    val typeName = if (property.nullable && !isList) baseType.copy(nullable = true) else baseType
    if (!property.mutable) {
      val delegateExpr = if (isList) {
        val delegate = when (property.kotlinType) {
          "List<Int>" -> "rdfInts"
          "List<Double>" -> "rdfDoubles"
          "List<Boolean>" -> "rdfBooleans"
          else -> "rdfStrings"
        }
        CodeBlock.of("%M(%L)", MemberName(DELEGATES, delegate), pred)
      } else {
        val delegate = if (property.nullable) "rdfLiteralOrNull" else "rdfLiteral"
        CodeBlock.of("%M(%L, %T::%N)", MemberName(DELEGATES, delegate), pred, XSD_LITERALS, decoder(property.kotlinType))
      }
      return PropertySpec.builder(property.name, typeName)
        .addModifiers(OVERRIDE)
        .delegate(delegateExpr)
        .build()
    }
    val getter = FunSpec.getterBuilder()
      .addCode(
        CodeBlock.builder()
          .add("return %T.getLiteralValues(rdf.graph, rdf.node, %L).firstOrNull()", KASTOR_GRAPH_OPS, pred)
          .add("?.let { %T.%N(it) ?: error(%S) }", XSD_LITERALS, decoder(property.kotlinType), "Value of ${label(property)} is not a valid ${property.kotlinType}")
          .apply { if (!property.nullable) add(" ?: error(%S)", "Required value of ${label(property)} is missing") }
          .add("\n")
          .build(),
      )
      .build()
    val setterBody = if (property.nullable) {
      CodeBlock.of("if (value == null) %M(%L) else %M(%L, %T(value))\n", CLEAR_LITERALS, pred, REPLACE_LITERALS, pred, RDF_LITERAL)
    } else {
      CodeBlock.of("%M(%L, %T(value))\n", REPLACE_LITERALS, pred, RDF_LITERAL)
    }
    return PropertySpec.builder(property.name, typeName)
      .mutable(true)
      .addModifiers(OVERRIDE)
      .getter(getter)
      .setter(FunSpec.setterBuilder().addParameter("value", typeName).addCode(setterBody).build())
      .build()
  }

  private fun objectProperty(domainPackageName: String, property: PropertyModel, pred: CodeBlock): PropertySpec {
    val elementTypeName = domainClassName(domainPackageName, property.kotlinType)
    val typeName = if (property.nullable) elementTypeName.copy(nullable = true) else elementTypeName
    if (!property.mutable) {
      val delegate = if (property.nullable) "rdfObjectOrNull" else "rdfObject"
      return PropertySpec.builder(property.name, typeName)
        .addModifiers(OVERRIDE)
        .delegate(CodeBlock.of("%M<%T>(%L)", MemberName(DELEGATES, delegate), elementTypeName, pred))
        .build()
    }
    val getterCode = CodeBlock.builder()
      .add("return %T.getObjectValues(rdf.graph, rdf.node, %L) { child ->\n", KASTOR_GRAPH_OPS, pred)
      .indent()
      .addStatement("%T.materialize(%T(child, rdf.graph), %T::class.java)", ONTO_MAPPER, RDF_REF, elementTypeName)
      .unindent()
      .add("}.firstOrNull()")
      .apply { if (!property.nullable) add(" ?: error(%S)", "Required object of ${label(property)} is missing") }
      .add("\n")
      .build()
    val setterBody = if (property.nullable) {
      CodeBlock.of("if (value == null) %M(%L) else %M(%L, value.%M().node)\n", CLEAR_OBJECTS, pred, REPLACE_OBJECT, pred, AS_RDF)
    } else {
      CodeBlock.of("%M(%L, value.%M().node)\n", REPLACE_OBJECT, pred, AS_RDF)
    }
    return PropertySpec.builder(property.name, typeName)
      .mutable(true)
      .addModifiers(OVERRIDE)
      .getter(FunSpec.getterBuilder().addCode(getterCode).build())
      .setter(FunSpec.setterBuilder().addParameter("value", typeName).addCode(setterBody).build())
      .build()
  }

  private fun objectListProperty(domainPackageName: String, property: PropertyModel, pred: CodeBlock): PropertySpec {
    val elementType = property.kotlinType.removePrefix("List<").removeSuffix(">")
    val elementTypeName = domainClassName(domainPackageName, elementType)
    val listType = KotlinPoetUtils.listOf(elementTypeName)
    return PropertySpec.builder(property.name, listType)
      .addModifiers(OVERRIDE)
      .delegate(CodeBlock.of("%M<%T>(%L)", MemberName(DELEGATES, "rdfObjects"), elementTypeName, pred))
      .build()
  }

  private fun determineTypeName(kotlinType: String, domainPackageName: String): TypeName {
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
          else -> domainClassName(domainPackageName, elementType)
        }
        KotlinPoetUtils.listOf(elementTypeName)
      }
      else -> domainClassName(domainPackageName, kotlinType)
    }
  }

  private fun domainClassName(domainPackageName: String, simpleOrQualified: String): ClassName {
    return if (simpleOrQualified.contains('.')) {
      ClassName.bestGuess(simpleOrQualified)
    } else {
      ClassName(domainPackageName, simpleOrQualified)
    }
  }
}
