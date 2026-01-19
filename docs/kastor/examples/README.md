# 📚 Examples Guide

This guide showcases the comprehensive examples available in Kastor RDF, demonstrating real-world usage patterns and best practices.

## 📋 Table of Contents

- [Overview](#-overview)
- [Getting Started Examples](#-getting-started-examples)
- [Core API Examples](#-core-api-examples)
- [Advanced Features](#-advanced-features)
- [Real-World Scenarios](#-real-world-scenarios)
- [Running Examples](#-running-examples)
- [Creating Your Own](#-creating-your-own)

## 🎯 Overview

Kastor RDF provides a rich collection of examples that demonstrate:

- **Basic Operations** - Creating repositories, adding data, querying
- **DSL Syntaxes** - Multiple ways to express RDF triples
- **Advanced Features** - Performance monitoring, batch operations, transactions
- **Real-World Patterns** - Common use cases and best practices
- **Backend Comparisons** - Jena vs RDF4J differences

## 🚀 Getting Started Examples

### Configuration and Parameter Discovery

**File**: `ConfigVariantInfoTest.kt`, `EnhancedParameterInfoTest.kt`, `CompleteParameterSystemExample.kt`

Demonstrates the enhanced configuration system with rich parameter metadata:

```kotlin
@Test
fun `demonstrate enhanced parameter information`() {
    // Get all available variants with detailed parameter info
    val variants = RdfApiRegistry.getAllConfigVariants()
    variants.forEach { variant ->
        println("${variant.type}: ${variant.description}")
        variant.parameters.forEach { param ->
            println("  ${param.name} (${param.type}): ${param.description}")
            if (param.examples.isNotEmpty()) {
                println("    Examples: ${param.examples.joinToString(", ")}")
            }
        }
    }
    
    // Get parameter information for specific variant
    val locationParam = RdfApiRegistry.getParameterInfo("jena:tdb2", "location")
    locationParam?.let { param ->
        println("Parameter: ${param.name}")
        println("Type: ${param.type}")
        println("Description: ${param.description}")
        println("Examples: ${param.examples.joinToString(", ")}")
    }
    
    // Validate configuration before creating repository
    val requiredParams = RdfApiRegistry.getRequiredParameters("sparql")
    val config = RdfConfig(
        providerId = "sparql",
        variantId = "sparql",
        options = mapOf("location" to "http://dbpedia.org/sparql")
    )
    val missingParams = requiredParams.filter { param -> 
        !config.params.containsKey(param.name) 
    }
    
    if (missingParams.isEmpty()) {
        println("Configuration is valid")
    } else {
        println("Missing required parameters: ${missingParams.map { it.name }}")
    }
}
```

### Basic Operations

**File**: `BasicOperationsExample.kt`

Demonstrates fundamental RDF operations:

```kotlin
fun main() {
    // Create repository
    val repo = Rdf.memory()
    
    // Add data
    repo.add {
        val person = "http://example.org/person/alice".toResource()
        person["http://example.org/person/name"] = "Alice Johnson"
        person["http://example.org/person/age"] = 30
    }
    
    // Query data
    val results = repo.query("""
        SELECT ?name ?age WHERE { 
            ?person <http://example.org/person/name> ?name ;
                    <http://example.org/person/age> ?age 
        }
    """)
    
    results.forEach { binding ->
        println("${binding.getString("name")} is ${binding.getInt("age")} years old")
    }
    
    repo.close()
}
```

### Repository Types

**File**: `RepositoryTypesExample.kt`

Shows different repository configurations:

```kotlin
fun main() {
    // In-memory repository
    val memoryRepo = Rdf.memory()
    
    // Persistent repository
    val persistentRepo = Rdf.persistent("my-data")
    
    // Repository with inference
    val inferenceRepo = Rdf.memoryWithInference()
    
    // Custom configuration
    val customRepo = Rdf.factory {
        providerId = "jena"
        variantId = "tdb2"
        location = "/path/to/storage"
    }
    
    // Clean up
    listOf(memoryRepo, persistentRepo, inferenceRepo, customRepo).forEach { it.close() }
}
```

## 🔧 Core API Examples

### DSL Syntax Examples

**File**: `CompactDslExample.kt`

Demonstrates all available DSL syntaxes:

```kotlin
fun main() {
    val repo = Rdf.memory()
    val person = "http://example.org/person/alice".toResource()
    
    repo.add {
        // Ultra-compact syntax
        person["http://example.org/person/name"] = "Alice"
        person["http://example.org/person/age"] = 30
        
        // Natural language syntax
        person has "http://example.org/person/email" with "alice@example.com"
        
        // Generic infix operator
        person has "http://example.org/person/city" with "New York"
        
        // Minus operator syntax
        person - "http://example.org/person/phone" - "+1-555-1234"
        
        // Multiple values using values() function (individual triples)
        person - FOAF.knows - values(friend1, friend2, friend3)
        
        // RDF lists using list() function
        person - FOAF.mbox - list("alice@example.com", "alice@work.com")
    }
    
    repo.close()
}
```

### Vocabulary Agnostic Examples

**File**: `VocabularyAgnosticExample.kt`

Shows how to use the core API without vocabulary assumptions:

```kotlin
fun main() {
    val repo = Rdf.memory()
    
    // Define your own vocabulary
    object PersonVocab {
        val name = "http://example.org/person/name".toIri()
        val age = "http://example.org/person/age".toIri()
        val email = "http://example.org/person/email".toIri()
    }
    
    repo.add {
        val person = "http://example.org/person/alice".toResource()
        
        // Use full IRIs
        person[PersonVocab.name] = "Alice"
        person[PersonVocab.age] = 30
        person[PersonVocab.email] = "alice@example.com"
    }
    
    repo.close()
}
```

### Ultra-Compact with Variables

**File**: `UltraCompactWithVariablesExample.kt`

Demonstrates using variables with the ultra-compact syntax:

### Minus Operator Examples

**File**: `MinusOperatorExamples.kt`

Demonstrates the new minus operator syntax with `values()` and `list()` functions:

```kotlin
fun main() {
    val repo = Rdf.memory()
    val person = iri("http://example.org/person/alice")
    val friend1 = iri("http://example.org/person/bob")
    val friend2 = iri("http://example.org/person/charlie")
    val friend3 = iri("http://example.org/person/diana")
    
    repo.add {
        // Single values
        person - FOAF.name - "Alice"
        person - FOAF.age - 30
        
        // Multiple individual triples using values() function
        person - FOAF.knows - values(friend1, friend2, friend3)
        
        // RDF lists using list() function
        person - FOAF.mbox - list("alice@example.com", "alice@work.com")
        
        // RDF containers using bag(), seq(), alt() functions
        person - DCTERMS.subject - bag("Technology", "AI", "RDF", "Technology")  // rdf:Bag
        person - FOAF.knows - seq(friend1, friend2, friend3)                    // rdf:Seq
        person - FOAF.mbox - alt("alice@example.com", "alice@work.com")        // rdf:Alt
        
        // Mixed types work with all functions
        person - DCTERMS.subject - values("Technology", "Programming", "RDF", 42, true)
        
        // Comparison with traditional syntax
        person - FOAF.member - arrayOf(org1, org2, org3)  // Individual triples
        person - DCTERMS.type - listOf("Person", "Agent")  // RDF List
    }
    
    // Query the results
    val allTriples = repo.defaultGraph.getTriples()
    println("Total triples: ${allTriples.size}")
    
    repo.close()
}
```

**Key Features Demonstrated:**
- Single value syntax: `person - predicate - value`
- Multiple individual triples: `person - predicate - values(v1, v2, v3)`
- RDF lists: `person - predicate - list(v1, v2, v3)`
- RDF containers: `bag()`, `seq()`, `alt()` functions
- Mixed data types support
- Comparison with traditional array/list syntax

```kotlin
fun main() {
    val repo = Rdf.memory()
    
    // Define variables
    val name = iri("http://example.org/person/name")
    val age = iri("http://example.org/person/age")
    
    // Use variables in ultra-compact syntax
    repo.add {
        val person = "http://example.org/person/alice".toResource()
        person[name] = "Alice Johnson"
        person[age] = 30
    }
    
    repo.close()
}
```

## ⚡ Advanced Features

### Super Sleek API

**File**: `SuperSleekExample.kt`

Showcases the most elegant and modern features:

```kotlin
fun main() {
    println("🚀 === Super Sleek RDF API Demo ===\n")
    
    // Elegant factory methods
    val repo = Rdf.memory()
    
    // Fluent interface operations
    repo.fluent()
        .add {
            val alice = "http://example.org/person/alice".toResource()
            alice["http://example.org/person/name"] = "Alice Johnson"
            alice["http://example.org/person/age"] = 30
        }
        .query("SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }")
        .forEach { binding ->
            println("Found: ${binding.getString("name")}")
        }
    
    // Performance monitoring
    val (_, queryDuration) = repo.queryTimed("""
        SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }
    """)
    println("Query took: $queryDuration")
    
    // Batch operations
    repo.addBatch(batchSize = 50) {
        for (i in 1..100) {
            val person = "http://example.org/person/person$i".toResource()
            person["http://example.org/person/name"] = "Person $i"
            person["http://example.org/person/age"] = 20 + (i % 50)
        }
    }
    
    // Advanced query features
    val firstPerson = repo.queryFirst("""
        SELECT ?name WHERE { ?person <http://example.org/person/name> ?name } LIMIT 1
    """)
    println("First person: ${firstPerson?.getString("name")}")
    
    // Transaction operations
    repo.transaction {
        add {
            val david = "http://example.org/person/david".toResource()
            david["http://example.org/person/name"] = "David Wilson"
            david["http://example.org/person/age"] = 40
        }
        
        val count = query("SELECT (COUNT(?person) AS ?count) WHERE { ?person <http://example.org/person/name> ?name }")
            .firstOrNull()?.getInt("count") ?: 0
        println("People count in transaction: $count")
    }
    
    // Operator overloads
    val triple1 = alice -> ("http://example.org/person/name" to "Alice")
    val triple2 = alice -> ("http://example.org/person/age" to 30)
    repo.addTriples(listOf(triple1, triple2))
    
    // Convenience functions
    val eve = resource("http://example.org/person/eve")
    val eveName = literal("Eve Anderson")
    val eveAge = literal(28)
    
    val eveTriples = listOf(
        triple(eve, Iri("http://example.org/person/name"), eveName),
        triple(eve, Iri("http://example.org/person/age"), eveAge)
    )
    repo.addTriples(eveTriples)
    
    // Graph operations
    val metadataGraph = repo.createGraph("http://example.org/graphs/metadata".toIri())
    repo.addToGraph("http://example.org/graphs/metadata".toIri()) {
        val metadata = "http://example.org/metadata".toResource()
        metadata["http://example.org/metadata/created"] = "2024-01-01"
        metadata["http://example.org/metadata/version"] = "1.0"
    }
    
    // Final statistics
    val stats = repo.getStatistics()
    println("""
        📊 Repository Statistics:
        ├─ Total Triples: ${stats.totalTriples}
        ├─ Named Graphs: ${stats.graphCount}
        ├─ Size: ${stats.sizeBytes / 1024}KB
        └─ Last Modified: ${java.time.Instant.ofEpochMilli(stats.lastModified)}
    """.trimIndent())
    
    repo.close()
    println("\n🎉 === Super Sleek API Demo Complete ===")
}
```

### Performance Examples

**File**: `PerformanceExample.kt`

Demonstrates performance optimization techniques:

```kotlin
fun main() {
    val repo = Rdf.memory()
    
    // Batch operations for large datasets
    val startTime = System.currentTimeMillis()
    
    repo.addBatch(batchSize = 1000) {
        for (i in 1..10000) {
            val person = "http://example.org/person/person$i".toResource()
            person["http://example.org/person/name"] = "Person $i"
            person["http://example.org/person/age"] = 20 + (i % 50)
            person["http://example.org/person/email"] = "person$i@example.com"
        }
    }
    
    val addTime = System.currentTimeMillis() - startTime
    println("Added 30,000 triples in ${addTime}ms")
    
    // Performance monitoring
    val (results, queryTime) = repo.queryTimed("""
        SELECT ?name ?age WHERE { 
            ?person <http://example.org/person/name> ?name ;
                    <http://example.org/person/age> ?age 
        } LIMIT 100
    """)
    
    println("Query returned ${results.count()} results in $queryTime")
    
    // Statistics
    val stats = repo.getStatistics()
    println("Repository size: ${stats.sizeFormatted()}")
    
    repo.close()
}
```

## 🌍 Real-World Scenarios

### Knowledge Graph Example

**File**: `KnowledgeGraphExample.kt`

Demonstrates building a knowledge graph:

```kotlin
fun main() {
    val repo = Rdf.memory()
    
    // Define ontology
    object Ontology {
        val Person = "http://example.org/ontology/Person".toIri()
        val Company = "http://example.org/ontology/Company".toIri()
        val name = "http://example.org/ontology/name".toIri()
        val worksFor = "http://example.org/ontology/worksFor".toIri()
        val founded = "http://example.org/ontology/founded".toIri()
        val industry = "http://example.org/ontology/industry".toIri()
    }
    
    // Add knowledge graph data
    repo.add {
        // People
        val alice = "http://example.org/person/alice".toResource()
        val bob = "http://example.org/person/bob".toResource()
        val charlie = "http://example.org/person/charlie".toResource()
        
        // Companies
        val techCorp = "http://example.org/company/techcorp".toResource()
        val dataInc = "http://example.org/company/datainc".toResource()
        
        // Type assertions
        alice[Ontology.Person] = true
        bob[Ontology.Person] = true
        charlie[Ontology.Person] = true
        techCorp[Ontology.Company] = true
        dataInc[Ontology.Company] = true
        
        // Properties
        alice[Ontology.name] = "Alice Johnson"
        bob[Ontology.name] = "Bob Smith"
        charlie[Ontology.name] = "Charlie Brown"
        
        alice[Ontology.worksFor] = techCorp
        bob[Ontology.worksFor] = techCorp
        charlie[Ontology.worksFor] = dataInc
        
        techCorp[Ontology.name] = "Tech Corporation"
        techCorp[Ontology.industry] = "Software Development"
        
        dataInc[Ontology.name] = "Data Inc."
        dataInc[Ontology.industry] = "Data Analytics"
        
        alice[Ontology.founded] = techCorp
        charlie[Ontology.founded] = dataInc
    }
    
    // Query the knowledge graph
    println("=== Knowledge Graph Queries ===\n")
    
    // Find all people
    println("All People:")
    repo.query("""
        SELECT ?name WHERE { 
            ?person <http://example.org/ontology/Person> true ;
                    <http://example.org/ontology/name> ?name 
        }
    """).forEach { binding ->
        println("- ${binding.getString("name")}")
    }
    
    // Find people by company
    println("\nPeople at Tech Corporation:")
    repo.query("""
        SELECT ?name WHERE { 
            ?person <http://example.org/ontology/name> ?name ;
                    <http://example.org/ontology/worksFor> <http://example.org/company/techcorp>
        }
    """).forEach { binding ->
        println("- ${binding.getString("name")}")
    }
    
    // Find founders
    println("\nCompany Founders:")
    repo.query("""
        SELECT ?personName ?companyName WHERE { 
            ?person <http://example.org/ontology/name> ?personName ;
                    <http://example.org/ontology/founded> ?company .
            ?company <http://example.org/ontology/name> ?companyName
        }
    """).forEach { binding ->
        println("- ${binding.getString("personName")} founded ${binding.getString("companyName")}")
    }
    
    repo.close()
}
```

### Linked Data Example

**File**: `LinkedDataExample.kt`

Shows how to work with linked data:

```kotlin
fun main() {
    val repo = Rdf.memory()
    
    // Add linked data from multiple sources
    repo.add {
        // FOAF data
        val alice = "http://example.org/person/alice".toResource()
        alice["http://xmlns.com/foaf/0.1/name"] = "Alice Johnson"
        alice["http://xmlns.com/foaf/0.1/mbox"] = "alice@example.com"
        alice["http://xmlns.com/foaf/0.1/homepage"] = "http://alice.example.com"
        
        // Dublin Core metadata
        alice["http://purl.org/dc/terms/created"] = "2024-01-01"
        alice["http://purl.org/dc/terms/modified"] = "2024-01-15"
        
        // Schema.org data
        alice["http://schema.org/jobTitle"] = "Software Engineer"
        alice["http://schema.org/worksFor"] = "http://example.org/company/techcorp"
        
        // Friend relationships
        val bob = "http://example.org/person/bob".toResource()
        alice["http://xmlns.com/foaf/0.1/knows"] = bob
        bob["http://xmlns.com/foaf/0.1/name"] = "Bob Smith"
        bob["http://xmlns.com/foaf/0.1/mbox"] = "bob@example.com"
    }
    
    // Query across different vocabularies
    println("=== Linked Data Queries ===\n")
    
    // Find people with their contact info
    println("People with Contact Information:")
    repo.query("""
        SELECT ?name ?email ?homepage WHERE { 
            ?person <http://xmlns.com/foaf/0.1/name> ?name ;
                    <http://xmlns.com/foaf/0.1/mbox> ?email .
            OPTIONAL { ?person <http://xmlns.com/foaf/0.1/homepage> ?homepage }
        }
    """).forEach { binding ->
        val name = binding.getString("name")
        val email = binding.getString("email")
        val homepage = binding.getString("homepage")
        
        println("- $name ($email)")
        if (homepage != null) {
            println("  Homepage: $homepage")
        }
    }
    
    // Find social network
    println("\nSocial Network:")
    repo.query("""
        SELECT ?personName ?friendName WHERE { 
            ?person <http://xmlns.com/foaf/0.1/name> ?personName ;
                    <http://xmlns.com/foaf/0.1/knows> ?friend .
            ?friend <http://xmlns.com/foaf/0.1/name> ?friendName
        }
    """).forEach { binding ->
        println("- ${binding.getString("personName")} knows ${binding.getString("friendName")}")
    }
    
    // Find people with job information
    println("\nPeople with Job Information:")
    repo.query("""
        SELECT ?name ?jobTitle ?company WHERE { 
            ?person <http://xmlns.com/foaf/0.1/name> ?name ;
                    <http://schema.org/jobTitle> ?jobTitle ;
                    <http://schema.org/worksFor> ?company
        }
    """).forEach { binding ->
        println("- ${binding.getString("name")}: ${binding.getString("jobTitle")} at ${binding.getString("company")}")
    }
    
    repo.close()
}
```

## 🏃‍♂️ Running Examples

### From Command Line

```bash
# Navigate to examples directory
cd rdf/examples

# Run a specific example
./gradlew run --args="com.geoknoesis.kastor.rdf.examples.BasicOperationsExampleKt"

# Run with specific backend
./gradlew run --args="com.geoknoesis.kastor.rdf.examples.RepositoryTypesExampleKt"
```

### From IDE

1. **IntelliJ IDEA**:
   - Open the `rdf/examples` project
   - Navigate to `src/main/kotlin/com/geoknoesis/kastor/rdf/examples/`
   - Right-click on any example file
   - Select "Run 'ExampleNameKt'"

2. **Eclipse**:
   - Import the project
   - Right-click on example file
   - Select "Run As" → "Kotlin Application"

3. **VS Code**:
   - Open the examples folder
   - Install Kotlin extension
   - Use "Run" button or F5

### Build and Run

```bash
# Build the examples
./gradlew :rdf:examples:build

# Run all examples
./gradlew :rdf:examples:run

# Run specific example
./gradlew :rdf:examples:run --args="com.geoknoesis.kastor.rdf.examples.SuperSleekExampleKt"
```

## 🛠️ Creating Your Own

### Example Structure

Create your own example following this structure:

```kotlin
package com.geoknoesis.kastor.rdf.examples

import com.geoknoesis.kastor.rdf.*

/**
 * Your Example Name
 * 
 * Brief description of what this example demonstrates.
 * Include key concepts and learning objectives.
 */
fun main() {
    println("🚀 === Your Example Name ===\n")
    
    try {
        // Your example code here
        val repo = Rdf.memory()
        
        // Demonstrate the feature
        repo.add {
            // Your RDF data
        }
        
        // Show results
        val results = repo.query("""
            SELECT ?result WHERE { 
                # Your query
            }
        """)
        
        results.forEach { binding ->
            println("Result: ${binding.getString("result")}")
        }
        
        // Clean up
        repo.close()
        
        println("\n✅ Example completed successfully!")
        
    } catch (e: Exception) {
        println("❌ Example failed: ${e.message}")
        e.printStackTrace()
    }
}
```

### Best Practices

1. **Clear Structure**: Use clear sections with comments
2. **Error Handling**: Wrap in try-catch blocks
3. **Resource Management**: Always close repositories
4. **Documentation**: Include detailed comments
5. **Realistic Data**: Use meaningful example data
6. **Progressive Complexity**: Start simple, build up

### Example Categories

Consider creating examples for:

- **Basic Operations**: CRUD operations, simple queries
- **DSL Features**: Different syntax styles
- **Performance**: Optimization techniques
- **Integration**: Working with external data
- **Domain-Specific**: Industry use cases
- **Advanced Features**: Complex scenarios

## 📚 Learning Path

### Beginner Path

1. `BasicOperationsExample.kt` - Start here
2. `RepositoryTypesExample.kt` - Understand different backends
3. `CompactDslExample.kt` - Learn DSL syntaxes
4. `MinusOperatorExamples.kt` - Learn new values() and list() functions
5. `VocabularyAgnosticExample.kt` - Core API principles

### Intermediate Path

1. `UltraCompactWithVariablesExample.kt` - Advanced DSL usage
2. `PerformanceExample.kt` - Optimization techniques
3. `KnowledgeGraphExample.kt` - Real-world patterns
4. `LinkedDataExample.kt` - Integration scenarios

### Advanced Path

1. `SuperSleekExample.kt` - All features combined
2. Create custom examples
3. Explore backend-specific features
4. Build domain-specific applications

## 🤝 Contributing Examples

We welcome contributions! To add your example:

1. **Create the file** in `rdf/examples/src/main/kotlin/com/geoknoesis/kastor/rdf/examples/`
2. **Follow the structure** above
3. **Add documentation** explaining the example
4. **Test thoroughly** to ensure it works
5. **Submit a pull request**

## 🎯 Next Steps

After exploring the examples:

1. **[Quick Start Guide](../getting-started/quick-start.md)** - Build your first application
2. **[RDF Fundamentals](../concepts/rdf-fundamentals.md)** - Deepen your understanding
3. **[API Reference](../api/api-reference.md)** - Complete API documentation
4. **[DSL Guide](../api/compact-dsl-guide.md)** - Master advanced features

## 📞 Need Help?

- **Examples**: Check the example code for patterns
- **Documentation**: [docs/](docs/)
- **Issues**: [GitHub Issues](https://github.com/geoknoesis/kastor-rdf/issues)
- **Discussions**: [GitHub Discussions](https://github.com/geoknoesis/kastor-rdf/discussions)

---

**🎉 Ready to explore? Start with the basic examples and work your way up to building amazing RDF applications!**




