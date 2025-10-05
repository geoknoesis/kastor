# 🚀 Super Sleek RDF API Guide

## Overview

The Kastor RDF API is the **most elegant and modern RDF API for Kotlin**, designed with developer productivity and code elegance as top priorities. This guide showcases the super sleek features that make it the best RDF API on the market.

## ✨ Key Features

- 🏭 **Elegant Factory Methods** - Simple, intuitive repository creation
- 🔄 **Fluent Interface Operations** - Chain operations for readable code
- ⚡ **Performance Monitoring** - Built-in timing and statistics
- 📦 **Batch Operations** - Efficient bulk data processing
- 🔍 **Advanced Query Features** - Type-safe result processing
- 💼 **Transaction Operations** - Atomic operations with rollback
- 🎯 **Operator Overloads** - Natural triple creation syntax
- 🛠️ **Convenience Functions** - Minimal boilerplate code
- 📊 **Graph Operations** - Named graph management
- 📈 **Comprehensive Statistics** - Detailed repository insights

## 🏭 Elegant Factory Methods

Create repositories with minimal code and maximum clarity:

```kotlin
// Simple in-memory repository
val repo = Rdf.memory()

// Persistent repository with TDB2 backend
val persistentRepo = Rdf.persistent("my-data")

// In-memory repository with RDFS inference
val inferenceRepo = Rdf.memoryWithInference()

// Custom configuration
val customRepo = Rdf.factory {
    type = "tdb2"
    location = "custom-data"
    inference = true
    optimization = true
    cacheSize = 2000
    maxMemory = "2GB"
}
```

## 🔄 Fluent Interface Operations

Chain operations together for elegant, readable code:

```kotlin
val alice = "http://example.org/person/alice".toResource()
val company = "http://example.org/company/tech".toResource()

// Define vocabulary objects for clean organization
object PersonVocab {
    val name = "http://example.org/person/name".toIri()
    val age = "http://example.org/person/age".toIri()
    val worksFor = "http://example.org/person/worksFor".toIri()
}

// Chain operations fluently
repo.fluent()
    .add {
        alice[PersonVocab.name] = "Alice Johnson"
        alice[PersonVocab.age] = 30
        alice[PersonVocab.worksFor] = company
    }
    .query("SELECT ?name ?age WHERE { ?person <http://example.org/person/name> ?name ; <http://example.org/person/age> ?age }")
    .forEach { binding ->
        println("${binding.getString("name")} is ${binding.getInt("age")} years old")
    }
    .clear()
    .statistics()
```

## ⚡ Performance Monitoring

Monitor query and operation performance with built-in timing:

```kotlin
// Time query execution
val (results, queryDuration) = repo.queryTimed("""
    SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }
""")
println("Query executed in: $queryDuration")

// Time operation execution
val (_, operationDuration) = repo.operationTimed {
    add {
        val person = "http://example.org/person/new".toResource()
        person[PersonVocab.name] = "New Person"
        person[PersonVocab.age] = 25
    }
}
println("Operation completed in: $operationDuration")

// Get comprehensive statistics
println(repo.statisticsFormatted())
```

## 📦 Batch Operations

Process large datasets efficiently with batch operations:

```kotlin
// Create many people efficiently
val people = (1..1000).map { i ->
    "http://example.org/person/person$i".toResource()
}

// Add in batches for better performance
repo.addBatch(batchSize = 100) {
    people.forEachIndexed { index, person ->
        person[PersonVocab.name] = "Person ${index + 1}"
        person[PersonVocab.age] = 20 + (index % 50)
        person[PersonVocab.worksFor] = company
    }
}

// Add to specific graph in batches
repo.addBatchToGraph(
    graphName = "http://example.org/graphs/temp".toIri(),
    batchSize = 50
) {
    // Batch operations for specific graph
}
```

## 🔍 Advanced Query Features

Type-safe query results with convenient access patterns:

```kotlin
// Get first result directly
val firstPerson = repo.queryFirst("""
    SELECT ?name WHERE { ?person <http://example.org/person/name> ?name } LIMIT 1
""")
println("First person: ${firstPerson?.getString("name")}")

// Get results as a map
val nameAgeMap = repo.queryMap(
    sparql = "SELECT ?name ?age WHERE { ?person <http://example.org/person/name> ?name ; <http://example.org/person/age> ?age }",
    keySelector = { it.getString("name") ?: "Unknown" },
    valueSelector = { it.getInt("age").toString() }
)

// Get results as specific types
val names: List<String> = repo.query("""
    SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }
""").mapAs()

// Type-safe result access
val results = repo.query("SELECT ?name ?age ?email WHERE { ?person <http://example.org/person/name> ?name }")
val firstResult = results.firstAs<String>() // Get first result as String
val allNames = results.mapAs<String>() // Get all results as List<String>
```

