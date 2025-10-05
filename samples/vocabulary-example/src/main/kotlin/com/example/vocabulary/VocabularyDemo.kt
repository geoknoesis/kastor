package com.example.vocabulary

import com.example.dcatus.vocab.DCAT
import com.example.schema.vocab.SCHEMA
import com.example.foaf.vocab.FOAF
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.*

/**
 * Demonstration of using generated vocabulary files.
 * 
 * This example shows how to use vocabulary files generated from SHACL and JSON-LD context files,
 * following the Kastor vocabulary pattern for type-safe access to RDF terms.
 */
fun main() {
    println("=== Vocabulary Generation Demo ===\n")
    
    // Create a repository and add some RDF data using generated vocabularies
    val repo = Rdf.memory()
    
    // Add data using generated vocabulary constants
    repo.add {
        // DCAT-US data using generated vocabulary
        val catalog = iri("http://example.org/catalog")
        catalog - RDF.type - DCAT.Catalog
        catalog - DCTERMS.title - "My Data Catalog"
        catalog - DCTERMS.description - "A catalog of datasets"
        
        val dataset = iri("http://example.org/dataset")
        dataset - RDF.type - DCAT.Dataset
        dataset - DCTERMS.title - "Sample Dataset"
        dataset - DCTERMS.description - "A sample dataset"
        
        catalog - DCAT.datasetProp - dataset
        
        // Schema.org data using generated vocabulary
        val person = iri("http://example.org/person")
        person - RDF.type - SCHEMA.Person
        person - SCHEMA.name - "John Doe"
        person - SCHEMA.email - "john@example.com"
        
        val address = iri("http://example.org/address")
        address - RDF.type - SCHEMA.PostalAddress
        address - SCHEMA.streetAddress - "123 Main St"
        address - SCHEMA.addressLocality - "Anytown"
        address - SCHEMA.addressCountry - "USA"
        
        person - SCHEMA.address - address
        
        // FOAF data using generated vocabulary
        val foafPerson = iri("http://example.org/foaf-person")
        foafPerson - RDF.type - FOAF.Person
        foafPerson - FOAF.name - "Jane Smith"
        foafPerson - FOAF.mbox - "jane@example.com"
        foafPerson - FOAF.knows - person
        
        val document = iri("http://example.org/document")
        document - RDF.type - FOAF.Document
        document - FOAF.title - "Sample Document"
        document - FOAF.primaryTopic - foafPerson
    }
    
    println("Added RDF data to repository using generated vocabularies")
    println("Repository size: ${repo.defaultGraph.size} triples")
    
    // Demonstrate vocabulary usage
    println("\n=== Generated Vocabulary Usage ===")
    
    // DCAT vocabulary
    println("DCAT Vocabulary:")
    println("  Namespace: ${DCAT.namespace}")
    println("  Prefix: ${DCAT.prefix}")
    println("  Catalog class: ${DCAT.Catalog.value}")
    println("  Dataset class: ${DCAT.Dataset.value}")
    println("  Dataset property: ${DCAT.datasetProp.value}")
    
    // Schema.org vocabulary
    println("\nSchema.org Vocabulary:")
    println("  Namespace: ${SCHEMA.namespace}")
    println("  Prefix: ${SCHEMA.prefix}")
    println("  Person class: ${SCHEMA.Person.value}")
    println("  PostalAddress class: ${SCHEMA.PostalAddress.value}")
    println("  Name property: ${SCHEMA.name.value}")
    println("  Email property: ${SCHEMA.email.value}")
    
    // FOAF vocabulary
    println("\nFOAF Vocabulary:")
    println("  Namespace: ${FOAF.namespace}")
    println("  Prefix: ${FOAF.prefix}")
    println("  Person class: ${FOAF.Person.value}")
    println("  Document class: ${FOAF.Document.value}")
    println("  Name property: ${FOAF.name.value}")
    println("  Mbox property: ${FOAF.mbox.value}")
    
    // Demonstrate vocabulary interface methods
    println("\n=== Vocabulary Interface Methods ===")
    
    // Check if terms belong to vocabularies
    println("DCAT.contains(DCAT.Catalog): ${DCAT.contains(DCAT.Catalog)}")
    println("DCAT.contains(SCHEMA.Person): ${DCAT.contains(SCHEMA.Person)}")
    
    // Get local names
    println("DCAT.localname(DCAT.Catalog): ${DCAT.localname(DCAT.Catalog)}")
    println("SCHEMA.localname(SCHEMA.name): ${SCHEMA.localname(SCHEMA.name)}")
    
    // Create terms dynamically
    val customDcatTerm = DCAT.term("customProperty")
    println("Custom DCAT term: ${customDcatTerm.value}")
    
    val customSchemaTerm = SCHEMA.term("customClass")
    println("Custom Schema term: ${customSchemaTerm.value}")
    
    // Query using generated vocabularies
    val results = repo.query {
        select("?name", "?email", "?type") where {
            "?person" - RDF.type - "?type"
            "?person" - "?nameProp" - "?name"
            "?person" - "?emailProp" - "?email"
            filter {
                "?nameProp" in (SCHEMA.name, FOAF.name)
                "?emailProp" in (SCHEMA.email, FOAF.mbox)
            }
        }
    }
    
    println("\n=== Query Results Using Generated Vocabularies ===")
    results.forEach { bindingSet ->
        val name = bindingSet.getValue("name")?.asLiteral()?.lexical
        val email = bindingSet.getValue("email")?.asLiteral()?.lexical
        val type = bindingSet.getValue("type")?.asIri()?.value
        println("Name: $name, Email: $email, Type: $type")
    }
    
    // Demonstrate type safety
    println("\n=== Type Safety Benefits ===")
    println("All vocabulary terms are strongly typed as Iri objects")
    println("IDE autocomplete works for all vocabulary terms")
    println("Compile-time checking ensures correct term usage")
    
    // Show vocabulary term creation
    println("\n=== Dynamic Term Creation ===")
    val dynamicTerms = listOf(
        DCAT.term("newClass"),
        SCHEMA.term("newProperty"),
        FOAF.term("newRelationship")
    )
    
    dynamicTerms.forEach { term ->
        println("Dynamic term: ${term.value}")
    }
    
    println("\n=== Demo Complete ===")
}
