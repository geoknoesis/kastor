package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.nio.file.Files

class JenaBridgeTest {

    private lateinit var testModel: org.apache.jena.rdf.model.Model
    private lateinit var testGraph: org.apache.jena.graph.Graph

    @BeforeEach
    fun setup() {
        testModel = ModelFactory.createDefaultModel()
        testGraph = testModel.graph
        
        // Add some test data
        val personClass = ResourceFactory.createResource("http://xmlns.com/foaf/0.1/Person")
        val nameProperty = ResourceFactory.createProperty("http://xmlns.com/foaf/0.1/name")
        val john = ResourceFactory.createResource("http://example.org/john")
        
        testModel.add(john, nameProperty, "John Doe")
        testModel.add(john, ResourceFactory.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), personClass)
        
        // Add data to the model instead of the graph directly
        val jane = ResourceFactory.createResource("http://example.org/jane")
        val nameProperty2 = ResourceFactory.createProperty("http://xmlns.com/foaf/0.1/name")
        testModel.add(jane, nameProperty2, "Jane Doe")
    }

    @Test
    fun `fromJenaModel converts Model to RdfGraph`() {
        val rdfGraph = JenaBridge.fromJenaModel(testModel)
        
        assertNotNull(rdfGraph)
        assertTrue(rdfGraph is JenaGraph)
        assertEquals(3, rdfGraph.size()) // John's name, type, and Jane's name
        
        val triples = rdfGraph.getTriples()
        assertEquals(3, triples.size)
        
        // Check that the triples contain expected data
        val nameTriple = triples.find { it.predicate.value == "http://xmlns.com/foaf/0.1/name" }
        assertNotNull(nameTriple)
        assertEquals("John Doe", (nameTriple!!.obj as Literal).lexical)
    }

    @Test
    fun `fromJenaGraph converts Graph to RdfGraph`() {
        val rdfGraph = JenaBridge.fromJenaGraph(testGraph)
        
        assertNotNull(rdfGraph)
        assertTrue(rdfGraph is JenaGraph)
        assertEquals(3, rdfGraph.size()) // All triples from the model
        
        val triples = rdfGraph.getTriples()
        assertEquals(3, triples.size)
        
        val janeTriple = triples.find { (it.subject as Iri).value == "http://example.org/jane" }
        assertNotNull(janeTriple)
        assertEquals("http://example.org/jane", (janeTriple!!.subject as Iri).value)
        assertEquals("http://xmlns.com/foaf/0.1/name", janeTriple.predicate.value)
        assertEquals("Jane Doe", (janeTriple.obj as Literal).lexical)
    }

    @Test
    fun `toJenaModel converts RdfGraph to Model`() {
        val rdfGraph = JenaBridge.fromJenaModel(testModel)
        val convertedModel = JenaBridge.toJenaModel(rdfGraph)
        
        assertNotNull(convertedModel)
        assertEquals(testModel.size(), convertedModel.size())
        
        // Check that the converted model contains the same data
        val statements = convertedModel.listStatements().toList()
        assertEquals(3, statements.size)
        
        val nameStatement = statements.find { 
            it.predicate.uri == "http://xmlns.com/foaf/0.1/name" 
        }
        assertNotNull(nameStatement)
        assertEquals("John Doe", nameStatement!!.`object`.asLiteral().lexicalForm)
    }

    @Test
    fun `toJenaGraph converts RdfGraph to Graph`() {
        val rdfGraph = JenaBridge.fromJenaGraph(testGraph)
        val convertedGraph = JenaBridge.toJenaGraph(rdfGraph)
        
        assertNotNull(convertedGraph)
        assertEquals(testGraph.size(), convertedGraph.size())
        
        // Check that the converted graph contains the same data
        val triples = convertedGraph.find().toList()
        assertEquals(3, triples.size)
        
        val janeTriple = triples.find { it.subject.uri == "http://example.org/jane" }
        assertNotNull(janeTriple)
        assertEquals("http://example.org/jane", janeTriple!!.subject.uri)
        assertEquals("http://xmlns.com/foaf/0.1/name", janeTriple.predicate.uri)
        assertEquals("Jane Doe", janeTriple.`object`.literalValue.toString())
    }

    @Test
    fun `createEmptyModel creates empty RdfGraph`() {
        val rdfGraph = JenaBridge.createEmptyModel()
        
        assertNotNull(rdfGraph)
        assertTrue(rdfGraph is JenaGraph)
        assertEquals(0, rdfGraph.size())
        assertTrue(rdfGraph.getTriples().isEmpty())
    }

    @Test
    fun `createEmptyGraph creates empty RdfGraph`() {
        val rdfGraph = JenaBridge.createEmptyGraph()
        
        assertNotNull(rdfGraph)
        assertTrue(rdfGraph is JenaGraph)
        assertEquals(0, rdfGraph.size())
        assertTrue(rdfGraph.getTriples().isEmpty())
    }

    @Test
    fun `createInferenceModel creates RdfGraph with RDFS inference`() {
        val rdfGraph = JenaBridge.createInferenceModel()
        
        assertNotNull(rdfGraph)
        assertTrue(rdfGraph is JenaGraph)
        assertEquals(0, rdfGraph.size())
        
        // Add some RDFS data to test inference
        val personClass = Iri("http://xmlns.com/foaf/0.1/Person")
        val agentClass = Iri("http://xmlns.com/foaf/0.1/Agent")
        val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
        val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        val john = Iri("http://example.org/john")
        
        rdfGraph.addTriple(RdfTriple(personClass, subClassOf, agentClass))
        rdfGraph.addTriple(RdfTriple(john, type, personClass))
        
        // With RDFS inference, john should also be of type Agent
        val triples = rdfGraph.getTriples()
        assertTrue(triples.any { 
            it.subject == john && it.predicate == type && it.obj == agentClass 
        })
    }

    @Test
    fun `fromString loads RDF data from string`() {
        val turtleData = """
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            @prefix ex: <http://example.org/> .
            
            ex:alice a foaf:Person ;
                     foaf:name "Alice Smith" ;
                     foaf:knows ex:bob .
                     
            ex:bob a foaf:Person ;
                   foaf:name "Bob Jones" .
        """.trimIndent()
        
        val rdfGraph = JenaBridge.fromString(turtleData, "TURTLE")
        
        assertNotNull(rdfGraph)
        assertEquals(4, rdfGraph.size()) // 2 type statements + 2 name statements + 1 knows statement
        
        val triples = rdfGraph.getTriples()
        assertTrue(triples.any { 
            (it.subject as Iri).value == "http://example.org/alice" && 
            (it.obj as Literal).lexical == "Alice Smith" 
        })
        assertTrue(triples.any { 
            (it.subject as Iri).value == "http://example.org/bob" && 
            (it.obj as Literal).lexical == "Bob Jones" 
        })
    }

    @Test
    fun `toString serializes RdfGraph to string`() {
        val rdfGraph = JenaBridge.createEmptyModel()
        
        // Add some test data
        val personClass = Iri("http://xmlns.com/foaf/0.1/Person")
        val nameProperty = Iri("http://xmlns.com/foaf/0.1/name")
        val john = Iri("http://example.org/john")
        
        rdfGraph.addTriple(RdfTriple(john, nameProperty, Literal("John Doe")))
        rdfGraph.addTriple(RdfTriple(john, Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), personClass))
        
        val turtleString = JenaBridge.toString(rdfGraph, "TURTLE")
        
        assertNotNull(turtleString)
        assertTrue(turtleString.contains("John Doe"))
        assertTrue(turtleString.contains("http://example.org/john"))
        assertTrue(turtleString.contains("http://xmlns.com/foaf/0.1/name"))
    }

    @Test
    fun `toString serializes to different formats`() {
        val rdfGraph = JenaBridge.createEmptyModel()
        
        val nameProperty = Iri("http://xmlns.com/foaf/0.1/name")
        val john = Iri("http://example.org/john")
        
        rdfGraph.addTriple(RdfTriple(john, nameProperty, Literal("John Doe")))
        
        // Test different formats
        val turtleString = JenaBridge.toString(rdfGraph, "TURTLE")
        val rdfXmlString = JenaBridge.toString(rdfGraph, "RDF/XML")
        val nTriplesString = JenaBridge.toString(rdfGraph, "N-TRIPLES")
        
        assertTrue(turtleString.contains("John Doe"))
        assertTrue(rdfXmlString.contains("John Doe"))
        assertTrue(nTriplesString.contains("John Doe"))
    }

    @Test
    fun `isJenaBacked identifies Jena-backed graphs`() {
        val jenaGraph = JenaBridge.createEmptyModel()
        val nonJenaGraph = Rdf.memory().defaultGraph
        
        assertTrue(JenaBridge.isJenaBacked(jenaGraph))
        assertFalse(JenaBridge.isJenaBacked(nonJenaGraph))
    }

    @Test
    fun `getJenaModel returns underlying Model for Jena-backed graphs`() {
        val rdfGraph = JenaBridge.fromJenaModel(testModel)
        val underlyingModel = JenaBridge.getJenaModel(rdfGraph)
        
        assertNotNull(underlyingModel)
        assertEquals(testModel, underlyingModel)
        
        val nonJenaGraph = Rdf.memory().defaultGraph
        assertNull(JenaBridge.getJenaModel(nonJenaGraph))
    }

    @Test
    fun `getJenaGraph returns underlying Graph for Jena-backed graphs`() {
        val rdfGraph = JenaBridge.fromJenaGraph(testGraph)
        val underlyingGraph = JenaBridge.getJenaGraph(rdfGraph)
        
        assertNotNull(underlyingGraph)
        assertEquals(testGraph, underlyingGraph)
        
        val nonJenaGraph = Rdf.memory().defaultGraph
        assertNull(JenaBridge.getJenaGraph(nonJenaGraph))
    }

    @Test
    fun `extension functions work correctly`() {
        val model = ModelFactory.createDefaultModel()
        val graph = model.graph
        
        // Test Model.toKastorGraph()
        val rdfGraph1 = model.toKastorGraph()
        assertTrue(rdfGraph1 is JenaGraph)
        
        // Test Graph.toKastorGraph()
        val rdfGraph2 = graph.toKastorGraph()
        assertTrue(rdfGraph2 is JenaGraph)
        
        // Test RdfGraph.toJenaModel()
        val convertedModel = rdfGraph1.toJenaModel()
        assertNotNull(convertedModel)
        
        // Test RdfGraph.toJenaGraph()
        val convertedGraph = rdfGraph2.toJenaGraph()
        assertNotNull(convertedGraph)
        
        // Test RdfGraph.isJenaBacked()
        assertTrue(rdfGraph1.isJenaBacked())
        assertTrue(rdfGraph2.isJenaBacked())
        
        val nonJenaGraph = Rdf.memory().defaultGraph
        assertFalse(nonJenaGraph.isJenaBacked())
        
        // Test RdfGraph.getJenaModel()
        assertNotNull(rdfGraph1.getJenaModel())
        assertNull(nonJenaGraph.getJenaModel())
        
        // Test RdfGraph.getJenaGraph()
        assertNotNull(rdfGraph2.getJenaGraph())
        assertNull(nonJenaGraph.getJenaGraph())
        
        // Test RdfGraph.serialize()
        val serialized = rdfGraph1.serialize("TURTLE")
        assertNotNull(serialized)
    }

    @Test
    fun `round-trip conversion preserves data`() {
        val originalModel = ModelFactory.createDefaultModel()
        
        // Add test data
        val personClass = ResourceFactory.createResource("http://xmlns.com/foaf/0.1/Person")
        val nameProperty = ResourceFactory.createProperty("http://xmlns.com/foaf/0.1/name")
        val john = ResourceFactory.createResource("http://example.org/john")
        
        originalModel.add(john, nameProperty, "John Doe")
        originalModel.add(john, ResourceFactory.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), personClass)
        
        // Convert to Kastor and back
        val rdfGraph = JenaBridge.fromJenaModel(originalModel)
        val convertedModel = JenaBridge.toJenaModel(rdfGraph)
        
        // Check that data is preserved
        assertEquals(originalModel.size(), convertedModel.size())
        
        val originalStatements = originalModel.listStatements().toList()
        val convertedStatements = convertedModel.listStatements().toList()
        
        assertEquals(originalStatements.size, convertedStatements.size)
        
        // Check that all statements are preserved
        for (originalStatement in originalStatements) {
            assertTrue(convertedStatements.any { converted ->
                converted.subject.uri == originalStatement.subject.uri &&
                converted.predicate.uri == originalStatement.predicate.uri &&
                converted.`object`.toString() == originalStatement.`object`.toString()
            })
        }
    }

    @Test
    fun `conversion handles different RDF term types`() {
        val rdfGraph = JenaBridge.createEmptyModel()
        
        // Test different term types
        val subject = Iri("http://example.org/subject")
        val predicate = Iri("http://example.org/predicate")
        val stringLiteral = Literal("Hello World")
        val typedLiteral = Literal("42", Iri("http://www.w3.org/2001/XMLSchema#integer"))
        val languageLiteral = Literal("Bonjour", "fr")
        val blankNode = BlankNode("_:b1")
        
        rdfGraph.addTriple(RdfTriple(subject, predicate, stringLiteral))
        rdfGraph.addTriple(RdfTriple(subject, predicate, typedLiteral))
        rdfGraph.addTriple(RdfTriple(subject, predicate, languageLiteral))
        rdfGraph.addTriple(RdfTriple(blankNode, predicate, stringLiteral))
        
        // Convert to Jena and back
        val jenaModel = JenaBridge.toJenaModel(rdfGraph)
        val convertedGraph = JenaBridge.fromJenaModel(jenaModel)
        
        assertEquals(4, convertedGraph.size())
        
        val triples = convertedGraph.getTriples()
        assertTrue(triples.any { it.obj == stringLiteral })
        assertTrue(triples.any { it.obj == typedLiteral })
        assertTrue(triples.any { it.obj == languageLiteral })
        assertTrue(triples.any { it.subject == blankNode })
    }
}
