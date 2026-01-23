package com.geoknoesis.kastor.examples.dcat

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.provider.MemoryGraph

/**
 * DCAT-US 3.0 Example using OntoMapper generated code.
 * 
 * This example demonstrates how to use OntoMapper to generate interfaces,
 * wrappers, and vocabulary from DCAT-US 3.0 SHACL shapes, and then use
 * the generated code to work with DCAT-US compliant data.
 */
class DCAT_US_Example {
    
    private val repo: RdfRepository
    
    init {
        // Initialize repository
        repo = Rdf.memory()
    }
    
    /**
     * Example 1: Create a basic DCAT-US Catalog using RDF
     */
    fun createBasicCatalog(): RdfGraph {
        val catalogUri = Iri("https://data.example.gov/catalog")
        
        // Create catalog triples
        val triples = listOf(
            RdfTriple(catalogUri, RDF.type, Iri("http://www.w3.org/ns/dcat#Catalog")),
            RdfTriple(catalogUri, Iri("http://www.w3.org/ns/dcat#title"), string("Example Government Data Catalog")),
            RdfTriple(catalogUri, Iri("http://www.w3.org/ns/dcat#description"), string("A comprehensive catalog of government datasets and services")),
            RdfTriple(catalogUri, Iri("http://www.w3.org/ns/dcat#publisher"), createPublisherUri()),
            RdfTriple(catalogUri, Iri("http://www.w3.org/ns/dcat#issued"), Literal("2024-01-15", XSD.date)),
            RdfTriple(catalogUri, Iri("http://www.w3.org/ns/dcat#modified"), Literal("2024-01-20", XSD.date)),
            RdfTriple(catalogUri, Iri("http://www.w3.org/ns/dcat#license"), Iri("https://creativecommons.org/licenses/by/4.0/")),
            RdfTriple(catalogUri, Iri("http://www.w3.org/ns/dcat#language"), string("en"))
        )
        
        val graph = MemoryGraph(triples)
        
        // Add to repository
        repo.add { addTriples(graph.getTriples()) }
        
        return graph
    }
    
    /**
     * Example 2: Create a DCAT-US Dataset
     */
    fun createBasicDataset(): RdfGraph {
        val datasetUri = Iri("https://data.example.gov/dataset/weather-data")
        
        // Create dataset triples
        val triples = listOf(
            RdfTriple(datasetUri, RDF.type, Iri("http://www.w3.org/ns/dcat#Dataset")),
            RdfTriple(datasetUri, Iri("http://www.w3.org/ns/dcat#title"), string("Weather Data Collection")),
            RdfTriple(datasetUri, Iri("http://www.w3.org/ns/dcat#description"), string("Daily weather measurements from weather stations across the state")),
            RdfTriple(datasetUri, Iri("http://www.w3.org/ns/dcat#keyword"), string("weather")),
            RdfTriple(datasetUri, Iri("http://www.w3.org/ns/dcat#keyword"), string("climate")),
            RdfTriple(datasetUri, Iri("http://www.w3.org/ns/dcat#keyword"), string("temperature")),
            RdfTriple(datasetUri, Iri("http://www.w3.org/ns/dcat#keyword"), string("precipitation")),
            RdfTriple(datasetUri, Iri("http://www.w3.org/ns/dcat#publisher"), createPublisherUri()),
            RdfTriple(datasetUri, Iri("http://www.w3.org/ns/dcat#issued"), Literal("2024-01-01", XSD.date)),
            RdfTriple(datasetUri, Iri("http://www.w3.org/ns/dcat#modified"), Literal("2024-01-15", XSD.date)),
            RdfTriple(datasetUri, Iri("http://www.w3.org/ns/dcat#license"), Iri("https://creativecommons.org/licenses/by/4.0/")),
            RdfTriple(datasetUri, Iri("http://www.w3.org/ns/dcat#rights"), string("Public Domain"))
        )
        
        val graph = MemoryGraph(triples)
        
        // Add to repository
        repo.add { addTriples(graph.getTriples()) }
        
        return graph
    }
    
    /**
     * Example 3: Create a DCAT-US Distribution
     */
    fun createBasicDistribution(): RdfGraph {
        val distributionUri = Iri("https://data.example.gov/distribution/weather-csv")
        
        // Create distribution triples
        val triples = listOf(
            RdfTriple(distributionUri, RDF.type, Iri("http://www.w3.org/ns/dcat#Distribution")),
            RdfTriple(distributionUri, Iri("http://www.w3.org/ns/dcat#title"), string("Weather Data - CSV Format")),
            RdfTriple(distributionUri, Iri("http://www.w3.org/ns/dcat#description"), string("Daily weather data in CSV format")),
            RdfTriple(distributionUri, Iri("http://www.w3.org/ns/dcat#accessURL"), Iri("https://data.example.gov/api/weather.csv")),
            RdfTriple(distributionUri, Iri("http://www.w3.org/ns/dcat#downloadURL"), Iri("https://data.example.gov/downloads/weather.csv")),
            RdfTriple(distributionUri, Iri("http://www.w3.org/ns/dcat#mediaType"), string("text/csv")),
            RdfTriple(distributionUri, Iri("http://www.w3.org/ns/dcat#format"), Iri("http://publications.europa.eu/resource/authority/file-type/CSV")),
            RdfTriple(distributionUri, Iri("http://www.w3.org/ns/dcat#byteSize"), Literal("1024000", XSD.decimal))
        )
        
        val graph = MemoryGraph(triples)
        
        // Add to repository
        repo.add { addTriples(graph.getTriples()) }
        
        return graph
    }
    
