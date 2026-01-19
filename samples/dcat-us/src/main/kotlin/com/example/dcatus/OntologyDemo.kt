package com.example.dcatus

import com.example.dcatus.generated.*
import com.geoknoesis.kastor.gen.runtime.*
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.DCAT
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SKOS

/**
 * Demo showing generated domain interfaces and wrappers from SHACL + JSON-LD.
 * 
 * This demonstrates how Kastor Gen can generate pure domain interfaces
 * and RDF-backed wrapper implementations from ontology files.
 */
fun main() {
    println("=== Kastor Gen SHACL/JSON-LD Generation Demo ===\n")
    
    // Note: In a real scenario, the interfaces and wrappers would be generated
    // by the KSP processor from the SHACL and JSON-LD context files.
    // For this demo, we'll show what the generated code would look like.
    
    println("1. Generated Domain Interfaces:")
    println("   - Catalog (from dcat:Catalog)")
    println("   - Dataset (from dcat:Dataset)")  
    println("   - Distribution (from dcat:Distribution)")
    println("   - Agent (from foaf:Agent)")
    println()
    
    println("2. Generated Wrapper Classes:")
    println("   - CatalogWrapper")
    println("   - DatasetWrapper")
    println("   - DistributionWrapper")
    println("   - AgentWrapper")
    println()
    
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
    
    println("3. Generated Code Structure:")
    println("   The following interfaces would be generated from SHACL:")
    println()
    println("   @RdfClass(iri = \"http://www.w3.org/ns/dcat#Catalog\")")
    println("   interface Catalog {")
    println("     @get:RdfProperty(iri = \"http://purl.org/dc/terms/title\")")
    println("     val title: String")
    println("     @get:RdfProperty(iri = \"http://purl.org/dc/terms/description\")")
    println("     val description: String")
    println("     @get:RdfProperty(iri = \"http://www.w3.org/ns/dcat#dataset\")")
    println("     val dataset: List<Dataset>")
    println("     @get:RdfProperty(iri = \"http://purl.org/dc/terms/publisher\")")
    println("     val publisher: List<Agent>")
    println("   }")
    println()
    
    println("   The following wrapper would be generated:")
    println()
    println("   internal class CatalogWrapper(")
    println("     override val rdf: RdfHandle")
    println("   ) : Catalog, RdfBacked {")
    println("     override val title: String by lazy {")
    println("       KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, DCTERMS.title)")
    println("         .map { it.lexical }.firstOrNull() ?: \"\"")
    println("     }")
    println("     // ... other properties")
    println("   }")
    println()
    
    println("4. Benefits of SHACL/JSON-LD Generation:")
    println("   ✓ Automatic interface generation from ontology definitions")
    println("   ✓ Type-safe property mappings")
    println("   ✓ Validation rules from SHACL constraints")
    println("   ✓ Pure domain interfaces with no RDF dependencies")
    println("   ✓ RDF side-channel access for advanced use cases")
    println("   ✓ Consistent code generation across projects")
    println()
    
    println("5. Usage Example:")
    println("   // After generation, you could use:")
    println("   val catalogRef = RdfRef(Iri(\"https://data.example.org/catalog\"), repo.defaultGraph)")
    println("   val catalog: Catalog = catalogRef.asType()")
    println("   println(\"Title: \${catalog.title}\")")
    println("   println(\"Dataset count: \${catalog.dataset.size}\")")
    println()
    println("   // Side-channel access:")
    println("   val extras = catalog.asRdf().extras")
    println("   val altLabels = extras.strings(SKOS.altLabel)")
    println("   println(\"Alternative labels: \${altLabels.joinToString()}\")")
    println()
    
    println("=== Demo Complete ===")
    
    repo.close()
}