## 💼 Transaction Operations

Atomic operations with automatic rollback on failure:

```kotlin
// Execute operations in a transaction
repo.fluent()
    .transaction {
        // All operations in this block are atomic
        add {
            val person = "http://example.org/person/atomic".toResource()
            person[PersonVocab.name] = "Atomic Person"
            person[PersonVocab.age] = 35
        }
        
        // Query within transaction
        val count = query("SELECT (COUNT(?person) AS ?count) WHERE { ?person <http://example.org/person/name> ?name }")
            .firstOrNull()?.getInt("count") ?: 0
        
        println("People count in transaction: $count")
    }
    .readTransaction {
        // Read-only transaction for better performance
        val avgAge = query("""
            SELECT (AVG(?age) AS ?avgAge) 
            WHERE { ?person <http://example.org/person/age> ?age }
        """).firstOrNull()?.getDouble("avgAge") ?: 0.0
        
        println("Average age: ${String.format("%.1f", avgAge)}")
    }
```

## 🎯 Operator Overloads

Create triples with natural, intuitive syntax:

```kotlin
val alice = "http://example.org/person/alice".toResource()
val name = "http://example.org/person/name".toIri()

// Natural triple creation with operator overloads
val triple1 = alice -> (name to "Alice")
val triple2 = alice -> ("http://example.org/person/age" to 30)
val triple3 = alice -> (name to "Alice Johnson")

// Add triples created with operators
repo.addTriples(listOf(triple1, triple2, triple3))
```

## 🛠️ Convenience Functions

Minimal boilerplate with convenient utility functions:

```kotlin
// Create resources and literals naturally
val person = resource("http://example.org/person/john")
val name = literal("John Doe")
val age = literal(30)
val email = literal("john@example.com")

// Create triples with convenience functions
val triples = listOf(
    triple(person, PersonVocab.name, name),
    triple(person, PersonVocab.age, age),
    triple(person, PersonVocab.email, email)
)

// String extensions for easy conversion
val iri = "http://example.org/resource".toIri()
val resource = "http://example.org/person".toResource()
val literal = "Hello World".toLiteral()
```

## 📊 Graph Operations

Manage named graphs with elegant operations:

```kotlin
// Create a named graph
val metadataGraph = repo.createGraph("http://example.org/graphs/metadata".toIri())

// Add triples to specific graph
repo.addToGraph("http://example.org/graphs/metadata".toIri()) {
    val metadata = "http://example.org/metadata".toResource()
    metadata["http://example.org/metadata/created"] = "2024-01-01"
    metadata["http://example.org/metadata/version"] = "1.0"
    metadata["http://example.org/metadata/description"] = "Sample dataset"
}

// List all named graphs
val graphs = repo.listGraphs()

// Check if graph exists
if (repo.hasGraph("http://example.org/graphs/metadata".toIri())) {
    println("Metadata graph exists")
}

// Remove a graph
repo.removeGraph("http://example.org/graphs/temp".toIri())
```

## 📈 Comprehensive Statistics

Get detailed insights into repository performance and usage:

```kotlin
// Get repository statistics
val stats = repo.getStatistics()
println("""
    Repository Statistics:
    ├─ Total Triples: ${stats.totalTriples}
    ├─ Named Graphs: ${stats.graphCount}
    ├─ Size: ${stats.sizeBytes / 1024}KB
    ├─ Last Modified: ${java.time.Instant.ofEpochMilli(stats.lastModified)}
    └─ Index Stats: ${stats.indexStats}
""".trimIndent())

// Get performance monitoring data
val perf = repo.getPerformanceMonitor()
println("""
    Performance Metrics:
    ├─ Query Count: ${perf.queryCount}
    ├─ Average Query Time: ${String.format("%.2f", perf.averageQueryTime)}ms
    ├─ Slowest Queries: ${perf.slowestQueries.take(3)}
    ├─ Cache Hit Rate: ${String.format("%.1f", perf.cacheHitRate * 100)}%
    └─ Memory Usage: ${perf.memoryUsage / 1024 / 1024}MB
""".trimIndent())

// Human-readable statistics
println(repo.sizeFormatted()) // "1.2K triples" or "1.5M triples"
println(repo.statisticsFormatted()) // Formatted statistics output
```

