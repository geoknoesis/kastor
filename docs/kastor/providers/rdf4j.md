# RDF4J Repository Management

The Kastor RDF API provides comprehensive RDF4J repository management capabilities, allowing you to create, configure, and manage multiple RDF4J repositories with advanced features like inference, validation, and federation.

## Overview

The RDF4J repository management system provides:

- **Centralized Repository Management**: Create and manage multiple repositories
- **Advanced Storage Backends**: Memory, Native, and specialized variants
- **Inference Capabilities**: RDFS and OWL reasoning support
- **Validation Support**: SHACL constraint validation
- **Federation**: Cross-repository query capabilities
- **Statistics and Monitoring**: Performance tracking
- **Configuration Management**: Dynamic repository configuration updates

## Repository Variants

### Basic Repositories

#### `rdf4j:memory`
Basic in-memory RDF4J repository with default configuration.

```kotlin
val api = Rdf.factory {
    providerId = "rdf4j"
    variantId = "memory"
}
```

#### `rdf4j:native`
Persistent NativeStore with high-performance disk storage.

```kotlin
val api = Rdf.factory {
    providerId = "rdf4j"
    variantId = "native"
    location = "/data/rdf4j"
}
```

### RDF-star Repositories

#### `rdf4j:memory:star`
In-memory repository with explicit RDF-star support.

```kotlin
val api = Rdf.factory {
    providerId = "rdf4j"
    variantId = "memory-star"
}
```

#### `rdf4j:native:star`
Persistent repository with explicit RDF-star support.

```kotlin
val api = Rdf.factory {
    providerId = "rdf4j"
    variantId = "native-star"
    location = "/data/rdf4j"
}
```

### Inference Repositories

#### `rdf4j:memory:rdfs`
In-memory repository with RDFS inference enabled.

```kotlin
val api = Rdf.factory {
    providerId = "rdf4j"
    variantId = "memory-rdfs"
}
```

#### `rdf4j:native:rdfs`
Persistent repository with RDFS inference enabled.

```kotlin
val api = Rdf.factory {
    providerId = "rdf4j"
    variantId = "native-rdfs"
    location = "/data/rdf4j"
}
```

### Validation Repositories

#### `rdf4j:memory:shacl`
In-memory repository with SHACL validation enabled.

```kotlin
val api = Rdf.factory {
    providerId = "rdf4j"
    variantId = "memory-shacl"
}
```

#### `rdf4j:native:shacl`
Persistent repository with SHACL validation enabled.

```kotlin
val api = Rdf.factory {
    providerId = "rdf4j"
    variantId = "native-shacl"
    location = "/data/rdf4j"
}
```

## Repository Manager

### Basic Usage

```kotlin
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepositoryManager
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepositoryManagerFactory

// Create a basic repository manager
val manager = Rdf4jRepositoryManagerFactory.create()

// Create repositories
manager.createRepository("users", RdfConfig(providerId = "rdf4j", variantId = "memory"))
manager.createRepository(
    "products",
    RdfConfig(providerId = "rdf4j", variantId = "native", options = mapOf("location" to "/data/products"))
)

// Get repositories
val usersRepo = manager.getRepository("users")
val productsRepo = manager.getRepository("products")

// List all repositories
val repoNames = manager.listRepositories()
println("Repositories: $repoNames")

// Remove a repository
manager.removeRepository("products")

// Close all repositories
manager.closeAll()
```

### Factory Methods

#### Development Setup
```kotlin
val devManager = Rdf4jRepositoryManagerFactory.createForDevelopment()
// Creates: dev, test, temp repositories
```

#### Production Setup
```kotlin
val prodManager = Rdf4jRepositoryManagerFactory.createForProduction(
    dataLocation = "/data/main",
    backupLocation = "/data/backup"
)
// Creates: data, backup repositories
```

#### Inference Setup
```kotlin
val inferenceManager = Rdf4jRepositoryManagerFactory.createWithInference()
// Creates: inference repository with RDFS support
```

#### Validation Setup
```kotlin
val validationManager = Rdf4jRepositoryManagerFactory.createWithValidation()
// Creates: validated repository with SHACL support
```