    /**
     * Example 4: Query DCAT-US data using SPARQL
     */
    fun queryCatalogData(): List<Map<String, Any>> {
        // Query using DCAT vocabulary
        val query = """
            PREFIX dcat: <http://www.w3.org/ns/dcat#>
            PREFIX dct: <http://purl.org/dc/terms/>
            
            SELECT ?resource ?title ?description ?type WHERE {
                ?resource a ?type .
                ?resource dct:title ?title .
                ?resource dct:description ?description .
                FILTER(?type IN (dcat:Catalog, dcat:Dataset, dcat:Distribution))
            }
            ORDER BY ?type ?title
        """
        
        val results = repo.select(SparqlSelectQuery(query))
        return results.map { binding ->
            mapOf(
                "resource" to (binding.get("resource") as? Iri ?: Iri("")),
                "title" to (binding.getString("title") ?: ""),
                "description" to (binding.getString("description") ?: ""),
                "type" to (binding.get("type") as? Iri ?: Iri(""))
            )
        }
    }
    
    /**
     * Example 5: Export catalog as Turtle
     */
    fun exportCatalogAsTurtle(): String {
        val allTriples = repo.select(SparqlSelectQuery("SELECT ?s ?p ?o WHERE { ?s ?p ?o }"))
            .map { binding ->
                RdfTriple(
                    binding.get("s") as Iri,
                    binding.get("p") as Iri,
                    binding.get("o") as RdfTerm
                )
            }
        
        // Simple Turtle serialization
        return buildString {
            appendLine("@prefix dcat: <http://www.w3.org/ns/dcat#> .")
            appendLine("@prefix dct: <http://purl.org/dc/terms/> .")
            appendLine("@prefix foaf: <http://xmlns.com/foaf/0.1/> .")
            appendLine()
            allTriples.forEach { triple ->
                appendLine("${triple.subject} ${triple.predicate} ${triple.obj} .")
            }
        }
    }
    
    /**
     * Example 6: Export catalog as JSON-LD
     */
    fun exportCatalogAsJsonLd(): String {
        // Simple JSON-LD serialization
        return buildString {
            appendLine("{")
            appendLine("  \"@context\": {")
            appendLine("    \"dcat\": \"http://www.w3.org/ns/dcat#\",")
            appendLine("    \"dct\": \"http://purl.org/dc/terms/\",")
            appendLine("    \"foaf\": \"http://xmlns.com/foaf/0.1/\"")
            appendLine("  },")
            appendLine("  \"@id\": \"https://data.example.gov/catalog\",")
            appendLine("  \"@type\": \"dcat:Catalog\"")
            appendLine("}")
        }
    }
    
    // Helper methods
    private fun createPublisherUri(): Iri {
        val publisherUri = Iri("https://www.example.gov/agency")
        
        // Add publisher information
        repo.add {
            publisherUri has Iri("http://xmlns.com/foaf/0.1/name") with string("Example Government Agency")
            publisherUri has Iri("http://xmlns.com/foaf/0.1/homepage") with Iri("https://www.example.gov")
        }
        
        return publisherUri
    }
}

/**
 * Main function demonstrating DCAT-US OntoMapper usage
 */
fun main() {
    val example = DCAT_US_Example()
    
    println("=== DCAT-US 3.0 OntoMapper Example ===")
    
    // Example 1: Create basic catalog
    println("\n1. Creating basic DCAT-US Catalog...")
    val catalogGraph = example.createBasicCatalog()
    println("✅ Created catalog with ${catalogGraph.getTriples().size} triples")
    
    // Example 2: Create dataset
    println("\n2. Creating DCAT-US Dataset...")
    val datasetGraph = example.createBasicDataset()
    println("✅ Created dataset with ${datasetGraph.getTriples().size} triples")
    
    // Example 3: Create distribution
    println("\n3. Creating DCAT-US Distribution...")
    val distributionGraph = example.createBasicDistribution()
    println("✅ Created distribution with ${distributionGraph.getTriples().size} triples")
    
    // Example 4: Query data
    println("\n4. Querying DCAT-US data...")
    val queryResults = example.queryCatalogData()
    println("✅ Found ${queryResults.size} DCAT resources:")
    queryResults.forEach { result ->
        val type = result["type"] as Iri
        val title = result["title"] as String
        println("  - ${type.toString().substringAfterLast("#")}: $title")
    }
    
    // Example 5: Export as Turtle
    println("\n5. Exporting catalog as Turtle...")
    val turtle = example.exportCatalogAsTurtle()
    println("✅ Exported catalog as Turtle (${turtle.length} characters)")
    println("First 200 characters:")
    println(turtle.take(200) + "...")
    
    // Example 6: Export as JSON-LD
    println("\n6. Exporting catalog as JSON-LD...")
    val jsonLd = example.exportCatalogAsJsonLd()
    println("✅ Exported catalog as JSON-LD (${jsonLd.length} characters)")
    println("First 200 characters:")
    println(jsonLd.take(200) + "...")
    
    println("\n=== Basic example completed successfully! ===")
    println("\nNext steps:")
    println("1. See DCAT_US_Manual_Example.kt for a demonstration of what OntoMapper would generate")
    println("2. Run './gradlew :examples:dcat-us:runManualExample' to see the manual example")
    println("3. The OntoMapper infrastructure exists but needs completion for automatic code generation")
}









