package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.api.model.*
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.util.*

/**
 * Tests to ensure code generation produces deterministic output regardless of
 * the order of shapes and properties in the input.
 */
class DeterministicOutputTest {

    private lateinit var logger: KSPLogger
    private lateinit var interfaceGenerator: InterfaceGenerator
    private lateinit var wrapperGenerator: OntologyWrapperGenerator
    private lateinit var dslGenerator: InstanceDslGenerator

    @BeforeEach
    fun setup() {
        logger = object : KSPLogger {
            override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
        interfaceGenerator = InterfaceGenerator(logger, ValidationAnnotations.NONE)
        wrapperGenerator = OntologyWrapperGenerator(logger)
        dslGenerator = InstanceDslGenerator(logger)
    }

    /**
     * Creates a test ontology model with multiple shapes and properties.
     * The order of shapes and properties can be shuffled to test determinism.
     */
    private fun createTestModel(shuffleShapes: Boolean = false, shuffleProperties: Boolean = false): OntologyModel {
        val properties1 = listOf(
            ShaclProperty(
                path = "http://example.org/prop3",
                name = "prop3",
                description = "Property 3",
                datatype = "http://www.w3.org/2001/XMLSchema#string",
                targetClass = null,
                minCount = null,
                maxCount = null
            ),
            ShaclProperty(
                path = "http://example.org/prop1",
                name = "prop1",
                description = "Property 1",
                datatype = "http://www.w3.org/2001/XMLSchema#string",
                targetClass = null,
                minCount = 1,
                maxCount = 1
            ),
            ShaclProperty(
                path = "http://example.org/prop2",
                name = "prop2",
                description = "Property 2",
                datatype = "http://www.w3.org/2001/XMLSchema#string",
                targetClass = null,
                minCount = null,
                maxCount = null
            )
        )

        val properties2 = listOf(
            ShaclProperty(
                path = "http://example.org/propB",
                name = "propB",
                description = "Property B",
                datatype = "http://www.w3.org/2001/XMLSchema#string",
                targetClass = null,
                minCount = null,
                maxCount = null
            ),
            ShaclProperty(
                path = "http://example.org/propA",
                name = "propA",
                description = "Property A",
                datatype = "http://www.w3.org/2001/XMLSchema#string",
                targetClass = null,
                minCount = 1,
                maxCount = 1
            )
        )

        val shapes = listOf(
            ShaclShape(
                shapeIri = "http://example.org/shapes/ClassB",
                targetClass = "http://example.org/ClassB",
                properties = if (shuffleProperties) properties2.shuffled() else properties2
            ),
            ShaclShape(
                shapeIri = "http://example.org/shapes/ClassA",
                targetClass = "http://example.org/ClassA",
                properties = if (shuffleProperties) properties1.shuffled() else properties1
            )
        )

        val finalShapes = if (shuffleShapes) shapes.shuffled() else shapes

        val context = JsonLdContext(
            prefixes = emptyMap(),
            baseIri = null,
            vocabIri = null,
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        return OntologyModel(finalShapes, context)
    }

    private fun <T> List<T>.shuffled(): List<T> {
        val list = ArrayList(this)
        Collections.shuffle(list, Random(42)) // Fixed seed for reproducibility
        return list
    }

    @Test
    fun `interface generation produces deterministic output regardless of input order`() {
        val model1 = createTestModel(shuffleShapes = true, shuffleProperties = true)
        val model2 = createTestModel(shuffleShapes = true, shuffleProperties = true)

        val interfaces1 = interfaceGenerator.generateInterfaces(model1, "com.example.test")
        val interfaces2 = interfaceGenerator.generateInterfaces(model2, "com.example.test")

        // Convert to sorted maps for comparison
        val sorted1 = interfaces1.toSortedMap()
        val sorted2 = interfaces2.toSortedMap()

        assertEquals(sorted1.keys, sorted2.keys, "Should generate same interface names")

        // Compare generated code for each interface
        sorted1.forEach { (name, fileSpec1) ->
            val fileSpec2 = sorted2[name] ?: fail("Interface $name missing in second generation")
            
            val code1 = StringWriter().also { fileSpec1.writeTo(it) }.toString()
            val code2 = StringWriter().also { fileSpec2.writeTo(it) }.toString()
            
            assertEquals(code1, code2, "Generated code for interface $name should be identical")
        }
    }

    @Test
    fun `wrapper generation produces deterministic output regardless of input order`() {
        val model1 = createTestModel(shuffleShapes = true, shuffleProperties = true)
        val model2 = createTestModel(shuffleShapes = true, shuffleProperties = true)

        val wrappers1 = wrapperGenerator.generateWrappers(model1, "com.example.test")
        val wrappers2 = wrapperGenerator.generateWrappers(model2, "com.example.test")

        // Convert to sorted maps for comparison
        val sorted1 = wrappers1.toSortedMap()
        val sorted2 = wrappers2.toSortedMap()

        assertEquals(sorted1.keys, sorted2.keys, "Should generate same wrapper names")

        // Compare generated code for each wrapper
        sorted1.forEach { (name, fileSpec1) ->
            val fileSpec2 = sorted2[name] ?: fail("Wrapper $name missing in second generation")
            
            val code1 = StringWriter().also { fileSpec1.writeTo(it) }.toString()
            val code2 = StringWriter().also { fileSpec2.writeTo(it) }.toString()
            
            assertEquals(code1, code2, "Generated code for wrapper $name should be identical")
        }
    }

    @Test
    fun `DSL generation produces deterministic output regardless of input order`() {
        val model1 = createTestModel(shuffleShapes = true, shuffleProperties = true)
        val model2 = createTestModel(shuffleShapes = true, shuffleProperties = true)

        val request1 = InstanceDslRequest(
            dslName = "test",
            ontologyModel = model1,
            packageName = "com.example.test",
            options = DslGenerationOptions()
        )
        val request2 = InstanceDslRequest(
            dslName = "test",
            ontologyModel = model2,
            packageName = "com.example.test",
            options = DslGenerationOptions()
        )

        val fileSpec1 = dslGenerator.generate(request1)
        val fileSpec2 = dslGenerator.generate(request2)

        val code1 = StringWriter().also { fileSpec1.writeTo(it) }.toString()
        val code2 = StringWriter().also { fileSpec2.writeTo(it) }.toString()

        assertEquals(code1, code2, "Generated DSL code should be identical regardless of input order")
    }

    @Test
    fun `properties are generated in sorted order within interfaces`() {
        val model = createTestModel(shuffleShapes = false, shuffleProperties = true)
        val interfaces = interfaceGenerator.generateInterfaces(model, "com.example.test")

        val classAInterface = interfaces["ClassA"] ?: fail("ClassA interface should be generated")
        val code = StringWriter().also { classAInterface.writeTo(it) }.toString()

        // Properties should appear in sorted order (prop1, prop2, prop3)
        val prop1Index = code.indexOf("prop1")
        val prop2Index = code.indexOf("prop2")
        val prop3Index = code.indexOf("prop3")

        assertTrue(prop1Index > 0, "prop1 should be present")
        assertTrue(prop2Index > 0, "prop2 should be present")
        assertTrue(prop3Index > 0, "prop3 should be present")
        assertTrue(prop1Index < prop2Index, "prop1 should come before prop2")
        assertTrue(prop2Index < prop3Index, "prop2 should come before prop3")
    }

    @Test
    fun `shapes are generated in sorted order`() {
        val model = createTestModel(shuffleShapes = true, shuffleProperties = false)
        val interfaces = interfaceGenerator.generateInterfaces(model, "com.example.test")

        val keys = interfaces.keys.toList()
        
        // Should be sorted by targetClass IRI
        assertTrue(keys.contains("ClassA"), "Should contain ClassA")
        assertTrue(keys.contains("ClassB"), "Should contain ClassB")
        
        // When converted to sorted map, ClassA should come before ClassB
        // (http://example.org/ClassA < http://example.org/ClassB)
        val sortedKeys = interfaces.toSortedMap().keys.toList()
        assertEquals("ClassA", sortedKeys[0], "ClassA should come first")
        assertEquals("ClassB", sortedKeys[1], "ClassB should come second")
    }
}

