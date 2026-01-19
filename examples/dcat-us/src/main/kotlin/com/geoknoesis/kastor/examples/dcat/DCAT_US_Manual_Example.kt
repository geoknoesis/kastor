package com.geoknoesis.kastor.examples.dcat

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.provider.MemoryGraph

/**
 * Manual DCAT-US 3.0 Example demonstrating how OntoMapper would work.
 * 
 * This example shows what the generated code would look like and how
 * it would integrate with Kastor's RDF API. In a working OntoMapper
 * system, these classes would be automatically generated from the
 * DCAT-US 3.0 SHACL shapes.
 */
class DCAT_US_Manual_Example {
    
    private val repo: RdfRepository
    
    init {
        repo = Rdf.memory()
    }
    
    /**
     * Example 1: Create a DCAT-US Catalog using manual domain classes
     */
    fun createCatalogWithManualClasses(): Catalog {
        val catalog = Catalog(
            title = "Example Government Data Catalog",
            description = "A comprehensive catalog of government datasets and services",
            publisher = Organization(
                name = "Example Government Agency",
                homepage = "https://www.example.gov"
            ),
            issued = "2024-01-15",
            modified = "2024-01-20",
            license = "https://creativecommons.org/licenses/by/4.0/",
            language = "en"
        )
        
        // Convert to RDF using manual mapping
        val catalogUri = Iri("https://data.example.gov/catalog")
        val triples = mutableListOf<RdfTriple>()
        
        triples.add(RdfTriple(catalogUri, RDF.type, Iri("http://www.w3.org/ns/dcat#Catalog")))
        triples.add(RdfTriple(catalogUri, Iri("http://purl.org/dc/terms/title"), string(catalog.title)))
        triples.add(RdfTriple(catalogUri, Iri("http://purl.org/dc/terms/description"), string(catalog.description)))
        triples.add(RdfTriple(catalogUri, Iri("http://purl.org/dc/terms/publisher"), Iri("https://www.example.gov/agency")))
        triples.add(RdfTriple(catalogUri, Iri("http://purl.org/dc/terms/issued"), Literal(catalog.issued, XSD.date)))
        triples.add(RdfTriple(catalogUri, Iri("http://purl.org/dc/terms/modified"), Literal(catalog.modified, XSD.date)))
        triples.add(RdfTriple(catalogUri, Iri("http://purl.org/dc/terms/license"), Iri(catalog.license)))
        triples.add(RdfTriple(catalogUri, Iri("http://purl.org/dc/terms/language"), string(catalog.language)))
        
        // Add publisher information
        val publisherUri = Iri("https://www.example.gov/agency")
        triples.add(RdfTriple(publisherUri, RDF.type, Iri("http://xmlns.com/foaf/0.1/Organization")))
        triples.add(RdfTriple(publisherUri, Iri("http://xmlns.com/foaf/0.1/name"), string(catalog.publisher.name)))
        triples.add(RdfTriple(publisherUri, Iri("http://xmlns.com/foaf/0.1/homepage"), Iri(catalog.publisher.homepage)))
        
        val graph = MemoryGraph(triples)
        repo.add { graph }
        
        return catalog
    }
    
