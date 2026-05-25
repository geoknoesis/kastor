package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.CodegenConstants
import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.TypeMapper
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.OVERRIDE

/**
 * Generates factory objects that eagerly load a [DataClassGenerator]-produced data class
 * from an [RdfHandle] and register themselves in [OntoMapper].
 *
 * Each generated factory file contains a single Kotlin `object`:
 * ```
 * object PersonRecordFactory : RdfProjection {
 *     init { OntoMapper.registry[PersonRecord::class.java] = { h -> from(h) } }
 *     fun from(handle: RdfHandle): PersonRecord { ... }
 * }
 * ```
 *
 * The data class file itself has zero RDF imports; all infrastructure lives here.
 */
class DataClassFactoryGenerator(
    private val logger: KSPLogger,
    private val suffix: String,
    private val nestedMode: NestedMode,
) {

    fun generateFactories(model: OntologyModel, packageName: String): Map<String, FileSpec> =
        model.shapes
            .sortedBy { it.targetClass }
            .associate { shape ->
                val name = factoryName(shape.targetClass)
                name to generateFactory(shape, model.context, packageName)
            }

    private fun dataClassName(classIri: String) =
        NamingUtils.extractInterfaceName(classIri) + suffix

    private fun factoryName(classIri: String) = "${dataClassName(classIri)}Factory"

    // ── Per-shape factory generation ──────────────────────────────────────────

    private fun generateFactory(
        shape: ShaclShape,
        context: JsonLdContext,
        packageName: String,
    ): FileSpec {
        val dcName      = dataClassName(shape.targetClass)
        val fName       = factoryName(shape.targetClass)
        val dcClassName = ClassName(packageName, dcName)

        val file = FileSpec.builder(packageName, fName)
            .addFileComment("GENERATED FILE - DO NOT EDIT")
            .addFileComment("Generated factory for %L from SHACL shape: %L", dcName, shape.shapeIri)
            .addImport(CodegenConstants.RUNTIME_PACKAGE, "RdfHandle", "OntoMapper", "KastorGraphOps", "RdfRef")
            .addImport(CodegenConstants.RDF_PACKAGE, "Iri")

        val objectBuilder = TypeSpec.objectBuilder(fName)
            .addKdoc(
                "Factory that loads [%L] eagerly from an RDF graph and registers itself in [OntoMapper].\n" +
                "Generated from SHACL shape: %L",
                dcName, shape.shapeIri,
            )

        // OntoMapper registration in init block
        objectBuilder.addInitializerBlock(
            CodeBlock.of(
                "%T.registry[%T::class.java] = { handle -> from(handle) }\n",
                ClassName(CodegenConstants.RUNTIME_PACKAGE, "OntoMapper"),
                dcClassName,
            )
        )

        // from(handle: RdfHandle): DataClass
        objectBuilder.addFunction(buildFromFunction(shape, context, packageName, dcClassName))

        file.addType(objectBuilder.build())
        logger.info("Generated factory: $fName")
        return file.build()
    }

    // ── from() function ───────────────────────────────────────────────────────

    private fun buildFromFunction(
        shape: ShaclShape,
        context: JsonLdContext,
        packageName: String,
        dcClassName: ClassName,
    ): FunSpec {
        val handleType = ClassName(CodegenConstants.RUNTIME_PACKAGE, "RdfHandle")
        val fn = FunSpec.builder("from")
            .addParameter("handle", handleType)
            .returns(dcClassName)

        // One local val per property, then the constructor call
        shape.properties.sortedBy { it.path }.forEach { property ->
            fn.addCode(buildPropertyLoad(property, context, packageName))
        }

        // Constructor call: DataClass(prop1 = prop1Val, ...)
        val args = shape.properties
            .sortedBy { it.path }
            .joinToString(",\n    ") { p ->
                val name = NamingUtils.toValidKotlinIdentifier(p.name)
                "$name = _$name"
            }
        fn.addStatement("return %T(\n    %L\n)", dcClassName, args)

        return fn.build()
    }

    // ── Per-property eager load ───────────────────────────────────────────────

    private fun buildPropertyLoad(
        property: ShaclProperty,
        context: JsonLdContext,
        packageName: String,
    ): CodeBlock {
        val name  = NamingUtils.toValidKotlinIdentifier(property.name)
        val isList = property.maxCount == null || property.maxCount > 1
        val isRequired = !isList && property.minCount != null && property.minCount > 0
        val pred  = property.path

        return if (property.targetClass != null) {
            buildObjectLoad(name, pred, property, packageName, isList, isRequired)
        } else {
            buildLiteralLoad(name, pred, property, isList, isRequired)
        }
    }

    // object / IRI property ───────────────────────────────────────────────────

    private fun buildObjectLoad(
        name: String,
        pred: String,
        property: ShaclProperty,
        packageName: String,
        isList: Boolean,
        isRequired: Boolean,
    ): CodeBlock {
        val targetBaseName = NamingUtils.extractInterfaceName(property.targetClass!!)

        return when (nestedMode) {
            NestedMode.IRI_ONLY -> buildIriOnlyLoad(name, pred, isList, isRequired)

            NestedMode.INTERFACE -> {
                val targetType = ClassName(packageName, targetBaseName)
                buildMaterializeLoad(name, pred, targetType, isList, isRequired)
            }

            NestedMode.DATA_CLASS -> {
                val targetType = ClassName(packageName, "$targetBaseName$suffix")
                buildMaterializeLoad(name, pred, targetType, isList, isRequired)
            }
        }
    }

    private fun buildIriOnlyLoad(
        name: String,
        pred: String,
        isList: Boolean,
        isRequired: Boolean,
    ): CodeBlock {
        // Cast object nodes to Iri and extract value string; non-Iri objects are skipped
        val listExpr = CodeBlock.of(
            "KastorGraphOps.getObjectValues(handle.graph, handle.node, Iri(%S)) { child ->\n" +
            "    (child as? %T)?.value ?: child.toString()\n" +
            "}",
            pred,
            ClassName(CodegenConstants.RDF_PACKAGE, "Iri"),
        )
        return buildCardinality(name, listExpr, isList, isRequired, defaultForEmpty = "\"\"")
    }

    private fun buildMaterializeLoad(
        name: String,
        pred: String,
        targetType: ClassName,
        isList: Boolean,
        isRequired: Boolean,
    ): CodeBlock {
        val listExpr = CodeBlock.of(
            "KastorGraphOps.getObjectValues(handle.graph, handle.node, Iri(%S)) { child ->\n" +
            "    %T.materialize(%T(child, handle.graph), %T::class.java)\n" +
            "}",
            pred,
            ClassName(CodegenConstants.RUNTIME_PACKAGE, "OntoMapper"),
            ClassName(CodegenConstants.RUNTIME_PACKAGE, "RdfRef"),
            targetType,
        )
        return buildCardinality(name, listExpr, isList, isRequired,
            defaultForEmpty = "error(\"Required object $name missing\")")
    }

    // literal property ────────────────────────────────────────────────────────

    private fun buildLiteralLoad(
        name: String,
        pred: String,
        property: ShaclProperty,
        isList: Boolean,
        isRequired: Boolean,
    ): CodeBlock {
        val kotlinType = TypeMapper.mapDatatype(property.datatype)
        val typeName = kotlinType.toString().substringAfterLast('.')

        return when (typeName) {
            "Int" -> buildTypedLiteralLoad(name, pred, isList, isRequired,
                listConvert = ".mapNotNull { it.lexical.toIntOrNull() }",
                singleConvert = "?.toIntOrNull()",
                requiredConvert = ".lexical.toInt()",
                defaultEmpty = "0",
            )
            "Double" -> buildTypedLiteralLoad(name, pred, isList, isRequired,
                listConvert = ".mapNotNull { it.lexical.toDoubleOrNull() }",
                singleConvert = "?.toDoubleOrNull()",
                requiredConvert = ".lexical.toDouble()",
                defaultEmpty = "0.0",
            )
            "Boolean" -> buildTypedLiteralLoad(name, pred, isList, isRequired,
                listConvert = ".mapNotNull { it.lexical.toBooleanStrictOrNull() }",
                singleConvert = "?.toBooleanStrictOrNull()",
                requiredConvert = ".lexical.toBooleanStrict()",
                defaultEmpty = "false",
            )
            else -> buildStringLiteralLoad(name, pred, isList, isRequired)
        }
    }

    private fun buildStringLiteralLoad(
        name: String,
        pred: String,
        isList: Boolean,
        isRequired: Boolean,
    ): CodeBlock {
        return if (isList) {
            CodeBlock.of(
                "val _$name = KastorGraphOps.getLiteralValues(handle.graph, handle.node, Iri(%S)).map { it.lexical }\n",
                pred,
            )
        } else if (isRequired) {
            CodeBlock.of(
                "val _$name = KastorGraphOps.getRequiredLiteralValue(handle.graph, handle.node, Iri(%S)).lexical\n",
                pred,
            )
        } else {
            CodeBlock.of(
                "val _$name = KastorGraphOps.getLiteralValues(handle.graph, handle.node, Iri(%S)).map { it.lexical }.firstOrNull()\n",
                pred,
            )
        }
    }

    private fun buildTypedLiteralLoad(
        name: String,
        pred: String,
        isList: Boolean,
        isRequired: Boolean,
        listConvert: String,
        singleConvert: String,
        requiredConvert: String,
        defaultEmpty: String,
    ): CodeBlock {
        return if (isList) {
            CodeBlock.of(
                "val _$name = KastorGraphOps.getLiteralValues(handle.graph, handle.node, Iri(%S))$listConvert\n",
                pred,
            )
        } else if (isRequired) {
            CodeBlock.of(
                "val _$name = KastorGraphOps.getRequiredLiteralValue(handle.graph, handle.node, Iri(%S))$requiredConvert\n",
                pred,
            )
        } else {
            CodeBlock.of(
                "val _$name = KastorGraphOps.getLiteralValues(handle.graph, handle.node, Iri(%S)).map { it.lexical }.firstOrNull()$singleConvert\n",
                pred,
            )
        }
    }

    // ── Cardinality wrapper for object loads ──────────────────────────────────

    private fun buildCardinality(
        name: String,
        listExpr: CodeBlock,
        isList: Boolean,
        isRequired: Boolean,
        defaultForEmpty: String,
    ): CodeBlock {
        val listVal = CodeBlock.builder()
            .add("val _${name}List = ")
            .add(listExpr)
            .add("\n")
            .build()

        return if (isList) {
            CodeBlock.builder().add(listVal)
                .addStatement("val _$name = _${name}List")
                .build()
        } else if (isRequired) {
            CodeBlock.builder().add(listVal)
                .addStatement("val _$name = _${name}List.firstOrNull() ?: $defaultForEmpty")
                .build()
        } else {
            CodeBlock.builder().add(listVal)
                .addStatement("val _$name = _${name}List.firstOrNull()")
                .build()
        }
    }
}
