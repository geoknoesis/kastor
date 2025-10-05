package com.example.dcatus

import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.jena.*
import com.geoknoesis.kastor.rdf.*
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.ResourceFactory

/**
 * Simple demonstration of the Jena Bridge functionality.
 */
fun main() {
    println("=== Simple Jena Bridge Demo ===\n")
    
    // Create a Jena Model
    val jenaModel = ModelFactory.createDefaultModel()
    val personClass = ResourceFactory.createResource("http://xmlns.com/foaf/0.1/Person")
    val nameProperty = ResourceFactory.createProperty("http://xmlns.com/foaf/0.1/name")
    val john = ResourceFactory.createResource("http://example.org/john")
    
    jenaModel.add(john, nameProperty, "John Doe")
    jenaModel.add(john, ResourceFactory.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), personClass)
    
    println("Jena Model created with ${jenaModel.size()} statements")
    
    // Convert to Kastor RdfGraph using extension function
    val kastorGraph = jenaModel.toKastorGraph()
    println("Converted to Kastor RdfGraph with ${kastorGraph.size()} triples")
    
    // Use Kastor API
    val triples = kastorGraph.getTriples()
    println("Triples in Kastor graph:")
    triples.forEach { triple ->
        println("  ${triple.subject} ${triple.predicate} ${triple.obj}")
    }
    
    // Add more data using Kastor API
    val jane = Iri("http://example.org/jane")
    kastorGraph.addTriple(RdfTriple(jane, Iri("http://xmlns.com/foaf/0.1/name"), Literal("Jane Doe")))
    
    println("Added Jane using Kastor API: ${kastorGraph.size()} triples")
    
    // Convert back to Jena using extension function
    val convertedModel = kastorGraph.toJenaModel()
    println("Converted back to Jena Model: ${convertedModel.size()} statements")
    
    // Serialize using extension function
    val turtleString = kastorGraph.serialize("TURTLE")
    println("Serialized to Turtle:")
    println(turtleString)
    
    println("\n=== Demo Complete ===")
}
