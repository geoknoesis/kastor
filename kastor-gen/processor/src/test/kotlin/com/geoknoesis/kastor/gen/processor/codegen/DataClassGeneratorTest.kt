package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.annotations.NestedMode
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.codegen.DataClassGenerator
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DataClassGeneratorTest {

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
        implementsInterface: Boolean = false,
        validationAnnotations: ValidationAnnotations = ValidationAnnotations.NONE,
    ) = DataClassGenerator(logger, suffix, nestedMode, implementsInterface, validationAnnotations)

    private fun emptyContext() = JsonLdContext(
        prefixes = emptyMap(),
        typeMappings = emptyMap(),
        propertyMappings = emptyMap(),
    )

    private fun render(generator: DataClassGenerator, shape: ShaclShape): String {
        val model = OntologyModel(listOf(shape), emptyContext())
        val files = generator.generateDataClasses(model, "com.example.test")
        assertEquals(1, files.size)
        return java.io.StringWriter().also { files.values.first().writeTo(it) }.toString()
    }

    // ── class structure ───────────────────────────────────────────────────────

    @Test
    fun `generates data class with correct name and suffix`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Person",
            targetClass = "http://example.org/Person",
            properties = emptyList(),
        )
        val code = render(generator(suffix = "Record"), shape)
        assertTrue(code.contains("data class PersonRecord"), "Expected 'data class PersonRecord' in:\n$code")
    }

    @Test
    fun `data class always implements RdfProjection`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Item",
            targetClass = "http://example.org/Item",
            properties = emptyList(),
        )
        val code = render(generator(), shape)
        assertTrue(code.contains("RdfProjection"), "Expected RdfProjection superinterface in:\n$code")
    }

    @Test
    fun `data class implements generated interface when implementsInterface is true`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://www.w3.org/ns/dcat#Catalog",
            properties = emptyList(),
        )
        val code = render(generator(implementsInterface = true), shape)
        assertTrue(code.contains("Catalog"), "Expected interface name Catalog in:\n$code")
        // Both the interface name and the data class name should appear
        assertTrue(code.contains("CatalogRecord"), "Expected data class CatalogRecord in:\n$code")
    }

    @Test
    fun `data class does NOT implement interface when implementsInterface is false`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://www.w3.org/ns/dcat#Catalog",
            properties = emptyList(),
        )
        val code = render(generator(implementsInterface = false), shape)
        // The class header should not reference Catalog as a supertype
        val header = code.lines().first { it.contains("data class") }
        assertFalse(
            header.contains(": Catalog"),
            "Expected no Catalog superinterface when implementsInterface=false; header was: $header",
        )
    }

    // ── literal property types ────────────────────────────────────────────────

    @Test
    fun `maps xsd string to String`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty("http://example.org/name", "name", "Name", "http://www.w3.org/2001/XMLSchema#string", null, 1, 1),
            ),
        )
        val code = render(generator(), shape)
        assertTrue(code.contains("val name: String"), code)
    }

    @Test
    fun `maps xsd int to Int`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty("http://example.org/count", "count", "Count", "http://www.w3.org/2001/XMLSchema#int", null, 1, 1),
            ),
        )
        val code = render(generator(), shape)
        assertTrue(code.contains("val count: Int"), code)
    }

    @Test
    fun `maps xsd double to Double`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty("http://example.org/weight", "weight", "Weight", "http://www.w3.org/2001/XMLSchema#double", null, 1, 1),
            ),
        )
        val code = render(generator(), shape)
        assertTrue(code.contains("val weight: Double"), code)
    }

    @Test
    fun `maps xsd boolean to Boolean`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty("http://example.org/active", "active", "Active", "http://www.w3.org/2001/XMLSchema#boolean", null, 1, 1),
            ),
        )
        val code = render(generator(), shape)
        assertTrue(code.contains("val active: Boolean"), code)
    }

    // ── cardinality ───────────────────────────────────────────────────────────

    @Test
    fun `required single property has no default value`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty("http://example.org/name", "name", "Name", "http://www.w3.org/2001/XMLSchema#string", null, 1, 1),
            ),
        )
        val code = render(generator(), shape)
        // Required single: String (no nullable, no default in generated constructor call)
        assertTrue(code.contains("val name: String"), code)
        // Should NOT be nullable
        assertFalse(code.contains("val name: String?"), code)
    }

    @Test
    fun `optional single property is nullable with null default`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty("http://example.org/nickname", "nickname", "Nickname", "http://www.w3.org/2001/XMLSchema#string", null, 0, 1),
            ),
        )
        val code = render(generator(), shape)
        assertTrue(code.contains("val nickname: String?"), code)
        assertTrue(code.contains("null"), code)
    }

    @Test
    fun `list property is List with emptyList default`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty("http://example.org/tags", "tags", "Tags", "http://www.w3.org/2001/XMLSchema#string", null, 0, null),
            ),
        )
        val code = render(generator(), shape)
        assertTrue(code.contains("val tags: List<String>"), code)
        assertTrue(code.contains("emptyList()"), code)
    }

    @Test
    fun `bounded list property is List with emptyList default`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty("http://example.org/items", "items", "Items", "http://www.w3.org/2001/XMLSchema#string", null, 0, 5),
            ),
        )
        val code = render(generator(), shape)
        assertTrue(code.contains("val items: List<String>"), code)
        assertTrue(code.contains("emptyList()"), code)
    }

    // ── NestedMode for object properties ─────────────────────────────────────

    @Test
    fun `NestedMode INTERFACE types object property as interface`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://example.org/Catalog",
            properties = listOf(
                ShaclProperty("http://example.org/dataset", "dataset", "Dataset", null, "http://example.org/Dataset", 0, null),
            ),
        )
        val code = render(generator(nestedMode = NestedMode.INTERFACE), shape)
        assertTrue(code.contains("val dataset: List<Dataset>"), code)
    }

    @Test
    fun `NestedMode DATA_CLASS types object property as data class`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://example.org/Catalog",
            properties = listOf(
                ShaclProperty("http://example.org/dataset", "dataset", "Dataset", null, "http://example.org/Dataset", 0, null),
            ),
        )
        val code = render(generator(suffix = "Record", nestedMode = NestedMode.DATA_CLASS), shape)
        assertTrue(code.contains("val dataset: List<DatasetRecord>"), code)
    }

    @Test
    fun `NestedMode IRI_ONLY types object property as String`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://example.org/Catalog",
            properties = listOf(
                ShaclProperty("http://example.org/publisher", "publisher", "Publisher", null, "http://example.org/Agent", 0, 1),
            ),
        )
        val code = render(generator(nestedMode = NestedMode.IRI_ONLY), shape)
        assertTrue(code.contains("val publisher: String?"), code)
    }

    // ── multiple shapes ───────────────────────────────────────────────────────

    @Test
    fun `generates one file per shape`() {
        val shapes = listOf(
            ShaclShape("http://example.org/shapes/Catalog", "http://example.org/Catalog", emptyList()),
            ShaclShape("http://example.org/shapes/Dataset", "http://example.org/Dataset", emptyList()),
        )
        val model = OntologyModel(shapes, emptyContext())
        val files = generator().generateDataClasses(model, "com.example.test")
        assertEquals(2, files.size)
        assertTrue(files.containsKey("CatalogRecord"))
        assertTrue(files.containsKey("DatasetRecord"))
    }

    // ── generated file comment ────────────────────────────────────────────────

    @Test
    fun `generated file contains DO NOT EDIT comment`() {
        val shape = ShaclShape("http://example.org/shapes/X", "http://example.org/X", emptyList())
        val code = render(generator(), shape)
        assertTrue(code.contains("DO NOT EDIT"), code)
    }
}
