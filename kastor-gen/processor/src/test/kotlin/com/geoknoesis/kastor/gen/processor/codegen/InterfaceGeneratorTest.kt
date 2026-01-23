package com.geoknoesis.kastor.gen.processor.codegen

import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdProperty
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdType
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.api.model.ShaclProperty
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.rdf.Iri
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
        generator = InterfaceGenerator(logger, ValidationAnnotations.NONE)
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
            baseIri = null,
            vocabIri = null,
            typeMappings = mapOf(
                "Catalog" to Iri("http://www.w3.org/ns/dcat#Catalog"),
                "Dataset" to Iri("http://www.w3.org/ns/dcat#Dataset")
            ),
            propertyMappings = mapOf(
                "title" to JsonLdProperty(
                    id = Iri("http://purl.org/dc/terms/title"),
                    type = JsonLdType.Iri(Iri("http://www.w3.org/2001/XMLSchema#string")),
                    container = null
                ),
                "description" to JsonLdProperty(
                    id = Iri("http://purl.org/dc/terms/description"),
                    type = JsonLdType.Iri(Iri("http://www.w3.org/2001/XMLSchema#string")),
                    container = null
                ),
                "dataset" to JsonLdProperty(
                    id = Iri("http://www.w3.org/ns/dcat#dataset"),
                    type = JsonLdType.Id,
                    container = null
                )
            )
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val interfaces = generator.generateInterfaces(ontologyModel, "com.example.test")

        assertEquals(1, interfaces.size)
        assertTrue(interfaces.containsKey("Catalog"))

        val catalogCode = java.io.StringWriter().also { interfaces["Catalog"]!!.writeTo(it) }.toString()
        
        // Check package declaration
        assertTrue(catalogCode.contains("package com.example.test"))
        
        // Check imports
        assertTrue(catalogCode.contains("import com.geoknoesis.kastor.gen.annotations.RdfClass"))
        assertTrue(catalogCode.contains("import com.geoknoesis.kastor.gen.annotations.RdfProperty"))
        
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

        val testCode = java.io.StringWriter().also { interfaces["Test"]!!.writeTo(it) }.toString()
        
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

        val testCode = java.io.StringWriter().also { interfaces["Test"]!!.writeTo(it) }.toString()
        
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

        val catalogCode = java.io.StringWriter().also { interfaces["Catalog"]!!.writeTo(it) }.toString()
        
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

        val catalogCode = java.io.StringWriter().also { interfaces["Catalog"]!!.writeTo(it) }.toString()
        val datasetCode = java.io.StringWriter().also { interfaces["Dataset"]!!.writeTo(it) }.toString()

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

        val emptyCode = java.io.StringWriter().also { interfaces["Empty"]!!.writeTo(it) }.toString()
        
        assertTrue(emptyCode.contains("interface Empty"))
        // Should not contain any property declarations
        assertFalse(emptyCode.contains("@get:RdfProperty"))
        // Should not contain property declarations (check for val with type annotation)
        val hasPropertyDeclaration = emptyCode.contains(Regex("""val\s+\w+\s*:"""))
        assertFalse(hasPropertyDeclaration, "Empty interface should not contain property declarations")
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

        val testCode = java.io.StringWriter().also { interfaces["Test"]!!.writeTo(it) }.toString()
        
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

        val catalogCode = java.io.StringWriter().also { interfaces["Catalog"]!!.writeTo(it) }.toString()
        
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














