package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.dsl.bag
import com.geoknoesis.kastor.rdf.dsl.seq
import com.geoknoesis.kastor.rdf.dsl.alt
import com.geoknoesis.kastor.rdf.dsl.values
import com.geoknoesis.kastor.rdf.dsl.list

/**
 * Example demonstrating the new RDF container DSL functions.
 */
fun main() {
    val repo = Rdf.memory()
    val person = Iri("http://example.org/person/alice")
    val friend1 = Iri("http://example.org/person/bob")
    val friend2 = Iri("http://example.org/person/charlie")
    val friend3 = Iri("http://example.org/person/diana")

    repo.add {
        // Basic properties
        person - FOAF.name - "Alice"
        person - FOAF.age - 30
        
        // Multiple individual triples using values() function
        person - FOAF.knows - values(friend1, friend2, friend3)
        
        // RDF List using list() function
        person - FOAF.mbox - list("alice@example.com", "alice@work.com")
        
        // RDF Containers using bag(), seq(), alt() functions
        person - DCTERMS.subject - bag("Technology", "AI", "RDF", "Technology")  // rdf:Bag (duplicates allowed)
        person - FOAF.knows - seq(friend1, friend2, friend3)                    // rdf:Seq (ordered)
        person - FOAF.mbox - alt("alice@example.com", "alice@work.com")        // rdf:Alt (alternatives)
        
        // Mixed types work with all functions
        person - DCTERMS.creator - values("Alice", "Bob", 42, true)
    }

    // Query the results
    val allTriples = repo.defaultGraph.getTriples()
    println("Total triples created: ${allTriples.size}")
    
    // Show the different container types created
    println("\n=== RDF Container Examples ===")
    println("✅ Individual triples: values() creates separate triples for each value")
    println("✅ RDF Lists: list() creates proper rdf:first, rdf:rest, rdf:nil structure")
    println("✅ RDF Bag: bag() creates rdf:Bag with rdf:_1, rdf:_2, etc. (duplicates allowed)")
    println("✅ RDF Seq: seq() creates rdf:Seq with rdf:_1, rdf:_2, etc. (ordered)")
    println("✅ RDF Alt: alt() creates rdf:Alt with rdf:_1, rdf:_2, etc. (alternatives)")
    
    // Count different types of triples
    val personTriples = allTriples.filter { it.subject == person }
    val containerTriples = allTriples.filter { 
        it.predicate.value.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#_") 
    }
    val typeTriples = allTriples.filter { it.predicate.value == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" }
    
    println("\n=== Statistics ===")
    println("Direct person properties: ${personTriples.size}")
    println("Container member triples: ${containerTriples.size}")
    println("Type declaration triples: ${typeTriples.size}")
    
    repo.close()
}









