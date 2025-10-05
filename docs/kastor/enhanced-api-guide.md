# Enhanced Kastor RDF API Guide

## Overview

The Kastor RDF API has been significantly enhanced to be more Kotlin-idiomatic, easier to use, and better leverage the full capabilities of Jena and RDF4J. This guide covers all the improvements and new features.

## Key Improvements

### 1. Simplified Factory DSL

The factory DSL has been simplified to make common use cases much easier:

```kotlin
// Old way (verbose)
val api = Rdf.factory {
    type("jena:memory")
    param("inferencing", "true")
}

// New way (simplified)
val repo = Rdf.memoryWithInference()  // Uses configured default provider
val repo = Rdf.jenaTdb2("/data/storage")  // Explicitly Jena
val repo = Rdf.rdf4jNative("/data/rdf4j")  // Explicitly RDF4J
```

#### Available Factory Functions

**Provider-Agnostic (uses configured default or best available):**
- `Rdf.memory()` - Basic in-memory repository
- `Rdf.memoryWithInference()` - In-memory with RDFS inference

**Jena-Specific:**
- `Rdf.jenaMemory()` - Jena in-memory repository
- `Rdf.jenaMemoryWithInference()` - Jena in-memory with RDFS inference
- `Rdf.jenaTdb2(location)` - Jena TDB2 persistent storage

**RDF4J-Specific:**
- `Rdf.rdf4jMemory()` - RDF4J in-memory repository
- `Rdf.rdf4jNative(location)` - RDF4J native storage
- `Rdf.rdf4jInference()` - RDF4J with RDFS inference
- `Rdf.rdf4jShacl()` - RDF4J with SHACL validation

#### Configuring Default Provider

```kotlin
// Set RDF4J as the default provider
Rdf.setDefaultProvider("rdf4j")

// Now these will use RDF4J by default
val repo1 = Rdf.memory()  // Uses RDF4J memory
val repo2 = Rdf.memoryWithInference()  // Uses RDF4J with RDFS inference

// You can still explicitly specify providers
val jenaRepo = Rdf.jenaMemory()  // Explicitly Jena
val rdf4jRepo = Rdf.rdf4jMemory()  // Explicitly RDF4J
```

#### Enhanced DSL

```kotlin
val repo = Rdf.factory {
    jenaInference()           // Use Jena with inference
    inferencing(true)         // Enable inference
    validation(true)          // Enable validation
}
```

### 2. Enhanced Triple DSL

The triple DSL has been enhanced with more convenience features:

```kotlin
// Natural language DSL
val triple = person has name with "John Doe"
val triple = person has age with 30

// Batch operations
val triples = person.properties(
    name to "John Doe",
    age to 30,
    email to "john@example.com"
)

// Graph building DSL
val graph = RdfDsl.graph("http://example.org/people") {
    addTriple(alice has name with "Alice")
    addTriple(alice has age with 25)
    addTriple(bob has name with "Bob")
    addTriple(bob has age with 30)
}
```

### 3. Enhanced ResultSet API

The ResultSet interface now provides many convenience methods:

```kotlin
val results = repo.query("SELECT ?name ?age WHERE { ?s ?p ?o }")

// Convenience methods
val count = results.count()
val isEmpty = results.isEmpty()
val first = results.firstOrNull()
val list = results.toList()

// Functional operations
val names = results.map { it.getString("name") }
val filtered = results.filter { it.getInt("age")!! > 20 }
results.forEach { binding ->
    println("Name: ${binding.getString("name")}")
}
```

### 4. Enhanced BindingSet API

BindingSet now provides type-safe access to values:

```kotlin
val binding = results.first()

// Type-safe access
val name = binding.getString("name")
val age = binding.getInt("age")
val score = binding.getDouble("score")
val active = binding.getBoolean("active")
val iri = binding.getIri("resource")

// Operator overload
val value = binding["name"]

// Default values
val name = binding.getString("name", "Unknown")
val age = binding.getInt("age", 0)
```

### 5. Convenience Extension Functions

Many extension functions have been added for common operations:

```kotlin
// Repository extensions
repo.addTriple(subject, predicate, object)
repo.addTriples(triple1, triple2, triple3)
repo.addProperties(person, name to "John", age to 30)

// SPARQL convenience
val results = repo.query("SELECT ?s WHERE { ?s ?p ?o }")
val exists = repo.ask("ASK { ?s ?p ?o }")
repo.update("INSERT { ?s ?p ?o } WHERE { }")

// Transaction convenience
val result = repo.transaction {
    addTriple(person has name with "John")
    addTriple(person has age with 30)
    "Success"
}

val queryResult = repo.readTransaction {
    query("SELECT ?name WHERE { ?s ?p ?name }").firstOrNull()
}
```

### 6. Enhanced Error Handling

Specific exception types for better error handling:

