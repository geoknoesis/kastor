# Jena Bridge Tutorial

This tutorial demonstrates how to use the Jena Bridge to seamlessly convert between Jena Model/Graph objects and Kastor RdfGraph objects, enabling interoperability between the two RDF libraries.

## Table of Contents

- [Overview](#overview)
- [Basic Usage](#basic-usage)
- [Conversion Methods](#conversion-methods)
- [Extension Functions](#extension-functions)
- [Advanced Features](#advanced-features)
- [Examples](#examples)
- [Best Practices](#best-practices)
- [Performance Considerations](#performance-considerations)

## Overview

The Jena Bridge provides a set of utilities that allow you to:

- Convert Jena Model objects to Kastor RdfGraph objects
- Convert Jena Graph objects to Kastor RdfGraph objects
- Convert Kastor RdfGraph objects back to Jena Model/Graph objects
- Create new graphs with different Jena configurations
- Load and serialize RDF data in various formats
- Use convenient extension functions for seamless integration

## Basic Usage

### Import the Bridge

```kotlin
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import com.geoknoesis.kastor.rdf.jena.* // For extension functions
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.graph.GraphFactory
```

### Converting Jena to Kastor

```kotlin
// Create a Jena Model
val jenaModel = ModelFactory.createDefaultModel()
jenaModel.add(
    jenaModel.createResource("http://example.org/john"),
    jenaModel.createProperty("http://xmlns.com/foaf/0.1/name"),
    "John Doe"
)

// Convert to Kastor RdfGraph
val kastorGraph = JenaBridge.fromJenaModel(jenaModel)

// Or use the extension function
val kastorGraph2 = jenaModel.toKastorGraph()

// Use Kastor API
println("Graph size: ${kastorGraph.size()}")
val triples = kastorGraph.getTriples()
triples.forEach { triple ->
    println("${triple.subject} ${triple.predicate} ${triple.obj}")
}
```

### Converting Kastor to Jena

```kotlin
// Create a Kastor RdfGraph
val kastorGraph = Rdf.memory().defaultGraph
kastorGraph.addTriple(
    RdfTriple(
        Iri("http://example.org/jane"),
        Iri("http://xmlns.com/foaf/0.1/name"),
        Literal("Jane Doe")
    )
)

// Convert to Jena Model
val jenaModel = JenaBridge.toJenaModel(kastorGraph)

// Or use the extension function
val jenaModel2 = kastorGraph.toJenaModel()

// Use Jena API
val statements = jenaModel.listStatements().toList()
statements.forEach { statement ->
    println("${statement.subject} ${statement.predicate} ${statement.`object`}")
}
```

## Conversion Methods

### Jena Model to Kastor RdfGraph

```kotlin
// Basic conversion
val jenaModel = ModelFactory.createDefaultModel()
val kastorGraph = JenaBridge.fromJenaModel(jenaModel)

// Extension function
val kastorGraph2 = jenaModel.toKastorGraph()
```

### Jena Graph to Kastor RdfGraph

```kotlin
// Basic conversion
val jenaGraph = GraphFactory.createDefaultGraph()
val kastorGraph = JenaBridge.fromJenaGraph(jenaGraph)

// Extension function
val kastorGraph2 = jenaGraph.toKastorGraph()
```

### Kastor RdfGraph to Jena Model

```kotlin
// Basic conversion
val kastorGraph = Rdf.memory().defaultGraph
val jenaModel = JenaBridge.toJenaModel(kastorGraph)

// Extension function
val jenaModel2 = kastorGraph.toJenaModel()
```

### Kastor RdfGraph to Jena Graph

```kotlin
// Basic conversion
val kastorGraph = Rdf.memory().defaultGraph
val jenaGraph = JenaBridge.toJenaGraph(kastorGraph)

// Extension function
val jenaGraph2 = kastorGraph.toJenaGraph()
```

## Extension Functions

The Jena Bridge provides convenient extension functions for seamless integration:

```kotlin
// Jena to Kastor
val kastorGraph = jenaModel.toKastorGraph()
val kastorGraph2 = jenaGraph.toKastorGraph()

// Kastor to Jena
val jenaModel = kastorGraph.toJenaModel()
val jenaGraph = kastorGraph.toJenaGraph()

// Utility functions
val isJenaBacked = kastorGraph.isJenaBacked()
val underlyingModel = kastorGraph.getJenaModel()
val underlyingGraph = kastorGraph.getJenaGraph()

// Serialization
val turtleString = kastorGraph.serialize("TURTLE")
val rdfXmlString = kastorGraph.serialize("RDF/XML")
```

## Advanced Features

### Creating Specialized Graphs

```kotlin
// Empty graphs
val emptyModel = JenaBridge.createEmptyModel()
val emptyGraph = JenaBridge.createEmptyGraph()

// Inference-enabled graphs
val rdfsModel = JenaBridge.createInferenceModel()
val owlModel = JenaBridge.createOwlInferenceModel()

// Add RDFS data to test inference
val personClass = Iri("http://xmlns.com/foaf/0.1/Person")
val agentClass = Iri("http://xmlns.com/foaf/0.1/Agent")
val subClassOf = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
val john = Iri("http://example.org/john")

rdfsModel.addTriple(RdfTriple(personClass, subClassOf, agentClass))
rdfsModel.addTriple(RdfTriple(john, type, personClass))

// With RDFS inference, john should also be of type Agent
val triples = rdfsModel.getTriples()
assertTrue(triples.any { 
    it.subject == john && it.predicate == type && it.obj == agentClass 
})
```

### Loading RDF Data

```kotlin
// From string
val turtleData = """
    @prefix foaf: <http://xmlns.com/foaf/0.1/> .
    @prefix ex: <http://example.org/> .
    
    ex:alice a foaf:Person ;
             foaf:name "Alice Smith" ;
             foaf:knows ex:bob .
             
    ex:bob a foaf:Person ;
           foaf:name "Bob Jones" .
""".trimIndent()

val RdfGraph = JenaBridge.fromString(turtleData, "TURTLE")

// From file
val rdfGraph2 = JenaBridge.fromFile("data.ttl", "TURTLE")

// From URL
val rdfGraph3 = JenaBridge.fromUrl("https://example.org/data.ttl", "TURTLE")
```

### Serializing RDF Data

```kotlin
val RdfGraph = JenaBridge.createEmptyModel()

// Add some data
val nameProperty = Iri("http://xmlns.com/foaf/0.1/name")
val john = Iri("http://example.org/john")
RdfGraph.addTriple(RdfTriple(john, nameProperty, Literal("John Doe")))

// Serialize to different formats
val turtleString = JenaBridge.toString(RdfGraph, "TURTLE")
val rdfXmlString = JenaBridge.toString(RdfGraph, "RDF/XML")
val nTriplesString = JenaBridge.toString(RdfGraph, "N-TRIPLES")
val jsonLdString = JenaBridge.toString(RdfGraph, "JSON-LD")

// Or use extension function
val turtleString2 = RdfGraph.serialize("TURTLE")
```

### Checking Jena Backing

```kotlin
val jenaGraph = JenaBridge.createEmptyModel()
val nonJenaGraph = Rdf.memory().defaultGraph

// Check if graph is Jena-backed
val isJenaBacked = JenaBridge.isJenaBacked(jenaGraph) // true
val isNotJenaBacked = JenaBridge.isJenaBacked(nonJenaGraph) // false

// Or use extension function
val isJenaBacked2 = jenaGraph.isJenaBacked() // true

// Get underlying Jena objects
val underlyingModel = JenaBridge.getJenaModel(jenaGraph)
val underlyingGraph = JenaBridge.getJenaGraph(jenaGraph)

// Or use extension functions
val underlyingModel2 = jenaGraph.getJenaModel()
val underlyingGraph2 = jenaGraph.getJenaGraph()
```

## Examples

### Example 1: Migrating from Jena to Kastor

```kotlin
// Existing Jena code
val jenaModel = ModelFactory.createDefaultModel()
val personClass = jenaModel.createResource("http://xmlns.com/foaf/0.1/Person")
val nameProperty = jenaModel.createProperty("http://xmlns.com/foaf/0.1/name")
val john = jenaModel.createResource("http://example.org/john")

jenaModel.add(john, nameProperty, "John Doe")
jenaModel.add(john, jenaModel.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), personClass)

// Convert to Kastor
val kastorGraph = jenaModel.toKastorGraph()

// Use Kastor API
val triples = kastorGraph.getTriples()
val nameTriple = triples.find { 
    it.predicate.value == "http://xmlns.com/foaf/0.1/name" 
}
println("Name: ${(nameTriple!!.obj as Literal).lexical}")

// Convert back to Jena if needed
val convertedModel = kastorGraph.toJenaModel()
val statements = convertedModel.listStatements().toList()
println("Number of statements: ${statements.size}")
```

### Example 2: Using Jena Inference with Kastor

```kotlin
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

// Query inferred triples using Kastor
val triples = rdfsGraph.getTriples()
val inferredTriples = triples.filter { 
    it.subject == john && it.predicate == type && it.obj == agentClass 
}

println("Inferred triples: ${inferredTriples.size}")
// Output: Inferred triples: 1
```

### Example 3: Loading and Processing RDF Data

```kotlin
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

val RdfGraph = JenaBridge.fromString(turtleData, "TURTLE")

// Process using Kastor API
val triples = RdfGraph.getTriples()
val nameTriples = triples.filter { 
    it.predicate.value == "http://xmlns.com/foaf/0.1/name" 
}

nameTriples.forEach { triple ->
    val subject = (triple.subject as Iri).value
    val name = (triple.obj as Literal).lexical
    println("$subject has name: $name")
}

// Serialize back to RDF/XML
val rdfXmlString = RdfGraph.serialize("RDF/XML")
println("RDF/XML:\n$rdfXmlString")
```

### Example 4: Round-trip Conversion

```kotlin
// Start with Jena
val jenaModel = ModelFactory.createDefaultModel()
val personClass = jenaModel.createResource("http://xmlns.com/foaf/0.1/Person")
val nameProperty = jenaModel.createProperty("http://xmlns.com/foaf/0.1/name")
val john = jenaModel.createResource("http://example.org/john")

jenaModel.add(john, nameProperty, "John Doe")
jenaModel.add(john, jenaModel.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), personClass)

// Convert to Kastor
val kastorGraph = jenaModel.toKastorGraph()

// Modify using Kastor API
val jane = Iri("http://example.org/jane")
kastorGraph.addTriple(RdfTriple(jane, Iri("http://xmlns.com/foaf/0.1/name"), Literal("Jane Doe")))

// Convert back to Jena
val convertedModel = kastorGraph.toJenaModel()

// Verify data integrity
val originalStatements = jenaModel.listStatements().toList()
val convertedStatements = convertedModel.listStatements().toList()

println("Original statements: ${originalStatements.size}")
println("Converted statements: ${convertedStatements.size}")
// Output: Original statements: 2, Converted statements: 3
```

## Best Practices

### 1. Use Extension Functions for Cleaner Code

```kotlin
// ✅ Good: Use extension functions
val kastorGraph = jenaModel.toKastorGraph()
val jenaModel2 = kastorGraph.toJenaModel()

// ❌ Avoid: Verbose method calls
val kastorGraph = JenaBridge.fromJenaModel(jenaModel)
val jenaModel2 = JenaBridge.toJenaModel(kastorGraph)
```

### 2. Check Jena Backing Before Accessing Underlying Objects

```kotlin
// ✅ Good: Check before accessing
if (RdfGraph.isJenaBacked()) {
    val jenaModel = RdfGraph.getJenaModel()
    // Use Jena-specific features
}

// ❌ Avoid: Direct access without checking
val jenaModel = RdfGraph.getJenaModel() // May return null
```

### 3. Use Appropriate Graph Types for Your Use Case

```kotlin
// ✅ Good: Use specialized graphs when needed
val rdfsGraph = JenaBridge.createInferenceModel() // For RDFS inference
val owlGraph = JenaBridge.createOwlInferenceModel() // For OWL inference
val simpleGraph = JenaBridge.createEmptyModel() // For simple operations

// ❌ Avoid: Using inference when not needed
val rdfsGraph = JenaBridge.createInferenceModel() // Overkill for simple operations
```

### 4. Handle Different RDF Term Types

```kotlin
// ✅ Good: Handle all term types
val triples = RdfGraph.getTriples()
triples.forEach { triple ->
    when (triple.obj) {
        is Literal -> println("Literal: ${triple.obj.lexical}")
        is Iri -> println("IRI: ${triple.obj.value}")
        is BlankNode -> println("Blank node: ${triple.obj.id}")
    }
}

// ❌ Avoid: Assuming specific term types
val literal = triple.obj as Literal // May throw ClassCastException
```

### 5. Use Appropriate Serialization Formats

```kotlin
// ✅ Good: Choose format based on use case
val turtleString = RdfGraph.serialize("TURTLE") // Human-readable
val rdfXmlString = RdfGraph.serialize("RDF/XML") // Standard format
val nTriplesString = RdfGraph.serialize("N-TRIPLES") // Simple format
val jsonLdString = RdfGraph.serialize("JSON-LD") // Web-friendly

// ❌ Avoid: Using wrong format for the use case
val turtleString = RdfGraph.serialize("TURTLE") // Not suitable for machine processing
```

## Performance Considerations

### 1. Minimize Conversions

```kotlin
// ✅ Good: Minimize conversions
val jenaModel = ModelFactory.createDefaultModel()
// ... add data to Jena model ...
val kastorGraph = jenaModel.toKastorGraph()
// ... use Kastor API extensively ...
val finalModel = kastorGraph.toJenaModel()

// ❌ Avoid: Frequent conversions
val jenaModel = ModelFactory.createDefaultModel()
val kastorGraph = jenaModel.toKastorGraph()
val jenaModel2 = kastorGraph.toJenaModel()
val kastorGraph2 = jenaModel2.toKastorGraph() // Unnecessary conversion
```

### 2. Use Jena-Backed Graphs When Possible

```kotlin
// ✅ Good: Use Jena-backed graphs for Jena operations
val jenaGraph = JenaBridge.createEmptyModel()
// ... operations using Kastor API ...
if (jenaGraph.isJenaBacked()) {
    val jenaModel = jenaGraph.getJenaModel()
    // Use Jena-specific features efficiently
}

// ❌ Avoid: Converting non-Jena graphs frequently
val nonJenaGraph = Rdf.memory().defaultGraph
val jenaModel = nonJenaGraph.toJenaModel() // Expensive conversion
```

### 3. Batch Operations

```kotlin
// ✅ Good: Batch operations
val triples = listOf(
    RdfTriple(subject1, predicate1, object1),
    RdfTriple(subject2, predicate2, object2),
    RdfTriple(subject3, predicate3, object3)
)
RdfGraph.addTriples(triples)

// ❌ Avoid: Individual operations
RdfGraph.addTriple(RdfTriple(subject1, predicate1, object1))
RdfGraph.addTriple(RdfTriple(subject2, predicate2, object2))
RdfGraph.addTriple(RdfTriple(subject3, predicate3, object3))
```

### 4. Memory Management

```kotlin
// ✅ Good: Clear graphs when done
val RdfGraph = JenaBridge.createEmptyModel()
// ... use graph ...
RdfGraph.clear() // Free memory

// ❌ Avoid: Keeping large graphs in memory
val RdfGraph = JenaBridge.createEmptyModel()
// ... add large amounts of data ...
// Graph remains in memory even when not needed
```

## Conclusion

The Jena Bridge provides a powerful way to integrate Jena and Kastor RDF libraries, enabling you to:

- **Seamlessly convert** between Jena and Kastor objects
- **Use the best features** of both libraries
- **Migrate gradually** from Jena to Kastor
- **Maintain compatibility** with existing Jena code
- **Leverage Jena's inference** capabilities with Kastor's clean API

For more information, see:
- [Kastor RDF Core API](core-api.md)
- [Jena Integration](enhanced-jena.md)
- [Best Practices](best-practices.md)
- [Performance Guide](performance.md)



