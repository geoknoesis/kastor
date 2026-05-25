package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassFactoryGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassWriterGenerator
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DataClassWriterGeneratorTest {

    private lateinit var logger: KSPLogger
    private val warnings = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        warnings.clear()
        logger = object : KSPLogger {
            override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) { warnings += message }
            override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
    }

    private fun emptyContext() = JsonLdContext(
        prefixes = emptyMap(),
        typeMappings = emptyMap(),
        propertyMappings = emptyMap(),
    )

    private fun render(
        shape: ShaclShape,
        suffix: String = "Record",
        nestedMode: NestedMode = NestedMode.INTERFACE,
        withWriter: Boolean = true,
    ): String {
        val writer = if (withWriter) DataClassWriterGenerator(logger, suffix, nestedMode) else null
        val factory = DataClassFactoryGenerator(logger, suffix, nestedMode, writer)
        val model = OntologyModel(listOf(shape), emptyContext())
        val files = factory.generateFactories(model, "com.example.test")
        assertEquals(1, files.size)
        return java.io.StringWriter().also { files.values.first().writeTo(it) }.toString()
    }

    // ── presence / absence ────────────────────────────────────────────────────

    @Test
    fun `toTriples function is absent when writerGenerator is null`() {
        val shape = ShaclShape("http://example.org/shapes/Person", "http://example.org/Person", emptyList())
        val code = render(shape, withWriter = false)
        assertFalse(code.contains("toTriples"), "Expected no toTriples without writer:\n$code")
    }

    @Test
    fun `toTriples function is present when writerGenerator is set`() {
        val shape = ShaclShape("http://example.org/shapes/Person", "http://example.org/Person", emptyList())
        val code = render(shape)
        assertTrue(code.contains("fun toTriples("), "Expected toTriples in:\n$code")
    }

    @Test
    fun `toTriples has correct signature with record and subject parameters`() {
        val shape = ShaclShape("http://example.org/shapes/Person", "http://example.org/Person", emptyList())
        val code = render(shape)
        assertTrue(code.contains("record: PersonRecord"), "Expected record parameter in:\n$code")
        assertTrue(code.contains("subject: Iri"), "Expected subject parameter in:\n$code")
    }

    @Test
    fun `toTriples returns List of RdfTriple`() {
        val shape = ShaclShape("http://example.org/shapes/Person", "http://example.org/Person", emptyList())
        val code = render(shape)
        assertTrue(code.contains("List<RdfTriple>"), "Expected List<RdfTriple> return type in:\n$code")
    }

    // ── rdf:type triple ───────────────────────────────────────────────────────

    @Test
    fun `toTriples always emits rdf type triple`() {
        val shape = ShaclShape("http://example.org/shapes/Person", "http://example.org/Person", emptyList())
        val code = render(shape)
        assertTrue(code.contains("RDF.type"), "Expected RDF.type in:\n$code")
        assertTrue(code.contains("http://example.org/Person") || code.contains("Iri("), "Expected type IRI in:\n$code")
    }

    // ── literal properties ────────────────────────────────────────────────────

    @Test
    fun `required literal property emits direct Literal triple`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Person",
            targetClass = "http://example.org/Person",
            properties = listOf(
                ShaclProperty("http://example.org/name", "name", "Name", "http://www.w3.org/2001/XMLSchema#string", null, 1, 1),
            ),
        )
        val code = render(shape)
        assertTrue(code.contains("Literal(record.name)") || code.contains("Literal("), "Expected Literal for required property in:\n$code")
        assertTrue(code.contains("triples +="), "Expected triple accumulation in:\n$code")
    }

    @Test
    fun `optional literal property emits conditional triple`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Person",
            targetClass = "http://example.org/Person",
            properties = listOf(
                ShaclProperty("http://example.org/nickname", "nickname", "Nickname", "http://www.w3.org/2001/XMLSchema#string", null, 0, 1),
            ),
        )
        val code = render(shape)
        assertTrue(code.contains("?.let {"), "Expected optional let block in:\n$code")
        assertTrue(code.contains("Literal("), "Expected Literal in:\n$code")
    }

    @Test
    fun `list literal property emits forEach loop`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Person",
            targetClass = "http://example.org/Person",
            properties = listOf(
                ShaclProperty("http://example.org/tags", "tags", "Tags", "http://www.w3.org/2001/XMLSchema#string", null, 0, null),
            ),
        )
        val code = render(shape)
        assertTrue(code.contains("forEach {"), "Expected forEach in:\n$code")
        assertTrue(code.contains("Literal("), "Expected Literal in:\n$code")
    }

    // ── object properties ─────────────────────────────────────────────────────

    @Test
    fun `IRI_ONLY mode emits Iri object triple`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://example.org/Catalog",
            properties = listOf(
                ShaclProperty("http://example.org/publisher", "publisher", "Publisher", null, "http://example.org/Agent", 0, 1),
            ),
        )
        val code = render(shape, nestedMode = NestedMode.IRI_ONLY)
        assertTrue(code.contains("Iri("), "Expected Iri emission in:\n$code")
        assertFalse(code.contains("RdfBacked"), "Should not reference RdfBacked in IRI_ONLY mode:\n$code")
    }

    @Test
    fun `INTERFACE mode casts to RdfBacked to extract node`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://example.org/Catalog",
            properties = listOf(
                ShaclProperty("http://example.org/dataset", "dataset", "Dataset", null, "http://example.org/Dataset", 0, null),
            ),
        )
        val code = render(shape, nestedMode = NestedMode.INTERFACE)
        assertTrue(code.contains("RdfBacked"), "Expected RdfBacked cast in:\n$code")
        assertTrue(code.contains("rdf?.node"), "Expected .rdf?.node extraction in:\n$code")
    }

    @Test
    fun `DATA_CLASS mode emits comment and skips object property`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://example.org/Catalog",
            properties = listOf(
                ShaclProperty("http://example.org/dataset", "dataset", "Dataset", null, "http://example.org/Dataset", 0, null),
            ),
        )
        val code = render(shape, nestedMode = NestedMode.DATA_CLASS)
        assertTrue(code.contains("// toTriples"), "Expected skip comment in:\n$code")
        assertFalse(code.contains("RdfBacked"), "Should not reference RdfBacked in DATA_CLASS mode:\n$code")
    }

    @Test
    fun `DATA_CLASS mode logs a warning for skipped object property`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://example.org/Catalog",
            properties = listOf(
                ShaclProperty("http://example.org/dataset", "dataset", "Dataset", null, "http://example.org/Dataset", 0, null),
            ),
        )
        render(shape, nestedMode = NestedMode.DATA_CLASS)
        assertTrue(warnings.any { "dataset" in it }, "Expected warning mentioning 'dataset', got: $warnings")
    }

    // ── imports ───────────────────────────────────────────────────────────────

    @Test
    fun `RdfTriple and Literal are imported when writer is present`() {
        val shape = ShaclShape("http://example.org/shapes/Person", "http://example.org/Person", emptyList())
        val code = render(shape)
        assertTrue(code.contains("RdfTriple"), "Expected RdfTriple import in:\n$code")
        assertTrue(code.contains("Literal"), "Expected Literal import in:\n$code")
    }
}
