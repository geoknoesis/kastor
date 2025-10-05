package com.example.gradleontology

import com.example.dcatus.generated.*
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.*

/**
 * Demonstration of using OntoMapper with Gradle-only configuration.
 * 
 * This example shows how to generate domain interfaces and wrappers
 * from SHACL and JSON-LD context files using only Gradle configuration,
 * without requiring any annotations in the source code.
 */
fun main() {
    println("=== Gradle Ontology Generation Demo ===\n")
    
    // Create a repository and add some RDF data
    val repo = Rdf.memory()
    
    // Add DCAT-US data using the generated interfaces
    repo.add {
        val catalog = iri("http://example.org/catalog")
        catalog - RDF.type - DCAT.Catalog
        catalog - DCTERMS.title - "My Data Catalog"
        catalog - DCTERMS.description - "A catalog of datasets"
        catalog - DCTERMS.publisher - iri("http://example.org/publisher")
        
        val dataset = iri("http://example.org/dataset")
        dataset - RDF.type - DCAT.Dataset
        dataset - DCTERMS.title - "Sample Dataset"
        dataset - DCTERMS.description - "A sample dataset"
        dataset - DCAT.distribution - iri("http://example.org/distribution")
        
        catalog - DCAT.dataset - dataset
    }
    
    println("Added RDF data to repository")
    println("Repository size: ${repo.defaultGraph.size()} triples")
    
    // Materialize using generated interfaces
    val catalogRef = RdfRef(iri("http://example.org/catalog"), repo.defaultGraph)
    val catalog: Catalog = catalogRef.asType()
    
    println("\nMaterialized Catalog:")
    println("Title: ${catalog.title}")
    println("Description: ${catalog.description}")
    println("Publisher: ${catalog.publisher}")
    println("Dataset count: ${catalog.dataset.size}")
    
    // Access RDF side-channel
    val catalogHandle = catalogRef.rdf
    println("\nRDF Side-channel access:")
    println("Node: ${catalogHandle.node}")
    println("Graph size: ${catalogHandle.graph.size()}")
    
    // Use generated wrapper directly
    val catalogWrapper = CatalogWrapper(catalogHandle)
    println("\nUsing generated wrapper:")
    println("Title: ${catalogWrapper.title}")
    println("Description: ${catalogWrapper.description}")
    
    // Query the data
    val results = repo.query {
        select("?title", "?description") where {
            "?catalog" - RDF.type - DCAT.Catalog
            "?catalog" - DCTERMS.title - "?title"
            "?catalog" - DCTERMS.description - "?description"
        }
    }
    
    println("\nSPARQL Query Results:")
    results.forEach { bindingSet ->
        val title = bindingSet.getValue("title")?.asLiteral()?.lexical
        val description = bindingSet.getValue("description")?.asLiteral()?.lexical
        println("Title: $title, Description: $description")
    }
    
    println("\n=== Demo Complete ===")
}
