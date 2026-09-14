package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind
import com.geoknoesis.kastor.gen.processor.api.model.EnumModel
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.Cardinality
import com.geoknoesis.kastor.gen.processor.internal.utils.CodegenConstants
import com.geoknoesis.kastor.gen.processor.internal.utils.EffectiveMember
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.TypeMapper
import com.geoknoesis.kastor.gen.processor.internal.utils.ValueKind
import com.geoknoesis.kastor.gen.processor.internal.utils.kdocText
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*

/**
 * Generates factory objects that eagerly load a [DataClassGenerator]-produced data class
 * from an `RdfHandle` and register themselves in `OntoMapper`.
 *
 * Each generated factory file contains a single Kotlin `object`:
 * ```
 * object PersonRecordFactory {
 *     init { OntoMapper.register(PersonRecord::class.java) { h -> from(h) } }
 *     fun from(handle: RdfHandle): PersonRecord { ... }
 * }
 * ```
 *
 * Nested objects in [NestedMode.DATA_CLASS] are materialized eagerly through `OntoMapper.materialize`, whose
 * per-call scope reuses shared nodes and reports cyclic data with a `MaterializationException`.
 *
 * The data class file itself has zero RDF imports; all infrastructure lives here.
 */
public class DataClassFactoryGenerator(
    private val logger: KSPLogger,
    private val suffix: String,
    private val nestedMode: NestedMode,
    private val writerGenerator: DataClassWriterGenerator? = null,
) {
    private val iriClass = ClassName(CodegenConstants.RDF_PACKAGE, "Iri")
    private val graphOps = ClassName(CodegenConstants.RUNTIME_PACKAGE, "KastorGraphOps")
    private val ontoMapper = ClassName(CodegenConstants.RUNTIME_PACKAGE, "OntoMapper")
    private val rdfRef = ClassName(CodegenConstants.RUNTIME_PACKAGE, "RdfRef")

    /**
     * @param fallbackUnshapedToIri when true, `sh:class` targets without a shape in [model] are loaded as IRI strings
     */
    public fun generateFactories(model: OntologyModel, packageName: String, fallbackUnshapedToIri: Boolean = false): Map<String, FileSpec> {
        GenerationNames.checkCollisions(model)
        val enumsByName = model.enums.associateBy { it.name }
        val knownTypes = if (fallbackUnshapedToIri) GenerationNames.knownTypes(model) else null
        val members = GenerationNames.effectiveMembers(model, GenerationNames.superTypes(model))
        return model.shapes
            .sortedBy { it.targetClass }
            .associateTo(sortedMapOf()) { shape ->
                val name = factoryName(shape.targetClass, model.context)
                name to generateFactory(
                    shape, members[shape.targetClass].orEmpty(), model.context, packageName, enumsByName, knownTypes,
                )
            }
    }

    private fun dataClassName(classIri: String, context: JsonLdContext) =
        NamingUtils.domainName(classIri, context) + suffix

    private fun factoryName(classIri: String, context: JsonLdContext) = "${dataClassName(classIri, context)}Factory"

    // ── Per-shape factory generation ──────────────────────────────────────────

    private fun generateFactory(
        shape: ShaclShape,
        members: List<EffectiveMember>,
        context: JsonLdContext,
        packageName: String,
        enumsByName: Map<String, EnumModel>,
        knownTypes: Set<String>?,
    ): FileSpec {
        val properties = members.map { it.typing }
        val dcName      = dataClassName(shape.targetClass, context)
        val fName       = factoryName(shape.targetClass, context)
        val dcClassName = ClassName(packageName, dcName)

        val file = FileSpec.builder(packageName, fName)
            .addFileComment("GENERATED FILE - DO NOT EDIT")
            .addFileComment("Generated factory for %L from SHACL shape: %L", dcName, shape.shapeIri)
            .addImport(CodegenConstants.RUNTIME_PACKAGE, "RdfHandle", "OntoMapper", "KastorGraphOps", "RdfRef")
            .addImport(CodegenConstants.RDF_PACKAGE, "Iri")

        // Additional imports required by toTriples()
        if (writerGenerator != null) {
            file.addImport(CodegenConstants.RDF_PACKAGE, "RdfTriple", "Literal")
                .addImport(CodegenConstants.VOCAB_PACKAGE, "RDF")
            if (nestedMode == NestedMode.INTERFACE) {
                file.addImport(CodegenConstants.RUNTIME_PACKAGE, "RdfBacked")
            }
        }

        val objectBuilder = TypeSpec.objectBuilder(fName)
            .addKdoc(
                "%L",
                kdocText(
                    "Factory that loads [$dcName] eagerly from an RDF graph and registers itself in OntoMapper.\n" +
                        "Generated from SHACL shape: ${shape.shapeIri}"
                ),
            )

        // OntoMapper registration in init block
        objectBuilder.addInitializerBlock(
            CodeBlock.builder()
                .addStatement("%T.register(%T::class.java) { handle -> from(handle) }", ontoMapper, dcClassName)
                .build()
        )

        objectBuilder.addFunction(buildFromFunction(properties, context, packageName, dcClassName, enumsByName, knownTypes, shape.shapeIri))

        // toTriples(record, subject): List<RdfTriple>  — only when write support is enabled
        // One write per path: members that alias a path (same path under two inherited names) hold the same values.
        val writtenProperties = members.filter { it.primaryForPath }.map { it.typing }
        writerGenerator?.buildToTriplesFunction(shape, packageName, dcClassName, enumsByName, context, knownTypes, writtenProperties)
            ?.let { objectBuilder.addFunction(it) }

        file.addType(objectBuilder.build())
        logger.info("Generated factory: $fName")
        return file.build()
    }

    // ── from() function ───────────────────────────────────────────────────────

    private fun buildFromFunction(
        properties: List<ShaclProperty>,
        context: JsonLdContext,
        packageName: String,
        dcClassName: ClassName,
        enumsByName: Map<String, EnumModel>,
        knownTypes: Set<String>?,
        shapeIri: String,
    ): FunSpec {
        val handleType = ClassName(CodegenConstants.RUNTIME_PACKAGE, "RdfHandle")
        val fn = FunSpec.builder("from")
            .addParameter("handle", handleType)
            .returns(dcClassName)

        // One local val per property, then the constructor call
        properties.forEach { property ->
            fn.addCode(buildPropertyLoad(property, context, packageName, enumsByName, knownTypes, shapeIri))
        }

        // Constructor call: DataClass(prop1 = _prop1, ...). Local names derive from the unescaped identifier.
        val args = properties.map { p ->
            val name = NamingUtils.propertyName(p)
            CodeBlock.of("%N = %N", name, "_$name")
        }
        fn.addCode(
            CodeBlock.builder()
                .add("return %T(\n", dcClassName).indent()
                .apply { args.forEach { add("%L,\n", it) } }
                .unindent().add(")\n")
                .build()
        )

        return fn.build()
    }

    // ── Per-property eager load ───────────────────────────────────────────────

    private fun buildPropertyLoad(
        property: ShaclProperty,
        context: JsonLdContext,
        packageName: String,
        enumsByName: Map<String, EnumModel>,
        knownTypes: Set<String>?,
        shapeIri: String,
    ): CodeBlock {
        val name = NamingUtils.propertyName(property)
        val pred = property.path
        val label = "$name <$pred> of shape <$shapeIri>"
        val policy = ClassName(CodegenConstants.RUNTIME_PACKAGE, "MaterializationPolicy")
        val kind = TypeMapper.valueKind(property, context, knownTypes, nestedMode)

        val listExpr: CodeBlock = when (kind) {
            ValueKind.ENUM -> {
                val enumModel = enumsByName[property.enumName]
                    ?: error("enum ${property.enumName} referenced by $pred is not in the model")
                val enumType = ClassName(packageName, enumModel.name)
                if (enumModel.memberKind == EnumMemberKind.IRI) {
                    CodeBlock.of(
                        "%T.getObjectValues(handle.graph, handle.node, %T(%S)) { it }.filterIsInstance<%T>().map { %T.from(it) }",
                        graphOps, iriClass, pred, iriClass, enumType,
                    )
                } else {
                    CodeBlock.of(
                        "%T.getLiteralValues(handle.graph, handle.node, %T(%S)).map { %T.from(it.lexical) }",
                        graphOps, iriClass, pred, enumType,
                    )
                }
            }
            ValueKind.IRI -> CodeBlock.of(
                "%T.getObjectValues(handle.graph, handle.node, %T(%S)) { it }.filterIsInstance<%T>().map { it.value }",
                graphOps, iriClass, pred, iriClass,
            )
            ValueKind.OBJECT -> {
                val baseName = NamingUtils.domainName(property.targetClass!!, context)
                val targetType = when (nestedMode) {
                    NestedMode.DATA_CLASS -> ClassName(packageName, "$baseName$suffix")
                    else -> ClassName(packageName, baseName)
                }
                CodeBlock.of(
                    "%T.getObjectValues(handle.graph, handle.node, %T(%S)) { child ->\n⇥%T.materialize(%T(child, handle.graph), %T::class.java)\n⇤}",
                    graphOps, iriClass, pred, ontoMapper, rdfRef, targetType,
                )
            }
            ValueKind.LITERAL -> {
                val mapping = TypeMapper.literalMapping(property.datatype)
                // Ill-typed values follow MaterializationPolicy (throw by default) instead of disappearing.
                CodeBlock.of(
                    "%T.getLiteralValues(handle.graph, handle.node, %T(%S)).mapNotNull { lit -> %L ?: %T.illTyped(lit, %S, %S) }",
                    graphOps, iriClass, pred, mapping.decode(CodeBlock.of("lit")),
                    ClassName(CodegenConstants.RUNTIME_PACKAGE, "MaterializationPolicy"), label, mapping.expectedDescription(),
                )
            }
        }

        // Same cardinality rule as the live wrappers: a required member without a value throws MaterializationException.
        val local = "_$name"
        return when {
            Cardinality.isList(property) && Cardinality.isRequired(property) -> CodeBlock.builder()
                .addStatement("val %N = %L.ifEmpty { %T.missingRequired(%S) }", local, listExpr, policy, label)
                .build()
            Cardinality.isList(property) -> CodeBlock.builder().addStatement("val %N = %L", local, listExpr).build()
            Cardinality.isRequiredSingle(property) -> CodeBlock.builder()
                .addStatement("val %N = %L.firstOrNull() ?: %T.missingRequired(%S)", local, listExpr, policy, label)
                .build()
            else -> CodeBlock.builder().addStatement("val %N = %L.firstOrNull()", local, listExpr).build()
        }
    }
}
