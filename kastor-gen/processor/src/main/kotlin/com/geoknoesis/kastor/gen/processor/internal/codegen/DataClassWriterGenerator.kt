package com.geoknoesis.kastor.gen.processor.internal.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.processor.api.model.EnumMemberKind
import com.geoknoesis.kastor.gen.processor.api.model.EnumModel
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.utils.Cardinality
import com.geoknoesis.kastor.gen.processor.internal.utils.CodegenConstants
import com.geoknoesis.kastor.gen.processor.internal.utils.KotlinPoetUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.NamingUtils
import com.geoknoesis.kastor.gen.processor.internal.utils.TypeMapper
import com.geoknoesis.kastor.gen.processor.internal.utils.ValueKind
import com.geoknoesis.kastor.gen.processor.internal.utils.kdocText
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec

/**
 * Generates a `toTriples(record, subject)` function that serializes a data-class snapshot
 * back to a list of RDF triples — the write-path mirror of [DataClassFactoryGenerator]'s `from()`.
 *
 * The returned [FunSpec] is inserted into the existing factory object by [DataClassFactoryGenerator];
 * it is NOT written to a separate file.
 *
 * ## Design constraints
 * - Returns `List<RdfTriple>` only; callers decide whether to append or replace.
 * - Always emits `rdf:type` for the shape's target class.
 * - Literals are written with the property's declared `sh:datatype` (via `XsdLiterals.encode`).
 * - Object properties in [NestedMode.DATA_CLASS] cannot be serialized (no subject IRI available)
 *   and are skipped with a comment; a warning is logged at generation time.
 */
public class DataClassWriterGenerator(
    private val logger: KSPLogger,
    private val suffix: String,
    private val nestedMode: NestedMode,
) {

    private val rdfTripleClass   = ClassName(CodegenConstants.RDF_PACKAGE, "RdfTriple")
    private val iriClass         = ClassName(CodegenConstants.RDF_PACKAGE, "Iri")
    private val rdfTermClass     = ClassName(CodegenConstants.RDF_PACKAGE, "RdfTerm")
    private val rdfBackedClass   = ClassName(CodegenConstants.RUNTIME_PACKAGE, "RdfBacked")
    private val rdfVocabRdfClass = ClassName(CodegenConstants.VOCAB_PACKAGE, "RDF")
    private val xsdLiterals      = ClassName(CodegenConstants.RUNTIME_PACKAGE, "XsdLiterals")

    // ── Public entry point ────────────────────────────────────────────────────

    public fun buildToTriplesFunction(
        shape: ShaclShape,
        packageName: String,
        dcClassName: ClassName,
        enumsByName: Map<String, EnumModel> = emptyMap(),
        context: JsonLdContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap()),
        knownTypes: Set<String>? = null,
        properties: List<ShaclProperty> = shape.properties.sortedBy { it.path },
    ): FunSpec {
        val fn = FunSpec.builder("toTriples")
            .addKdoc(
                "%L",
                kdocText(
                    "Serializes a [${dcClassName.simpleName}] snapshot to a list of RDF triples.\n\n" +
                        "Always includes `rdf:type ${shape.targetClass}`.\n" +
                        "Callers are responsible for clearing stale triples before adding the result.\n" +
                        "Object sub-objects (if any) must be serialized separately.\n" +
                        (if (nestedMode == NestedMode.DATA_CLASS) {
                            "\n**Note:** `NestedMode.DATA_CLASS` object properties are not included — " +
                                "data-class snapshots carry no subject IRI for nested objects.\n"
                        } else "")
                ),
            )
            .addParameter("record", dcClassName)
            .addParameter("subject", iriClass)
            .returns(KotlinPoetUtils.listOf(rdfTripleClass))

        fn.addStatement("val triples = mutableListOf<%T>()", rdfTripleClass)

        // rdf:type triple — always
        fn.addStatement(
            "triples += %T(subject, %T.type, %L)",
            rdfTripleClass,
            rdfVocabRdfClass,
            CodegenConstants.iriConstant(shape.targetClass),
        )

        properties.forEach { property ->
            fn.addCode(buildPropertyWrite(property, enumsByName, context, knownTypes))
        }

        fn.addStatement("return triples")
        return fn.build()
    }

    // ── Per-property write code ───────────────────────────────────────────────

    private fun buildPropertyWrite(
        property: ShaclProperty,
        enumsByName: Map<String, EnumModel>,
        context: JsonLdContext,
        knownTypes: Set<String>?,
    ): CodeBlock {
        val name = NamingUtils.propertyName(property)
        val pred = property.path
        val kind = TypeMapper.valueKind(property, context, knownTypes, nestedMode)

        // Expression producing the RDF term for the element `it`.
        val term: CodeBlock = when (kind) {
            ValueKind.ENUM -> {
                val enumModel = enumsByName[property.enumName]
                if (enumModel == null || enumModel.memberKind == EnumMemberKind.IRI) {
                    CodeBlock.of("it.iri")
                } else {
                    val datatype = property.datatype
                        ?: enumModel.members.firstNotNullOfOrNull { it.datatype }
                        ?: "http://www.w3.org/2001/XMLSchema#string"
                    CodeBlock.of("%T.encode(it.code, %T(%S))", xsdLiterals, iriClass, datatype)
                }
            }
            ValueKind.IRI -> CodeBlock.of("%T(it)", iriClass)
            ValueKind.OBJECT -> when (nestedMode) {
                NestedMode.DATA_CLASS -> return buildDataClassSkip(name, property)
                // Live wrappers expose their node. Any other implementation has no subject: silently dropping the
                // value would lose data, so the write fails with guidance.
                else -> CodeBlock.of(
                    "((it as? %T)?.rdf?.node ?: throw IllegalArgumentException(%S))",
                    rdfBackedClass,
                    "toTriples: '$name' <$pred> holds a value that is not RdfBacked, so it has no RDF subject. " +
                        "In NestedMode.INTERFACE nested values must be materialized from a graph (live wrappers); " +
                        "otherwise use NestedMode.IRI_ONLY or write the nested object separately.",
                )
            }
            ValueKind.LITERAL -> {
                val mapping = TypeMapper.literalMapping(property.datatype)
                if (mapping.isString && mapping.writeDatatype == "http://www.w3.org/2001/XMLSchema#string") {
                    CodeBlock.of("%T(it)", ClassName(CodegenConstants.RDF_PACKAGE, "Literal"))
                } else {
                    mapping.encode(CodeBlock.of("it"))
                }
            }
        }

        val add = CodeBlock.of("triples += %T(subject, %T(%S), %L)", rdfTripleClass, iriClass, pred, term)
        return if (Cardinality.isList(property)) {
            CodeBlock.builder()
                .beginControlFlow("record.%N.forEach { value ->", name)
                .addStatement("value.let { %L }", add)
                .endControlFlow()
                .build()
        } else {
            CodeBlock.builder().addStatement("record.%N?.let { %L }", name, add).build()
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
}
