package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.CodegenConstants
import com.geoknoesis.kastor.gen.processor.internal.utils.KotlinPoetUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.TypeMapper
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec

/**
 * Generates a `toTriples(record, subject)` function that serializes a data-class snapshot
 * back to a list of RDF triples — the write-path mirror of [DataClassFactoryGenerator.buildFromFunction].
 *
 * The returned [FunSpec] is inserted into the existing factory object by [DataClassFactoryGenerator];
 * it is NOT written to a separate file.
 *
 * ## Design constraints
 * - Returns `List<RdfTriple>` only; callers decide whether to append or replace.
 * - Always emits `rdf:type` for the shape's target class.
 * - Object properties in [NestedMode.DATA_CLASS] cannot be serialized (no subject IRI available)
 *   and are skipped with a comment; a warning is logged at generation time.
 */
class DataClassWriterGenerator(
    private val logger: KSPLogger,
    private val suffix: String,
    private val nestedMode: NestedMode,
) {

    private val rdfTripleClass   = ClassName(CodegenConstants.RDF_PACKAGE, "RdfTriple")
    private val iriClass         = ClassName(CodegenConstants.RDF_PACKAGE, "Iri")
    private val literalClass     = ClassName(CodegenConstants.RDF_PACKAGE, "Literal")
    private val rdfBackedClass   = ClassName(CodegenConstants.RUNTIME_PACKAGE, "RdfBacked")
    private val rdfVocabRdfClass = ClassName(CodegenConstants.VOCAB_PACKAGE, "RDF")

    // ── Public entry point ────────────────────────────────────────────────────

    fun buildToTriplesFunction(
        shape: ShaclShape,
        packageName: String,
        dcClassName: ClassName,
    ): FunSpec {
        val fn = FunSpec.builder("toTriples")
            .addKdoc(
                "Serializes a [%T] snapshot to a list of RDF triples.\n\n" +
                "Always includes `rdf:type %L`.\n" +
                "Callers are responsible for clearing stale triples before adding the result.\n" +
                "Object sub-objects (if any) must be serialized separately.\n" +
                (if (nestedMode == NestedMode.DATA_CLASS) {
                    "\n**Note:** `NestedMode.DATA_CLASS` object properties are not included — " +
                    "data-class snapshots carry no subject IRI for nested objects.\n"
                } else ""),
                dcClassName, shape.targetClass,
            )
            .addParameter("record", dcClassName)
            .addParameter("subject", iriClass)
            .returns(KotlinPoetUtils.listOf(rdfTripleClass))

        fn.addStatement("val triples = mutableListOf<%T>()", rdfTripleClass)

        // rdf:type triple — always
        fn.addCode(
            CodeBlock.of(
                "triples += %T(subject, %T.type, %L)\n",
                rdfTripleClass,
                rdfVocabRdfClass,
                CodegenConstants.iriConstant(shape.targetClass),
            )
        )

        shape.properties.sortedBy { it.path }.forEach { property ->
            fn.addCode(buildPropertyWrite(property, packageName))
        }

        fn.addStatement("return triples")
        return fn.build()
    }

    // ── Per-property write code ───────────────────────────────────────────────

    private fun buildPropertyWrite(property: ShaclProperty, packageName: String): CodeBlock {
        val name   = NamingUtils.toValidKotlinIdentifier(property.name)
        val isList = property.maxCount == null || property.maxCount > 1
        val isRequired = !isList && property.minCount != null && property.minCount > 0

        return if (property.targetClass != null) {
            buildObjectWrite(name, property.path, property, packageName, isList, isRequired)
        } else {
            buildLiteralWrite(name, property.path, property, isList, isRequired)
        }
    }

    // ── Object properties ─────────────────────────────────────────────────────

    private fun buildObjectWrite(
        name: String,
        pred: String,
        property: ShaclProperty,
        packageName: String,
        isList: Boolean,
        isRequired: Boolean,
    ): CodeBlock = when (nestedMode) {
        NestedMode.IRI_ONLY   -> buildIriOnlyWrite(name, pred, isList)
        NestedMode.INTERFACE  -> buildInterfaceWrite(name, pred, property, packageName, isList)
        NestedMode.DATA_CLASS -> buildDataClassSkip(name, property)
    }

    private fun buildIriOnlyWrite(name: String, pred: String, isList: Boolean): CodeBlock =
        if (isList) {
            CodeBlock.of(
                "record.%L.forEach { triples += %T(subject, %T(%S), %T(it)) }\n",
                name, rdfTripleClass, iriClass, pred, iriClass,
            )
        } else {
            CodeBlock.of(
                "record.%L?.let { triples += %T(subject, %T(%S), %T(it)) }\n",
                name, rdfTripleClass, iriClass, pred, iriClass,
            )
        }

    private fun buildInterfaceWrite(
        name: String,
        pred: String,
        property: ShaclProperty,
        packageName: String,
        isList: Boolean,
    ): CodeBlock {
        // Extract the backing node by casting to RdfBacked (live wrappers implement it).
        // If the object is not RdfBacked (e.g. another snapshot), the cast returns null and
        // the triple is silently skipped — callers should ensure wrappers are used here.
        val extractNode = CodeBlock.of(
            "(obj as? %T)?.rdf?.node as? %T",
            rdfBackedClass,
            iriClass,
        )
        return if (isList) {
            CodeBlock.of(
                "record.%L.forEach { obj ->\n" +
                "    val objNode = %L\n" +
                "    objNode?.let { triples += %T(subject, %T(%S), it) }\n" +
                "}\n",
                name, extractNode, rdfTripleClass, iriClass, pred,
            )
        } else {
            CodeBlock.of(
                "record.%L?.let { obj ->\n" +
                "    val objNode = %L\n" +
                "    objNode?.let { triples += %T(subject, %T(%S), it) }\n" +
                "}\n",
                name, extractNode, rdfTripleClass, iriClass, pred,
            )
        }
    }

    private fun buildDataClassSkip(name: String, property: ShaclProperty): CodeBlock {
        logger.warn(
            "generateWriteSupport: property '${property.name}' (${property.path}) is an object ref " +
            "in NestedMode.DATA_CLASS — it cannot be serialized to triples because the nested " +
            "data class carries no subject IRI. Write sub-objects separately."
        )
        return CodeBlock.of(
            "// toTriples: '%L' skipped — NestedMode.DATA_CLASS object refs carry no subject IRI\n",
            name,
        )
    }

    // ── Literal properties ────────────────────────────────────────────────────

    private fun buildLiteralWrite(
        name: String,
        pred: String,
        property: ShaclProperty,
        isList: Boolean,
        isRequired: Boolean,
    ): CodeBlock {
        val typeName = TypeMapper.mapDatatype(property.datatype).toString().substringAfterLast('.')
        // Literal companion invoke operators exist for String, Int, Double, Boolean.
        // All are emitted as Literal(value) — the companion resolves the correct overload.
        return when {
            isList -> CodeBlock.of(
                "record.%L.forEach { triples += %T(subject, %T(%S), %T(it)) }\n",
                name, rdfTripleClass, iriClass, pred, literalClass,
            )
            isRequired -> CodeBlock.of(
                "triples += %T(subject, %T(%S), %T(record.%L))\n",
                rdfTripleClass, iriClass, pred, literalClass, name,
            )
            else -> CodeBlock.of(
                "record.%L?.let { triples += %T(subject, %T(%S), %T(it)) }\n",
                name, rdfTripleClass, iriClass, pred, literalClass,
            )
        }
    }
}
