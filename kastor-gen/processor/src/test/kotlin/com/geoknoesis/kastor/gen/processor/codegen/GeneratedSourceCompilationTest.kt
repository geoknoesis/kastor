package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassFactoryGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassWriterGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.EnumGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.enums.ShaclEnumExtractor
import com.geoknoesis.kastor.gen.processor.testing.EX
import com.geoknoesis.kastor.gen.processor.testing.KotlinSourceCompiler
import com.geoknoesis.kastor.gen.processor.testing.RecordingLogger
import com.geoknoesis.kastor.gen.processor.testing.XSD_NS
import com.geoknoesis.kastor.gen.processor.testing.prop
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.Test

/**
 * Compiles the output of every generator for deliberately hostile ontology inputs: capitalised,
 * hyphenated, spaced and keyword `sh:name`s, class IRIs whose local name starts with a digit,
 * `%`, `$`, quotes, backslashes and comment delimiters in descriptions, patterns and
 * `sh:in`/`sh:hasValue` values, numeric bounds on string properties and non-trivial datatypes.
 */
class GeneratedSourceCompilationTest {

    private val emptyContext = JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())

    private val backslash = "\\"

    private val hostileDescription = "Percent 100% of image/* files */ plus \$graph and \"quotes\" /* open $backslash"

    private fun hostileModel(): OntologyModel {
        val person = ShaclShape(
            shapeIri = EX + "PersonShape",
            targetClass = EX + "Person",
            properties = listOf(
                prop("title", name = "Title", minCount = 1, description = hostileDescription),
                prop("issued", name = "date-issued", datatype = "${XSD_NS}date"),
                prop("clazz", name = "class"),
                prop("knows", targetClass = EX + "Person", maxCount = null),
                prop("model", targetClass = EX + "3d-model"),
                prop("age", datatype = "${XSD_NS}integer").copy(minInclusive = java.math.BigDecimal("0.0"), maxExclusive = java.math.BigDecimal("200.0")),
                prop("height", datatype = "${XSD_NS}float").copy(minExclusive = java.math.BigDecimal("0.0")),
                prop("price", datatype = "${XSD_NS}decimal", maxCount = null).copy(minInclusive = java.math.BigDecimal("0.5")),
                prop("count", datatype = "${XSD_NS}long").copy(maxInclusive = java.math.BigDecimal("10.0")),
                prop("code").copy(
                    pattern = "^[A-Z]{2}$backslash$\"%s/*",
                    patternFlags = "i",
                    inValues = listOf("a\"b", "\$graph", "100%", "back${backslash}slash"),
                ),
                prop("kind").copy(hasValue = "x\"\$y%$backslash"),
                prop("label").copy(minInclusive = java.math.BigDecimal("1.0"), maxLength = 10),
                prop("tags", name = "object", maxCount = null).copy(pattern = "^t$", minLength = 1),
                prop("homepage", datatype = null).copy(nodeKind = "http://www.w3.org/ns/shacl#IRI"),
                prop("name", datatype = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString", maxCount = null),
                prop("active", datatype = "${XSD_NS}boolean"),
                prop("duration", datatype = "${XSD_NS}duration"),
                prop("rdf", name = "rdf"),
            ),
        )
        val document = ShaclShape(
            shapeIri = EX + "DocumentShape",
            targetClass = EX + "Document",
            properties = listOf(
                prop("issued", name = "Date issued", datatype = "${XSD_NS}date", description = "ends with backslash $backslash"),
                prop("in", name = "in", targetClass = EX + "Person"),
            ),
        )
        val model3d = ShaclShape(
            shapeIri = EX + "ModelShape",
            targetClass = EX + "3d-model",
            properties = listOf(prop("value", name = "val")),
        )
        return OntologyModel(listOf(person, document, model3d), emptyContext)
    }

    private fun generateAll(model: OntologyModel, pkg: String, nestedMode: NestedMode, implementsInterface: Boolean): List<FileSpec> {
        val logger = RecordingLogger()
        val enriched = ShaclEnumExtractor(logger).enrich(model)
        val files = mutableListOf<FileSpec>()
        files += EnumGenerator(logger).generateEnums(enriched, pkg).values
        files += InterfaceGenerator(logger, ValidationAnnotations.NONE).generateInterfaces(enriched, pkg).values
        files += OntologyWrapperGenerator(logger).generateWrappers(enriched, pkg).values
        files += DataClassGenerator(logger, "Record", nestedMode, implementsInterface, ValidationAnnotations.NONE)
            .generateDataClasses(enriched, pkg).values
        files += DataClassFactoryGenerator(logger, "Record", nestedMode, DataClassWriterGenerator(logger, "Record", nestedMode))
            .generateFactories(enriched, pkg).values
        files += InstanceDslGenerator(logger).generate(
            InstanceDslRequest(dslName = "hostile", ontologyModel = enriched, packageName = "$pkg.dsl", options = DslGenerationOptions())
        )
        return files
    }

    @Test
    fun `hostile names and text compile with interfaces wrappers data classes factories and dsl`() {
        val files = generateAll(hostileModel(), "gen.hostile", NestedMode.INTERFACE, implementsInterface = true)
        KotlinSourceCompiler.compile(files).assertOk()
    }

    @Test
    fun `hostile names compile with nested data classes`() {
        val files = generateAll(hostileModel(), "gen.nested", NestedMode.DATA_CLASS, implementsInterface = true)
        KotlinSourceCompiler.compile(files).assertOk()
    }

    @Test
    fun `hostile names compile with iri only nesting`() {
        val files = generateAll(hostileModel(), "gen.iri", NestedMode.IRI_ONLY, implementsInterface = false)
        KotlinSourceCompiler.compile(files).assertOk()
    }
}
