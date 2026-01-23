# Configuration Variants Reference

This document provides a reference for provider variants and the configuration
options they accept in the current API.

## Overview

The RDF API supports multiple providers with various configuration variants. Each
variant may require different options for proper operation. Options are provider‑specific,
so this page is the canonical source of truth for required settings.

## Variant Discovery

You can list providers and variants at runtime:

```kotlin
val providers = RdfProviderRegistry.discoverProviders()
providers.forEach { provider ->
    provider.variants().forEach { variant ->
        println("${provider.id}:${variant.id} — ${variant.description}")
    }
}
```

## Provider: Jena (`jena`)

### `jena:memory`
**Description**: Basic in-memory Jena repository
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(RdfConfig(providerId = "jena", variantId = "memory"))
```

### `jena:memory:inference`
**Description**: In-memory Jena repository with RDFS inference
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(RdfConfig(providerId = "jena", variantId = "memory-inference"))
```

### `jena:tdb2`
**Description**: Persistent TDB2 repository
**Parameters**:
- `location` (required): Directory path for TDB2 storage
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(
  RdfConfig(providerId = "jena", variantId = "tdb2", options = mapOf("location" to "/data/tdb2"))
)
```

### `jena:tdb2:inference`
**Description**: Persistent TDB2 repository with RDFS inference
**Parameters**:
- `location` (required): Directory path for TDB2 storage
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(
  RdfConfig(providerId = "jena", variantId = "tdb2-inference", options = mapOf("location" to "/data/tdb2"))
)
```

## Provider: RDF4J (`rdf4j`)

### `rdf4j:memory`
**Description**: Basic in-memory RDF4J repository
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(RdfConfig(providerId = "rdf4j", variantId = "memory"))
```

### `rdf4j:native`
**Description**: Persistent NativeStore repository
**Parameters**:
- `location` (required): Directory path for NativeStore storage
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(
  RdfConfig(providerId = "rdf4j", variantId = "native", options = mapOf("location" to "/data/native"))
)
```

### `rdf4j:memory:star`
**Description**: In-memory repository with RDF-star support
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(RdfConfig(providerId = "rdf4j", variantId = "memory-star"))
```

### `rdf4j:native:star`
**Description**: Persistent repository with RDF-star support
**Parameters**:
- `location` (required): Directory path for NativeStore storage
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(
  RdfConfig(providerId = "rdf4j", variantId = "native-star", options = mapOf("location" to "/data/native"))
)
```

### `rdf4j:memory:rdfs`
**Description**: In-memory repository with RDFS inference
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(RdfConfig(providerId = "rdf4j", variantId = "memory-rdfs"))
```

### `rdf4j:native:rdfs`
**Description**: Persistent repository with RDFS inference
**Parameters**:
- `location` (required): Directory path for NativeStore storage
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(
  RdfConfig(providerId = "rdf4j", variantId = "native-rdfs", options = mapOf("location" to "/data/native"))
)
```

### `rdf4j:memory:shacl`
**Description**: In-memory repository with SHACL validation
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(RdfConfig(providerId = "rdf4j", variantId = "memory-shacl"))
```

### `rdf4j:native:shacl`
**Description**: Persistent repository with SHACL validation
**Parameters**:
- `location` (required): Directory path for NativeStore storage
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(
  RdfConfig(providerId = "rdf4j", variantId = "native-shacl", options = mapOf("location" to "/data/native"))
)
```

## Provider: SPARQL (`sparql`)

### `sparql`
**Description**: Remote SPARQL endpoint repository
**Parameters**:
- `location` (required): SPARQL endpoint URL
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(
  RdfConfig(providerId = "sparql", variantId = "sparql", options = mapOf("location" to "http://dbpedia.org/sparql"))
)
```

## Fallback: Memory (`memory`)

### `memory`
**Description**: Simple in-memory repository (fallback when no provider is available)
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfProviderRegistry.create(RdfConfig(providerId = "memory", variantId = "memory"))
```

## Common Options

| Option | Type | Required | Description | Examples | Used By |
|--------|------|----------|-------------|----------|---------|
| `location` | String | Yes* | Directory path or URL for persistent storage or remote endpoint | `/data/tdb2`, `./storage`, `http://dbpedia.org/sparql` | `jena:tdb2*`, `rdf4j:native*`, `sparql` |

*Required for variants that need persistent storage or remote connectivity.

## Usage Patterns

### Basic In-Memory Repository
```kotlin
// Any of these work for basic in-memory storage
val repo1 = RdfProviderRegistry.create(RdfConfig(providerId = "memory", variantId = "memory"))
val repo2 = RdfProviderRegistry.create(RdfConfig(providerId = "jena", variantId = "memory"))
val repo3 = RdfProviderRegistry.create(RdfConfig(providerId = "rdf4j", variantId = "memory"))
```

### Persistent Storage
```kotlin
// Jena TDB2
val jenaRepo = RdfProviderRegistry.create(
  RdfConfig(providerId = "jena", variantId = "tdb2", options = mapOf("location" to "/data/jena"))
)

// RDF4J Native
val rdf4jRepo = RdfProviderRegistry.create(
  RdfConfig(providerId = "rdf4j", variantId = "native", options = mapOf("location" to "/data/rdf4j"))
)
```

### Remote SPARQL Endpoint
```kotlin
val sparqlRepo = RdfProviderRegistry.create(
  RdfConfig(providerId = "sparql", variantId = "sparql", options = mapOf("location" to "https://query.wikidata.org/sparql"))
)
```

### Advanced Features
```kotlin
// Inference
val inferenceRepo = RdfProviderRegistry.create(RdfConfig(providerId = "jena", variantId = "memory-inference"))

// RDF-star support
val starRepo = RdfProviderRegistry.create(RdfConfig(providerId = "rdf4j", variantId = "memory-star"))

// SHACL validation
val shaclRepo = RdfProviderRegistry.create(RdfConfig(providerId = "rdf4j", variantId = "memory-shacl"))
```

## Discovery

You can discover available variants programmatically:

```kotlin
// Get all supported types
val allTypes = RdfProviderRegistry.getSupportedTypes()
println("Available types: $allTypes")

// Get providers and their variants
val providers = RdfProviderRegistry.discoverProviders()
providers.forEach { provider ->
    val types = provider.variants().map { "${provider.id}:${it.id}" }
    println("${provider.name} v${provider.version}: ${types.joinToString()}")
}

// Check if a specific type is supported
val isSupported = RdfProviderRegistry.supportsVariant("jena", "memory")
```

## Variant Metadata

Each `RdfVariant` provides an id, description, and optional default options.
Required options are documented in this page and in provider docs.

```kotlin
val providers = RdfProviderRegistry.discoverProviders()
providers.forEach { provider ->
    provider.variants().forEach { variant ->
        println("${provider.id}:${variant.id} — ${variant.description}")
        if (variant.defaultOptions.isNotEmpty()) {
            println("defaults: ${variant.defaultOptions}")
        }
    }
}
```

## Error Handling

When creating repositories with invalid parameters:

```kotlin
try {
    val repo = RdfProviderRegistry.create(
        RdfConfig(providerId = "sparql", variantId = "sparql") // Missing endpoint
    )
} catch (e: IllegalArgumentException) {
    println("Error: ${e.message}")
    // Output: "SPARQL endpoint URL required"
}
```



