package com.example.separatepackages

// Import from separate packages
import com.example.dcatus.interfaces.*
import com.example.dcatus.wrappers.*
import com.example.dcatus.vocab.DCAT
import com.example.schema.interfaces.*
import com.example.schema.wrappers.*
import com.example.schema.vocab.SCHEMA
import com.example.foaf.interfaces.*
import com.example.foaf.wrappers.*
import com.example.foaf.vocab.FOAF
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.*

/**
 * Demonstration of using separate packages for vocabularies, interfaces, and wrappers.
 * 
 * This example shows how to generate code with different packages for each component type,
 * providing better organization and separation of concerns.
 */
fun main() {
    println("=== Separate Packages Demo ===\n")
    
    // Create a repository and add some RDF data
    val repo = Rdf.memory()
    
    // Add data using generated vocabularies from separate packages
    repo.add {
        // DCAT-US data using vocabulary from separate package
        val catalog = Iri("http://example.org/catalog")
        catalog - RDF.type - DCAT.Catalog
        catalog - DCTERMS.title - "My Data Catalog"
        catalog - DCTERMS.description - "A catalog of datasets"
        
        val dataset = Iri("http://example.org/dataset")
        dataset - RDF.type - DCAT.Dataset
        dataset - DCTERMS.title - "Sample Dataset"
        dataset - DCTERMS.description - "A sample dataset"
        
        catalog - DCAT.datasetProp - dataset
        
        // Schema.org data using vocabulary from separate package
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
        
        // FOAF data using vocabulary from separate package
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
    
    println("Added RDF data to repository using separate package vocabularies")
    println("Repository size: ${repo.defaultGraph.size} triples")
    
    // Demonstrate usage of generated components from separate packages
    
    // 1. Vocabulary usage (from vocab packages)
    println("\n=== Vocabulary Usage (from vocab packages) ===")
    println("DCAT Vocabulary:")
    println("  Package: com.example.dcatus.vocab")
    println("  Namespace: ${DCAT.namespace}")
    println("  Prefix: ${DCAT.prefix}")
    println("  Catalog class: ${DCAT.Catalog.value}")
    println("  Dataset class: ${DCAT.Dataset.value}")
    
    println("\nSchema Vocabulary:")
    println("  Package: com.example.schema.vocab")
    println("  Namespace: ${SCHEMA.namespace}")
    println("  Prefix: ${SCHEMA.prefix}")
    println("  Person class: ${SCHEMA.Person.value}")
    println("  Name property: ${SCHEMA.name.value}")
    
    println("\nFOAF Vocabulary:")
    println("  Package: com.example.foaf.vocab")
    println("  Namespace: ${FOAF.namespace}")
    println("  Prefix: ${FOAF.prefix}")
    println("  Person class: ${FOAF.Person.value}")
    println("  Name property: ${FOAF.name.value}")
    
    // 2. Interface usage (from interface packages)
    println("\n=== Interface Usage (from interface packages) ===")
    
    // Materialize using interfaces from separate packages
    val catalogRef = RdfRef(Iri("http://example.org/catalog"), repo.defaultGraph)
    val catalog: Catalog = catalogRef.asType()
    
    println("Catalog Interface (from com.example.dcatus.interfaces):")
    println("  Title: ${catalog.title}")
    println("  Description: ${catalog.description}")
    println("  Dataset count: ${catalog.dataset.size}")
    
    val personRef = RdfRef(Iri("http://example.org/person"), repo.defaultGraph)
    val person: Person = personRef.asType()
    
    println("\nPerson Interface (from com.example.schema.interfaces):")
    println("  Name: ${person.name}")
    println("  Email: ${person.email}")
    println("  Address: ${person.address?.let { "${it.streetAddress}, ${it.addressLocality}, ${it.addressCountry}" }}")
    
    val foafPersonRef = RdfRef(Iri("http://example.org/foaf-person"), repo.defaultGraph)
    val foafPerson: foaf.Person = foafPersonRef.asType()
    
    println("\nFOAF Person Interface (from com.example.foaf.interfaces):")
    println("  Name: ${foafPerson.name}")
    println("  Email: ${foafPerson.mbox}")
    println("  Knows count: ${foafPerson.knows.size}")
    
    // 3. Wrapper usage (from wrapper packages)
    println("\n=== Wrapper Usage (from wrapper packages) ===")
    
    // Use generated wrappers directly from separate packages
    val catalogHandle = catalogRef.rdf
    val catalogWrapper = CatalogWrapper(catalogHandle)
    
    println("Catalog Wrapper (from com.example.dcatus.wrappers):")
    println("  Title: ${catalogWrapper.title}")
    println("  Description: ${catalogWrapper.description}")
    
    val personHandle = personRef.rdf
    val personWrapper = PersonWrapper(personHandle)
    
    println("\nPerson Wrapper (from com.example.schema.wrappers):")
    println("  Name: ${personWrapper.name}")
    println("  Email: ${personWrapper.email}")
    
    val foafPersonHandle = foafPersonRef.rdf
    val foafPersonWrapper = foaf.PersonWrapper(foafPersonHandle)
    
    println("\nFOAF Person Wrapper (from com.example.foaf.wrappers):")
    println("  Name: ${foafPersonWrapper.name}")
    println("  Email: ${foafPersonWrapper.mbox}")
    
    // 4. Cross-package queries
    println("\n=== Cross-Package Queries ===")
    
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
    
    println("Query Results using vocabularies from separate packages:")
    results.forEach { bindingSet ->
        val name = bindingSet.getValue("name")?.asLiteral()?.lexical
        val email = bindingSet.getValue("email")?.asLiteral()?.lexical
        val type = bindingSet.getValue("type")?.asIri()?.value
        println("  Name: $name, Email: $email, Type: $type")
    }
    
    // 5. Package organization benefits
    println("\n=== Package Organization Benefits ===")
    println("✅ Clear separation of concerns")
    println("✅ Vocabularies in dedicated vocab packages")
    println("✅ Interfaces in dedicated interface packages")
    println("✅ Wrappers in dedicated wrapper packages")
    println("✅ No naming conflicts between component types")
    println("✅ Better IDE navigation and organization")
    println("✅ Easier maintenance and updates")
    
    // 6. Generated file structure
    println("\n=== Generated File Structure ===")
    println("build/generated/sources/")
    println("├── dcat/")
    println("│   ├── com/example/dcatus/interfaces/")
    println("│   │   ├── Catalog.kt")
    println("│   │   └── Dataset.kt")
    println("│   ├── com/example/dcatus/wrappers/")
    println("│   │   ├── CatalogWrapper.kt")
    println("│   │   └── DatasetWrapper.kt")
    println("│   └── com/example/dcatus/vocab/")
    println("│       └── DCAT.kt")
    println("├── schema/")
    println("│   ├── com/example/schema/interfaces/")
    println("│   │   ├── Person.kt")
    println("│   │   └── PostalAddress.kt")
    println("│   ├── com/example/schema/wrappers/")
    println("│   │   ├── PersonWrapper.kt")
    println("│   │   └── PostalAddressWrapper.kt")
    println("│   └── com/example/schema/vocab/")
    println("│       └── SCHEMA.kt")
    println("└── foaf/")
    println("    ├── com/example/foaf/interfaces/")
    println("    │   ├── Person.kt")
    println("    │   └── Document.kt")
    println("    ├── com/example/foaf/wrappers/")
    println("    │   ├── PersonWrapper.kt")
    println("    │   └── DocumentWrapper.kt")
    println("    └── com/example/foaf/vocab/")
    println("        └── FOAF.kt")
    
    println("\n=== Demo Complete ===")
}









