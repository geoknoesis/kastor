package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.processor.internal.model.ClassModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyType
import com.geoknoesis.kastor.gen.processor.internal.utils.KotlinPoetUtils
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.*

private val KASTOR_GRAPH_OPS = ClassName("com.geoknoesis.kastor.gen.runtime", "KastorGraphOps")
private val ONTO_MAPPER = ClassName("com.geoknoesis.kastor.gen.runtime", "OntoMapper")
private val RDF_REF = ClassName("com.geoknoesis.kastor.gen.runtime", "RdfRef")
private val RDF_LITERAL = ClassName("com.geoknoesis.kastor.rdf", "Literal")

internal class WrapperGenerator(@Suppress("UNUSED_PARAMETER") private val logger: KSPLogger) {

  fun generateWrapper(classModel: ClassModel): FileSpec {
    val wrapperName = "${classModel.simpleName}Wrapper"

    val fileBuilder = FileSpec.builder(classModel.packageName, wrapperName)
      .addFileComment("GENERATED FILE - DO NOT EDIT")

    fileBuilder.addImport("com.geoknoesis.kastor.gen.runtime", "RdfBacked", "OntoMapper", "RdfHandle", "withKnownPredicates")
    fileBuilder.addImport("com.geoknoesis.kastor.gen.runtime.delegates", *delegateImportsFor(classModel).sorted().toTypedArray())
    fileBuilder.addImport("com.geoknoesis.kastor.rdf", "Iri")
    if (classModel.properties.any { it.mutable }) {
      fileBuilder.addImport(
        "com.geoknoesis.kastor.gen.runtime",
        "asRdf",
        "replacePredicateLiterals",
        "replacePredicateObjectTerm",
      )
      fileBuilder.addImport("com.geoknoesis.kastor.rdf", "Literal")
    }

    val knownIris = classModel.properties
      .sortedBy { it.predicateIri }
      .map { CodeBlock.of("Iri(%S)", it.predicateIri) }
    val knownIrisCode = knownIris.joinToString(", ") { it.toString() }
    val setType = KotlinPoetUtils.setOf(ClassName("com.geoknoesis.kastor.rdf", "Iri"))

    val domainInterface = ClassName(classModel.packageName, classModel.simpleName)
    val classBuilder = TypeSpec.classBuilder(wrapperName)
      .addModifiers(INTERNAL)
      .primaryConstructor(
        FunSpec.constructorBuilder()
          .addParameter("input", ClassName("com.geoknoesis.kastor.gen.runtime", "RdfHandle"))
          .addModifiers(PRIVATE)
          .build(),
      )
      .addSuperinterface(domainInterface)
      .addSuperinterface(ClassName("com.geoknoesis.kastor.gen.runtime", "RdfBacked"))
      .addProperty(
        PropertySpec.builder("rdf", ClassName("com.geoknoesis.kastor.gen.runtime", "RdfHandle"))
          .addModifiers(OVERRIDE)
          .initializer("input.withKnownPredicates(KNOWN)")
          .build(),
      )

    classModel.properties
      .sortedBy { it.predicateIri }
      .forEach { property ->
        classBuilder.addProperty(generatePropertyImplementation(classModel.packageName, property))
      }

    val companion = TypeSpec.companionObjectBuilder()
      .addProperty(
        PropertySpec.builder("KNOWN", setType)
          .addModifiers(PRIVATE)
          .initializer("setOf(%L)", CodeBlock.of(knownIrisCode))
          .build(),
      )
      .addInitializerBlock(
        CodeBlock.of(
          "OntoMapper.registry[%T::class.java] = { handle -> %T(handle) }",
          domainInterface,
          ClassName(classModel.packageName, wrapperName),
        ),
      )
    classBuilder.addType(companion.build())

    fileBuilder.addType(classBuilder.build())
    return fileBuilder.build()
  }

  private fun delegateImportsFor(classModel: ClassModel): Set<String> {
    val names = mutableSetOf<String>()
    classModel.properties.forEach { p ->
      names.addAll(delegateNamesForProperty(p))
    }
    return names
  }

  private fun delegateNamesForProperty(property: PropertyModel): Set<String> {
    return when (property.type) {
      PropertyType.LITERAL -> when (property.kotlinType) {
        "String" -> setOf("rdfString")
        "Int" -> setOf("rdfInt")
        "Double" -> setOf("rdfDouble")
        "Boolean" -> setOf("rdfBoolean")
        "List<String>" -> setOf("rdfStrings")
        "List<Int>" -> setOf("rdfInts")
        "List<Double>" -> setOf("rdfDoubles")
        "List<Boolean>" -> setOf("rdfBooleans")
        else -> setOf("rdfString")
      }
      PropertyType.OBJECT -> setOf("rdfObject")
      PropertyType.OBJECT_LIST -> setOf("rdfObjects")
    }
  }

  private fun generatePropertyImplementation(domainPackageName: String, property: PropertyModel): PropertySpec {
    val pred = CodeBlock.of("Iri(%S)", property.predicateIri)
    return when (property.type) {
      PropertyType.LITERAL -> literalProperty(domainPackageName, property, pred)
      PropertyType.OBJECT -> objectProperty(domainPackageName, property, pred)
      PropertyType.OBJECT_LIST -> objectListProperty(domainPackageName, property, pred)
    }
  }