    /**
     * Example 2: Create a DCAT-US Dataset with multiple distributions
     */
    fun createDatasetWithDistributions(): Dataset {
        val dataset = Dataset(
            title = "Weather Data Collection",
            description = "Daily weather measurements from weather stations across the state",
            keywords = listOf("weather", "climate", "temperature", "precipitation"),
            publisher = Organization(
                name = "Example Government Agency",
                homepage = "https://www.example.gov"
            ),
            issued = "2024-01-01",
            modified = "2024-01-15",
            license = "https://creativecommons.org/licenses/by/4.0/",
            rights = "Public Domain",
            distributions = listOf(
                Distribution(
                    title = "Weather Data - CSV Format",
                    description = "Daily weather data in CSV format",
                    accessUrl = "https://data.example.gov/api/weather.csv",
                    downloadUrl = "https://data.example.gov/downloads/weather.csv",
                    mediaType = "text/csv",
                    format = "http://publications.europa.eu/resource/authority/file-type/CSV",
                    byteSize = 1024000L
                ),
                Distribution(
                    title = "Weather Data - JSON Format",
                    description = "Daily weather data in JSON format",
                    accessUrl = "https://data.example.gov/api/weather.json",
                    downloadUrl = "https://data.example.gov/downloads/weather.json",
                    mediaType = "application/json",
                    format = "http://publications.europa.eu/resource/authority/file-type/JSON",
                    byteSize = 2048000L
                )
            )
        )
        
        // Convert to RDF using manual mapping
        val datasetUri = Iri("https://data.example.gov/dataset/weather-data")
        val triples = mutableListOf<RdfTriple>()
        
        triples.add(RdfTriple(datasetUri, RDF.type, Iri("http://www.w3.org/ns/dcat#Dataset")))
        triples.add(RdfTriple(datasetUri, Iri("http://purl.org/dc/terms/title"), string(dataset.title)))
        triples.add(RdfTriple(datasetUri, Iri("http://purl.org/dc/terms/description"), string(dataset.description)))
        triples.add(RdfTriple(datasetUri, Iri("http://purl.org/dc/terms/publisher"), Iri("https://www.example.gov/agency")))
        triples.add(RdfTriple(datasetUri, Iri("http://purl.org/dc/terms/issued"), Literal(dataset.issued, XSD.date)))
        triples.add(RdfTriple(datasetUri, Iri("http://purl.org/dc/terms/modified"), Literal(dataset.modified, XSD.date)))
        triples.add(RdfTriple(datasetUri, Iri("http://purl.org/dc/terms/license"), Iri(dataset.license)))
        triples.add(RdfTriple(datasetUri, Iri("http://purl.org/dc/terms/rights"), string(dataset.rights)))
        
        // Add keywords
        dataset.keywords.forEach { keyword ->
            triples.add(RdfTriple(datasetUri, Iri("http://www.w3.org/ns/dcat#keyword"), string(keyword)))
        }
        
        // Add distributions
        dataset.distributions.forEachIndexed { index, distribution ->
            val distributionUri = Iri("https://data.example.gov/distribution/weather-${index + 1}")
            triples.add(RdfTriple(distributionUri, RDF.type, Iri("http://www.w3.org/ns/dcat#Distribution")))
            triples.add(RdfTriple(distributionUri, Iri("http://purl.org/dc/terms/title"), string(distribution.title)))
            triples.add(RdfTriple(distributionUri, Iri("http://purl.org/dc/terms/description"), string(distribution.description)))
            triples.add(RdfTriple(distributionUri, Iri("http://www.w3.org/ns/dcat#accessURL"), Iri(distribution.accessUrl)))
            triples.add(RdfTriple(distributionUri, Iri("http://www.w3.org/ns/dcat#downloadURL"), Iri(distribution.downloadUrl)))
            triples.add(RdfTriple(distributionUri, Iri("http://www.w3.org/ns/dcat#mediaType"), string(distribution.mediaType)))
            triples.add(RdfTriple(distributionUri, Iri("http://purl.org/dc/terms/format"), Iri(distribution.format)))
            triples.add(RdfTriple(distributionUri, Iri("http://www.w3.org/ns/dcat#byteSize"), Literal(distribution.byteSize.toString(), XSD.decimal)))
            
            // Link distribution to dataset
            triples.add(RdfTriple(datasetUri, Iri("http://www.w3.org/ns/dcat#distribution"), distributionUri))
        }
        
        val graph = MemoryGraph(triples)
        repo.add { graph }
        
        return dataset
    }
    