#### Mixed Setup
```kotlin
val mixedManager = Rdf4jRepositoryManagerFactory.createMixed(
    dataLocation = "/data/mixed",
    includeInference = true,
    includeValidation = false
)
// Creates: main, temp repositories with specified capabilities
```

## Advanced Features

### Inference Capabilities

```kotlin
// Create repository with RDFS inference
val api = Rdf.factory {
    providerId = "rdf4j"
    variantId = "memory-rdfs"
}

val repo = api.repository
val graphEditor = repo.editDefaultGraph()

// Add RDFS schema
val rdfsSubClassOf = iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
val personClass = iri("http://example.org/Person")
val animalClass = iri("http://example.org/Animal")

graphEditor.addTriple(triple(personClass, rdfsSubClassOf, animalClass))

// Query with inference
val results = repo.select(
    "SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/Animal> }",
    BindingSet.from(emptyMap())
)
// Returns Person class due to inference
```

### Validation Capabilities

```kotlin
// Create repository with SHACL validation
val api = Rdf.factory {
    providerId = "rdf4j"
    variantId = "memory-shacl"
}

val repo = api.repository

// Add SHACL shapes
val shapesGraph = repo.getGraph(iri("http://example.org/shapes"))
val shaclTargetClass = iri("http://www.w3.org/ns/shacl#targetClass")
val shaclProperty = iri("http://www.w3.org/ns/shacl#property")
val shaclPath = iri("http://www.w3.org/ns/shacl#path")
val shaclMinCount = iri("http://www.w3.org/ns/shacl#minCount")

val personShape = iri("http://example.org/PersonShape")
val personClass = iri("http://example.org/Person")
val nameProperty = iri("http://example.org/nameProperty")
val namePath = iri("http://xmlns.com/foaf/0.1/name")

shapesGraph.add(triple(personShape, shaclTargetClass, personClass))
shapesGraph.add(triple(personShape, shaclProperty, nameProperty))
shapesGraph.add(triple(nameProperty, shaclPath, namePath))
shapesGraph.add(triple(nameProperty, shaclMinCount, int(1)))
```

### Federation

```kotlin
val manager = Rdf4jRepositoryManagerFactory.create()

// Create multiple repositories
manager.createRepository("users", RdfConfig(providerId = "rdf4j", variantId = "memory"))
manager.createRepository("products", RdfConfig(providerId = "rdf4j", variantId = "memory"))

// Add data to repositories
val usersRepo = manager.getRepository("users")
val productsRepo = manager.getRepository("products")

val usersGraph = usersRepo.repository.defaultGraph
val productsGraph = productsRepo.repository.defaultGraph

val person1 = iri("http://example.org/person1")
val person2 = iri("http://example.org/person2")
val name = iri("http://xmlns.com/foaf/0.1/name")

usersGraph.add(triple(person1, name, string("John")))
productsGraph.add(triple(person2, name, string("Jane")))

// Federated query across repositories
val results = manager.federatedQuery(
    "SELECT ?s ?name WHERE { ?s <http://xmlns.com/foaf/0.1/name> ?name }",
    setOf("users", "products")
)
// Returns results from both repositories
```

### Graph Operations

```kotlin
val manager = Rdf4jRepositoryManagerFactory.create()

manager.createRepository("source", RdfConfig(providerId = "rdf4j", variantId = "memory"))
manager.createRepository("target", RdfConfig(providerId = "rdf4j", variantId = "memory"))

// Add data to source repository
val sourceRepo = manager.getRepository("source")
val sourceGraph = sourceRepo.repository.getGraph(iri("http://example.org/graph"))

val person = iri("http://example.org/person")
val name = iri("http://xmlns.com/foaf/0.1/name")
sourceGraph.add(triple(person, name, string("John Doe")))

// Copy graph between repositories
manager.copyGraph("source", "target", "http://example.org/graph")

// Verify graph was copied
val targetRepo = manager.getRepository("target")
val targetGraph = targetRepo.repository.getGraph(iri("http://example.org/graph"))
val triples = targetGraph.triples().toList()
assertEquals(1, triples.size)
```

### Statistics and Monitoring