```kotlin
try {
    val results = repo.query("SELECT ?s WHERE { ?s ?p ?o }")
} catch (e: RdfQueryException) {
    println("Query failed: ${e.message}")
} catch (e: RdfTransactionException) {
    println("Transaction failed: ${e.message}")
} catch (e: RdfProviderException) {
    println("Provider error: ${e.message}")
}
```

### 7. Performance Optimizations

New performance features for better scalability:

```kotlin
// Batch operations (if supported)
repo.addBatch(triples)
repo.removeBatch(triples)

// Prepared statements (if supported)
val preparedQuery = repo.prepareQuery("SELECT ?name WHERE { ?s ?p ?name }")
val results = preparedQuery.execute(mapOf("p" to namePredicate))

// Statistics
val stats = repo.getStatistics()
println("Triple count: ${stats.tripleCount}")
println("Average query time: ${stats.getAverageQueryTimeFormatted()}")
```

### 8. Repository Management

Enhanced repository management capabilities:

```kotlin
// Create repository manager
val manager = Rdf.manager {
    repository("memory") {
        jena()
    }
    repository("inference") {
        jenaInference()
    }
    repository("tdb2") {
        jenaTdb2("./data/tdb2")
    }
    repository("rdf4j") {
        rdf4j()
    }
}

// Use repositories
val memoryRepo = manager.getRepository("memory")
val inferenceRepo = manager.getRepository("inference")

// Cross-repository operations
val federatedResults = manager.federatedQuery(
    "SELECT ?name WHERE { ?s ?p ?name }",
    setOf("memory", "inference")
)
```

### 9. Enhanced Provider Capabilities

Providers now expose more capabilities:

```kotlin
val provider = RdfApiRegistry.getProvider("jena")
val capabilities = provider.getCapabilities()
val formats = provider.getSupportedFormats()
val features = provider.getAdvancedFeatures()

// Check specific features
if (provider.isFeatureSupported("inference")) {
    // Use inference features
}
```

## Migration Guide

### From Old API to New API

```kotlin
// Old way
val api = Rdf.factory { type("jena:memory") }
val repo = api.repository
repo.beginTransaction()
repo.addTriple(null, triple(person, name, "John"))
repo.commit()
repo.end()

// New way
val repo = Rdf.memory()
repo.transaction {
    addTriple(person has name with "John")
}
```



## Best Practices

### 1. Use Provider-Agnostic Functions When Possible

```kotlin
// Good - provider-agnostic
val repo = Rdf.memoryWithInference()  // Uses configured default

// Good - explicit provider when needed
val repo = Rdf.jenaMemoryWithInference()  // Explicitly Jena
val repo = Rdf.rdf4jInference()  // Explicitly RDF4J

// Avoid - verbose DSL for simple cases
val repo = Rdf.factory { 
    type("jena:memory:inference") 
}
```

### 2. Use Transaction Blocks

```kotlin
// Good
val result = repo.transaction {
    addTriple(person has name with "John")
    addTriple(person has age with 30)
    "Success"
}

// Avoid
repo.beginTransaction()
try {
    repo.addTriple(person has name with "John")
    repo.commit()
} catch (e: Exception) {
    repo.rollback()
    throw e
} finally {
    repo.end()
}
```

### 3. Use Enhanced ResultSet Features

```kotlin
// Good
val results = repo.query("SELECT ?name WHERE { ?s ?p ?name }")
results.forEach { binding ->
    println(binding.getString("name"))
}

// Avoid
val results = repo.query("SELECT ?name WHERE { ?s ?p ?name }")
val iterator = results.iterator()
while (iterator.hasNext()) {
    val binding = iterator.next()
    val name = binding.get("name")
    if (name is Literal) {
        println(name.lexical)
    }
}
```

### 4. Use Type-Safe Binding Access

```kotlin
// Good
val name = binding.getString("name")
val age = binding.getInt("age")

// Avoid
val name = binding.get("name") as? Literal
val age = binding.get("age") as? Literal
```

## Performance Tips

### 1. Use Batch Operations

```kotlin
// For large datasets
val triples = generateTriples()
repo.addBatch(triples)
```

### 2. Use Prepared Statements

```kotlin
// For repeated queries
val preparedQuery = repo.prepareQuery("SELECT ?name WHERE { ?s ?p ?name }")
repeat(100) {
    val results = preparedQuery.execute(mapOf("p" to namePredicate))
}
```

### 3. Use Read-Only Transactions

```kotlin
// For queries only
val results = repo.readTransaction {
    query("SELECT ?s WHERE { ?s ?p ?o }")
}
```

### 4. Monitor Performance

```kotlin
val stats = repo.getStatistics()
println("Average query time: ${stats.getAverageQueryTimeFormatted()}")
println("Memory usage: ${stats.getMemoryUsageFormatted()}")
```

## Conclusion

The enhanced Kastor RDF API provides a much more Kotlin-idiomatic and user-friendly experience. The new features make it easier to write RDF applications and better leverage the full capabilities of underlying RDF engines like Jena and RDF4J.

Start with the simplified factory functions and adopt the other enhancements as needed for your use case.
