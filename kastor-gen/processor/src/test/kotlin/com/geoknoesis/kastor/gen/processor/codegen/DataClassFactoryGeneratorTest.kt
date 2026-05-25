package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassFactoryGenerator
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DataClassFactoryGeneratorTest {

    private lateinit var logger: KSPLogger

    @BeforeEach
    fun setup() {
        logger = object : KSPLogger {
            override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
    }

    private fun generator(
        suffix: String = "Record",
        nestedMode: NestedMode = NestedMode.INTERFACE,
    ) = DataClassFactoryGenerator(logger, suffix, nestedMode)

    private fun emptyContext() = JsonLdContext(
        prefixes = emptyMap(),
        typeMappings = emptyMap(),
        propertyMappings = emptyMap(),
    )

    private fun render(generator: DataClassFactoryGenerator, shape: ShaclShape): String {
        val model = OntologyModel(listOf(shape), emptyContext())
        val files = generator.generateFactories(model, "com.example.test")
        assertEquals(1, files.size)
        return java.io.StringWriter().also { files.values.first().writeTo(it) }.toString()
    }

    // ── naming ────────────────────────────────────────────────────────────────

    @Test
    fun `factory file is named DataClassNameFactory`() {
        val shape = ShaclShape("http://example.org/shapes/Person", "http://example.org/Person", emptyList())
        val model = OntologyModel(listOf(shape), emptyContext())
        val files = generator().generateFactories(model, "com.example.test")
        assertTrue(files.containsKey("PersonRecordFactory"), "Expected key PersonRecordFactory, got: ${files.keys}")
    }

    @Test
    fun `factory is a Kotlin object`() {
        val shape = ShaclShape("http://example.org/shapes/Person", "http://example.org/Person", emptyList())
        val code = render(generator(), shape)
        assertTrue(code.contains("object PersonRecordFactory"), code)
    }

    // ── OntoMapper registration ───────────────────────────────────────────────

    @Test
    fun `init block registers factory in OntoMapper`() {
        val shape = ShaclShape("http://example.org/shapes/Catalog", "http://example.org/Catalog", emptyList())
        val code = render(generator(), shape)
        assertTrue(code.contains("OntoMapper"), "Expected OntoMapper reference in:\n$code")
        assertTrue(code.contains("registry"), "Expected registry access in:\n$code")
        assertTrue(code.contains("CatalogRecord::class.java"), "Expected CatalogRecord::class.java in:\n$code")
    }

    // ── from() function ───────────────────────────────────────────────────────

    @Test
    fun `from function is generated with RdfHandle parameter`() {
        val shape = ShaclShape("http://example.org/shapes/Person", "http://example.org/Person", emptyList())
        val code = render(generator(), shape)
        assertTrue(code.contains("fun from("), code)
        assertTrue(code.contains("RdfHandle"), code)
    }

    @Test
    fun `from function returns the data class type`() {
        val shape = ShaclShape("http://example.org/shapes/Catalog", "http://example.org/Catalog", emptyList())
        val code = render(generator(), shape)
        assertTrue(code.contains("): CatalogRecord"), code)
    }

    @Test
    fun `from function returns constructor call`() {
        val shape = ShaclShape("http://example.org/shapes/Catalog", "http://example.org/Catalog", emptyList())
        val code = render(generator(), shape)
        // KotlinPoet emits expression-function syntax for a single-expression body
        assertTrue(code.contains("CatalogRecord("), code)
    }

    // ── string literal loading ────────────────────────────────────────────────

    @Test
    fun `string property loads via KastorGraphOps getLiteralValues`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty("http://example.org/name", "name", "Name", "http://www.w3.org/2001/XMLSchema#string", null, 1, 1),
            ),
        )
        val code = render(generator(), shape)
        assertTrue(code.contains("KastorGraphOps"), code)
        assertTrue(code.contains("getLiteralValues") || code.contains("getRequiredLiteralValue"), code)
        assertTrue(code.contains("_name"), code)
    }

    @Test
    fun `list string property loads all values`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty("http://example.org/tags", "tags", "Tags", "http://www.w3.org/2001/XMLSchema#string", null, 0, null),
            ),
        )
        val code = render(generator(), shape)
        assertTrue(code.contains("getLiteralValues"), code)
        assertTrue(code.contains("_tags"), code)
    }

    // ── object property loading ───────────────────────────────────────────────

    @Test
    fun `INTERFACE mode materializes object properties via OntoMapper`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://example.org/Catalog",
            properties = listOf(
                ShaclProperty("http://example.org/dataset", "dataset", "Dataset", null, "http://example.org/Dataset", 0, null),
            ),
        )
        val code = render(generator(nestedMode = NestedMode.INTERFACE), shape)
        assertTrue(code.contains("OntoMapper.materialize"), code)
        assertTrue(code.contains("Dataset::class.java"), code)
    }

    @Test
    fun `DATA_CLASS mode materializes object properties using data class type`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://example.org/Catalog",
            properties = listOf(
                ShaclProperty("http://example.org/dataset", "dataset", "Dataset", null, "http://example.org/Dataset", 0, null),
            ),
        )
        val code = render(generator(suffix = "Record", nestedMode = NestedMode.DATA_CLASS), shape)
        assertTrue(code.contains("OntoMapper.materialize"), code)
        assertTrue(code.contains("DatasetRecord::class.java"), code)
    }

    @Test
    fun `IRI_ONLY mode extracts IRI string without OntoMapper`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://example.org/Catalog",
            properties = listOf(
                ShaclProperty("http://example.org/publisher", "publisher", "Publisher", null, "http://example.org/Agent", 0, 1),
            ),
        )
        val code = render(generator(nestedMode = NestedMode.IRI_ONLY), shape)
        // Should cast to Iri and extract value, not call OntoMapper.materialize
        assertFalse(code.contains("OntoMapper.materialize"), code)
        assertTrue(code.contains("getObjectValues") || code.contains("Iri"), code)
    }

    // ── multiple shapes ───────────────────────────────────────────────────────

    @Test
    fun `generates one factory per shape`() {
        val shapes = listOf(
            ShaclShape("http://example.org/shapes/Catalog", "http://example.org/Catalog", emptyList()),
            ShaclShape("http://example.org/shapes/Dataset", "http://example.org/Dataset", emptyList()),
        )
        val model = OntologyModel(shapes, emptyContext())
        val files = generator().generateFactories(model, "com.example.test")
        assertEquals(2, files.size)
        assertTrue(files.containsKey("CatalogRecordFactory"))
        assertTrue(files.containsKey("DatasetRecordFactory"))
    }

    // ── file comment ──────────────────────────────────────────────────────────

    @Test
    fun `generated factory file contains DO NOT EDIT comment`() {
        val shape = ShaclShape("http://example.org/shapes/X", "http://example.org/X", emptyList())
        val code = render(generator(), shape)
        assertTrue(code.contains("DO NOT EDIT"), code)
    }
}
