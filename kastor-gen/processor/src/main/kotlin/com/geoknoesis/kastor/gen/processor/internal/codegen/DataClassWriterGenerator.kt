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
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.joinToCode

/**
 * Generates a `toTriples(record, subject)` function that serializes a data-class snapshot
 * back to a list of RDF triples — the write-path mirror of [DataClassFactoryGenerator]'s `from()`.
 *
 * The returned [FunSpec] is inserted into the existing factory object by [DataClassFactoryGenerator];
 * it is NOT written to a separate file.
 *
 * ## Design constraints
 * - Returns `List<RdfTriple>` only; callers decide whether to append or replace. [buildReplaceInGraphFunction]
 *   generates the scoped replace (`replaceInGraph`) that keeps every predicate `toTriples` does not write.
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
    private val mutableGraphClass = ClassName(CodegenConstants.RDF_PACKAGE, "MutableRdfGraph")
    private val mergeRecord = MemberName(CodegenConstants.RUNTIME_PACKAGE, "mergeRecord")
    private val recordFieldClass = ClassName(CodegenConstants.RUNTIME_PACKAGE, "RecordField")

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
                        "Callers are responsible for clearing stale triples before adding the result; " +
                        "[replaceInGraph] does that for the predicates written here only.\n" +
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

    /**
     * `replaceInGraph(graph, record, subject)`: the scoped read-modify-write companion of `toTriples`, through the
     * runtime's `mergeRecord`. Only the predicates `toTriples` writes are edited (and `rdf:type` added), so predicates
     * the record does not model, object links it skips ([NestedMode.DATA_CLASS]) and complex-path triples are kept.
     * Within those predicates, every term whose value the record still holds keeps its original form (language tag,
     * datatype, lexical form), terms the record cannot represent are kept, and an unchanged single-valued field keeps
     * the values beyond `sh:maxCount` that its reader did not return.
     */
    public fun buildReplaceInGraphFunction(
        shape: ShaclShape,
        packageName: String,
        dcClassName: ClassName,
        enumsByName: Map<String, EnumModel> = emptyMap(),
        context: JsonLdContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap()),
        knownTypes: Set<String>? = null,
        properties: List<ShaclProperty> = shape.properties.sortedBy { it.path },
    ): FunSpec {
        val fields = properties
            .filterNot { nestedMode == NestedMode.DATA_CLASS && TypeMapper.valueKind(it, context, knownTypes, nestedMode) == ValueKind.OBJECT }
            .distinctBy { it.path }
            .sortedBy { it.path }
            .map { recordField(it, packageName, enumsByName, context, knownTypes) }
        return FunSpec.builder("replaceInGraph")
            .addKdoc(
                "%L",
                kdocText(
                    "Writes [record] to [graph] as the description of [subject], in one atomic edit. Only the predicates " +
                        "[toTriples] writes are edited and `rdf:type ${shape.targetClass}` is added; every other triple of " +
                        "[subject] (other types, predicates the record does not model" +
                        (if (nestedMode == NestedMode.DATA_CLASS) ", object links skipped in `NestedMode.DATA_CLASS`" else "") +
                        ") is kept.\n\nWithin the edited predicates, a value the record still holds keeps its original term " +
                        "(language tag, datatype, lexical form), a term the record cannot represent is kept, and an unchanged " +
                        "single-valued field keeps every value of its predicate, also those beyond `sh:maxCount`. Values " +
                        "the record no longer holds are removed and new ones are added as [toTriples] writes them." +
                        "\n\nRequires an `AtomicMutableRdfGraph`; other graphs are rejected before any change."
                ),
            )
            .addParameter("graph", mutableGraphClass)
            .addParameter("record", dcClassName)
            .addParameter("subject", iriClass)
            .addCode(
                CodeBlock.builder()
                    .add("graph.%M(\n", mergeRecord).indent()
                    .add("subject,\n")
                    .add("listOf(%L),\n", CodegenConstants.iriConstant(shape.targetClass))
                    .add("listOf(\n").indent()
                    .apply { fields.forEach { add("%L,\n", it) } }
                    .unindent().add("),\n")
                    .unindent().add(")\n")
                    .build(),
            )
            .build()
    }

    /**
     * `RecordField<T>(predicate, values, singleValued, decode, encode)` for [property]: `decode` mirrors the reader of
     * `from()` (which terms it accepts and the value it makes of them), `encode` is the term [toTriples] writes.
     */
    private fun recordField(
        property: ShaclProperty,
        packageName: String,
        enumsByName: Map<String, EnumModel>,
        context: JsonLdContext,
        knownTypes: Set<String>?,
    ): CodeBlock {
        val name = NamingUtils.propertyName(property)
        val kind = TypeMapper.valueKind(property, context, knownTypes, nestedMode)
        val term = termFor(property, kind, enumsByName)
        val values = if (Cardinality.isList(property)) CodeBlock.of("record.%N", name) else CodeBlock.of("listOfNotNull(record.%N)", name)
        val literal = ClassName(CodegenConstants.RDF_PACKAGE, "Literal")
        val resource = ClassName(CodegenConstants.RDF_PACKAGE, "RdfResource")
        val field = when (kind) {
            ValueKind.ENUM -> {
                val enumModel = enumsByName[property.enumName]
                val enumType = ClassName(packageName, enumModel?.name ?: property.enumName!!)
                val decode = if (enumModel == null || enumModel.memberKind == EnumMemberKind.IRI) {
                    CodeBlock.of("{ (it as? %T)?.let { iri -> %T.from(iri) } }", iriClass, enumType)
                } else {
                    CodeBlock.of("{ (it as? %T)?.let { lit -> %T.from(lit.lexical) } }", literal, enumType)
                }
                FieldCode(enumType, values, decode, CodeBlock.of("{ %L }", term))
            }
            ValueKind.IRI -> if (TypeMapper.isResourceReference(property)) {
                FieldCode(resource, values, CodeBlock.of("{ it as? %T }", resource), CodeBlock.of("{ it }"))
            } else {
                FieldCode(String::class.asTypeName(), values, CodeBlock.of("{ (it as? %T)?.value }", iriClass), CodeBlock.of("{ %L }", term))
            }
            // Nested objects are compared by node: the record holds live wrappers whose node is the graph term.
            ValueKind.OBJECT -> FieldCode(
                rdfTermClass, CodeBlock.of("%L.map { %L }", values, term), CodeBlock.of("{ it as? %T }", resource), CodeBlock.of("{ it }"),
            )
            ValueKind.LITERAL -> {
                val mapping = TypeMapper.literalMapping(property.datatype)
                FieldCode(
                    mapping.type, values,
                    CodeBlock.of("{ (it as? %T)?.let { lit -> %L } }", literal, mapping.decode(CodeBlock.of("lit"))),
                    CodeBlock.of("{ %L }", term),
                )
            }
        }
        return CodeBlock.of(
            "%T(%T(%S), %L, %L, %L, %L)",
            recordFieldClass.parameterizedBy(field.type), iriClass, property.path, field.values,
            !Cardinality.isList(property), field.decode, field.encode,
        )
    }

    private data class FieldCode(val type: TypeName, val values: CodeBlock, val decode: CodeBlock, val encode: CodeBlock)

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

        if (kind == ValueKind.OBJECT && nestedMode == NestedMode.DATA_CLASS) return buildDataClassSkip(name, property)
        val term = termFor(property, kind, enumsByName)

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

    /** The expression of the RDF term written for the element `it` of [property]. */
    private fun termFor(property: ShaclProperty, kind: ValueKind, enumsByName: Map<String, EnumModel>): CodeBlock {
        return when (kind) {
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
            // RdfResource members (sh:BlankNodeOrIRI) already hold the term; IRI members hold the IRI string.
            ValueKind.IRI -> if (TypeMapper.isResourceReference(property)) CodeBlock.of("it") else CodeBlock.of("%T(it)", iriClass)
            ValueKind.OBJECT -> when (nestedMode) {
                NestedMode.DATA_CLASS -> error("NestedMode.DATA_CLASS object members are not written")
                // Live wrappers expose their node. Any other implementation has no subject: silently dropping the
                // value would lose data, so the write fails with guidance.
                else -> CodeBlock.of(
                    "((it as? %T)?.rdf?.node ?: throw IllegalArgumentException(%S))",
                    rdfBackedClass,
                    "toTriples: '${NamingUtils.propertyName(property)}' <${property.path}> holds a value that is not RdfBacked, so it has no RDF subject. " +
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