  private fun literalProperty(domainPackageName: String, property: PropertyModel, pred: CodeBlock): PropertySpec {
    val typeName = determineTypeName(property.kotlinType, domainPackageName)
    if (!property.mutable) {
      val delegateExpr = when (property.kotlinType) {
        "String" -> CodeBlock.of("rdfString(%L)", pred)
        "Int" -> CodeBlock.of("rdfInt(%L)", pred)
        "Double" -> CodeBlock.of("rdfDouble(%L)", pred)
        "Boolean" -> CodeBlock.of("rdfBoolean(%L)", pred)
        "List<String>" -> CodeBlock.of("rdfStrings(%L)", pred)
        "List<Int>" -> CodeBlock.of("rdfInts(%L)", pred)
        "List<Double>" -> CodeBlock.of("rdfDoubles(%L)", pred)
        "List<Boolean>" -> CodeBlock.of("rdfBooleans(%L)", pred)
        else -> CodeBlock.of("rdfString(%L)", pred)
      }
      return PropertySpec.builder(property.name, typeName)
        .addModifiers(OVERRIDE)
        .delegate(delegateExpr)
        .build()
    }
    val getter = FunSpec.getterBuilder()
      .addCode(literalMutableGetterBody(property, pred))
      .build()
    val setter = FunSpec.setterBuilder()
      .addParameter("value", typeName)
      .addCode(literalMutableSetterBody(pred))
      .build()
    return PropertySpec.builder(property.name, typeName)
      .mutable(true)
      .addModifiers(OVERRIDE)
      .getter(getter)
      .setter(setter)
      .build()
  }

  private fun literalMutableGetterBody(property: PropertyModel, pred: CodeBlock): CodeBlock {
    val tail = when (property.kotlinType) {
      "String" -> ".map { it.lexical }.firstOrNull() ?: \"\""
      "Int" -> ".mapNotNull { it.lexical.toIntOrNull() }.firstOrNull() ?: 0"
      "Double" -> ".mapNotNull { it.lexical.toDoubleOrNull() }.firstOrNull() ?: 0.0"
      "Boolean" -> ".mapNotNull { it.lexical.toBooleanStrictOrNull() }.firstOrNull() ?: false"
      else -> ".map { it.lexical }.firstOrNull() ?: \"\""
    }
    return CodeBlock.builder()
      .add("return %T.getLiteralValues(rdf.graph, rdf.node, ", KASTOR_GRAPH_OPS)
      .add(pred)
      .add(tail)
      .add("\n")
      .build()
  }

  private fun literalMutableSetterBody(pred: CodeBlock): CodeBlock =
    CodeBlock.builder()
      .add("replacePredicateLiterals(")
      .add(pred)
      .add(", %T(value))\n", RDF_LITERAL)
      .build()

  private fun objectProperty(domainPackageName: String, property: PropertyModel, pred: CodeBlock): PropertySpec {
    val elementType = property.kotlinType
    val elementTypeName = domainClassName(domainPackageName, elementType)
    if (!property.mutable) {
      val delegateExpr = CodeBlock.of("rdfObject<%T>(%L)", elementTypeName, pred)
      return PropertySpec.builder(property.name, elementTypeName)
        .addModifiers(OVERRIDE)
        .delegate(delegateExpr)
        .build()
    }
    val getterCode = CodeBlock.builder()
      .add("return %T.getObjectValues(rdf.graph, rdf.node, ", KASTOR_GRAPH_OPS)
      .add(pred)
      .add(") { child ->\n")
      .indent()
      .addStatement("%T.materialize(%T(child, rdf.graph), %T::class.java)", ONTO_MAPPER, RDF_REF, elementTypeName)
      .unindent()
      .add("}.firstOrNull() ?: error(%S)\n", "Required object of type $elementType for mapped property is missing")
      .build()
    val getter = FunSpec.getterBuilder().addCode(getterCode).build()
    val setter = FunSpec.setterBuilder()
      .addParameter("value", elementTypeName)
      .addCode(
        CodeBlock.builder()
          .add("replacePredicateObjectTerm(")
          .add(pred)
          .add(", value.asRdf().node)\n")
          .build(),
      )
      .build()
    return PropertySpec.builder(property.name, elementTypeName)
      .mutable(true)
      .addModifiers(OVERRIDE)
      .getter(getter)
      .setter(setter)
      .build()
  }

  private fun objectListProperty(domainPackageName: String, property: PropertyModel, pred: CodeBlock): PropertySpec {
    val elementType = property.kotlinType.removePrefix("List<").removeSuffix(">")
    val elementTypeName = domainClassName(domainPackageName, elementType)
    val listType = KotlinPoetUtils.listOf(elementTypeName)
    val delegateExpr = CodeBlock.of("rdfObjects<%T>(%L)", elementTypeName, pred)
    return PropertySpec.builder(property.name, listType)
      .addModifiers(OVERRIDE)
      .delegate(delegateExpr)
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
