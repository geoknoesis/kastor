package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.OWL
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD

fun main() {
    println("=== Built-in Prefixes Example ===\n")
    
    val repo = Rdf.memory()
    
    repo.add {
        println("=== Using Built-in Prefixes ===")
        println("Available built-in prefixes: rdf, rdfs, owl, sh, xsd")
        println("No need to declare them - they're ready to use!")
        println()
        
        val person = Iri("http://example.org/person")
        
        // 1. RDF vocabulary
        println("1. RDF vocabulary:")
        person[RDF.type] = "rdfs:Class"              // Built-in prefixes
        person[RDF.type] = "rdf:Statement"                  // Turtle-style "a" alias
        println("   person[\"rdf:type\"] = \"rdfs:Class\"")
        println("   person[\"a\"] = \"rdf:Statement\"")
        println()
        
        // 2. RDFS vocabulary
        println("2. RDFS vocabulary:")
        person - qname("rdfs:label") - "Person Class"
        person - qname("rdfs:comment") - "A person in our system"
        person - qname("rdfs:subClassOf") - "rdfs:Resource"
        println("   person - \"rdfs:label\" - \"Person Class\"")
        println("   person - \"rdfs:comment\" - \"A person in our system\"")
        println("   person - \"rdfs:subClassOf\" - \"rdfs:Resource\"")
        println()
        
        // 3. OWL vocabulary
        println("3. OWL vocabulary:")
        person - qname("owl:sameAs") - Iri("http://example.org/person2")
        person - qname("owl:differentFrom") - Iri("http://example.org/animal")
        person - qname("owl:equivalentClass") - "rdfs:Class"
        println("   person - \"owl:sameAs\" - \"http://example.org/person2\"")
        println("   person - \"owl:differentFrom\" - \"http://example.org/animal\"")
        println("   person - \"owl:equivalentClass\" - \"rdfs:Class\"")
        println()
        
        // 4. SHACL vocabulary
        println("4. SHACL vocabulary:")
        person - qname("sh:targetClass") - "rdfs:Class"
        person - qname("sh:property") - "rdf:type"
        person - qname("sh:minCount") - 1
        println("   person - \"sh:targetClass\" - \"rdfs:Class\"")
        println("   person - \"sh:property\" - \"rdf:type\"")
        println("   person - \"sh:minCount\" - 1")
        println()
        
        // 5. Mix with custom prefixes
        println("5. Mix with custom prefixes:")
        prefixes {
            put("foaf", "http://xmlns.com/foaf/0.1/")
            put("schema", "http://schema.org/")
        }
        person - qname("foaf:name") - "Alice"                 // Custom prefix
        person - qname("rdfs:label") - "Person"               // Built-in prefix
        person - qname("schema:worksFor") - Iri("http://example.org/company")  // Custom prefix + full IRI
        println("   person - \"foaf:name\" - \"Alice\"                 // Custom prefix")
        println("   person - \"rdfs:label\" - \"Person\"               // Built-in prefix")
        println("   person - \"schema:worksFor\" - \"http://example.org/company\"")
        println()
        
        // 6. Smart QName detection with built-in prefixes
        println("6. Smart QName detection with built-in prefixes:")
        person - RDF.type - "rdfs:Class"           // Both QNames → IRIs
        person - qname("rdfs:subClassOf") - "rdfs:Resource" // Both QNames → IRIs
        person - qname("owl:sameAs") - Iri("http://example.org/person3")  // QName + Full IRI → IRIs
        person - qname("sh:targetClass") - "owl:Class"      // Both QNames → IRIs
        println("   person - \"rdf:type\" - \"rdfs:Class\"           // Both QNames → IRIs")
        println("   person - \"rdfs:subClassOf\" - \"rdfs:Resource\" // Both QNames → IRIs")
        println("   person - \"owl:sameAs\" - \"http://example.org/person3\"  // QName + Full IRI → IRIs")
        println("   person - \"sh:targetClass\" - \"owl:Class\"      // Both QNames → IRIs")
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
    
    println("=== Override Built-in Prefixes ===")
    
    // Demonstrate overriding built-in prefixes
    repo.add {
        prefixes {
            put("rdf", "http://example.org/custom-rdf#")  // Override built-in rdf prefix
            put("foaf", "http://xmlns.com/foaf/0.1/")
        }
        
        val resource = Iri("http://example.org/resource")
        
        // Should use custom namespace, not built-in
        resource[RDF.type] = "rdf:CustomType"
        resource - qname("rdf:label") - "Custom Resource"
        resource - qname("foaf:name") - "Test Resource"
        
        println("Override example:")
        println("  resource[\"rdf:type\"] = \"rdf:CustomType\"  // Uses custom namespace")
        println("  resource - \"rdf:label\" - \"Custom Resource\"")
        println("  resource - \"foaf:name\" - \"Test Resource\"")
    }
    
    println()
    println("Final triple count: ${repo.getTriples().size}")
    
    println()
    println("=== Built-in Prefixes Summary ===")
    println("✅ rdf:   → http://www.w3.org/1999/02/22-rdf-syntax-ns#")
    println("✅ rdfs:  → http://www.w3.org/2000/01/rdf-schema#")
    println("✅ owl:   → http://www.w3.org/2002/07/owl#")
    println("✅ sh:    → http://www.w3.org/ns/shacl#")
    println("✅ xsd:   → http://www.w3.org/2001/XMLSchema#")
    println()
    println("These prefixes are available immediately - no declaration needed!")
    println("You can override them if needed using the prefixes { } block.")
    
    repo.close()
}









