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

class OntologyWrapperGeneratorTest {

    private lateinit var logger: KSPLogger
    private lateinit var generator: OntologyWrapperGenerator

    @BeforeEach
    fun setup() {
        logger = object : KSPLogger {
            override fun logging(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun error(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
        generator = OntologyWrapperGenerator(logger)
    }

    @Test
    fun `generateWrappers creates catalog wrapper`() {
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
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        assertEquals(1, wrappers.size)
        assertTrue(wrappers.containsKey("CatalogWrapper"))

        val catalogCode = wrappers["CatalogWrapper"]!!
        
        // Check package declaration
        assertTrue(catalogCode.contains("package com.example.test"))
        
        // Check imports
        assertTrue(catalogCode.contains("import com.example.ontomapper.runtime.*"))
        assertTrue(catalogCode.contains("import com.geoknoesis.kastor.rdf.*"))
        
        // Check class declaration
        assertTrue(catalogCode.contains("internal class CatalogWrapper("))
        assertTrue(catalogCode.contains("override val rdf: RdfHandle"))
        assertTrue(catalogCode.contains(") : Catalog, RdfBacked {"))
        
        // Check known predicates
        assertTrue(catalogCode.contains("private val known: Set<Iri> = setOf("))
        assertTrue(catalogCode.contains("Iri(\"http://purl.org/dc/terms/title\")"))
        assertTrue(catalogCode.contains("Iri(\"http://purl.org/dc/terms/description\")"))
        assertTrue(catalogCode.contains("Iri(\"http://www.w3.org/ns/dcat#dataset\")"))
        
        // Check property implementations
        assertTrue(catalogCode.contains("override val title: String by lazy {"))
        assertTrue(catalogCode.contains("KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"http://purl.org/dc/terms/title\"))"))
        assertTrue(catalogCode.contains(".map { it.lexical }.firstOrNull() ?: \"\""))
        
        assertTrue(catalogCode.contains("override val description: String by lazy {"))
        assertTrue(catalogCode.contains("KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"http://purl.org/dc/terms/description\"))"))
        
        assertTrue(catalogCode.contains("override val dataset: List<Dataset> by lazy {"))
        assertTrue(catalogCode.contains("KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(\"http://www.w3.org/ns/dcat#dataset\"))"))
        assertTrue(catalogCode.contains("OntoMapper.materialize(RdfRef(child, rdf.graph), Dataset::class.java, false)"))
        
        // Check companion object
        assertTrue(catalogCode.contains("companion object {"))
        assertTrue(catalogCode.contains("OntoMapper.registry[Catalog::class.java] = { handle -> CatalogWrapper(handle) }"))
    }

    @Test
    fun `generateWrappers handles different datatypes`() {
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
                )
            )
        )

        val context = JsonLdContext(
            prefixes = emptyMap(),
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(listOf(shape), context)
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        val testCode = wrappers["TestWrapper"]!!
        
        // Check string property
        assertTrue(testCode.contains("override val stringProp: String by lazy {"))
        assertTrue(testCode.contains("KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"http://example.org/stringProp\"))"))
        assertTrue(testCode.contains(".map { it.lexical }.firstOrNull() ?: \"\""))
        
        // Check int property
        assertTrue(testCode.contains("override val intProp: Int by lazy {"))
        assertTrue(testCode.contains("KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"http://example.org/intProp\"))"))
        assertTrue(testCode.contains(".map { it.lexical }.mapNotNull { it.toIntOrNull() }.firstOrNull() ?: 0"))
        
        // Check boolean property
        assertTrue(testCode.contains("override val booleanProp: Boolean by lazy {"))
        assertTrue(testCode.contains("KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"http://example.org/booleanProp\"))"))
        assertTrue(testCode.contains(".map { it.lexical }.mapNotNull { it.toBooleanStrictOrNull() }.firstOrNull() ?: false"))
        
        // Check double property
        assertTrue(testCode.contains("override val doubleProp: Double by lazy {"))
        assertTrue(testCode.contains("KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"http://example.org/doubleProp\"))"))
        assertTrue(testCode.contains(".map { it.lexical }.mapNotNull { it.toDoubleOrNull() }.firstOrNull() ?: 0.0"))
    }

    @Test
    fun `generateWrappers handles cardinality constraints`() {
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
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        val testCode = wrappers["TestWrapper"]!!
        
        // Single value property
        assertTrue(testCode.contains("override val singleProp: String by lazy {"))
        assertTrue(testCode.contains(".map { it.lexical }.firstOrNull() ?: \"\""))
        
        // Multiple value property
        assertTrue(testCode.contains("override val multipleProp: List<String> by lazy {"))
        assertTrue(testCode.contains(".map { it.lexical }"))
        
        // Unbounded property
        assertTrue(testCode.contains("override val unboundedProp: List<String> by lazy {"))
        assertTrue(testCode.contains(".map { it.lexical }"))
    }

    @Test
    fun `generateWrappers handles object properties`() {
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
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        val catalogCode = wrappers["CatalogWrapper"]!!
        
        // List object property
        assertTrue(catalogCode.contains("override val dataset: List<Dataset> by lazy {"))
        assertTrue(catalogCode.contains("KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(\"http://www.w3.org/ns/dcat#dataset\"))"))
        assertTrue(catalogCode.contains("OntoMapper.materialize(RdfRef(child, rdf.graph), Dataset::class.java, false)"))
        
        // Single object property
        assertTrue(catalogCode.contains("override val publisher: Agent by lazy {"))
        assertTrue(catalogCode.contains("KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(\"http://purl.org/dc/terms/publisher\"))"))
        assertTrue(catalogCode.contains("OntoMapper.materialize(RdfRef(child, rdf.graph), Agent::class.java, false)"))
        assertTrue(catalogCode.contains(".firstOrNull() ?: error(\"Required object publisher missing\")"))
    }

    @Test
    fun `generateWrappers handles multiple shapes`() {
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
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        assertEquals(2, wrappers.size)
        assertTrue(wrappers.containsKey("CatalogWrapper"))
        assertTrue(wrappers.containsKey("DatasetWrapper"))

        val catalogCode = wrappers["CatalogWrapper"]!!
        val datasetCode = wrappers["DatasetWrapper"]!!

        assertTrue(catalogCode.contains("internal class CatalogWrapper("))
        assertTrue(datasetCode.contains("internal class DatasetWrapper("))
    }

    @Test
    fun `generateWrappers handles empty properties`() {
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
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        val emptyCode = wrappers["EmptyWrapper"]!!
        
        assertTrue(emptyCode.contains("internal class EmptyWrapper("))
        assertTrue(emptyCode.contains("override val rdf: RdfHandle"))
        assertTrue(emptyCode.contains(") : Empty, RdfBacked {"))
        assertTrue(emptyCode.contains("private val known: Set<Iri> = setOf("))
        assertTrue(emptyCode.contains(")"))
        assertTrue(emptyCode.contains("companion object {"))
        assertTrue(emptyCode.contains("OntoMapper.registry[Empty::class.java] = { handle -> EmptyWrapper(handle) }"))
    }

    @Test
    fun `generateWrappers includes proper documentation`() {
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
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        val catalogCode = wrappers["CatalogWrapper"]!!
        
        // Check class documentation
        assertTrue(catalogCode.contains("RDF-backed wrapper for Catalog"))
        assertTrue(catalogCode.contains("Generated from SHACL shape: http://example.org/shapes/Catalog"))
        
        // Check property documentation
        assertTrue(catalogCode.contains("A name given to the catalog."))
        assertTrue(catalogCode.contains("Path: http://purl.org/dc/terms/title"))
    }

    @Test
    fun `generateWrappers handles unknown datatypes`() {
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
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        val testCode = wrappers["TestWrapper"]!!
        
        // Unknown datatypes should default to String
        assertTrue(testCode.contains("override val unknownProp: String by lazy {"))
        assertTrue(testCode.contains("KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"http://example.org/unknownProp\"))"))
        assertTrue(testCode.contains(".map { it.lexical }.firstOrNull() ?: \"\""))
    }

    @Test
    fun `generateWrappers handles interface name extraction`() {
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
        val wrappers = generator.generateWrappers(ontologyModel, "com.example.test")

        assertEquals(3, wrappers.size)
        assertTrue(wrappers.containsKey("CatalogWrapper"))
        assertTrue(wrappers.containsKey("DatasetWrapper"))
        assertTrue(wrappers.containsKey("DataDistributionWrapper"))
    }
}
