package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.RDFS

/**
 * Example demonstrating the new minus operator DSL syntax.
 * 
 * The minus operator provides a more concise alternative to the "has...with" syntax.
 * Both syntaxes are equivalent and can be mixed within the same DSL block.
 */
fun main() {
    val repo = Rdf.memory()
    
    // Create some resources
    val person = Iri("http://example.org/person")
    val friend = Iri("http://example.org/friend")
    val document = Iri("http://example.org/document")
    
    // Using the new minus operator syntax
    repo.add {
        // Basic minus operator syntax
        person - FOAF.name - "Alice"
        person - FOAF.age - 30
        person - FOAF.knows - friend
        
        // Mixed with bracket syntax
        person[DCTERMS.title] = "Person Title"
        
        // More minus operator examples
        friend - FOAF.name - "Bob"
        friend - FOAF.age - 25
        
        document - DCTERMS.title - "Example Document"
        document - DCTERMS.creator - person
        document - DCTERMS.date - "2023-12-25"
        
        // Using with different literal types
        person - RDFS.comment - "A sample person"
        document - DCTERMS.extent - 1000
        document - DCTERMS.available - true
    }
    
    // Query the data
    val results = repo.select(SparqlSelectQuery("""
        SELECT ?subject ?predicate ?object WHERE {
            ?subject ?predicate ?object .
        } ORDER BY ?subject ?predicate
    """.trimIndent()))
    
    println("=== RDF Data using minus operator syntax ===")
    results.forEach { binding ->
        val subject = binding.get("subject")?.toString() ?: "null"
        val predicate = binding.get("predicate")?.toString() ?: "null"
        val obj = binding.get("object")?.toString() ?: "null"
        println("$subject $predicate $obj")
    }
    
    println("\n=== Repository Statistics ===")
    println("Total triples: ${repo.defaultGraph.size()}")
    
    repo.close()
}

/**
 * Comparison of different DSL syntaxes:
 * 
 * // Original bracket syntax
 * person[FOAF.name] = "Alice"
 * 
 * // Natural language syntax
 * person has FOAF.name with "Alice"
 * 
 * // New minus operator syntax (most concise)
 * person - FOAF.name - "Alice"
 * 
 * All three syntaxes are equivalent and can be mixed within the same DSL block.
 */









