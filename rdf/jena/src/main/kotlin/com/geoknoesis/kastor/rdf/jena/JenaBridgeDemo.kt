package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.ResourceFactory

/**
 * Demonstration of the Jena Bridge functionality.
 * 
 * This example shows how to use the Jena Bridge to convert between
 * Jena Model/Graph objects and Kastor RdfGraph objects.
 */
fun main() {
    println("=== Jena Bridge Demo ===\n")
    
    // Example 1: Basic conversion
    basicConversionExample()
    
    // Example 2: Loading RDF data
    loadingDataExample()
    
    // Example 3: Using inference
    inferenceExample()
    
    // Example 4: Round-trip conversion
    roundTripExample()
    
    // Example 5: Extension functions
    extensionFunctionsExample()
}

fun basicConversionExample() {
    println("1. Basic Conversion Example")
    println("=" * 30)
    
    // Create a Jena Model
    val jenaModel = ModelFactory.createDefaultModel()
    val personClass = ResourceFactory.createResource("http://xmlns.com/foaf/0.1/Person")
    val nameProperty = ResourceFactory.createProperty("http://xmlns.com/foaf/0.1/name")
    val john = ResourceFactory.createResource("http://example.org/john")
    
    jenaModel.add(john, nameProperty, "John Doe")
    jenaModel.add(john, ResourceFactory.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), personClass)
    
    println("Jena Model created with ${jenaModel.size()} statements")
    
    // Convert to Kastor RdfGraph
    val kastorGraph = jenaModel.toKastorGraph()
    println("Converted to Kastor RdfGraph with ${kastorGraph.size()} triples")
    
    // Use Kastor API
    val triples = kastorGraph.getTriples()
    println("Triples in Kastor graph:")
    triples.forEach { triple ->
        println("  ${triple.subject} ${triple.predicate} ${triple.obj}")
    }
    
    // Convert back to Jena
    val convertedModel = kastorGraph.toJenaModel()
    println("Converted back to Jena Model with ${convertedModel.size()} statements")
    
    println()
}

fun loadingDataExample() {
    println("2. Loading RDF Data Example")
    println("=" * 30)
    
    // Load RDF data from string
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
    println("Loaded RDF data into Kastor graph with ${rdfGraph.size()} triples")
    
    // Process using Kastor API
    val triples = rdfGraph.getTriples()
    val nameTriples = triples.filter { 
        it.predicate.value == "http://xmlns.com/foaf/0.1/name" 
    }
    
    println("Name triples:")
    nameTriples.forEach { triple ->
        val subject = (triple.subject as Iri).value
        val name = (triple.obj as Literal).lexical
        println("  $subject has name: $name")
    }
    
    // Serialize back to RDF/XML
    val rdfXmlString = rdfGraph.serialize("RDF/XML")
    println("Serialized to RDF/XML (first 200 chars):")
    println(rdfXmlString.take(200) + "...")
    
    println()
}

fun inferenceExample() {
    println("3. Inference Example")
    println("=" * 30)
    
    // Create RDFS inference model
    val rdfsGraph = JenaBridge.createInferenceModel()
    
    // Define RDFS hierarchy
    val personClass = Iri("http://xmlns.com/foaf/0.1/Person")
    val agentClass = Iri("http://xmlns.com/foaf/0.1/Agent")
    val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    val john = Iri("http://example.org/john")
    
    // Add RDFS rules
    rdfsGraph.addTriple(RdfTriple(personClass, subClassOf, agentClass))
    rdfsGraph.addTriple(RdfTriple(john, type, personClass))
    
    println("Added RDFS rules:")
    println("  Person subClassOf Agent")
    println("  john type Person")
    
    // Query inferred triples using Kastor
    val triples = rdfsGraph.getTriples()
    val inferredTriples = triples.filter { 
        it.subject == john && it.predicate == type && it.obj == agentClass 
    }
    
    println("Inferred triples: ${inferredTriples.size}")
    if (inferredTriples.isNotEmpty()) {
        println("  john type Agent (inferred)")
    }
    
    println("Total triples in graph: ${triples.size}")
    
    println()
}

fun roundTripExample() {
    println("4. Round-trip Conversion Example")
    println("=" * 30)
    
    // Start with Jena
    val jenaModel = ModelFactory.createDefaultModel()
    val personClass = ResourceFactory.createResource("http://xmlns.com/foaf/0.1/Person")
    val nameProperty = ResourceFactory.createProperty("http://xmlns.com/foaf/0.1/name")
    val john = ResourceFactory.createResource("http://example.org/john")
    
    jenaModel.add(john, nameProperty, "John Doe")
    jenaModel.add(john, ResourceFactory.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), personClass)
    
    println("Original Jena Model: ${jenaModel.size()} statements")
    
    // Convert to Kastor
    val kastorGraph = jenaModel.toKastorGraph()
    println("Converted to Kastor: ${kastorGraph.size()} triples")
    
    // Modify using Kastor API
    val jane = Iri("http://example.org/jane")
    kastorGraph.addTriple(RdfTriple(jane, Iri("http://xmlns.com/foaf/0.1/name"), Literal("Jane Doe")))
    kastorGraph.addTriple(RdfTriple(jane, Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), personClass.toKastorIri()))
    
    println("Added Jane using Kastor API: ${kastorGraph.size()} triples")
    
    // Convert back to Jena
    val convertedModel = kastorGraph.toJenaModel()
    println("Converted back to Jena: ${convertedModel.size()} statements")
    
    // Verify data integrity
    val originalStatements = jenaModel.listStatements().toList()
    val convertedStatements = convertedModel.listStatements().toList()
    
    println("Data integrity check:")
    println("  Original statements: ${originalStatements.size}")
    println("  Converted statements: ${convertedStatements.size}")
    println("  Data preserved: ${convertedStatements.size >= originalStatements.size}")
    
    println()
}

fun extensionFunctionsExample() {
    println("5. Extension Functions Example")
    println("=" * 30)
    
    // Create a Jena Model
    val jenaModel = ModelFactory.createDefaultModel()
    val nameProperty = ResourceFactory.createProperty("http://xmlns.com/foaf/0.1/name")
    val john = ResourceFactory.createResource("http://example.org/john")
    jenaModel.add(john, nameProperty, "John Doe")
    
    // Use extension functions
    val kastorGraph = jenaModel.toKastorGraph()
    println("Converted using extension function: ${kastorGraph.size()} triples")
    
    // Check if Jena-backed
    val isJenaBacked = kastorGraph.isJenaBacked()
    println("Is Jena-backed: $isJenaBacked")
    
    // Get underlying Jena objects
    val underlyingModel = kastorGraph.getJenaModel()
    println("Underlying Jena Model: ${underlyingModel?.size()} statements")
    
    // Serialize using extension function
    val turtleString = kastorGraph.serialize("TURTLE")
    println("Serialized to Turtle:")
    println(turtleString)
    
    println()
}

// Helper extension function for string repetition
private operator fun String.times(n: Int): String = this.repeat(n)

// Helper extension function to convert Jena Resource to Kastor Iri
private fun org.apache.jena.rdf.model.Resource.toKastorIri(): Iri {
    return Iri(this.uri)
}
