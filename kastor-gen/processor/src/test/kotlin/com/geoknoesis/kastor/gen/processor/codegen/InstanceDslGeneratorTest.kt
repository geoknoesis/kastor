package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.api.model.*
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InstanceDslGeneratorTest {

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

    @Test
    fun `generate creates DSL file with correct structure`() {
        val classes = listOf(
            OntologyClass(
                className = "Concept",
                classIri = "http://www.w3.org/2004/02/skos/core#Concept",
                superClasses = emptyList()
            )
        )

        val shapes = listOf(
            ShaclShape(
                shapeIri = "http://example.org/shapes/Concept",
                targetClass = "http://www.w3.org/2004/02/skos/core#Concept",
                properties = listOf(
                    ShaclProperty(
                        path = "http://www.w3.org/2004/02/skos/core#prefLabel",
                        name = "prefLabel",
                        description = "Preferred label",
                        datatype = "http://www.w3.org/2001/XMLSchema#string",
                        targetClass = null,
                        minCount = 1,
                        maxCount = 1
                    )
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            baseIri = null,
            vocabIri = null,
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(shapes, context)
        val request = InstanceDslRequest(
            dslName = "skos",
            ontologyModel = ontologyModel,
            packageName = "com.example.test",
            options = DslGenerationOptions()
        )
        val fileSpec = generator.generate(request)

        assertNotNull(fileSpec)
        assertEquals("com.example.test", fileSpec.packageName)
        assertEquals("SkosDsl", fileSpec.name)

        val writer = java.io.StringWriter()
        fileSpec.writeTo(writer)
        val code = writer.toString()
        
        // Check package declaration
        assertTrue(code.contains("package com.example.test"))
        
        // Check imports
        assertTrue(code.contains("import com.geoknoesis.kastor.rdf"))
        assertTrue(code.contains("import com.geoknoesis.kastor.rdf.provider.MemoryGraph"))
        assertTrue(code.contains("import com.geoknoesis.kastor.gen.runtime.ValidationException"))
        
        // Check top-level DSL function
        assertTrue(code.contains("fun skos("))
        assertTrue(code.contains("SkosDsl"))
        
        // Check main DSL class
        assertTrue(code.contains("class SkosDsl"))
        assertTrue(code.contains("private val graph: MemoryGraph"))
        assertTrue(code.contains("private val instances: MutableList<RdfResource>"))
        
        // Check builder method
        assertTrue(code.contains("fun concept("))
        
        // Check builder class
        assertTrue(code.contains("class ConceptBuilder"))
    }

    @Test
    fun `generate creates builder methods for all classes`() {
        val classes = listOf(
            OntologyClass(
                className = "Concept",
                classIri = "http://www.w3.org/2004/02/skos/core#Concept",
                superClasses = emptyList()
            ),
            OntologyClass(
                className = "ConceptScheme",
                classIri = "http://www.w3.org/2004/02/skos/core#ConceptScheme",
                superClasses = emptyList()
            )
        )

        val shapes = listOf(
            ShaclShape(
                shapeIri = "http://example.org/shapes/Concept",
                targetClass = "http://www.w3.org/2004/02/skos/core#Concept",
                properties = emptyList()
            ),
            ShaclShape(
                shapeIri = "http://example.org/shapes/ConceptScheme",
                targetClass = "http://www.w3.org/2004/02/skos/core#ConceptScheme",
                properties = emptyList()
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            baseIri = null,
            vocabIri = null,
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(shapes, context)
        val request = InstanceDslRequest(
            dslName = "skos",
            ontologyModel = ontologyModel,
            packageName = "com.example.test"
        )
        val fileSpec = generator.generate(request)

        val writer = java.io.StringWriter()
        fileSpec.writeTo(writer)
        val code = writer.toString()
        
        assertTrue(code.contains("fun concept("))
        assertTrue(code.contains("fun conceptScheme("))
        assertTrue(code.contains("class ConceptBuilder"))
        assertTrue(code.contains("class ConceptSchemeBuilder"))
    }

    @Test
    fun `generate skips classes without matching shapes`() {
        val classes = listOf(
            OntologyClass(
                className = "Concept",
                classIri = "http://www.w3.org/2004/02/skos/core#Concept",
                superClasses = emptyList()
            ),
            OntologyClass(
                className = "ConceptScheme",
                classIri = "http://www.w3.org/2004/02/skos/core#ConceptScheme",
                superClasses = emptyList()
            )
        )

        val shapes = listOf(
            ShaclShape(
                shapeIri = "http://example.org/shapes/Concept",
                targetClass = "http://www.w3.org/2004/02/skos/core#Concept",
                properties = emptyList()
            )
            // No shape for ConceptScheme
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            baseIri = null,
            vocabIri = null,
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(shapes, context)
        val request = InstanceDslRequest(
            dslName = "skos",
            ontologyModel = ontologyModel,
            packageName = "com.example.test"
        )
        val fileSpec = generator.generate(request)

        val writer = java.io.StringWriter()
        fileSpec.writeTo(writer)
        val code = writer.toString()
        
        assertTrue(code.contains("fun concept("))
        assertTrue(code.contains("class ConceptBuilder"))
        assertFalse(code.contains("fun conceptScheme("))
        assertFalse(code.contains("class ConceptSchemeBuilder"))
    }

    @Test
    fun `generate includes validation when option is enabled`() {
        val classes = listOf(
            OntologyClass(
                className = "Concept",
                classIri = "http://www.w3.org/2004/02/skos/core#Concept",
                superClasses = emptyList()
            )
        )

        val shapes = listOf(
            ShaclShape(
                shapeIri = "http://example.org/shapes/Concept",
                targetClass = "http://www.w3.org/2004/02/skos/core#Concept",
                properties = listOf(
                    ShaclProperty(
                        path = "http://www.w3.org/2004/02/skos/core#prefLabel",
                        name = "prefLabel",
                        description = "Preferred label",
                        datatype = "http://www.w3.org/2001/XMLSchema#string",
                        targetClass = null,
                        minCount = 1,
                        maxCount = 1
                    )
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            baseIri = null,
            vocabIri = null,
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(shapes, context)
        val request = InstanceDslRequest(
            dslName = "skos",
            ontologyModel = ontologyModel,
            packageName = "com.example.test",
            options = DslGenerationOptions(validation = DslGenerationOptions.ValidationConfig(enabled = true))
        )
        val fileSpec = generator.generate(request)

        val writer = java.io.StringWriter()
        fileSpec.writeTo(writer)
        val code = writer.toString()
        
        assertTrue(code.contains("builder.validate()"))
        assertTrue(code.contains("fun validate()"))
    }

    @Test
    fun `generate excludes validation when option is disabled`() {
        val classes = listOf(
            OntologyClass(
                className = "Concept",
                classIri = "http://www.w3.org/2004/02/skos/core#Concept",
                superClasses = emptyList()
            )
        )

        val shapes = listOf(
            ShaclShape(
                shapeIri = "http://example.org/shapes/Concept",
                targetClass = "http://www.w3.org/2004/02/skos/core#Concept",
                properties = listOf(
                    ShaclProperty(
                        path = "http://www.w3.org/2004/02/skos/core#prefLabel",
                        name = "prefLabel",
                        description = "Preferred label",
                        datatype = "http://www.w3.org/2001/XMLSchema#string",
                        targetClass = null,
                        minCount = 1,
                        maxCount = 1
                    )
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            baseIri = null,
            vocabIri = null,
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(shapes, context)
        val request = InstanceDslRequest(
            dslName = "skos",
            ontologyModel = ontologyModel,
            packageName = "com.example.test",
            options = DslGenerationOptions(validation = DslGenerationOptions.ValidationConfig(enabled = false))
        )
        val fileSpec = generator.generate(request)

        val writer = java.io.StringWriter()
        fileSpec.writeTo(writer)
        val code = writer.toString()
        
        assertFalse(code.contains("builder.validate()"))
        assertFalse(code.contains("fun validate()"))
    }

    @Test
    fun `generate creates build and instances methods`() {
        val classes = listOf(
            OntologyClass(
                className = "Concept",
                classIri = "http://www.w3.org/2004/02/skos/core#Concept",
                superClasses = emptyList()
            )
        )

        val shapes = listOf(
            ShaclShape(
                shapeIri = "http://example.org/shapes/Concept",
                targetClass = "http://www.w3.org/2004/02/skos/core#Concept",
                properties = emptyList()
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            baseIri = null,
            vocabIri = null,
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(shapes, context)
        val request = InstanceDslRequest(
            dslName = "skos",
            ontologyModel = ontologyModel,
            packageName = "com.example.test"
        )
        val fileSpec = generator.generate(request)

        val writer = java.io.StringWriter()
        fileSpec.writeTo(writer)
        val code = writer.toString()
        
        assertTrue(code.contains("fun build()"))
        assertTrue(code.contains("fun instances()"))
        assertTrue(code.contains("return") && code.contains("graph"))
        assertTrue(code.contains("return") && code.contains("instances.toList()"))
    }

    @Test
    fun `generate handles empty classes list`() {
        val shapes = emptyList<ShaclShape>()
        val context = JsonLdContext(
            prefixes = emptyMap(),
            baseIri = null,
            vocabIri = null,
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(shapes, context)
        val request = InstanceDslRequest(
            dslName = "skos",
            ontologyModel = ontologyModel,
            packageName = "com.example.test"
        )
        val fileSpec = generator.generate(request)

        assertNotNull(fileSpec)
        val writer = java.io.StringWriter()
        fileSpec.writeTo(writer)
        val code = writer.toString()
        
        // Should still create the main DSL class structure
        assertTrue(code.contains("class SkosDsl"))
        assertTrue(code.contains("fun build()"))
        // But no builder methods
        assertFalse(code.contains("fun concept("))
    }
}


