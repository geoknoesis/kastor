package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.processor.internal.model.ClassModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyModel
import com.geoknoesis.kastor.gen.processor.internal.model.PropertyType
import com.geoknoesis.kastor.gen.processor.internal.model.RdfEnumKind
import com.geoknoesis.kastor.gen.processor.internal.model.RdfMemberTypes
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
private val MATERIALIZATION_POLICY = ClassName(RUNTIME, "MaterializationPolicy")
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
 * spliced into a format string. Readers follow `MaterializationPolicy` like the SHACL-generated ones: ill-typed
 * values throw `MaterializationException` (or are skipped under `IllTypedValueHandling.SKIP`), a non-null member
 * without a value throws `MaterializationException`, a nullable member without a value reads `null`.
 *
 * Literal members support every type the SHACL generators map datatypes to (`String`, `Int`, `Long`, `Float`,
 * `Double`, `Boolean`, `BigInteger`, `BigDecimal`, `LocalDate`, `LangString`), decoded with the same `XsdLiterals`
 * codecs. Kotlin enums are read by constant name; enums generated from `sh:in` through their `from` factory (literal
 * codes or IRIs). `Iri` / `RdfResource` members read the RDF terms themselves.
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
      PropertyType.LITERAL ->
        if (property.enumKind != null) enumProperty(domainPackageName, property, pred) else literalProperty(property, pred)
      PropertyType.OBJECT -> objectProperty(domainPackageName, property, pred)
      PropertyType.OBJECT_LIST -> objectListProperty(domainPackageName, property, pred)
      PropertyType.TERM -> termProperty(property, pred)
    }
  }

  private fun label(property: PropertyModel) = "${property.name} <${property.predicateIri}>"

  private fun isList(property: PropertyModel) = RdfMemberTypes.isList(property.kotlinType)

  /** Member type: `List<E>` for lists, `E?` for nullable singles, `E` otherwise. */
  private fun memberType(property: PropertyModel, element: TypeName): TypeName = when {
    isList(property) -> KotlinPoetUtils.listOf(element)
    property.nullable -> element.copy(nullable = true)
    else -> element
  }

  /**
   * `by MaterializationPolicy.lazyWithCurrentPolicy { <values>… }` with the member's cardinality: list, first or null,
   * or first or MaterializationException. The policy in effect when the wrapper is created applies to later reads.
   */
  private fun lazyValues(property: PropertyModel, values: CodeBlock): CodeBlock = when {
    isList(property) -> CodeBlock.of("%T.lazyWithCurrentPolicy { %L }", MATERIALIZATION_POLICY, values)
    property.nullable -> CodeBlock.of("%T.lazyWithCurrentPolicy { %L.firstOrNull() }", MATERIALIZATION_POLICY, values)
    else -> CodeBlock.of(
      "%T.lazyWithCurrentPolicy { %L.firstOrNull() ?: %T.missingRequired(%S) }",
      MATERIALIZATION_POLICY, values, MATERIALIZATION_POLICY, label(property),
    )
  }

  private fun literalProperty(property: PropertyModel, pred: CodeBlock): PropertySpec {
    val element = RdfMemberTypes.element(property.kotlinType)
    val literal = requireNotNull(RdfMemberTypes.literal(element)) { "${property.name}: $element is not a literal type" }
    val typeName = memberType(property, literal.typeName)
    val decoder = CodeBlock.of("%T::%N", XSD_LITERALS, literal.decoder)
    if (!property.mutable) {
      val delegateExpr = when {
        isList(property) && element == "String" -> CodeBlock.of("%M(%L)", MemberName(DELEGATES, "rdfStrings"), pred)
        // Decoded values apply MaterializationPolicy to ill-typed values, like the SHACL-generated readers.
        isList(property) -> CodeBlock.of("%M(%L, %L)", MemberName(DELEGATES, "rdfLiterals"), pred, decoder)
        property.nullable -> CodeBlock.of("%M(%L, %L)", MemberName(DELEGATES, "rdfLiteralOrNull"), pred, decoder)
        else -> CodeBlock.of("%M(%L, %L)", MemberName(DELEGATES, "rdfLiteral"), pred, decoder)
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
          .add(
            "?.let { %T.%N(it) ?: %T.illTyped(it, %S, %S) }",
            XSD_LITERALS, literal.decoder, MATERIALIZATION_POLICY, label(property), property.kotlinType,
          )
          .apply { if (!property.nullable) add(" ?: %T.missingRequired(%S)", MATERIALIZATION_POLICY, label(property)) }
          .add("\n")
          .build(),
      )
      .build()
    val term = literal.writeDatatype?.let { CodeBlock.of("%T.encode(value, %T(%S))", XSD_LITERALS, IRI, it) }
      ?: CodeBlock.of("%T(value)", RDF_LITERAL)
    val setterBody = if (property.nullable) {
      CodeBlock.of("if (value == null) %M(%L) else %M(%L, %L)\n", CLEAR_LITERALS, pred, REPLACE_LITERALS, pred, term)
    } else {
      CodeBlock.of("%M(%L, %L)\n", REPLACE_LITERALS, pred, term)
    }
    return PropertySpec.builder(property.name, typeName)
      .mutable(true)
      .addModifiers(OVERRIDE)
      .getter(getter)
      .setter(FunSpec.setterBuilder().addParameter("value", typeName).addCode(setterBody).build())
      .build()
  }

  private fun enumProperty(domainPackageName: String, property: PropertyModel, pred: CodeBlock): PropertySpec {
    val enumType = domainClassName(domainPackageName, RdfMemberTypes.element(property.kotlinType), property.typePackage)
    val typeName = memberType(property, enumType)
    val builder = PropertySpec.builder(property.name, typeName).addModifiers(OVERRIDE)
    val decode = when (property.enumKind) {
      RdfEnumKind.IRI -> {
        // IRI-valued enum: values of any other term kind follow MaterializationPolicy.
        val values = CodeBlock.of(
          "%T.getIriValues(rdf.graph, rdf.node, %L, %S).map { %T.from(it) }", KASTOR_GRAPH_OPS, pred, label(property), enumType,
        )
        return builder.delegate(lazyValues(property, values)).build()
      }
      RdfEnumKind.CODE -> CodeBlock.of("{ lit -> %T.from(lit.lexical) }", enumType)
      else -> CodeBlock.of("{ lit -> enumValues<%T>().firstOrNull { it.name == lit.lexical } }", enumType)
    }
    val delegate = when {
      isList(property) -> "rdfLiterals"
      property.nullable -> "rdfLiteralOrNull"
      else -> "rdfLiteral"
    }
    return builder.delegate(CodeBlock.of("%M(%L) %L", MemberName(DELEGATES, delegate), pred, decode)).build()
  }

  private fun termProperty(property: PropertyModel, pred: CodeBlock): PropertySpec {
    val element = RdfMemberTypes.element(property.kotlinType)
    val (termType, reader) = requireNotNull(RdfMemberTypes.term(element)) { "${property.name}: $element is not an RDF term type" }
    val values = CodeBlock.of("%T.%N(rdf.graph, rdf.node, %L, %S)", KASTOR_GRAPH_OPS, reader, pred, label(property))
    return PropertySpec.builder(property.name, memberType(property, termType))
      .addModifiers(OVERRIDE)
      .delegate(lazyValues(property, values))
      .build()
  }

  private fun objectProperty(domainPackageName: String, property: PropertyModel, pred: CodeBlock): PropertySpec {
    val elementTypeName = domainClassName(domainPackageName, property.kotlinType, property.typePackage)
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
      .apply { if (!property.nullable) add(" ?: %T.missingRequired(%S)", MATERIALIZATION_POLICY, label(property)) }
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
    val elementType = RdfMemberTypes.element(property.kotlinType)
    val elementTypeName = domainClassName(domainPackageName, elementType, property.typePackage)
    val listType = KotlinPoetUtils.listOf(elementTypeName)
    return PropertySpec.builder(property.name, listType)
      .addModifiers(OVERRIDE)
      .delegate(CodeBlock.of("%M<%T>(%L)", MemberName(DELEGATES, "rdfObjects"), elementTypeName, pred))
      .build()
  }

  /**
   * The class for a member type: exact when the declaring package is known ([typePackage]), otherwise guessed from a
   * qualified name, or a simple name in the interface's package.
   */
  private fun domainClassName(domainPackageName: String, simpleOrQualified: String, typePackage: String?): ClassName = when {
    typePackage != null && simpleOrQualified.startsWith("$typePackage.") ->
      ClassName(typePackage, simpleOrQualified.removePrefix("$typePackage.").split('.'))
    simpleOrQualified.contains('.') -> ClassName.bestGuess(simpleOrQualified)
    else -> ClassName(domainPackageName, simpleOrQualified)
  }
}
