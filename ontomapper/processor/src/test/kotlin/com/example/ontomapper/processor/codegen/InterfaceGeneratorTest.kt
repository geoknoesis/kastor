package com.example.ontomapper.processor.codegen

import com.example.ontomapper.processor.model.JsonLdContext
import com.example.ontomapper.processor.model.JsonLdProperty
import com.example.ontomapper.processor.model.OntologyModel
import com.example.ontomapper.processor.model.ShaclProperty
import com.example.ontomapper.processor.model.ShaclShape
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InterfaceGeneratorTest {

    private lateinit var logger: KSPLogger
    private lateinit var generator: InterfaceGenerator

    @BeforeEach
    fun setup() {
        logger = object : KSPLogger {
            override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
        generator = InterfaceGenerator(logger)
    }

    @Test
    fun `generateInterfaces creates catalog interface`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://www.w3.org/ns/dcat#Catalog",
            properties = listOf(
                ShaclProperty(
                    path = "http://purl.org/dc/terms/title",
                    name = "title",
                    description = "A name given to the catalog.",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                ),
                ShaclProperty(
                    path = "http://purl.org/dc/terms/description",
                    name = "description",
                    description = "A free-text account of the catalog.",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 0,
                    maxCount = 1
                ),
                ShaclProperty(
                    path = "http://www.w3.org/ns/dcat#dataset",
                    name = "dataset",
                    description = "A collection of data that is listed in the catalog.",
                    datatype = null,
                    targetClass = "http://www.w3.org/ns/dcat#Dataset",
                    minCount = 0,
                    maxCount = null
                )
            )
        )

        val context = JsonLdContext(
            prefixes = mapOf(
                "dcat" to "http://www.w3.org/ns/dcat#",
                "dcterms" to "http://purl.org/dc/terms/"
            ),
            typeMappings = mapOf(
                "Catalog" to "http://www.w3.org/ns/dcat#Catalog",
                "Dataset" to "http://www.w3.org/ns/dcat#Dataset"
            ),
            propertyMappings = mapOf(
                "title" to JsonLdProperty("http://purl.org/dc/terms/title", "http://www.w3.org/2001/XMLSchema#string"),
                "description" to JsonLdProperty("http://purl.org/dc/terms/description", "http://www.w3.org/2001/XMLSchema#string"),
                "dataset" to JsonLdProperty("http://www.w3.org/ns/dcat#dataset", "@id")
            )
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val interfaces = generator.generateInterfaces(ontologyModel, "com.example.test")

        assertEquals(1, interfaces.size)
        assertTrue(interfaces.containsKey("Catalog"))

        val catalogCode = interfaces["Catalog"]!!
        
        // Check package declaration
        assertTrue(catalogCode.contains("package com.example.test"))
        
        // Check imports
        assertTrue(catalogCode.contains("import com.example.ontomapper.annotations.RdfClass"))
        assertTrue(catalogCode.contains("import com.example.ontomapper.annotations.RdfProperty"))
        
        // Check interface declaration
        assertTrue(catalogCode.contains("@RdfClass(iri = \"http://www.w3.org/ns/dcat#Catalog\")"))
        assertTrue(catalogCode.contains("interface Catalog {"))
        
        // Check properties
        assertTrue(catalogCode.contains("@get:RdfProperty(iri = \"http://purl.org/dc/terms/title\")"))
        assertTrue(catalogCode.contains("val title: String"))
        
        assertTrue(catalogCode.contains("@get:RdfProperty(iri = \"http://purl.org/dc/terms/description\")"))
        assertTrue(catalogCode.contains("val description: String"))
        
        assertTrue(catalogCode.contains("@get:RdfProperty(iri = \"http://www.w3.org/ns/dcat#dataset\")"))
        assertTrue(catalogCode.contains("val dataset: List<Dataset>"))
        
        // Check documentation
        assertTrue(catalogCode.contains("A name given to the catalog."))
        assertTrue(catalogCode.contains("A free-text account of the catalog."))
        assertTrue(catalogCode.contains("A collection of data that is listed in the catalog."))
    }

    @Test
    fun `generateInterfaces handles different datatypes`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty(
                    path = "http://example.org/stringProp",
                    name = "stringProp",
                    description = "String property",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                ),
                ShaclProperty(
                    path = "http://example.org/intProp",
                    name = "intProp",
                    description = "Integer property",
                    datatype = "http://www.w3.org/2001/XMLSchema#int",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                ),
                ShaclProperty(
                    path = "http://example.org/booleanProp",
                    name = "booleanProp",
                    description = "Boolean property",
                    datatype = "http://www.w3.org/2001/XMLSchema#boolean",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                ),
                ShaclProperty(
                    path = "http://example.org/doubleProp",
                    name = "doubleProp",
                    description = "Double property",
                    datatype = "http://www.w3.org/2001/XMLSchema#double",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                ),
                ShaclProperty(
                    path = "http://example.org/anyURIProp",
                    name = "anyURIProp",
                    description = "URI property",
                    datatype = "http://www.w3.org/2001/XMLSchema#anyURI",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val interfaces = generator.generateInterfaces(ontologyModel, "com.example.test")

        val testCode = interfaces["Test"]!!
        
        assertTrue(testCode.contains("val stringProp: String"))
        assertTrue(testCode.contains("val intProp: Int"))
        assertTrue(testCode.contains("val booleanProp: Boolean"))
        assertTrue(testCode.contains("val doubleProp: Double"))
        assertTrue(testCode.contains("val anyURIProp: String")) // anyURI maps to String
    }

    @Test
    fun `generateInterfaces handles cardinality constraints`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty(
                    path = "http://example.org/singleProp",
                    name = "singleProp",
                    description = "Single value property",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                ),
                ShaclProperty(
                    path = "http://example.org/multipleProp",
                    name = "multipleProp",
                    description = "Multiple value property",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 0,
                    maxCount = 5
                ),
                ShaclProperty(
                    path = "http://example.org/unboundedProp",
                    name = "unboundedProp",
                    description = "Unbounded property",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 0,
                    maxCount = null
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val interfaces = generator.generateInterfaces(ontologyModel, "com.example.test")

        val testCode = interfaces["Test"]!!
        
        assertTrue(testCode.contains("val singleProp: String"))
        assertTrue(testCode.contains("val multipleProp: List<String>"))
        assertTrue(testCode.contains("val unboundedProp: List<String>"))
    }

    @Test
    fun `generateInterfaces handles object properties`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://www.w3.org/ns/dcat#Catalog",
            properties = listOf(
                ShaclProperty(
                    path = "http://www.w3.org/ns/dcat#dataset",
                    name = "dataset",
                    description = "Dataset property",
                    datatype = null,
                    targetClass = "http://www.w3.org/ns/dcat#Dataset",
                    minCount = 0,
                    maxCount = null
                ),
                ShaclProperty(
                    path = "http://purl.org/dc/terms/publisher",
                    name = "publisher",
                    description = "Publisher property",
                    datatype = null,
                    targetClass = "http://xmlns.com/foaf/0.1/Agent",
                    minCount = 0,
                    maxCount = 1
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val interfaces = generator.generateInterfaces(ontologyModel, "com.example.test")

        val catalogCode = interfaces["Catalog"]!!
        
        assertTrue(catalogCode.contains("val dataset: List<Dataset>"))
        assertTrue(catalogCode.contains("val publisher: Agent"))
    }

    @Test
    fun `generateInterfaces handles multiple shapes`() {
        val shapes = listOf(
            ShaclShape(
                shapeIri = "http://example.org/shapes/Catalog",
                targetClass = "http://www.w3.org/ns/dcat#Catalog",
                properties = listOf(
                    ShaclProperty(
                        path = "http://purl.org/dc/terms/title",
                        name = "title",
                        description = "Title",
                        datatype = "http://www.w3.org/2001/XMLSchema#string",
                        targetClass = null,
                        minCount = 1,
                        maxCount = 1
                    )
                )
            ),
            ShaclShape(
                shapeIri = "http://example.org/shapes/Dataset",
                targetClass = "http://www.w3.org/ns/dcat#Dataset",
                properties = listOf(
                    ShaclProperty(
                        path = "http://purl.org/dc/terms/title",
                        name = "title",
                        description = "Title",
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
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(shapes, context)
        val interfaces = generator.generateInterfaces(ontologyModel, "com.example.test")

        assertEquals(2, interfaces.size)
        assertTrue(interfaces.containsKey("Catalog"))
        assertTrue(interfaces.containsKey("Dataset"))

        val catalogCode = interfaces["Catalog"]!!
        val datasetCode = interfaces["Dataset"]!!

        assertTrue(catalogCode.contains("interface Catalog {"))
        assertTrue(datasetCode.contains("interface Dataset {"))
    }

    @Test
    fun `generateInterfaces handles empty properties`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Empty",
            targetClass = "http://example.org/Empty",
            properties = emptyList()
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val interfaces = generator.generateInterfaces(ontologyModel, "com.example.test")

        val emptyCode = interfaces["Empty"]!!
        
        assertTrue(emptyCode.contains("interface Empty {"))
        assertTrue(emptyCode.contains("}"))
        // Should not contain any property declarations
        assertFalse(emptyCode.contains("@get:RdfProperty"))
        assertFalse(emptyCode.contains("val "))
    }

    @Test
    fun `generateInterfaces handles unknown datatypes`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Test",
            targetClass = "http://example.org/Test",
            properties = listOf(
                ShaclProperty(
                    path = "http://example.org/unknownProp",
                    name = "unknownProp",
                    description = "Unknown type property",
                    datatype = "http://example.org/unknownType",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val interfaces = generator.generateInterfaces(ontologyModel, "com.example.test")

        val testCode = interfaces["Test"]!!
        
        // Unknown datatypes should default to String
        assertTrue(testCode.contains("val unknownProp: String"))
    }

    @Test
    fun `generateInterfaces includes proper documentation`() {
        val shape = ShaclShape(
            shapeIri = "http://example.org/shapes/Catalog",
            targetClass = "http://www.w3.org/ns/dcat#Catalog",
            properties = listOf(
                ShaclProperty(
                    path = "http://purl.org/dc/terms/title",
                    name = "title",
                    description = "A name given to the catalog.",
                    datatype = "http://www.w3.org/2001/XMLSchema#string",
                    targetClass = null,
                    minCount = 1,
                    maxCount = 1
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val interfaces = generator.generateInterfaces(ontologyModel, "com.example.test")

        val catalogCode = interfaces["Catalog"]!!
        
        // Check interface documentation
        assertTrue(catalogCode.contains("Domain interface for http://www.w3.org/ns/dcat#Catalog"))
        assertTrue(catalogCode.contains("Pure domain interface with no RDF dependencies."))
        assertTrue(catalogCode.contains("Generated from SHACL shape: http://example.org/shapes/Catalog"))
        
        // Check property documentation
        assertTrue(catalogCode.contains("A name given to the catalog."))
        assertTrue(catalogCode.contains("Path: http://purl.org/dc/terms/title"))
        assertTrue(catalogCode.contains("Min count: 1"))
        assertTrue(catalogCode.contains("Max count: 1"))
    }

    @Test
    fun `generateInterfaces handles interface name extraction`() {
        val shapes = listOf(
            ShaclShape(
                shapeIri = "http://example.org/shapes/Catalog",
                targetClass = "http://www.w3.org/ns/dcat#Catalog",
                properties = emptyList()
            ),
            ShaclShape(
                shapeIri = "http://example.org/shapes/Dataset",
                targetClass = "http://www.w3.org/ns/dcat#Dataset",
                properties = emptyList()
            ),
            ShaclShape(
                shapeIri = "http://example.org/shapes/DataDistribution",
                targetClass = "http://www.w3.org/ns/dcat#DataDistribution",
                properties = emptyList()
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(shapes, context)
        val interfaces = generator.generateInterfaces(ontologyModel, "com.example.test")

        assertEquals(3, interfaces.size)
        assertTrue(interfaces.containsKey("Catalog"))
        assertTrue(interfaces.containsKey("Dataset"))
        assertTrue(interfaces.containsKey("DataDistribution"))
    }
}
