package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.*
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests that enum-typed properties in the instance DSL generate type-safe setters
 * that write the same RDF the readers expect, rather than falling through to a
 * String-based Literal setter.
 *
 * RED: before the fix, the setter parameter is String and the body writes
 *      Literal(value, XSD.string), which can never be read back.
 * GREEN: after the fix, the setter parameter is the enum type and the body
 *        writes value.iri (IRI members) or Literal(value.code, XSD.string) (literal members).
 */
class InstanceDslEnumTest {

    private lateinit var logger: KSPLogger
    private lateinit var generator: InstanceDslGenerator

    @BeforeEach
    fun setup() {
        logger = object : KSPLogger {
            override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
        generator = InstanceDslGenerator(logger)
    }

    // ----- helpers -------------------------------------------------------

    private fun noValidationOptions() = DslGenerationOptions(
        validation = DslGenerationOptions.ValidationConfig(enabled = false),
        output = DslGenerationOptions.OutputConfig(supportLanguageTags = false)
    )

    private fun render(request: InstanceDslRequest): String {
        val fileSpec = generator.generate(request)
        val writer = java.io.StringWriter()
        fileSpec.writeTo(writer)
        return writer.toString()
    }

    // ----- IRI-membered enum (single-valued) ------------------------------

    @Test
    fun `IRI enum setter has enum parameter type, not String`() {
        val code = renderIriEnumDsl(isList = false)
        // The setter must accept DocumentStatus, not String.
        // KotlinPoet quotes `value` because it is a soft keyword — match both forms.
        assertTrue(
            code.contains("fun status(value: DocumentStatus)") ||
                code.contains("fun status(`value`: DocumentStatus)"),
            "Expected 'fun status(value: DocumentStatus)' or backtick form but generated:\n$code"
        )
    }

    @Test
    fun `IRI enum setter body writes value dot iri`() {
        val code = renderIriEnumDsl(isList = false)
        assertTrue(
            code.contains("value.iri"),
            "Expected 'value.iri' in generated body but generated:\n$code"
        )
    }

    @Test
    fun `IRI enum setter does NOT write Literal(value`() {
        val code = renderIriEnumDsl(isList = false)
        assertFalse(
            code.contains("Literal(value,") || code.contains("Literal(value ,"),
            "Should not write Literal(value, ...) for IRI enum but generated:\n$code"
        )
    }

    // ----- IRI-membered enum (list / vararg) ------------------------------

    @Test
    fun `IRI enum list setter has vararg enum parameter`() {
        val code = renderIriEnumDsl(isList = true)
        assertTrue(
            code.contains("vararg values: DocumentStatus"),
            "Expected 'vararg values: DocumentStatus' but generated:\n$code"
        )
    }

    @Test
    fun `IRI enum list setter body writes it dot iri`() {
        val code = renderIriEnumDsl(isList = true)
        assertTrue(
            code.contains("it.iri"),
            "Expected 'it.iri' in list-variant body but generated:\n$code"
        )
    }

    // ----- LITERAL-membered enum (single-valued) --------------------------

    @Test
    fun `LITERAL enum setter has enum parameter type, not String`() {
        val code = renderLiteralEnumDsl(isList = false)
        // KotlinPoet quotes `value` because it is a soft keyword — match both forms.
        assertTrue(
            code.contains("fun priority(value: Priority)") ||
                code.contains("fun priority(`value`: Priority)"),
            "Expected 'fun priority(value: Priority)' or backtick form but generated:\n$code"
        )
    }

    @Test
    fun `LITERAL enum setter body writes Literal(value dot code`() {
        val code = renderLiteralEnumDsl(isList = false)
        assertTrue(
            code.contains("value.code"),
            "Expected 'value.code' in generated body but generated:\n$code"
        )
    }

    @Test
    fun `LITERAL enum setter does NOT write Literal(value,`() {
        val code = renderLiteralEnumDsl(isList = false)
        // Ensure it's not passing the enum directly as the literal value
        assertFalse(
            code.contains("Literal(value, ") && !code.contains("Literal(value.code"),
            "Should not write Literal(value, ...) for LITERAL enum (without .code) but generated:\n$code"
        )
    }

    // ----- LITERAL-membered enum (list / vararg) --------------------------

    @Test
    fun `LITERAL enum list setter has vararg enum parameter`() {
        val code = renderLiteralEnumDsl(isList = true)
        assertTrue(
            code.contains("vararg values: Priority"),
            "Expected 'vararg values: Priority' but generated:\n$code"
        )
    }

    @Test
    fun `LITERAL enum list setter body writes it dot code`() {
        val code = renderLiteralEnumDsl(isList = true)
        assertTrue(
            code.contains("it.code"),
            "Expected 'it.code' in list-variant body but generated:\n$code"
        )
    }

    // ----- private builders -----------------------------------------------

    /** IRI-membered enum: DocumentStatus with IRI members DRAFT / PUBLISHED */
    private fun renderIriEnumDsl(isList: Boolean): String {
        val enumModel = EnumModel(
            name = "DocumentStatus",
            classIri = "http://example.org/DocumentStatus",
            memberKind = EnumMemberKind.IRI,
            members = listOf(
                EnumMember(constantName = "DRAFT", iri = "http://example.org/status/draft"),
                EnumMember(constantName = "PUBLISHED", iri = "http://example.org/status/published"),
            )
        )

        val property = ShaclProperty(
            path = "http://example.org/status",
            name = "status",
            description = "Document status",
            datatype = null,
            targetClass = null,
            minCount = if (isList) 0 else 1,
            maxCount = if (isList) null else 1,
            enumName = "DocumentStatus",
            inValuesTyped = listOf(
                ShaclInValue("http://example.org/status/draft", isIri = true),
                ShaclInValue("http://example.org/status/published", isIri = true),
            )
        )

        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Document",
            targetClass = "http://example.org/Document",
            properties = listOf(property)
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            baseIri = null,
            vocabIri = null,
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val model = OntologyModel(
            shapes = listOf(shape),
            context = context,
            enums = listOf(enumModel)
        )

        val request = InstanceDslRequest(
            dslName = "document",
            ontologyModel = model,
            packageName = "com.example.test",
            options = noValidationOptions()
        )

        return render(request)
    }

    /** LITERAL-membered enum: Priority with code members LOW / HIGH */
    private fun renderLiteralEnumDsl(isList: Boolean): String {
        val enumModel = EnumModel(
            name = "Priority",
            classIri = null,
            memberKind = EnumMemberKind.LITERAL,
            members = listOf(
                EnumMember(constantName = "LOW", code = "low"),
                EnumMember(constantName = "HIGH", code = "high"),
            )
        )

        val property = ShaclProperty(
            path = "http://example.org/priority",
            name = "priority",
            description = "Task priority",
            datatype = null,
            targetClass = null,
            minCount = if (isList) 0 else 1,
            maxCount = if (isList) null else 1,
            enumName = "Priority",
            inValuesTyped = listOf(
                ShaclInValue("low", isIri = false),
                ShaclInValue("high", isIri = false),
            )
        )

        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Task",
            targetClass = "http://example.org/Task",
            properties = listOf(property)
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            baseIri = null,
            vocabIri = null,
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val model = OntologyModel(
            shapes = listOf(shape),
            context = context,
            enums = listOf(enumModel)
        )

        val request = InstanceDslRequest(
            dslName = "task",
            ontologyModel = model,
            packageName = "com.example.test",
            options = noValidationOptions()
        )

        return render(request)
    }
}