```kotlin
val manager = Rdf4jRepositoryManagerFactory.create()
manager.createRepository("test", RdfConfig(providerId = "rdf4j", variantId = "memory"))

if (manager is Rdf4jRepositoryManager) {
    // Get statistics for a specific repository
    val stats = manager.getRepositoryStats("test")
    println("Default graph size: ${stats["defaultGraphSize"]}")
    println("Named graph count: ${stats["namedGraphCount"]}")
    println("Supports RDF-star: ${stats["supportsRdfStar"]}")
    println("Supports transactions: ${stats["supportsTransactions"]}")
    
    // Get statistics for all repositories
    val allStats = manager.getAllRepositoryStats()
    allStats.forEach { (repoName, stats) ->
        println("Repository $repoName: $stats")
    }
    
    // Check if repository is initialized
    val isInitialized = manager.isRepositoryInitialized("test")
    println("Repository initialized: $isInitialized")
}
```

### Configuration Management

```kotlin
val manager = Rdf4jRepositoryManagerFactory.create()

// Create repository
manager.createRepository("test", RdfConfig(providerId = "rdf4j", variantId = "memory"))
val originalConfig = manager.getRepositoryConfig("test")
println("Original config: $originalConfig")

// Update configuration
val updatedConfig = RdfConfig(providerId = "rdf4j", variantId = "memory-rdfs")
val updatedRepo = manager.updateRepositoryConfig("test", updatedConfig)

val newConfig = manager.getRepositoryConfig("test")
println("Updated config: $newConfig")
```

## Best Practices

### 1. Repository Lifecycle Management

```kotlin
val manager = Rdf4jRepositoryManagerFactory.create()

try {
    // Create and use repositories
    manager.createRepository(
        "data",
        RdfConfig(providerId = "rdf4j", variantId = "native", options = mapOf("location" to "/data"))
    )
    val repo = manager.getRepository("data")
    
    // Perform operations...
    
} finally {
    // Always close the manager
    manager.closeAll()
}
```

### 2. Error Handling

```kotlin
val manager = Rdf4jRepositoryManagerFactory.create()

try {
    manager.createRepository("test", RdfConfig(providerId = "rdf4j", variantId = "memory"))
    val repo = manager.getRepository("test")
    
    // Operations that might fail
    repo.repository.select("INVALID SPARQL", BindingSet.from(emptyMap()))
    
} catch (e: Exception) {
    println("Error: ${e.message}")
} finally {
    manager.closeAll()
}
```

### 3. Performance Optimization

```kotlin
// Use appropriate repository types
val manager = Rdf4jRepositoryManagerFactory.create()

// For temporary data
manager.createRepository("temp", RdfConfig(providerId = "rdf4j", variantId = "memory"))

// For persistent data
manager.createRepository(
    "data",
    RdfConfig(providerId = "rdf4j", variantId = "native", options = mapOf("location" to "/data"))
)

// For inference-heavy workloads
manager.createRepository("inference", RdfConfig(providerId = "rdf4j", variantId = "memory-rdfs"))

// For validation-heavy workloads
manager.createRepository("validation", RdfConfig(providerId = "rdf4j", variantId = "memory-shacl"))
```

### 4. Federation Strategies

```kotlin
val manager = Rdf4jRepositoryManagerFactory.create()

// Create specialized repositories
manager.createRepository(
    "users",
    RdfConfig(providerId = "rdf4j", variantId = "native", options = mapOf("location" to "/data/users"))
)
manager.createRepository(
    "products",
    RdfConfig(providerId = "rdf4j", variantId = "native", options = mapOf("location" to "/data/products"))
)
manager.createRepository(
    "external",
    RdfConfig(providerId = "sparql", variantId = "sparql", options = mapOf("location" to "https://dbpedia.org/sparql"))
)

// Federated queries
val results = manager.federatedQuery(
    "SELECT ?s ?name WHERE { ?s <http://xmlns.com/foaf/0.1/name> ?name }",
    setOf("users", "products", "external")
)
```

## Migration from Basic Implementation

Use explicit provider/variant selection:

```kotlin
val api = Rdf.factory {
    providerId = "rdf4j"
    variantId = "memory"
}

val manager = Rdf4jRepositoryManagerFactory.create()
manager.createRepository("data", RdfConfig(providerId = "rdf4j", variantId = "memory-rdfs"))
```

## Examples

See the `Rdf4jRepositoryManagerExample.kt` file for comprehensive examples demonstrating all features.



