package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.dsl.bag
import com.geoknoesis.kastor.rdf.dsl.seq
import com.geoknoesis.kastor.rdf.dsl.alt
import com.geoknoesis.kastor.rdf.dsl.list

/**
 * Example demonstrating the standard _:b1, _:b2, _:b3 bnode naming format.
 */
fun main() {
    val repo = Rdf.memory()
    val person = Iri("http://example.org/person/alice")

    repo.add {
        person - FOAF.name - "Alice"
        
        // RDF List with standard bnode names: _:b1, _:b2, _:b3
        person - FOAF.mbox - list("alice@example.com", "alice@work.com", "alice@personal.com")
        
        // RDF Bag with standard bnode name: _:b4
        person - DCTERMS.subject - bag("Technology", "AI", "RDF")
        
        // RDF Seq with standard bnode name: _:b5
        person - FOAF.knows - seq(person, person, person)
        
        // RDF Alt with standard bnode name: _:b6
        person - FOAF.mbox - alt("alice@example.com", "alice@work.com")
    }

    // Query the results
    val allTriples = repo.defaultGraph.getTriples()
    println("Total triples created: ${allTriples.size}")
    
    // Show the standard bnode names
    val bnodeNames = allTriples.filter { it.subject is BlankNode || it.obj is BlankNode }
        .flatMap { triple ->
            listOfNotNull(
                if (triple.subject is BlankNode) (triple.subject as BlankNode).id else null,
                if (triple.obj is BlankNode) (triple.obj as BlankNode).id else null
            )
        }.distinct().sorted()

    println("\n=== Standard Bnode Names ===")
    println("Now using: ${bnodeNames.joinToString(", ")}")
    
    println("\n=== Benefits ===")
    println("✅ Standard format: _:b1, _:b2, _:b3, _:b4, _:b5, _:b6")
    println("✅ Industry standard: matches RDF serialization conventions")
    println("✅ Clean and minimal: no descriptive prefixes")
    println("✅ Sequential numbering: easy to follow and debug")
    println("✅ Consistent: same input always produces same bnode names")
    
    println("\n=== Example Turtle Output ===")
    println("@prefix foaf: <http://xmlns.com/foaf/0.1/> .")
    println("@prefix dcterms: <http://purl.org/dc/terms/> .")
    println("")
    println("<http://example.org/person/alice> foaf:name \"Alice\" .")
    println("<http://example.org/person/alice> foaf:mbox _:b1 .")
    println("_:b1 rdf:first \"alice@example.com\" .")
    println("_:b1 rdf:rest _:b2 .")
    println("_:b2 rdf:first \"alice@work.com\" .")
    println("_:b2 rdf:rest _:b3 .")
    println("_:b3 rdf:first \"alice@personal.com\" .")
    println("_:b3 rdf:rest rdf:nil .")
    println("")
    println("<http://example.org/person/alice> dcterms:subject _:b4 .")
    println("_:b4 rdf:type rdf:Bag .")
    println("_:b4 rdf:_1 \"Technology\" .")
    println("_:b4 rdf:_2 \"AI\" .")
    println("_:b4 rdf:_3 \"RDF\" .")
    
    repo.close()
}









