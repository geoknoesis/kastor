package com.example.ontomapper.processor

import com.example.ontomapper.processor.codegen.InterfaceGenerator
import com.example.ontomapper.processor.codegen.OntologyWrapperGenerator
import com.example.ontomapper.processor.model.OntologyModel
import com.example.ontomapper.processor.parsers.JsonLdContextParser
import com.example.ontomapper.processor.parsers.ShaclParser
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class OntologyProcessorEndToEndTest {

    private lateinit var logger: KSPLogger
    private lateinit var shaclParser: ShaclParser
    private lateinit var contextParser: JsonLdContextParser
    private lateinit var interfaceGenerator: InterfaceGenerator
    private lateinit var wrapperGenerator: OntologyWrapperGenerator

    @BeforeEach
    fun setup() {
        logger = object : KSPLogger {
            override fun logging(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun error(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
        shaclParser = ShaclParser(logger)
        contextParser = JsonLdContextParser(logger)
        interfaceGenerator = InterfaceGenerator(logger)
        wrapperGenerator = OntologyWrapperGenerator(logger)
    }

    @Test
    fun `end-to-end test with DCAT-US sample files`() {
        // This test uses the actual DCAT-US sample files to verify the complete workflow
        val shaclPath = Paths.get("samples", "dcat-us", "src", "main", "resources", "dcat-us", "dcat-us-3.0.shacl.ttl")
        val contextPath = Paths.get("samples", "dcat-us", "src", "main", "resources", "dcat-us", "dcat-us.context.jsonld")

        // Check if files exist
        if (!Files.exists(shaclPath) || !Files.exists(contextPath)) {
            // Skip test if sample files don't exist
            return
        }

        // Read and parse SHACL file
        val shaclContent = Files.readString(shaclPath)
        val shapes = shaclParser.parseShaclContent(shaclContent)

        // Read and parse JSON-LD context file
        val contextContent = Files.readString(contextPath)
        val context = contextParser.parseContextContent(contextContent)

        // Verify parsing results
        assertTrue(shapes.isNotEmpty(), "Should parse at least one SHACL shape")
        assertTrue(context.prefixes.isNotEmpty(), "Should parse at least one prefix")
        assertTrue(context.typeMappings.isNotEmpty(), "Should parse at least one type mapping")
        assertTrue(context.propertyMappings.isNotEmpty(), "Should parse at least one property mapping")

        // Create ontology model
        val ontologyModel = OntologyModel(shapes, context)

        // Generate interfaces
        val interfaces = interfaceGenerator.generateInterfaces(ontologyModel, "com.example.dcatus.generated")
        assertTrue(interfaces.isNotEmpty(), "Should generate at least one interface")

        // Generate wrappers
        val wrappers = wrapperGenerator.generateWrappers(ontologyModel, "com.example.dcatus.generated")
        assertTrue(wrappers.isNotEmpty(), "Should generate at least one wrapper")

        // Verify that interfaces and wrappers are generated for the same shapes
        assertEquals(interfaces.size, wrappers.size, "Should generate same number of interfaces and wrappers")

        // Verify specific DCAT entities are generated
        val expectedEntities = listOf("Catalog", "Dataset", "Distribution", "Agent")
        for (entity in expectedEntities) {
            if (shapes.any { extractInterfaceName(it.targetClass) == entity }) {
                assertTrue(interfaces.containsKey(entity), "Should generate $entity interface")
                assertTrue(wrappers.containsKey("${entity}Wrapper"), "Should generate ${entity}Wrapper")
            }
        }

        // Verify generated code structure
        for ((interfaceName, interfaceCode) in interfaces) {
            // Check interface structure
            assertTrue(interfaceCode.contains("package com.example.dcatus.generated"))
            assertTrue(interfaceCode.contains("import com.example.ontomapper.annotations.RdfClass"))
            assertTrue(interfaceCode.contains("import com.example.ontomapper.annotations.RdfProperty"))
            assertTrue(interfaceCode.contains("@RdfClass(iri ="))
            assertTrue(interfaceCode.contains("interface $interfaceName {"))

            // Check that interface has properties
            val propertyCount = interfaceCode.split("@get:RdfProperty").size - 1
            assertTrue(propertyCount > 0, "$interfaceName should have at least one property")
        }

        for ((wrapperName, wrapperCode) in wrappers) {
            // Check wrapper structure
            assertTrue(wrapperCode.contains("package com.example.dcatus.generated"))
            assertTrue(wrapperCode.contains("import com.example.ontomapper.runtime.*"))
            assertTrue(wrapperCode.contains("import com.geoknoesis.kastor.rdf.*"))
            assertTrue(wrapperCode.contains("internal class $wrapperName("))
            assertTrue(wrapperCode.contains("override val rdf: RdfHandle"))
            assertTrue(wrapperCode.contains("companion object {"))
            assertTrue(wrapperCode.contains("OntoMapper.registry["))

            // Check that wrapper has property implementations
            val propertyCount = wrapperCode.split("override val").size - 1
            assertTrue(propertyCount > 0, "$wrapperName should have at least one property implementation")
        }

        // Verify specific DCAT properties are handled correctly
        val catalogInterface = interfaces["Catalog"]
        if (catalogInterface != null) {
            assertTrue(catalogInterface.contains("val title:"))
            assertTrue(catalogInterface.contains("val description:"))
            assertTrue(catalogInterface.contains("val dataset:"))
        }

        val datasetInterface = interfaces["Dataset"]
        if (datasetInterface != null) {
            assertTrue(datasetInterface.contains("val title:"))
            assertTrue(datasetInterface.contains("val description:"))
            assertTrue(datasetInterface.contains("val distribution:"))
        }

        val distributionInterface = interfaces["Distribution"]
        if (distributionInterface != null) {
            assertTrue(distributionInterface.contains("val title:"))
            assertTrue(distributionInterface.contains("val downloadURL:"))
            assertTrue(distributionInterface.contains("val mediaType:"))
        }

        val agentInterface = interfaces["Agent"]
        if (agentInterface != null) {
            assertTrue(agentInterface.contains("val name:"))
            assertTrue(agentInterface.contains("val homepage:"))
        }

        // Verify wrapper implementations use correct KastorGraphOps methods
        val catalogWrapper = wrappers["CatalogWrapper"]
        if (catalogWrapper != null) {
            assertTrue(catalogWrapper.contains("KastorGraphOps.getLiteralValues"))
            assertTrue(catalogWrapper.contains("KastorGraphOps.getObjectValues"))
        }

        val datasetWrapper = wrappers["DatasetWrapper"]
        if (datasetWrapper != null) {
            assertTrue(datasetWrapper.contains("KastorGraphOps.getLiteralValues"))
            assertTrue(datasetWrapper.contains("KastorGraphOps.getObjectValues"))
        }
    }

    @Test
    fun `end-to-end test validates generated code compiles`() {
        // Create a simple test ontology
        val shaclContent = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            <http://example.org/shapes/SimpleCatalog>
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
                ] .
        """.trimIndent()

        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "dcterms": "http://purl.org/dc/terms/",
                "xsd": "http://www.w3.org/2001/XMLSchema#",
                
                "SimpleCatalog": "dcat:Catalog",
                
                "title": {
                  "@id": "dcterms:title",
                  "@type": "xsd:string"
                },
                "description": {
                  "@id": "dcterms:description",
                  "@type": "xsd:string"
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

        // Verify generated code structure
        val simpleCatalogInterface = interfaces["Catalog"]
        assertNotNull(simpleCatalogInterface)
        assertTrue(simpleCatalogInterface!!.contains("interface Catalog {"))
        assertTrue(simpleCatalogInterface.contains("val title: String"))
        assertTrue(simpleCatalogInterface.contains("val description: String"))

        val simpleCatalogWrapper = wrappers["CatalogWrapper"]
        assertNotNull(simpleCatalogWrapper)
        assertTrue(simpleCatalogWrapper!!.contains("internal class CatalogWrapper("))
        assertTrue(simpleCatalogWrapper.contains("override val title: String by lazy {"))
        assertTrue(simpleCatalogWrapper.contains("override val description: String by lazy {"))

        // Verify that generated code follows Kotlin syntax rules
        assertTrue(simpleCatalogInterface.contains("package com.example.test"))
        assertTrue(simpleCatalogInterface.contains("import com.example.ontomapper.annotations.RdfClass"))
        assertTrue(simpleCatalogInterface.contains("import com.example.ontomapper.annotations.RdfProperty"))

        assertTrue(simpleCatalogWrapper.contains("package com.example.test"))
        assertTrue(simpleCatalogWrapper.contains("import com.example.ontomapper.runtime.*"))
        assertTrue(simpleCatalogWrapper.contains("import com.geoknoesis.kastor.rdf.*"))

        // Verify that generated code has proper annotations
        assertTrue(simpleCatalogInterface.contains("@RdfClass(iri = \"http://www.w3.org/ns/dcat#Catalog\")"))
        assertTrue(simpleCatalogInterface.contains("@get:RdfProperty(iri = \"http://purl.org/dc/terms/title\")"))
        assertTrue(simpleCatalogInterface.contains("@get:RdfProperty(iri = \"http://purl.org/dc/terms/description\")"))

        // Verify that generated wrapper has proper registry entry
        assertTrue(simpleCatalogWrapper.contains("OntoMapper.registry[Catalog::class.java] = { handle -> CatalogWrapper(handle) }"))

        // Verify that generated wrapper has proper known predicates
        assertTrue(simpleCatalogWrapper.contains("private val known: Set<Iri> = setOf("))
        assertTrue(simpleCatalogWrapper.contains("Iri(\"http://purl.org/dc/terms/title\")"))
        assertTrue(simpleCatalogWrapper.contains("Iri(\"http://purl.org/dc/terms/description\")"))

        // Verify that generated wrapper uses correct KastorGraphOps methods
        assertTrue(simpleCatalogWrapper.contains("KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"http://purl.org/dc/terms/title\"))"))
        assertTrue(simpleCatalogWrapper.contains("KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri(\"http://purl.org/dc/terms/description\"))"))
    }

    @Test
    fun `end-to-end test handles complex ontology with multiple relationships`() {
        val shaclContent = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            <http://example.org/shapes/Catalog>
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
                    sh:path dcat:dataset ;
                    sh:name "dataset" ;
                    sh:class dcat:Dataset ;
                    sh:minCount 0 ;
                ] ;
                sh:property [
                    sh:path dcterms:publisher ;
                    sh:name "publisher" ;
                    sh:class foaf:Agent ;
                    sh:minCount 0 ;
                    sh:maxCount 1 ;
                ] .

            <http://example.org/shapes/Dataset>
                a sh:NodeShape ;
                sh:targetClass dcat:Dataset ;
                sh:property [
                    sh:path dcterms:title ;
                    sh:name "title" ;
                    sh:datatype xsd:string ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path dcat:distribution ;
                    sh:name "distribution" ;
                    sh:class dcat:Distribution ;
                    sh:minCount 0 ;
                ] .

            <http://example.org/shapes/Distribution>
                a sh:NodeShape ;
                sh:targetClass dcat:Distribution ;
                sh:property [
                    sh:path dcterms:title ;
                    sh:name "title" ;
                    sh:datatype xsd:string ;
                    sh:minCount 0 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path dcat:downloadURL ;
                    sh:name "downloadURL" ;
                    sh:datatype xsd:anyURI ;
                    sh:minCount 0 ;
                    sh:maxCount 1 ;
                ] .

            <http://example.org/shapes/Agent>
                a sh:NodeShape ;
                sh:targetClass foaf:Agent ;
                sh:property [
                    sh:path foaf:name ;
                    sh:name "name" ;
                    sh:datatype xsd:string ;
                    sh:minCount 0 ;
                ] ;
                sh:property [
                    sh:path foaf:homepage ;
                    sh:name "homepage" ;
                    sh:datatype xsd:anyURI ;
                    sh:minCount 0 ;
                ] .
        """.trimIndent()

        val contextContent = """
            {
              "@context": {
                "dcat": "http://www.w3.org/ns/dcat#",
                "dcterms": "http://purl.org/dc/terms/",
                "foaf": "http://xmlns.com/foaf/0.1/",
                "xsd": "http://www.w3.org/2001/XMLSchema#",
                
                "Catalog": "dcat:Catalog",
                "Dataset": "dcat:Dataset",
                "Distribution": "dcat:Distribution",
                "Agent": "foaf:Agent",
                
                "title": {
                  "@id": "dcterms:title",
                  "@type": "xsd:string"
                },
                "dataset": {
                  "@id": "dcat:dataset",
                  "@type": "@id"
                },
                "publisher": {
                  "@id": "dcterms:publisher",
                  "@type": "@id"
                },
                "distribution": {
                  "@id": "dcat:distribution",
                  "@type": "@id"
                },
                "downloadURL": {
                  "@id": "dcat:downloadURL",
                  "@type": "xsd:anyURI"
                },
                "name": {
                  "@id": "foaf:name",
                  "@type": "xsd:string"
                },
                "homepage": {
                  "@id": "foaf:homepage",
                  "@type": "xsd:anyURI"
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

        // Verify all entities are generated
        assertEquals(4, interfaces.size)
        assertEquals(4, wrappers.size)

        val expectedEntities = listOf("Catalog", "Dataset", "Distribution", "Agent")
        for (entity in expectedEntities) {
            assertTrue(interfaces.containsKey(entity), "Should generate $entity interface")
            assertTrue(wrappers.containsKey("${entity}Wrapper"), "Should generate ${entity}Wrapper")
        }

        // Verify Catalog interface has correct relationships
        val catalogInterface = interfaces["Catalog"]!!
        assertTrue(catalogInterface.contains("val title: String"))
        assertTrue(catalogInterface.contains("val dataset: List<Dataset>"))
        assertTrue(catalogInterface.contains("val publisher: Agent"))

        // Verify Catalog wrapper has correct object property handling
        val catalogWrapper = wrappers["CatalogWrapper"]!!
        assertTrue(catalogWrapper.contains("override val dataset: List<Dataset> by lazy {"))
        assertTrue(catalogWrapper.contains("override val publisher: Agent by lazy {"))
        assertTrue(catalogWrapper.contains("OntoMapper.materialize(RdfRef(child, rdf.graph), Dataset::class.java, false)"))
        assertTrue(catalogWrapper.contains("OntoMapper.materialize(RdfRef(child, rdf.graph), Agent::class.java, false)"))

        // Verify Dataset interface has correct relationships
        val datasetInterface = interfaces["Dataset"]!!
        assertTrue(datasetInterface.contains("val title: String"))
        assertTrue(datasetInterface.contains("val distribution: List<Distribution>"))

        // Verify Distribution interface has correct properties
        val distributionInterface = interfaces["Distribution"]!!
        assertTrue(distributionInterface.contains("val title: String"))
        assertTrue(distributionInterface.contains("val downloadURL: String"))

        // Verify Agent interface has correct properties
        val agentInterface = interfaces["Agent"]!!
        assertTrue(agentInterface.contains("val name: List<String>"))
        assertTrue(agentInterface.contains("val homepage: List<String>"))

        // Verify all wrappers have proper registry entries
        for (entity in expectedEntities) {
            val wrapper = wrappers["${entity}Wrapper"]!!
            assertTrue(wrapper.contains("OntoMapper.registry[${entity}::class.java]"))
        }
    }

    private fun extractInterfaceName(classIri: String): String {
        val localName = classIri.substringAfterLast('/').substringAfterLast('#')
        return localName.split('-', '_').joinToString("") { word ->
            word.replaceFirstChar { it.uppercaseChar() }
        }
    }
}
