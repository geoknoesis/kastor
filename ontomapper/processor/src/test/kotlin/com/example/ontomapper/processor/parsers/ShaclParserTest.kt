package com.example.ontomapper.processor.parsers

import com.example.ontomapper.processor.model.ShaclProperty
import com.example.ontomapper.processor.model.ShaclShape
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class ShaclParserTest {

    private lateinit var logger: KSPLogger
    private lateinit var parser: ShaclParser

    @BeforeEach
    fun setup() {
        logger = object : KSPLogger {
            override fun logging(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun error(msg: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
        parser = ShaclParser(logger)
    }

    @Test
    fun `parseShacl parses simple catalog shape`() {
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
                ] .
        """.trimIndent()

        val shapes = parser.parseShaclContent(shaclContent)

        assertEquals(1, shapes.size)
        
        val catalogShape = shapes[0]
        assertEquals("http://example.org/shapes/Catalog", catalogShape.shapeIri)
        assertEquals("http://www.w3.org/ns/dcat#Catalog", catalogShape.targetClass)
        assertEquals(2, catalogShape.properties.size)

        val titleProperty = catalogShape.properties.find { it.name == "title" }
        assertNotNull(titleProperty)
        assertEquals("http://purl.org/dc/terms/title", titleProperty!!.path)
        assertEquals("A name given to the catalog.", titleProperty.description)
        assertEquals("http://www.w3.org/2001/XMLSchema#string", titleProperty.datatype)
        assertEquals(1, titleProperty.minCount)
        assertEquals(1, titleProperty.maxCount)

        val descriptionProperty = catalogShape.properties.find { it.name == "description" }
        assertNotNull(descriptionProperty)
        assertEquals("http://purl.org/dc/terms/description", descriptionProperty!!.path)
        assertEquals("A free-text account of the catalog.", descriptionProperty.description)
        assertEquals("http://www.w3.org/2001/XMLSchema#string", descriptionProperty.datatype)
        assertEquals(0, descriptionProperty.minCount)
        assertEquals(1, descriptionProperty.maxCount)
    }

    @Test
    fun `parseShacl parses object properties with target class`() {
        val shaclContent = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .

            <http://example.org/shapes/Catalog>
                a sh:NodeShape ;
                sh:targetClass dcat:Catalog ;
                sh:property [
                    sh:path dcat:dataset ;
                    sh:name "dataset" ;
                    sh:description "A collection of data that is listed in the catalog." ;
                    sh:class dcat:Dataset ;
                    sh:minCount 0 ;
                ] .
        """.trimIndent()

        val shapes = parser.parseShaclContent(shaclContent)

        assertEquals(1, shapes.size)
        val datasetProperty = shapes[0].properties.find { it.name == "dataset" }
        assertNotNull(datasetProperty)
        assertEquals("http://www.w3.org/ns/dcat#dataset", datasetProperty!!.path)
        assertEquals("http://www.w3.org/ns/dcat#Dataset", datasetProperty.targetClass)
        assertEquals(0, datasetProperty.minCount)
        assertNull(datasetProperty.maxCount)
    }

    @Test
    fun `parseShacl handles multiple shapes`() {
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
                    sh:datatype xsd:string ;
                ] .

            <http://example.org/shapes/Dataset>
                a sh:NodeShape ;
                sh:targetClass dcat:Dataset ;
                sh:property [
                    sh:path dcterms:title ;
                    sh:name "title" ;
                    sh:datatype xsd:string ;
                ] .
        """.trimIndent()

        val shapes = parser.parseShaclContent(shaclContent)

        assertEquals(2, shapes.size)
        
        val catalogShape = shapes.find { it.shapeIri == "http://example.org/shapes/Catalog" }
        assertNotNull(catalogShape)
        assertEquals("http://www.w3.org/ns/dcat#Catalog", catalogShape!!.targetClass)

        val datasetShape = shapes.find { it.shapeIri == "http://example.org/shapes/Dataset" }
        assertNotNull(datasetShape)
        assertEquals("http://www.w3.org/ns/dcat#Dataset", datasetShape!!.targetClass)
    }

    @Test
    fun `parseShacl handles different datatypes`() {
        val shaclContent = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            <http://example.org/shapes/Test>
                a sh:NodeShape ;
                sh:targetClass dcat:Catalog ;
                sh:property [
                    sh:path <http://example.org/stringProp> ;
                    sh:name "stringProp" ;
                    sh:datatype xsd:string ;
                ] ;
                sh:property [
                    sh:path <http://example.org/intProp> ;
                    sh:name "intProp" ;
                    sh:datatype xsd:int ;
                ] ;
                sh:property [
                    sh:path <http://example.org/booleanProp> ;
                    sh:name "booleanProp" ;
                    sh:datatype xsd:boolean ;
                ] ;
                sh:property [
                    sh:path <http://example.org/doubleProp> ;
                    sh:name "doubleProp" ;
                    sh:datatype xsd:double ;
                ] ;
                sh:property [
                    sh:path <http://example.org/anyURIProp> ;
                    sh:name "anyURIProp" ;
                    sh:datatype xsd:anyURI ;
                ] .
        """.trimIndent()

        val shapes = parser.parseShaclContent(shaclContent)

        assertEquals(1, shapes.size)
        val properties = shapes[0].properties

        val stringProp = properties.find { it.name == "stringProp" }
        assertEquals("http://www.w3.org/2001/XMLSchema#string", stringProp!!.datatype)

        val intProp = properties.find { it.name == "intProp" }
        assertEquals("http://www.w3.org/2001/XMLSchema#int", intProp!!.datatype)

        val booleanProp = properties.find { it.name == "booleanProp" }
        assertEquals("http://www.w3.org/2001/XMLSchema#boolean", booleanProp!!.datatype)

        val doubleProp = properties.find { it.name == "doubleProp" }
        assertEquals("http://www.w3.org/2001/XMLSchema#double", doubleProp!!.datatype)

        val anyURIProp = properties.find { it.name == "anyURIProp" }
        assertEquals("http://www.w3.org/2001/XMLSchema#anyURI", anyURIProp!!.datatype)
    }

    @Test
    fun `parseShacl handles cardinality constraints`() {
        val shaclContent = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            <http://example.org/shapes/Test>
                a sh:NodeShape ;
                sh:targetClass dcat:Catalog ;
                sh:property [
                    sh:path <http://example.org/requiredProp> ;
                    sh:name "requiredProp" ;
                    sh:datatype xsd:string ;
                    sh:minCount 1 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path <http://example.org/optionalProp> ;
                    sh:name "optionalProp" ;
                    sh:datatype xsd:string ;
                    sh:minCount 0 ;
                    sh:maxCount 1 ;
                ] ;
                sh:property [
                    sh:path <http://example.org/multipleProp> ;
                    sh:name "multipleProp" ;
                    sh:datatype xsd:string ;
                    sh:minCount 0 ;
                    sh:maxCount 5 ;
                ] ;
                sh:property [
                    sh:path <http://example.org/unboundedProp> ;
                    sh:name "unboundedProp" ;
                    sh:datatype xsd:string ;
                    sh:minCount 0 ;
                ] .
        """.trimIndent()

        val shapes = parser.parseShaclContent(shaclContent)

        assertEquals(1, shapes.size)
        val properties = shapes[0].properties

        val requiredProp = properties.find { it.name == "requiredProp" }
        assertEquals(1, requiredProp!!.minCount)
        assertEquals(1, requiredProp.maxCount)

        val optionalProp = properties.find { it.name == "optionalProp" }
        assertEquals(0, optionalProp!!.minCount)
        assertEquals(1, optionalProp.maxCount)

        val multipleProp = properties.find { it.name == "multipleProp" }
        assertEquals(0, multipleProp!!.minCount)
        assertEquals(5, multipleProp.maxCount)

        val unboundedProp = properties.find { it.name == "unboundedProp" }
        assertEquals(0, unboundedProp!!.minCount)
        assertNull(unboundedProp.maxCount)
    }

    @Test
    fun `parseShacl handles empty content`() {
        val shapes = parser.parseShaclContent("")
        assertTrue(shapes.isEmpty())
    }

    @Test
    fun `parseShacl handles content without shapes`() {
        val shaclContent = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            
            <http://example.org/resource>
                a dcat:Catalog ;
                dcterms:title "Test" .
        """.trimIndent()

        val shapes = parser.parseShaclContent(shaclContent)
        assertTrue(shapes.isEmpty())
    }

    @Test
    fun `parseShacl handles malformed content gracefully`() {
        val shaclContent = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .

            <http://example.org/shapes/Catalog>
                a sh:NodeShape ;
                sh:targetClass dcat:Catalog ;
                sh:property [
                    sh:path dcat:title ;
                    sh:name "title" ;
                    # Missing datatype and other properties
                ] .
        """.trimIndent()

        val shapes = parser.parseShaclContent(shaclContent)

        assertEquals(1, shapes.size)
        val properties = shapes[0].properties
        assertTrue(properties.isEmpty()) // Properties without required fields are skipped
    }

    @Test
    fun `parseShacl from input stream works`() {
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
                    sh:datatype xsd:string ;
                ] .
        """.trimIndent()

        val inputStream = ByteArrayInputStream(shaclContent.toByteArray())
        val shapes = parser.parseShacl(inputStream)

        assertEquals(1, shapes.size)
        assertEquals("http://example.org/shapes/Catalog", shapes[0].shapeIri)
        assertEquals("http://www.w3.org/ns/dcat#Catalog", shapes[0].targetClass)
    }
}
