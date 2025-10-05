package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.RDF

/**
 * Comprehensive example demonstrating the Graph DSL functionality.
 * 
 * The Graph DSL allows you to create standalone RDF graphs using all the same
 * syntax options available in the repository DSL, but without needing a repository.
 * This is perfect for creating reusable graph components, data validation,
 * and working with RDF data in a more functional way.
 */
fun main() {
    println("=== Graph DSL Examples ===\n")
    
    // Example 1: Simple person graph
    val personGraph = Rdf.graph {
        val person = iri("http://example.org/person")
        val friend = iri("http://example.org/friend")
        
        // Using bracket syntax
        person[FOAF.name] = "Alice"
        person[FOAF.age] = 30
        
        // Using has/with syntax
        person has FOAF.knows with friend
        
        // Using minus operator syntax (most concise)
        friend - FOAF.name - "Bob"
        friend - FOAF.age - 25
    }
    
    println("1. Person Graph (${personGraph.size()} triples):")
    personGraph.getTriples().forEach { triple ->
        println("   $triple")
    }
    println()
    
    // Example 2: Document metadata graph
    val documentGraph = Rdf.graph {
        val document = iri("http://example.org/document")
        val author = iri("http://example.org/author")
        val publisher = iri("http://example.org/publisher")
        
        // Mix different syntaxes
        document - DCTERMS.title - "The Art of RDF"
        document[DCTERMS.creator] = author
        document has DCTERMS.publisher with publisher
        document - DCTERMS.date - "2023-12-25"
        document - DCTERMS.language - "en"
        
        // Author information
        author - FOAF.name - "Jane Smith"
        author[DCTERMS.type] = iri("http://purl.org/dc/terms/Agent")
        
        // Publisher information
        publisher - FOAF.name - "RDF Publishing House"
        publisher - RDF.type - iri("http://purl.org/dc/terms/Agent")
    }
    
    println("2. Document Graph (${documentGraph.size()} triples):")
    documentGraph.getTriples().forEach { triple ->
        println("   $triple")
    }
    println()
    
    // Example 3: Complex knowledge graph with blank nodes
    val knowledgeGraph = Rdf.graph {
        val person = iri("http://example.org/person")
        val organization = iri("http://example.org/org")
        
        // Person details
        person - FOAF.name - "Dr. Sarah Johnson"
        person[FOAF.age] = 35
        person - RDF.type - FOAF.Person
        
        // Organization details
        organization - FOAF.name - "Research Institute"
        organization - RDF.type - FOAF.Organization
        
        // Employment relationship (using blank node for complex relationship)
        val employment = bnode("employment1")
        employment - RDF.type - iri("http://example.org/Employment")
        employment - iri("http://example.org/employee") - person
        employment - iri("http://example.org/employer") - organization
        employment - iri("http://example.org/position") - "Senior Researcher"
        employment - iri("http://example.org/startDate") - "2020-01-01"
    }
    
    println("3. Knowledge Graph (${knowledgeGraph.size()} triples):")
    knowledgeGraph.getTriples().forEach { triple ->
        println("   $triple")
    }
    println()
    
    // Example 4: Graph operations and manipulation
    println("4. Graph Operations:")
    
    // Create a base graph
    val baseGraph = Rdf.graph {
        val resource = iri("http://example.org/resource")
        resource - DCTERMS.title - "Base Resource"
        resource - DCTERMS.type - "Document"
    }
    
    println("   Base graph size: ${baseGraph.size()}")
    
    // Add more triples to the graph
    baseGraph.addTriple(RdfTriple(
        iri("http://example.org/resource"),
        DCTERMS.description,
        literal("A sample resource for demonstration")
    ))
    
    println("   After adding description: ${baseGraph.size()}")
    
    // Check if specific triple exists
    val titleTriple = RdfTriple(
        iri("http://example.org/resource"),
        DCTERMS.title,
        literal("Base Resource")
    )
    println("   Contains title triple: ${baseGraph.hasTriple(titleTriple)}")
    
    // Remove a triple
    val removed = baseGraph.removeTriple(titleTriple)
    println("   Removed title triple: $removed")
    println("   Final size: ${baseGraph.size()}")
    println()
    
    // Example 5: Combining graphs
    println("5. Combining Graphs:")
    
    val graph1 = Rdf.graph {
        iri("http://example.org/person1") - FOAF.name - "Alice"
        iri("http://example.org/person1") - FOAF.age - 30
    }
    
    val graph2 = Rdf.graph {
        iri("http://example.org/person2") - FOAF.name - "Bob"
        iri("http://example.org/person2") - FOAF.age - 25
    }
    
    // Create a combined graph
    val combinedGraph = Rdf.graph {
        addTriples(graph1.getTriples())
        addTriples(graph2.getTriples())
        
        // Add a relationship between the people
        iri("http://example.org/person1") - FOAF.knows - iri("http://example.org/person2")
    }
    
    println("   Combined graph size: ${combinedGraph.size()}")
    println("   Combined graph triples:")
    combinedGraph.getTriples().forEach { triple ->
        println("     $triple")
    }
    println()
    
    // Example 6: Empty graph
    val emptyGraph = Rdf.graph {
        // Empty DSL block
    }
    println("6. Empty Graph:")
    println("   Size: ${emptyGraph.size()}")
    println("   Is empty: ${emptyGraph.size() == 0}")
    println()
    
    println("=== Graph DSL Examples Complete ===")
}

/**
 * Utility function to demonstrate graph validation
 */
fun validatePersonGraph(graph: RdfGraph): Boolean {
    val triples = graph.getTriples()
    
    // Check if graph has at least one person with a name
    val hasPersonWithName = triples.any { triple ->
        triple.predicate == FOAF.name && triple.obj is Literal
    }
    
    // Check if graph has at least one age
    val hasAge = triples.any { triple ->
        triple.predicate == FOAF.age && triple.obj is Literal
    }
    
    return hasPersonWithName && hasAge
}

/**
 * Example of creating a graph template that can be reused
 */
fun createPersonTemplate(personUri: String, name: String, age: Int): RdfGraph {
    return Rdf.graph {
        val person = iri(personUri)
        person - FOAF.name - name
        person - FOAF.age - age
        person - RDF.type - FOAF.Person
    }
}

/**
 * Example usage of the template
 */
fun demonstrateTemplate() {
    println("=== Graph Templates ===")
    
    val aliceGraph = createPersonTemplate("http://example.org/alice", "Alice", 30)
    val bobGraph = createPersonTemplate("http://example.org/bob", "Bob", 25)
    
    println("Alice's graph: ${aliceGraph.size()} triples")
    println("Bob's graph: ${bobGraph.size()} triples")
    
    // Validate the graphs
    println("Alice graph is valid: ${validatePersonGraph(aliceGraph)}")
    println("Bob graph is valid: ${validatePersonGraph(bobGraph)}")
}
