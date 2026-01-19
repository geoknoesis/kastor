package com.example.dcatus

import com.example.dcatus.domain.*
import com.example.ontomapper.runtime.*
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SKOS

/**
 * Minimal demo of OntoMapper with DCAT-US.
 * Demonstrates pure domain usage + RDF side-channel access.
 */
fun main() {
    println("=== OntoMapper DCAT-US Demo ===\n")
    
    // Create a Kastor repository with sample DCAT data
    val repo = Rdf.memory()
    
    // Add sample DCAT data
    repo.add {
        val catalog = Iri("https://data.example.org/catalog")
        val dataset = Iri("https://data.example.org/dataset/1")
        val distribution = Iri("https://data.example.org/distribution/1")
        val publisher = Iri("https://data.example.org/agency/department")
        
        // Catalog
        catalog - RDF.type - DCAT.Catalog
        catalog - DCTERMS.title - "Example Government Data Catalog"
        catalog - DCTERMS.description - "A sample catalog for demonstration"
        catalog - DCAT.datasetProp - dataset
        catalog - DCTERMS.publisher - publisher
        
        // Dataset
        dataset - RDF.type - DCAT.Dataset
        dataset - DCTERMS.title - "Sample Dataset"
        dataset - DCTERMS.description - "A sample dataset for demonstration"
        dataset - DCAT.distributionProp - distribution
        dataset - DCTERMS.subject - "government"
        dataset - DCTERMS.subject - "open-data"
        
        // Distribution
        distribution - RDF.type - DCAT.Distribution
        distribution - DCTERMS.title - "CSV Distribution"
        distribution - DCAT.downloadURL - Iri("https://data.example.org/files/dataset.csv")
        distribution - DCAT.mediaType - "text/csv"
        distribution - DCTERMS.format - "CSV"
        
        // Publisher
        publisher - RDF.type - FOAF.Agent
        publisher - FOAF.name - "Example Government Department"
        publisher - FOAF.homepage - Iri("https://example.gov")
        
        // Add some extra triples for side-channel demonstration
        catalog - SKOS.altLabel - "Alternative Catalog Name"
        dataset - SKOS.altLabel - "Alternative Dataset Name"
    }
    
    // Create a simple manual wrapper to demonstrate the concept
    class SimpleAgentWrapper(override val rdf: RdfHandle) : Agent, RdfBacked {
        override val name: List<String> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
        }
        override val homepage: List<String> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.homepage).map { it.lexical }
        }
    }
    
    class SimpleDistributionWrapper(override val rdf: RdfHandle) : Distribution, RdfBacked {
        override val title: List<String> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, DCTERMS.title).map { it.lexical }
        }
        override val downloadUrl: List<String> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, DCAT.downloadURL).map { it.lexical }
        }
        override val mediaType: List<String> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, DCAT.mediaType).map { it.lexical }
        }
        override val format: List<String> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, DCTERMS.format).map { it.lexical }
        }
    }
    
    class SimpleDatasetWrapper(override val rdf: RdfHandle) : Dataset, RdfBacked {
        override val title: List<String> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, DCTERMS.title).map { it.lexical }
        }
        override val description: List<String> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, DCTERMS.description).map { it.lexical }
        }
        override val distributions: List<Distribution> by lazy {
            KastorGraphOps.getObjectValues(rdf.graph, rdf.node, DCAT.distributionProp) { child ->
                SimpleDistributionWrapper(DefaultRdfHandle(child, rdf.graph, emptySet()))
            }
        }
        override val keywords: List<String> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, DCTERMS.subject).map { it.lexical }
        }
        override val themes: List<Concept> by lazy { emptyList() }
    }
    
    class SimpleCatalogWrapper(override val rdf: RdfHandle) : Catalog, RdfBacked {
        override val title: List<String> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, DCTERMS.title).map { it.lexical }
        }
        override val description: List<String> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, DCTERMS.description).map { it.lexical }
        }
        override val datasets: List<Dataset> by lazy {
            KastorGraphOps.getObjectValues(rdf.graph, rdf.node, DCAT.datasetProp) { child ->
                SimpleDatasetWrapper(DefaultRdfHandle(child, rdf.graph, emptySet()))
            }
        }
        override val publisher: List<Agent> by lazy {
            KastorGraphOps.getObjectValues(rdf.graph, rdf.node, DCTERMS.publisher) { child ->
                SimpleAgentWrapper(DefaultRdfHandle(child, rdf.graph, emptySet()))
            }
        }
    }
    
    // Register the manual wrappers
    OntoMapper.registry[Catalog::class.java] = { handle -> SimpleCatalogWrapper(handle) }
    OntoMapper.registry[Dataset::class.java] = { handle -> SimpleDatasetWrapper(handle) }
    OntoMapper.registry[Distribution::class.java] = { handle -> SimpleDistributionWrapper(handle) }
    OntoMapper.registry[Agent::class.java] = { handle -> SimpleAgentWrapper(handle) }
    
    // Find a Catalog node and materialize it
    val catalogRef = RdfRef(Iri("https://data.example.org/catalog"), repo.defaultGraph)
    val catalog: Catalog = catalogRef.asType()
    
    println("1. Pure Domain Usage:")
    println("   Title: ${catalog.title.firstOrNull()}")
    println("   Description: ${catalog.description.firstOrNull()}")
    println("   Dataset count: ${catalog.datasets.size}")
    
    if (catalog.datasets.isNotEmpty()) {
        val dataset = catalog.datasets.first()
        println("   First dataset title: ${dataset.title.firstOrNull()}")
        println("   First dataset keywords: ${dataset.keywords.joinToString(", ")}")
        
        if (dataset.distributions.isNotEmpty()) {
            val distribution = dataset.distributions.first()
            println("   First distribution: ${distribution.title.firstOrNull()}")
            println("   Download URL: ${distribution.downloadUrl.firstOrNull()}")
        }
    }
    
    println("\n2. RDF Side-Channel Access:")
    
    // Access RDF side-channel
    val rdfHandle = catalog.asRdf()
    
    // Validate against SHACL (if validation is configured)
    try {
        rdfHandle.validateOrThrow()
        println("   ✓ SHACL validation passed")
    } catch (e: Exception) {
        println("   ✗ SHACL validation failed: ${e.message}")
    }
    
    // Access unmapped properties
    val extras = rdfHandle.extras
    val altLabels = extras.strings(SKOS.altLabel)
    println("   Alternative labels: ${altLabels.joinToString(", ")}")
    
    // Access all predicates
    val allPredicates = extras.predicates()
    println("   All predicates: ${allPredicates.take(5).joinToString(", ")}${if (allPredicates.size > 5) "..." else ""}")
    
    // Materialize related objects via side-channel
    val relatedDatasets = extras.objects(DCAT.datasetProp, Dataset::class.java)
    println("   Related datasets via side-channel: ${relatedDatasets.size}")
    
    println("\n3. Java Compatibility Example:")
    // This would work in Java:
    // RdfRef ref = new RdfRef(new Iri("https://data.example.org/catalog"), graph);
    // Catalog catalog = OntoMapper.materialize(ref, Catalog.class, false);
    // RdfHandle handle = ((RdfBacked) catalog).getRdf();
    // handle.validateOrThrow();
    // List<String> labels = handle.getExtras().strings(SKOS.altLabel());
    
    println("   Java API available via OntoMapper.materialize() and RdfBacked interface")
    
    println("\n=== Demo Complete ===")
    
    repo.close()
}