## 🎨 Multiple DSL Syntax Styles

Choose the syntax that feels most natural to you:

```kotlin
val person = "http://example.org/person/alice".toResource()
val name = "http://example.org/person/name".toIri()

// 1. Ultra-compact syntax (most concise)
person[name] = "Alice"
person["http://example.org/person/age"] = 30

// 2. Natural language syntax (most explicit)
person has name with "Alice"
person has "http://example.org/person/age" with 30

// 3. Generic infix operator (natural flow)
person has name with "Alice"
person has "http://example.org/person/age" with 30

// 4. Operator overloads (minimal syntax)
person -> (name to "Alice")
person -> ("http://example.org/person/age" to 30)

// 5. Convenience functions (explicit)
triple(person, name, "Alice")
triple(person, "http://example.org/person/age", 30)
```

## 🔧 Advanced Configuration

Fine-tune repository behavior with comprehensive configuration:

```kotlin
// Repository manager for multi-repository operations
val manager = Rdf.manager {
    repository("main") {
        type = "tdb2"
        location = "main-data"
        inference = true
        optimization = true
        cacheSize = 2000
        maxMemory = "2GB"
    }
    
    repository("cache") {
        type = "memory"
        optimization = true
        cacheSize = 5000
    }
    
    repository("archive") {
        type = "tdb2"
        location = "archive-data"
        inference = false
        optimization = false
    }
}

// Federated queries across multiple repositories
val results = manager.federatedQuery(
    sparql = "SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }",
    repositories = listOf("main", "cache")
)

// Copy data between repositories
manager.copyData("main", "archive", graphName = "http://example.org/graphs/important".toIri())
```

## 🚀 Best Practices

### Performance Optimization

```kotlin
// Use batch operations for large datasets
repo.addBatch(batchSize = 1000) {
    // Large dataset operations
}

// Use read-only transactions for queries
repo.readTransaction {
    // Multiple read operations
}

// Monitor performance regularly
val (_, duration) = repo.queryTimed("SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }")
if (duration.inWholeMilliseconds > 1000) {
    println("Slow query detected: $duration")
}
```

### Code Organization

```kotlin
// Define vocabularies at the top of your file
object PersonVocab {
    val name = "http://example.org/person/name".toIri()
    val age = "http://example.org/person/age".toIri()
    val email = "http://example.org/person/email".toIri()
    val worksFor = "http://example.org/person/worksFor".toIri()
}

object CompanyVocab {
    val name = "http://example.org/company/name".toIri()
    val industry = "http://example.org/company/industry".toIri()
    val location = "http://example.org/company/location".toIri()
}

// Use fluent interface for complex operations
repo.fluent()
    .add { /* operations */ }
    .query("SELECT ...")
    .forEach { /* process results */ }
    .clear()
```

### Error Handling

```kotlin
// Use transactions for atomic operations
repo.transaction {
    try {
        add { /* operations */ }
        // If any operation fails, all changes are rolled back
    } catch (e: Exception) {
        println("Operation failed: ${e.message}")
        // Transaction automatically rolls back
    }
}

// Handle query errors gracefully
val results = try {
    repo.query("SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }")
} catch (e: Exception) {
    println("Query failed: ${e.message}")
    emptyList<BindingSet>()
}
```

## 🎉 Conclusion

The Kastor RDF API provides the most elegant and modern experience for working with RDF data in Kotlin. With its fluent interfaces, performance monitoring, batch operations, and comprehensive feature set, it's designed to make RDF development enjoyable and productive.

Key benefits:
- ✅ **Minimal boilerplate** - Focus on your data, not the API
- ✅ **Type safety** - Compile-time guarantees for data integrity
- ✅ **Performance** - Optimized for both small and large datasets
- ✅ **Flexibility** - Multiple syntax styles for different preferences
- ✅ **Monitoring** - Built-in performance and statistics tracking
- ✅ **Modern Kotlin** - Leverages the latest language features

This is the **most elegant RDF API for Kotlin** - designed for developers who value clean, readable, and maintainable code.
