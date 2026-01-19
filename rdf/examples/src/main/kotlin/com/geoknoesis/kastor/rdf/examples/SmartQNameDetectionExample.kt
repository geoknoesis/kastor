package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD

fun main() {
    println("=== Smart QName Detection Example ===\n")
    
    val repo = Rdf.memory()
    
    repo.add {
        // Configure prefix mappings
        prefixes {
            put("foaf", "http://xmlns.com/foaf/0.1/")
            put("rdfs", "http://www.w3.org/2000/01/rdf-schema#")
            put("schema", "http://schema.org/")
        }
        
        val person = Iri("http://example.org/person")
        
        println("=== Smart QName Detection Examples ===")
        println()
        
        // 1. QName with declared prefix → IRI
        println("1. QName with declared prefix → IRI:")
        person - qname("foaf:knows") - "foaf:Person"
        person - qname("rdfs:subClassOf") - "foaf:Agent"
        println("   person - \"foaf:knows\" - \"foaf:Person\"        // → <http://xmlns.com/foaf/0.1/Person>")
        println("   person - \"rdfs:subClassOf\" - \"foaf:Agent\"    // → <http://xmlns.com/foaf/0.1/Agent>")
        println()
        
        // 2. Full IRI → IRI
        println("2. Full IRI → IRI:")
        person - qname("foaf:homepage") - Iri("http://example.org/profile")
        person - qname("schema:url") - Iri("https://example.org/website")
        println("   person - \"foaf:homepage\" - \"http://example.org/profile\"")
        println("   person - \"schema:url\" - \"https://example.org/website\"")
        println()
        
        // 3. String without colon → string literal
        println("3. String without colon → string literal:")
        person - qname("foaf:name") - "Alice"
        person - qname("foaf:age") - 30
        println("   person - \"foaf:name\" - \"Alice\"               // → \"Alice\"^^xsd:string")
        println("   person - \"foaf:age\" - 30                     // → \"30\"^^xsd:integer")
        println()
        
        // 4. Undeclared prefix → string literal (safe fallback)
        println("4. Undeclared prefix → string literal (safe fallback):")
        person - qname("foaf:note") - "unknown:Person"
        person - qname("foaf:comment") - "dc:creator"
        println("   person - \"foaf:note\" - \"unknown:Person\"      // → \"unknown:Person\"^^xsd:string")
        println("   person - \"foaf:comment\" - \"dc:creator\"       // → \"dc:creator\"^^xsd:string")
        println()
        
        // 5. Special case: rdf:type always resolves QNames
        println("5. Special case: rdf:type always resolves QNames:")
        person[RDF.type] = "foaf:Person"
        person `is` "foaf:Agent"
        println("   person[\"a\"] = \"foaf:Person\"                  // → <http://xmlns.com/foaf/0.1/Person>")
        println("   person `is` \"foaf:Agent\"                     // → <http://xmlns.com/foaf/0.1/Agent>")
        println()
        
        // 6. Mixed syntax with smart detection
        println("6. Mixed syntax with smart detection:")
        person[qname("foaf:name")] = "Alice"
        person[qname("foaf:knows")] = "http://example.org/bob"  // Full IRI → IRI
        person has qname("foaf:age") with 25
        person has qname("schema:worksFor") with "http://example.org/company"  // Full IRI → IRI
        println("   person[\"foaf:name\"] = \"Alice\"")
        println("   person[\"foaf:knows\"] = \"http://example.org/bob\"  // Full IRI → IRI")
        println("   person has \"foaf:age\" with 25")
        println("   person has \"schema:worksFor\" with \"http://example.org/company\"  // Full IRI → IRI")
        println()
    }
    
    println("=== Results ===")
    println("Total triples created: ${repo.getTriples().size}")
    println()
    
    // Print all triples to show the results
    repo.getTriples().forEach { triple ->
        println("${triple.subject}")
        println("  ${triple.predicate}")
        println("    ${triple.obj}")
        println()
    }
    
    println("=== Explicit Control Examples ===")
    
    // Demonstrate explicit control when needed
    repo.add {
        prefixes {
            put("foaf", "http://xmlns.com/foaf/0.1/")
        }
        
        val person2 = Iri("http://example.org/person2")
        
        // Explicit QName resolution
        person2 - qname("foaf:knows") - qname("foaf:Person")    // → <http://xmlns.com/foaf/0.1/Person>
        
        // Explicit string literal
        person2 - qname("foaf:name") - Literal("foaf:Person")   // → "foaf:Person"^^xsd:string
        
        // Explicit IRI
        person2 - qname("foaf:homepage") - Iri("http://example.org")  // → <http://example.org>
        
        // Language-tagged literals
        person2 - qname("foaf:name") - lang("Alice", "en")      // → "Alice"@en
        
        // Typed literals
        person2 - qname("foaf:age") - Literal("30", XSD.integer)  // → "30"^^xsd:integer
        
        println("Explicit control examples:")
        println("  person2 - \"foaf:knows\" - qname(\"foaf:Person\")    // → <http://xmlns.com/foaf/0.1/Person>")
        println("  person2 - \"foaf:name\" - Literal(\"foaf:Person\")   // → \"foaf:Person\"^^xsd:string")
        println("  person2 - \"foaf:homepage\" - Iri(\"http://example.org\")  // → <http://example.org>")
        println("  person2 - \"foaf:name\" - lang(\"Alice\", \"en\")      // → \"Alice\"@en")
        println("  person2 - \"foaf:age\" - Literal(\"30\", XSD.integer)  // → \"30\"^^xsd:integer")
    }
    
    println()
    println("Final triple count: ${repo.getTriples().size}")
    
    repo.close()
}









