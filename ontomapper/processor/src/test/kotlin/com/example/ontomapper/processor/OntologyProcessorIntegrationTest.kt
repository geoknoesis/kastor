package com.example.ontomapper.processor

import com.example.ontomapper.processor.codegen.InterfaceGenerator
import com.example.ontomapper.processor.codegen.OntologyWrapperGenerator
import com.example.ontomapper.processor.model.OntologyModel
import com.example.ontomapper.processor.parsers.JsonLdContextParser
import com.example.ontomapper.processor.parsers.ShaclParser
import com.example.ontomapper.annotations.ValidationAnnotations
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class OntologyProcessorIntegrationTest {

    private lateinit var logger: KSPLogger
    private lateinit var shaclParser: ShaclParser
    private lateinit var contextParser: JsonLdContextParser
    private lateinit var interfaceGenerator: InterfaceGenerator
    private lateinit var wrapperGenerator: OntologyWrapperGenerator

    @BeforeEach
    fun setup() {
        logger = object : KSPLogger {
            override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
        shaclParser = ShaclParser(logger)
        contextParser = JsonLdContextParser(logger)
        interfaceGenerator = InterfaceGenerator(logger, ValidationAnnotations.NONE)
        wrapperGenerator = OntologyWrapperGenerator(logger)
    }

    @Test
    fun `complete DCAT ontology generation workflow`() {
        // SHACL content for DCAT Catalog and Dataset
        val shaclContent = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            <http://example.org/shapes/Catalog>
                a sh:NodeShape ;
                sh:targetClass dcat:Catalog ;
                sh:property [
                    sh:path dcterms:title ;
                    sh:name "title" ;
                    sh:description "A name given to the catalog." ;
                    sh:datatype xsd:string ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path dcterms:description ;
                    sh:name "description" ;
                    sh:description "A free-text account of the catalog." ;
                    sh:datatype xsd:string ;
                    sh:minCount 0 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path dcat:dataset ;
                    sh:name "dataset" ;
                    sh:description "A collection of data that is listed in the catalog." ;
                    sh:class dcat:Dataset ;
                    sh:minCount 0 ;
                ] .

            <http://example.org/shapes/Dataset>
                a sh:NodeShape ;
                sh:targetClass dcat:Dataset ;
                sh:property [
                    sh:path dcterms:title ;
                    sh:name "title" ;
                    sh:description "A name given to the dataset." ;
                    sh:datatype xsd:string ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path dcterms:description ;
                    sh:name "description" ;
                    sh:description "A free-text account of the dataset." ;
                    sh:datatype xsd:string ;
                    sh:minCount 0 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path dcat:distribution ;
                    sh:name "distribution" ;
                    sh:description "An available distribution of the dataset." ;
                    sh:class dcat:Distribution ;
                    sh:minCount 0 ;
                ] .
        """.trimIndent()

        // JSON-LD context content
        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "dcterms": "http://purl.org/dc/terms/",
                "xsd": "http://www.w3.org/2001/XMLSchema#",
                
                "Catalog": "dcat:Catalog",
                "Dataset": "dcat:Dataset",
                "Distribution": "dcat:Distribution",
                
                "title": {
                  "@id": "dcterms:title",
                  "@type": "xsd:string"
                },
                "description": {
                  "@id": "dcterms:description",
                  "@type": "xsd:string"
                },
                "dataset": {
                  "@id": "dcat:dataset",
                  "@type": "@id"
                },
                "distribution": {
                  "@id": "dcat:distribution",
                  "@type": "@id"
                }
              }
            }
        """.trimIndent()

        // Parse SHACL
        val shapes = shaclParser.parseShaclContent(shaclContent)
        assertEquals(2, shapes.size)

        // Parse JSON-LD context
        val context = contextParser.parseContextContent(contextContent)
        assertEquals(3, context.prefixes.size)
        assertEquals(3, context.typeMappings.size)
        assertEquals(4, context.propertyMappings.size)

        // Create ontology model
        val ontologyModel = OntologyModel(shapes, context)

        // Generate interfaces
        val interfaces = interfaceGenerator.generateInterfaces(ontologyModel, "com.example.dcatus.generated")
        assertEquals(2, interfaces.size)
        assertTrue(interfaces.containsKey("Catalog"))
        assertTrue(interfaces.containsKey("Dataset"))

        // Generate wrappers
        val wrappers = wrapperGenerator.generateWrappers(ontologyModel, "com.example.dcatus.generated")
        assertEquals(2, wrappers.size)
        assertTrue(wrappers.containsKey("CatalogWrapper"))
        assertTrue(wrappers.containsKey("DatasetWrapper"))

        // Verify Catalog interface
        val catalogInterface = interfaces["Catalog"]!!
        assertTrue(catalogInterface.contains("@RdfClass(iri = \"http://www.w3.org/ns/dcat#Catalog\")"))
        assertTrue(catalogInterface.contains("interface Catalog {"))
        assertTrue(catalogInterface.contains("val title: String"))
        assertTrue(catalogInterface.contains("val description: String"))
        assertTrue(catalogInterface.contains("val dataset: List<Dataset>"))

        // Verify Catalog wrapper
        val catalogWrapper = wrappers["CatalogWrapper"]!!
        assertTrue(catalogWrapper.contains("internal class CatalogWrapper("))
        assertTrue(catalogWrapper.contains(") : Catalog, RdfBacked {"))
        assertTrue(catalogWrapper.contains("override val title: String by lazy {"))
        assertTrue(catalogWrapper.contains("override val dataset: List<Dataset> by lazy {"))
        assertTrue(catalogWrapper.contains("OntoMapper.registry[Catalog::class.java]"))

        // Verify Dataset interface
        val datasetInterface = interfaces["Dataset"]!!
        assertTrue(datasetInterface.contains("@RdfClass(iri = \"http://www.w3.org/ns/dcat#Dataset\")"))
        assertTrue(datasetInterface.contains("interface Dataset {"))
        assertTrue(datasetInterface.contains("val title: String"))
        assertTrue(datasetInterface.contains("val description: String"))
        assertTrue(datasetInterface.contains("val distribution: List<Distribution>"))

        // Verify Dataset wrapper
        val datasetWrapper = wrappers["DatasetWrapper"]!!
        assertTrue(datasetWrapper.contains("internal class DatasetWrapper("))
        assertTrue(datasetWrapper.contains(") : Dataset, RdfBacked {"))
        assertTrue(datasetWrapper.contains("override val title: String by lazy {"))
        assertTrue(datasetWrapper.contains("override val distribution: List<Distribution> by lazy {"))
        assertTrue(datasetWrapper.contains("OntoMapper.registry[Dataset::class.java]"))
    }

    @Test
    fun `ontology generation handles complex datatypes and cardinalities`() {
        val shaclContent = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            <http://example.org/shapes/ComplexTest>
                a sh:NodeShape ;
                sh:targetClass dcat:Catalog ;
                sh:property [
                    sh:path dcterms:title ;
                    sh:name "title" ;
                    sh:datatype xsd:string ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path <http://example.org/keywords> ;
                    sh:name "keywords" ;
                    sh:datatype xsd:string ;
                    sh:minCount 0 ;
                ] ;
                sh:property [
                    sh:path <http://example.org/score> ;
                    sh:name "score" ;
                    sh:datatype xsd:double ;
                    sh:minCount 0 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path <http://example.org/isActive> ;
                    sh:name "isActive" ;
                    sh:datatype xsd:boolean ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path <http://example.org/itemCount> ;
                    sh:name "itemCount" ;
                    sh:datatype xsd:int ;
                    sh:minCount 0 ;
                    sh:maxCount 1 ;
                ] .
        """.trimIndent()

        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "dcterms": "http://purl.org/dc/terms/",
                "xsd": "http://www.w3.org/2001/XMLSchema#",
                
                "ComplexTest": "dcat:Catalog",
                
                "title": {
                  "@id": "dcterms:title",
                  "@type": "xsd:string"
                },
                "keywords": {
                  "@id": "http://example.org/keywords",
                  "@type": "xsd:string"
                },
                "score": {
                  "@id": "http://example.org/score",
                  "@type": "xsd:double"
                },
                "isActive": {
                  "@id": "http://example.org/isActive",
                  "@type": "xsd:boolean"
                },
                "itemCount": {
                  "@id": "http://example.org/itemCount",
                  "@type": "xsd:int"
                }
              }
            }
        """.trimIndent()

        // Parse and generate
        val shapes = shaclParser.parseShaclContent(shaclContent)
        val context = contextParser.parseContextContent(contextContent)
        val ontologyModel = OntologyModel(shapes, context)

        val interfaces = interfaceGenerator.generateInterfaces(ontologyModel, "com.example.test")
        val wrappers = wrapperGenerator.generateWrappers(ontologyModel, "com.example.test")

        val complexInterface = interfaces["Catalog"]!!
        val complexWrapper = wrappers["CatalogWrapper"]!!

        // Verify interface types
        assertTrue(complexInterface.contains("val title: String"))
        assertTrue(complexInterface.contains("val keywords: List<String>"))
        assertTrue(complexInterface.contains("val score: Double"))
        assertTrue(complexInterface.contains("val isActive: Boolean"))
        assertTrue(complexInterface.contains("val itemCount: Int"))

        // Verify wrapper implementations
        assertTrue(complexWrapper.contains("override val title: String by lazy {"))
        assertTrue(complexWrapper.contains("override val keywords: List<String> by lazy {"))
        assertTrue(complexWrapper.contains("override val score: Double? by lazy {"))
        assertTrue(complexWrapper.contains("override val isActive: Boolean by lazy {"))
        assertTrue(complexWrapper.contains("override val itemCount: Int? by lazy {"))

        // Verify type conversions
        assertTrue(complexWrapper.contains("KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(\"http://purl.org/dc/terms/title\"))"))
        assertTrue(complexWrapper.contains(".map { it.lexical }"))
        assertTrue(complexWrapper.contains(".map { it.lexical }.firstOrNull()?.toDoubleOrNull()"))
        assertTrue(complexWrapper.contains("KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri(\"http://example.org/isActive\"))"))
        assertTrue(complexWrapper.contains(".lexical.toBooleanStrict()"))
        assertTrue(complexWrapper.contains(".map { it.lexical }.firstOrNull()?.toIntOrNull()"))
    }

    @Test
    fun `ontology generation handles object properties with different cardinalities`() {
        val shaclContent = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .

            <http://example.org/shapes/ResourceTest>
                a sh:NodeShape ;
                sh:targetClass dcat:Catalog ;
                sh:property [
                    sh:path dcterms:publisher ;
                    sh:name "publisher" ;
                    sh:class foaf:Agent ;
                    sh:minCount 0 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path dcat:dataset ;
                    sh:name "dataset" ;
                    sh:class dcat:Dataset ;
                    sh:minCount 0 ;
                ] ;
                sh:property [
                    sh:path <http://example.org/requiredContact> ;
                    sh:name "requiredContact" ;
                    sh:class foaf:Agent ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                ] .
        """.trimIndent()

        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "dcterms": "http://purl.org/dc/terms/",
                "foaf": "http://xmlns.com/foaf/0.1/",
                
                "ResourceTest": "dcat:Catalog",
                "Agent": "foaf:Agent",
                "Dataset": "dcat:Dataset",
                
                "publisher": {
                  "@id": "dcterms:publisher",
                  "@type": "@id"
                },
                "dataset": {
                  "@id": "dcat:dataset",
                  "@type": "@id"
                },
                "requiredContact": {
                  "@id": "http://example.org/requiredContact",
                  "@type": "@id"
                }
              }
            }
        """.trimIndent()

        // Parse and generate
        val shapes = shaclParser.parseShaclContent(shaclContent)
        val context = contextParser.parseContextContent(contextContent)
        val ontologyModel = OntologyModel(shapes, context)

        val interfaces = interfaceGenerator.generateInterfaces(ontologyModel, "com.example.test")
        val wrappers = wrapperGenerator.generateWrappers(ontologyModel, "com.example.test")

        val resourceInterface = interfaces["Catalog"]!!
        val resourceWrapper = wrappers["CatalogWrapper"]!!

        // Verify interface object properties
        assertTrue(resourceInterface.contains("val publisher: Agent?"))
        assertTrue(resourceInterface.contains("val dataset: List<Dataset>"))
        assertTrue(resourceInterface.contains("val requiredContact: Agent"))

        // Verify wrapper object property implementations
        assertTrue(resourceWrapper.contains("override val publisher: Agent? by lazy {"))
        assertTrue(resourceWrapper.contains("override val dataset: List<Dataset> by lazy {"))
        assertTrue(resourceWrapper.contains("override val requiredContact: Agent by lazy {"))

        // Verify object materialization
        assertTrue(resourceWrapper.contains("KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri(\"http://purl.org/dc/terms/publisher\"))"))
        assertTrue(resourceWrapper.contains("OntoMapper.materialize(RdfRef(child, rdf.graph), Agent::class.java)"))
        assertTrue(resourceWrapper.contains(".firstOrNull() ?: error(\"Required object requiredContact missing\")"))
    }

    @Test
    fun `ontology generation handles empty ontology gracefully`() {
        val shaclContent = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .

            <http://example.org/shapes/Empty>
                a sh:NodeShape ;
                sh:targetClass dcat:Catalog .
        """.trimIndent()

        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "Empty": "dcat:Catalog"
              }
            }
        """.trimIndent()

        // Parse and generate
        val shapes = shaclParser.parseShaclContent(shaclContent)
        val context = contextParser.parseContextContent(contextContent)
        val ontologyModel = OntologyModel(shapes, context)

        val interfaces = interfaceGenerator.generateInterfaces(ontologyModel, "com.example.test")
        val wrappers = wrapperGenerator.generateWrappers(ontologyModel, "com.example.test")

        assertEquals(1, interfaces.size)
        assertEquals(1, wrappers.size)

        val emptyInterface = interfaces["Catalog"]!!
        val emptyWrapper = wrappers["CatalogWrapper"]!!

        // Verify empty interface
        assertTrue(emptyInterface.contains("interface Catalog {"))
        assertTrue(emptyInterface.contains("}"))
        assertFalse(emptyInterface.contains("@get:RdfProperty"))

        // Verify empty wrapper
        assertTrue(emptyWrapper.contains("internal class CatalogWrapper("))
        assertTrue(emptyWrapper.contains(") : Catalog, RdfBacked {"))
        assertTrue(emptyWrapper.contains("private val known: Set<Iri> = setOf("))
        assertTrue(emptyWrapper.contains(")"))
        assertTrue(emptyWrapper.contains("companion object {"))
        assertTrue(emptyWrapper.contains("OntoMapper.registry[Catalog::class.java]"))
    }

    @Test
    fun `ontology generation handles malformed SHACL gracefully`() {
        val malformedShaclContent = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .

            <http://example.org/shapes/Malformed>
                a sh:NodeShape ;
                sh:targetClass dcat:Catalog ;
                sh:property [
                    sh:path dcat:title ;
                    sh:name "title" ;
                    # Missing datatype and other required properties
                ] .
        """.trimIndent()

        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "Malformed": "dcat:Catalog"
              }
            }
        """.trimIndent()

        // Parse and generate
        val shapes = shaclParser.parseShaclContent(malformedShaclContent)
        val context = contextParser.parseContextContent(contextContent)
        val ontologyModel = OntologyModel(shapes, context)

        val interfaces = interfaceGenerator.generateInterfaces(ontologyModel, "com.example.test")
        val wrappers = wrapperGenerator.generateWrappers(ontologyModel, "com.example.test")

        assertEquals(1, interfaces.size)
        assertEquals(1, wrappers.size)

        val malformedInterface = interfaces["Catalog"]!!
        val malformedWrapper = wrappers["CatalogWrapper"]!!

        // Should generate empty interface and wrapper (no properties due to malformed SHACL)
        assertTrue(malformedInterface.contains("interface Catalog {"))
        assertTrue(malformedInterface.contains("}"))
        assertFalse(malformedInterface.contains("@get:RdfProperty"))

        assertTrue(malformedWrapper.contains("internal class CatalogWrapper("))
        assertTrue(malformedWrapper.contains("private val known: Set<Iri> = setOf("))
        assertTrue(malformedWrapper.contains(")"))
    }

    @Test
    fun `ontology generation handles input streams correctly`() {
        val shaclContent = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            <http://example.org/shapes/StreamTest>
                a sh:NodeShape ;
                sh:targetClass dcat:Catalog ;
                sh:property [
                    sh:path dcterms:title ;
                    sh:name "title" ;
                    sh:datatype xsd:string ;
                ] .
        """.trimIndent()

        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "dcterms": "http://purl.org/dc/terms/",
                "xsd": "http://www.w3.org/2001/XMLSchema#",
                
                "StreamTest": "dcat:Catalog",
                
                "title": {
                  "@id": "dcterms:title",
                  "@type": "xsd:string"
                }
              }
            }
        """.trimIndent()

        // Parse from input streams
        val shapes = shaclParser.parseShacl(ByteArrayInputStream(shaclContent.toByteArray()))
        val context = contextParser.parseContext(ByteArrayInputStream(contextContent.toByteArray()))
        val ontologyModel = OntologyModel(shapes, context)

        val interfaces = interfaceGenerator.generateInterfaces(ontologyModel, "com.example.test")
        val wrappers = wrapperGenerator.generateWrappers(ontologyModel, "com.example.test")

        assertEquals(1, interfaces.size)
        assertEquals(1, wrappers.size)

        val streamInterface = interfaces["Catalog"]!!
        val streamWrapper = wrappers["CatalogWrapper"]!!

        assertTrue(streamInterface.contains("interface Catalog {"))
        assertTrue(streamInterface.contains("val title: List<String>"))

        assertTrue(streamWrapper.contains("internal class CatalogWrapper("))
        assertTrue(streamWrapper.contains("override val title: List<String> by lazy {"))
    }
}












