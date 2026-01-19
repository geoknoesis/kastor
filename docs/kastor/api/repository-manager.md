# Repository Manager

The `RdfRepositoryManager` provides centralized management for multiple RDF repositories, enabling complex applications to work with multiple data sources while maintaining the simplicity of the single-repository API.

## Overview

The Repository Manager extends the Kastor RDF API to support:
- **Multiple Repository Management**: Create, configure, and manage multiple repositories
- **Cross-Repository Operations**: Federated queries and graph copying between repositories
- **Configuration Management**: Centralized configuration discovery and management
- **Lifecycle Management**: Unified resource management for all repositories

## Basic Usage

### Creating a Repository Manager

```kotlin
import com.geoknoesis.kastor.rdf.*

// Create a manager
val manager = createRepositoryManager()

// Or using the factory DSL
val manager = Rdf.manager {
    repository("users") {
        providerId = "jena"
        variantId = "memory"
        inference = true
    }
    repository("products") {
        providerId = "rdf4j"
        variantId = "native"
        location = "./data/products"
    }
}
```

### Managing Repositories

```kotlin
// Create repositories
val usersApi = manager.createRepository("users", RdfConfig(
    providerId = "jena",
    variantId = "memory",
    options = mapOf("inferencing" to "true")
))

// Get repositories
val usersRepo = manager.getRepository("users")

// List all repositories
val repoNames = manager.listRepositories()
println("Managed repositories: $repoNames")

// Remove a repository
manager.removeRepository("old-repo")

// Close all repositories
manager.closeAll()
```

## Configuration Discovery

The Repository Manager provides access to the existing configuration discovery system:

```kotlin
// Discover available providers
val providers = manager.getAvailableProviders()
println("Available providers: $providers")

// Discover variants for a specific provider
manager.getProviderVariants("jena")?.forEach { variant ->
    println("Variant: ${variant.type}")
    println("Description: ${variant.description}")
    variant.params.forEach { param ->
        val req = if (param.required) " [REQUIRED]" else ""
        println("  Parameter: ${param.name} (${param.type})$req")
    }
}
```

## Cross-Repository Operations

### Federated Queries

Execute queries across multiple repositories:

```kotlin
// Query across multiple repositories
val results = manager.federatedQuery(
    "SELECT ?s ?p ?o WHERE { ?s ?p ?o }",
    setOf("users", "products")
)

// Process results
results.iterator().forEach { bindingSet ->
    val s = bindingSet.get("s")
    val p = bindingSet.get("p")
    val o = bindingSet.get("o")
    println("$s $p $o")
}
```

### Graph Copying

Copy graphs between repositories:

```kotlin
// Copy a graph from one repository to another
manager.copyGraph("source-repo", "target-repo", "http://example.org/graph")
```

## Configuration Management

### Getting Repository Configurations

```kotlin
// Get configuration for a repository
val config = manager.getRepositoryConfig("users")
println("Users repository config: $config")
```

### Updating Repository Configurations

```kotlin
// Update repository configuration
val newConfig = RdfConfig(
    providerId = "jena",
    variantId = "tdb2",
    options = mapOf("location" to "/new/location")
)
val updatedApi = manager.updateRepositoryConfig("users", newConfig)
```

## Factory DSL Integration

The Repository Manager integrates with the existing Factory DSL:

```kotlin
val manager = Rdf.manager {
    repository("users") {
        providerId = "jena"
        variantId = "memory"
        inference = true
    }
    repository("products") {
        providerId = "rdf4j"
        variantId = "native"
        location = "./data/products"
    }
    repository("external") {
        providerId = "sparql"
        variantId = "sparql"
        location = "https://dbpedia.org/sparql"
    }
}
```

## Advanced Usage

### Mixed Repository Types

```kotlin
val manager = createRepositoryManager()

// Local in-memory repository for temporary data
manager.createRepository("temp", RdfConfig(providerId = "jena", variantId = "memory"))

// Local persistent repository for user data
manager.createRepository("users", RdfConfig(
    providerId = "jena",
    variantId = "tdb2",
    options = mapOf("location" to "/data/users")
))

// Remote SPARQL endpoint for external data
manager.createRepository("external", RdfConfig(
    providerId = "sparql",
    variantId = "sparql",
    options = mapOf("queryEndpoint" to "https://dbpedia.org/sparql")
))

// Federated query across all repositories
val results = manager.federatedQuery(
    "SELECT ?s ?name WHERE { ?s <http://xmlns.com/foaf/0.1/name> ?name }",
    setOf("users", "external")
)
```

### Repository Lifecycle Management

```kotlin
val manager = createRepositoryManager()

try {
    // Create repositories
    manager.createRepository("repo1", RdfConfig(providerId = "jena", variantId = "memory"))
    manager.createRepository("repo2", RdfConfig(providerId = "rdf4j", variantId = "memory"))
    
    // Use repositories
    val repo1 = manager.getRepository("repo1")
    val repo2 = manager.getRepository("repo2")
    
    // Perform operations...
    
} finally {
    // Ensure all repositories are properly closed
    manager.closeAll()
}
```

## Integration with Existing API

The Repository Manager keeps the single-repository API consistent alongside multi-repository usage:

```kotlin
// Single repository (existing way)
val api = Rdf.factory {
    providerId = "jena"
    variantId = "memory"
}
val repo = api.repository

// Multiple repositories (new way)
val manager = createRepositoryManager()
val api1 = manager.createRepository("repo1", RdfConfig(providerId = "jena", variantId = "memory"))
val api2 = manager.createRepository("repo2", RdfConfig(providerId = "rdf4j", variantId = "memory"))

// Both approaches work the same way
val graph1 = api.repository.getGraph()
val graph2 = api1.repository.getGraph()
```

## Benefits

- **Backward Compatibility**: Existing single-repository code continues to work unchanged
- **Progressive Complexity**: Simple apps use single repositories, complex apps use the manager
- **Centralized Management**: Unified resource management and configuration
- **Cross-Repository Operations**: Built-in federation and graph copying capabilities
- **Configuration Discovery**: Leverages existing provider and variant discovery
- **Consistent API**: Same configuration patterns for single and multiple repositories

## Best Practices

1. **Always close the manager**: Use try-finally blocks or automatic resource management
2. **Use meaningful repository names**: Choose descriptive names for better management
3. **Leverage configuration discovery**: Use the discovery methods to understand available options
4. **Consider federation costs**: Federated queries can be expensive; use them judiciously
5. **Monitor resource usage**: Multiple repositories consume more resources than single repositories