    /**
     * Example 3: Query the RDF data and convert back to domain objects
     */
    fun queryAndConvertBack(): List<Catalog> {
        val query = """
            PREFIX dcat: <http://www.w3.org/ns/dcat#>
            PREFIX dct: <http://purl.org/dc/terms/>
            PREFIX foaf: <http://xmlns.com/foaf/0.1/>
            
            SELECT ?catalog ?title ?description ?issued ?modified ?license ?language ?publisherName ?publisherHomepage WHERE {
                ?catalog a dcat:Catalog .
                ?catalog dct:title ?title .
                ?catalog dct:description ?description .
                ?catalog dct:issued ?issued .
                ?catalog dct:modified ?modified .
                ?catalog dct:license ?license .
                ?catalog dct:language ?language .
                ?catalog dct:publisher ?publisher .
                ?publisher foaf:name ?publisherName .
                ?publisher foaf:homepage ?publisherHomepage .
            }
        """
        
        val results = repo.select(SparqlSelectQuery(query))
        return results.map { binding ->
            Catalog(
                title = binding.getString("title") ?: "",
                description = binding.getString("description") ?: "",
                publisher = Organization(
                    name = binding.getString("publisherName") ?: "",
                    homepage = binding.getString("publisherHomepage") ?: ""
                ),
                issued = binding.getString("issued") ?: "",
                modified = binding.getString("modified") ?: "",
                license = binding.getString("license") ?: "",
                language = binding.getString("language") ?: ""
            )
        }
    }
    
    /**
     * Example 4: Export as different formats
     */
    fun exportAsTurtle(): String {
        val allTriples = repo.select(SparqlSelectQuery("SELECT ?s ?p ?o WHERE { ?s ?p ?o }"))
            .map { binding ->
                RdfTriple(
                    binding.get("s") as Iri,
                    binding.get("p") as Iri,
                    binding.get("o") as RdfTerm
                )
            }
        
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
}

// Manual domain classes (what OntoMapper would generate)

data class Catalog(
    val title: String,
    val description: String,
    val publisher: Organization,
    val issued: String,
    val modified: String,
    val license: String,
    val language: String
)

data class Dataset(
    val title: String,
    val description: String,
    val keywords: List<String>,
    val publisher: Organization,
    val issued: String,
    val modified: String,
    val license: String,
    val rights: String,
    val distributions: List<Distribution>
)

data class Distribution(
    val title: String,
    val description: String,
    val accessUrl: String,
    val downloadUrl: String,
    val mediaType: String,
    val format: String,
    val byteSize: Long
)

data class Organization(
    val name: String,
    val homepage: String
)

/**
 * Main function demonstrating manual DCAT-US usage
 */
fun main() {
    val example = DCAT_US_Manual_Example()
    
    println("=== DCAT-US 3.0 Manual Example ===")
    println("This demonstrates what OntoMapper would generate automatically")
    
    // Example 1: Create catalog with manual classes
    println("\n1. Creating DCAT-US Catalog with manual domain classes...")
    val catalog = example.createCatalogWithManualClasses()
    println("✅ Created catalog: ${catalog.title}")
    
    // Example 2: Create dataset with distributions
    println("\n2. Creating DCAT-US Dataset with distributions...")
    val dataset = example.createDatasetWithDistributions()
    println("✅ Created dataset: ${dataset.title}")
    println("   - ${dataset.distributions.size} distributions")
    println("   - Keywords: ${dataset.keywords.joinToString(", ")}")
    
    // Example 3: Query and convert back
    println("\n3. Querying RDF data and converting back to domain objects...")
    val queriedCatalogs = example.queryAndConvertBack()
    println("✅ Found ${queriedCatalogs.size} catalogs:")
    queriedCatalogs.forEach { c ->
        println("   - ${c.title} (published by ${c.publisher.name})")
    }
    
    // Example 4: Export as Turtle
    println("\n4. Exporting data as Turtle...")
    val turtle = example.exportAsTurtle()
    println("✅ Exported data as Turtle (${turtle.length} characters)")
    println("First 300 characters:")
    println(turtle.take(300) + "...")
    
    println("\n=== Manual example completed! ===")
    println("\nThis demonstrates the power of OntoMapper:")
    println("- Type-safe domain classes")
    println("- Automatic RDF mapping")
    println("- Bidirectional conversion")
    println("- Multi-format export")
}









