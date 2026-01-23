package com.geoknoesis.kastor.gen.processor

import com.geoknoesis.kastor.gen.processor.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.model.*
import com.geoknoesis.kastor.gen.processor.parsers.OntologyExtractor
import com.geoknoesis.kastor.gen.processor.parsers.ShaclParser
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests for the complete instance DSL generation workflow.
 */
class InstanceDslIntegrationTest {

    private lateinit var logger: KSPLogger
    private lateinit var shaclParser: ShaclParser
    private lateinit var ontologyExtractor: OntologyExtractor
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
        shaclParser = ShaclParser(logger)
        ontologyExtractor = OntologyExtractor(logger)
        dslGenerator = InstanceDslGenerator(logger)
    }

    @Test
    fun `complete SKOS DSL generation workflow`() {
        // SKOS ontology
        val ontologyContent = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .

            skos:Concept a owl:Class .
            skos:ConceptScheme a owl:Class .
        """.trimIndent()

        // SHACL shapes
        val shaclContent = """
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            <http://example.org/shapes/Concept>
                a sh:NodeShape ;
                sh:targetClass skos:Concept ;
                sh:property [
                    sh:path skos:prefLabel ;
                    sh:name "prefLabel" ;
                    sh:description "Preferred label for the concept" ;
                    sh:datatype xsd:string ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path skos:altLabel ;
                    sh:name "altLabel" ;
                    sh:description "Alternative label for the concept" ;
                    sh:datatype xsd:string ;
                    sh:minCount 0 ;
                ] .

            <http://example.org/shapes/ConceptScheme>
                a sh:NodeShape ;
                sh:targetClass skos:ConceptScheme ;
                sh:property [
                    sh:path skos:prefLabel ;
                    sh:name "prefLabel" ;
                    sh:description "Preferred label for the concept scheme" ;
                    sh:datatype xsd:string ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                ] .
        """.trimIndent()

        // Extract classes and shapes
        val classes = ontologyExtractor.extractClassesFromContent(ontologyContent)
        val shapes = shaclParser.parseShaclContent(shaclContent)

        assertEquals(2, classes.size)
        assertEquals(2, shapes.size)

        // Generate DSL
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
        val fileSpec = dslGenerator.generate(request)

        assertNotNull(fileSpec)
        val writer = java.io.StringWriter()
        fileSpec.writeTo(writer)
        val code = writer.toString()

        // Verify structure
        assertTrue(code.contains("package com.example.test"))
        assertTrue(code.contains("class SkosDsl"))
        assertTrue(code.contains("fun skos("))
        
        // Verify builder methods
        assertTrue(code.contains("fun concept("))
        assertTrue(code.contains("fun conceptScheme("))
        
        // Verify builder classes
        assertTrue(code.contains("class ConceptBuilder"))
        assertTrue(code.contains("class ConceptSchemeBuilder"))
        
        // Verify properties
        assertTrue(code.contains("fun prefLabel("))
        assertTrue(code.contains("fun altLabel("))
    }

    @Test
    fun `complete DCAT DSL generation workflow`() {
        // DCAT ontology
        val ontologyContent = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix dcat: <http://www.w3.org/ns/dcat#> .

            dcat:Catalog a owl:Class .
            dcat:Dataset a owl:Class .
        """.trimIndent()

        // SHACL shapes
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
                    sh:description "Title of the catalog" ;
                    sh:datatype xsd:string ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path dcat:dataset ;
                    sh:name "dataset" ;
                    sh:description "Datasets in the catalog" ;
                    sh:class dcat:Dataset ;
                    sh:minCount 0 ;
                ] .

            <http://example.org/shapes/Dataset>
                a sh:NodeShape ;
                sh:targetClass dcat:Dataset ;
                sh:property [
                    sh:path dcterms:title ;
                    sh:name "title" ;
                    sh:description "Title of the dataset" ;
                    sh:datatype xsd:string ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                ] .
        """.trimIndent()

        // Extract classes and shapes
        val classes = ontologyExtractor.extractClassesFromContent(ontologyContent)
        val shapes = shaclParser.parseShaclContent(shaclContent)

        // Generate DSL
        val context = JsonLdContext(
            prefixes = emptyMap(),
            baseIri = null,
            vocabIri = null,
            typeMappings = emptyMap(),
            propertyMappings = emptyMap()
        )

        val ontologyModel = OntologyModel(shapes, context)
        val request = InstanceDslRequest(
            dslName = "dcat",
            ontologyModel = ontologyModel,
            packageName = "com.example.test"
        )
        val fileSpec = dslGenerator.generate(request)

        assertNotNull(fileSpec)
        val writer = java.io.StringWriter()
        fileSpec.writeTo(writer)
        val code = writer.toString()

        // Verify structure
        assertTrue(code.contains("class DcatDsl"))
        assertTrue(code.contains("fun dcat("))
        assertTrue(code.contains("fun catalog("))
        assertTrue(code.contains("fun dataset("))
        assertTrue(code.contains("class CatalogBuilder"))
        assertTrue(code.contains("class DatasetBuilder"))
    }

    @Test
    fun `DSL generation with validation enabled`() {
        val ontologyContent = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .

            skos:Concept a owl:Class .
        """.trimIndent()

        val shaclContent = """
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            <http://example.org/shapes/Concept>
                a sh:NodeShape ;
                sh:targetClass skos:Concept ;
                sh:property [
                    sh:path skos:prefLabel ;
                    sh:name "prefLabel" ;
                    sh:datatype xsd:string ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                    sh:minLength 1 ;
                ] .
        """.trimIndent()

        val classes = ontologyExtractor.extractClassesFromContent(ontologyContent)
        val shapes = shaclParser.parseShaclContent(shaclContent)

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
        val fileSpec = dslGenerator.generate(request)

        val writer = java.io.StringWriter()
        fileSpec.writeTo(writer)
        val code = writer.toString()

        // Verify validation is included
        assertTrue(code.contains("builder.validate()"))
        assertTrue(code.contains("fun validate()"))
        assertTrue(code.contains("ValidationException"))
        assertTrue(code.contains("prefLabelCount"))
    }

    @Test
    fun `DSL generation with language tag support`() {
        val ontologyContent = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .

            skos:Concept a owl:Class .
        """.trimIndent()

        val shaclContent = """
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            <http://example.org/shapes/Concept>
                a sh:NodeShape ;
                sh:targetClass skos:Concept ;
                sh:property [
                    sh:path skos:prefLabel ;
                    sh:name "prefLabel" ;
                    sh:datatype xsd:string ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                ] .
        """.trimIndent()

        val classes = ontologyExtractor.extractClassesFromContent(ontologyContent)
        val shapes = shaclParser.parseShaclContent(shaclContent)

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
            options = DslGenerationOptions(output = DslGenerationOptions.OutputConfig(supportLanguageTags = true))
        )
        val fileSpec = dslGenerator.generate(request)

        val writer = java.io.StringWriter()
        fileSpec.writeTo(writer)
        val code = writer.toString()

        // Verify language tag support
        assertTrue(code.contains("lang"))
        assertTrue(code.contains("LangString"))
        assertTrue(code.contains("lang != null"))
    }

    @Test
    fun `DSL generation handles classes without matching shapes`() {
        val ontologyContent = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .

            skos:Concept a owl:Class .
            skos:ConceptScheme a owl:Class .
        """.trimIndent()

        val shaclContent = """
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            <http://example.org/shapes/Concept>
                a sh:NodeShape ;
                sh:targetClass skos:Concept ;
                sh:property [
                    sh:path skos:prefLabel ;
                    sh:name "prefLabel" ;
                    sh:datatype xsd:string ;
                ] .
        """.trimIndent()

        val classes = ontologyExtractor.extractClassesFromContent(ontologyContent)
        val shapes = shaclParser.parseShaclContent(shaclContent)

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
        val fileSpec = dslGenerator.generate(request)

        val writer = java.io.StringWriter()
        fileSpec.writeTo(writer)
        val code = writer.toString()

        // Only Concept should have a builder (ConceptScheme has no matching shape)
        assertTrue(code.contains("fun concept("))
        assertTrue(code.contains("class ConceptBuilder"))
        assertFalse(code.contains("fun conceptScheme("))
        assertFalse(code.contains("class ConceptSchemeBuilder"))
    }
}

