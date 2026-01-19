package com.example.multiontology

import com.example.dcatus.generated.*
import com.example.schema.generated.*
import com.example.foaf.generated.*
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.*

/**
 * Demonstration of using Kastor Gen with multiple ontology configurations.
 * 
 * This example shows how to generate domain interfaces and wrappers
 * from multiple SHACL and JSON-LD context files using Gradle configuration,
 * each in different packages.
 */
fun main() {
    println("=== Multi-Ontology Generation Demo ===\n")
    
    // Create a repository and add some RDF data
    val repo = Rdf.memory()
    
    // Add data from different ontologies
    repo.add {
        // DCAT-US data
        val catalog = Iri("http://example.org/catalog")
        catalog - RDF.type - DCAT.Catalog
        catalog - DCTERMS.title - "My Data Catalog"
        catalog - DCTERMS.description - "A catalog of datasets"
        
        val dataset = Iri("http://example.org/dataset")
        dataset - RDF.type - DCAT.Dataset
        dataset - DCTERMS.title - "Sample Dataset"
        dataset - DCTERMS.description - "A sample dataset"
        
        catalog - DCAT.dataset - dataset
        
        // Schema.org data
        val person = Iri("http://example.org/person")
        person - RDF.type - SCHEMA.Person
        person - SCHEMA.name - "John Doe"
        person - SCHEMA.email - "john@example.com"
        
        val address = Iri("http://example.org/address")
        address - RDF.type - SCHEMA.PostalAddress
        address - SCHEMA.streetAddress - "123 Main St"
        address - SCHEMA.addressLocality - "Anytown"
        address - SCHEMA.addressCountry - "USA"
        
        person - SCHEMA.address - address
        
        // FOAF data
        val foafPerson = Iri("http://example.org/foaf-person")
        foafPerson - RDF.type - FOAF.Person
        foafPerson - FOAF.name - "Jane Smith"
        foafPerson - FOAF.mbox - "jane@example.com"
        foafPerson - FOAF.knows - person
        
        val document = Iri("http://example.org/document")
        document - RDF.type - FOAF.Document
        document - FOAF.title - "Sample Document"
        document - FOAF.primaryTopic - foafPerson
    }
    
    println("Added RDF data to repository")
    println("Repository size: ${repo.defaultGraph.size} triples")
    
    // Materialize using generated interfaces from different ontologies
    
    // DCAT-US
    val catalogRef = RdfRef(Iri("http://example.org/catalog"), repo.defaultGraph)
    val catalog: Catalog = catalogRef.asType()
    
    println("\n=== DCAT-US Materialization ===")
    println("Title: ${catalog.title}")
    println("Description: ${catalog.description}")
    println("Dataset count: ${catalog.dataset.size}")
    
    // Schema.org
    val personRef = RdfRef(Iri("http://example.org/person"), repo.defaultGraph)
    val person: Person = personRef.asType()
    
    println("\n=== Schema.org Materialization ===")
    println("Name: ${person.name}")
    println("Email: ${person.email}")
    println("Address: ${person.address?.let { "${it.streetAddress}, ${it.addressLocality}, ${it.addressCountry}" }}")
    
    // FOAF
    val foafPersonRef = RdfRef(Iri("http://example.org/foaf-person"), repo.defaultGraph)
    val foafPerson: foaf.Person = foafPersonRef.asType()
    
    println("\n=== FOAF Materialization ===")
    println("Name: ${foafPerson.name}")
    println("Email: ${foafPerson.mbox}")
    println("Knows count: ${foafPerson.knows.size}")
    
    // Query across ontologies
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
    
    println("\n=== Cross-Ontology Query Results ===")
    results.forEach { bindingSet ->
        val name = bindingSet.getValue("name")?.asLiteral()?.lexical
        val email = bindingSet.getValue("email")?.asLiteral()?.lexical
        val type = bindingSet.getValue("type")?.asIri()?.value
        println("Name: $name, Email: $email, Type: $type")
    }
    
    // Demonstrate RDF side-channel access
    println("\n=== RDF Side-channel Access ===")
    val catalogHandle = catalogRef.rdf
    println("Catalog node: ${catalogHandle.node}")
    println("Catalog graph size: ${catalogHandle.graph.size}")
    
    val personHandle = personRef.rdf
    println("Person node: ${personHandle.node}")
    println("Person graph size: ${personHandle.graph.size}")
    
    // Use generated wrappers directly
    println("\n=== Generated Wrappers ===")
    val catalogWrapper = CatalogWrapper(catalogHandle)
    println("Catalog wrapper title: ${catalogWrapper.title}")
    
    val personWrapper = PersonWrapper(personHandle)
    println("Person wrapper name: ${personWrapper.name}")
    
    println("\n=== Demo Complete ===")
}









