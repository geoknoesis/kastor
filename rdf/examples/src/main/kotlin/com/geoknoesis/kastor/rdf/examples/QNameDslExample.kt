package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*

fun main() {
    println("=== QName DSL Example with Smart QName Detection ===\n")
    
    // This example demonstrates smart QName detection in object position:
    // - QNames with declared prefixes → IRIs
    // - Full IRIs → IRIs  
    // - Strings without colons → string literals
    // - Undeclared prefixes → string literals (safe fallback)
    
    // Create a repository and use QNames with prefix mappings
    val repo = Rdf.memory()
    
    repo.add {
        // Configure prefix mappings
        prefixes {
            put("foaf", "http://xmlns.com/foaf/0.1/")
            put("dcat", "http://www.w3.org/ns/dcat#")
            put("dcterms", "http://purl.org/dc/terms/")
        }
        
        // Use QNames with minus operator and smart QName detection
        val catalog = iri("http://example.org/catalog")
        catalog - "dcterms:title" - "My Data Catalog"
        catalog - "dcterms:description" - "A catalog of datasets"
        catalog - "dcat:dataset" - "http://example.org/dataset1"  // Full IRI → IRI
        catalog - "dcat:dataset" - iri("http://example.org/dataset2")
        
        // Use QNames with bracket syntax and smart QName detection
        val person = iri("http://example.org/person")
        person["foaf:name"] = "Alice"
        person["foaf:age"] = 30
        person["foaf:knows"] = "http://example.org/bob"  // Full IRI → IRI
        
        // Smart QName detection in object position
        person - "foaf:knows" - "foaf:Person"        // QName with declared prefix → IRI
        person - "foaf:homepage" - "http://example.org/profile"  // Full IRI → IRI
        person - "foaf:note" - "unknown:Person"      // Undeclared prefix → string literal
        
        // Use QNames with natural language syntax
        val bob = iri("http://example.org/bob")
        bob has "foaf:name" with "Bob"
        bob has "foaf:age" with 25
    }
    
    println("Repository now has ${repo.getTriples().size} triples")
    println()
    
    // Print all triples
    println("Triples:")
    repo.getTriples().forEach { triple ->
        println("  ${triple.subject}")
        println("    ${triple.predicate}")
        println("      ${triple.obj}")
    }
    println()
    
    // Create a standalone graph with QNames
    val graph = Rdf.graph {
        prefix("foaf", "http://xmlns.com/foaf/0.1/")
        prefix("schema", "http://schema.org/")
        
        val org = iri("http://example.org/organization")
        org - "schema:name" - "Example Corp"
        org - "schema:url" - "https://example.org"
        
        val employee = iri("http://example.org/employee1")
        employee - "foaf:name" - "Charlie"
        employee - "schema:worksFor" - org
    }
    
    println("Standalone graph has ${graph.getTriples().size} triples")
    println()
    
    // Use qname() function to create IRIs
    repo.add {
        prefix("skos", "http://www.w3.org/2004/02/skos/core#")
        
        val concept = iri("http://example.org/concept1")
        val prefLabelIri = qname("skos:prefLabel")
        val altLabelIri = qname("skos:altLabel")
        
        concept - prefLabelIri - "Preferred Label"
        concept - altLabelIri - "Alternative Label 1"
        concept - altLabelIri - "Alternative Label 2"
    }
    
    println("After adding SKOS concepts: ${repo.getTriples().size} triples")
    println()
    
    // Mix QNames and full IRIs
    repo.add {
        prefix("foaf", "http://xmlns.com/foaf/0.1/")
        
        val person = iri("http://example.org/person2")
        person - "foaf:name" - "David"
        person - "http://example.org/customProperty" - "custom value"  // Full IRI
    }
    
    println("Final triple count: ${repo.getTriples().size}")
    
    repo.close()
}
