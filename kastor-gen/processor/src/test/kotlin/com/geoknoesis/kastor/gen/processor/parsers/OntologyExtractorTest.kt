package com.geoknoesis.kastor.gen.processor.parsers

import com.geoknoesis.kastor.gen.processor.model.OntologyClass
import com.google.devtools.ksp.processing.KSPLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class OntologyExtractorTest {

    private lateinit var logger: KSPLogger
    private lateinit var extractor: OntologyExtractor

    @BeforeEach
    fun setup() {
        logger = object : KSPLogger {
            override fun logging(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun info(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun warn(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun error(message: String, symbol: com.google.devtools.ksp.symbol.KSNode?) {}
            override fun exception(e: Throwable) {}
        }
        extractor = OntologyExtractor(logger)
    }

    @Test
    fun `extractClassesFromContent extracts OWL classes`() {
        val ontologyContent = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .

            skos:Concept a owl:Class .
            skos:ConceptScheme a owl:Class .
        """.trimIndent()

        val classes = extractor.extractClassesFromContent(ontologyContent)

        assertEquals(2, classes.size)
        
        val concept = classes.find { it.classIri == "http://www.w3.org/2004/02/skos/core#Concept" }
        assertNotNull(concept)
        assertEquals("Concept", concept!!.className)
        
        val conceptScheme = classes.find { it.classIri == "http://www.w3.org/2004/02/skos/core#ConceptScheme" }
        assertNotNull(conceptScheme)
        assertEquals("ConceptScheme", conceptScheme!!.className)
    }

    @Test
    fun `extractClassesFromContent extracts RDFS classes`() {
        val ontologyContent = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix dcat: <http://www.w3.org/ns/dcat#> .

            dcat:Catalog a rdfs:Class .
            dcat:Dataset a rdfs:Class .
        """.trimIndent()

        val classes = extractor.extractClassesFromContent(ontologyContent)

        assertEquals(2, classes.size)
        
        val catalog = classes.find { it.classIri == "http://www.w3.org/ns/dcat#Catalog" }
        assertNotNull(catalog)
        assertEquals("Catalog", catalog!!.className)
        
        val dataset = classes.find { it.classIri == "http://www.w3.org/ns/dcat#Dataset" }
        assertNotNull(dataset)
        assertEquals("Dataset", dataset!!.className)
    }

    @Test
    fun `extractClassesFromContent extracts super classes`() {
        val ontologyContent = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .

            skos:Concept a owl:Class .
            skos:Collection a owl:Class ;
                rdfs:subClassOf skos:Concept .
        """.trimIndent()

        val classes = extractor.extractClassesFromContent(ontologyContent)

        assertEquals(2, classes.size)
        
        val concept = classes.find { it.classIri == "http://www.w3.org/2004/02/skos/core#Concept" }
        assertNotNull(concept)
        assertTrue(concept!!.superClasses.isEmpty())
        
        val collection = classes.find { it.classIri == "http://www.w3.org/2004/02/skos/core#Collection" }
        assertNotNull(collection)
        assertEquals(1, collection!!.superClasses.size)
        assertTrue(collection.superClasses.contains("http://www.w3.org/2004/02/skos/core#Concept"))
    }

    @Test
    fun `extractClassesFromContent handles classes with multiple super classes`() {
        val ontologyContent = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix ex: <http://example.org/> .

            ex:BaseClass1 a owl:Class .
            ex:BaseClass2 a owl:Class .
            ex:DerivedClass a owl:Class ;
                rdfs:subClassOf ex:BaseClass1 ;
                rdfs:subClassOf ex:BaseClass2 .
        """.trimIndent()

        val classes = extractor.extractClassesFromContent(ontologyContent)

        val derivedClass = classes.find { it.classIri == "http://example.org/DerivedClass" }
        assertNotNull(derivedClass)
        assertEquals(2, derivedClass!!.superClasses.size)
        assertTrue(derivedClass.superClasses.contains("http://example.org/BaseClass1"))
        assertTrue(derivedClass.superClasses.contains("http://example.org/BaseClass2"))
    }

    @Test
    fun `extractClassesFromContent handles empty content`() {
        val classes = extractor.extractClassesFromContent("")
        assertTrue(classes.isEmpty())
    }

    @Test
    fun `extractClassesFromContent handles content without classes`() {
        val ontologyContent = """
            @prefix ex: <http://example.org/> .

            ex:someResource a ex:SomeType .
        """.trimIndent()

        val classes = extractor.extractClassesFromContent(ontologyContent)
        assertTrue(classes.isEmpty())
    }

    @Test
    fun `extractClassesFromContent extracts local name correctly`() {
        val ontologyContent = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix ex: <http://example.org/vocab#> .

            ex:MyClass a owl:Class .
        """.trimIndent()

        val classes = extractor.extractClassesFromContent(ontologyContent)

        assertEquals(1, classes.size)
        assertEquals("MyClass", classes[0].className)
    }

    @Test
    fun `extractClasses handles input stream`() {
        val ontologyContent = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .

            skos:Concept a owl:Class .
        """.trimIndent()

        val inputStream = ByteArrayInputStream(ontologyContent.toByteArray())
        val classes = extractor.extractClasses(inputStream)

        assertEquals(1, classes.size)
        assertEquals("http://www.w3.org/2004/02/skos/core#Concept", classes[0].classIri)
        assertEquals("Concept", classes[0].className)
    }

    @Test
    fun `matchClassesWithShapes matches classes correctly`() {
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

        val shapeTargetClasses = listOf(
            "http://www.w3.org/2004/02/skos/core#Concept",
            "http://www.w3.org/2004/02/skos/core#ConceptScheme"
        )

        val matches = extractor.matchClassesWithShapes(classes, shapeTargetClasses)

        assertEquals(2, matches.size)
        assertTrue(matches.containsKey("http://www.w3.org/2004/02/skos/core#Concept"))
        assertTrue(matches.containsKey("http://www.w3.org/2004/02/skos/core#ConceptScheme"))
    }

    @Test
    fun `matchClassesWithShapes handles partial matches`() {
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

        val shapeTargetClasses = listOf(
            "http://www.w3.org/2004/02/skos/core#Concept"
        )

        val matches = extractor.matchClassesWithShapes(classes, shapeTargetClasses)

        assertEquals(1, matches.size)
        assertTrue(matches.containsKey("http://www.w3.org/2004/02/skos/core#Concept"))
        assertFalse(matches.containsKey("http://www.w3.org/2004/02/skos/core#ConceptScheme"))
    }

    @Test
    fun `matchClassesWithShapes handles no matches`() {
        val classes = listOf(
            OntologyClass(
                className = "Concept",
                classIri = "http://www.w3.org/2004/02/skos/core#Concept",
                superClasses = emptyList()
            )
        )

        val shapeTargetClasses = listOf(
            "http://example.org/OtherClass"
        )

        val matches = extractor.matchClassesWithShapes(classes, shapeTargetClasses)

        assertTrue(matches.isEmpty())
    }
}

