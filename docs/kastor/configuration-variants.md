# Configuration Variants Reference

This document provides a comprehensive reference for all available configuration variants and their parameters in the Kastor RDF API.

## Overview

The RDF API supports multiple providers with various configuration variants. Each variant may require different parameters for proper operation. Parameters now include detailed information including name, description, type, optionality, default values, and examples.

## Parameter Structure

Each parameter is defined with the following information:

- **name**: Parameter name (e.g., "location")
- **description**: Human-readable description of what the parameter does
- **type**: Data type (default: "String")
- **optional**: Whether the parameter is optional (default: false)
- **defaultValue**: Default value if optional (default: null)
- **examples**: List of example values for the parameter

## Provider: Jena (`jena`)

### `jena:memory`
**Description**: Basic in-memory Jena repository
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("jena:memory"))
```

### `jena:memory:inference`
**Description**: In-memory Jena repository with RDFS inference
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("jena:memory:inference"))
```

### `jena:tdb2`
**Description**: Persistent TDB2 repository
**Parameters**:
- `location` (required): Directory path for TDB2 storage
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("jena:tdb2", mapOf("location" to "/data/tdb2")))
```

### `jena:tdb2:inference`
**Description**: Persistent TDB2 repository with RDFS inference
**Parameters**:
- `location` (required): Directory path for TDB2 storage
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("jena:tdb2:inference", mapOf("location" to "/data/tdb2")))
```

## Provider: RDF4J (`rdf4j`)

### `rdf4j:memory`
**Description**: Basic in-memory RDF4J repository
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("rdf4j:memory"))
```

### `rdf4j:native`
**Description**: Persistent NativeStore repository
**Parameters**:
- `location` (required): Directory path for NativeStore storage
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("rdf4j:native", mapOf("location" to "/data/native")))
```

### `rdf4j:memory:star`
**Description**: In-memory repository with RDF-star support
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("rdf4j:memory:star"))
```

### `rdf4j:native:star`
**Description**: Persistent repository with RDF-star support
**Parameters**:
- `location` (required): Directory path for NativeStore storage
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("rdf4j:native:star", mapOf("location" to "/data/native")))
```

### `rdf4j:memory:rdfs`
**Description**: In-memory repository with RDFS inference
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("rdf4j:memory:rdfs"))
```

### `rdf4j:native:rdfs`
**Description**: Persistent repository with RDFS inference
**Parameters**:
- `location` (required): Directory path for NativeStore storage
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("rdf4j:native:rdfs", mapOf("location" to "/data/native")))
```

### `rdf4j:memory:shacl`
**Description**: In-memory repository with SHACL validation
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("rdf4j:memory:shacl"))
```

### `rdf4j:native:shacl`
**Description**: Persistent repository with SHACL validation
**Parameters**:
- `location` (required): Directory path for NativeStore storage
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("rdf4j:native:shacl", mapOf("location" to "/data/native")))
```

## Provider: SPARQL (`sparql`)

### `sparql`
**Description**: Remote SPARQL endpoint repository
**Parameters**:
- `location` (required): SPARQL endpoint URL
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("sparql", mapOf("location" to "http://dbpedia.org/sparql")))
```

## Fallback: Memory (`memory`)

### `memory`
**Description**: Simple in-memory repository (fallback when no provider is available)
**Parameters**: None required
**Example**:
```kotlin
val repo = RdfApiRegistry.create(RdfConfig("memory"))
```

## Parameter Reference

### Common Parameters

| Parameter | Type | Required | Description | Examples | Used By |
|-----------|------|----------|-------------|----------|---------|
| `location` | String | Yes* | Directory path or URL for persistent storage or remote endpoint | `/data/tdb2`, `./storage`, `http://dbpedia.org/sparql` | `jena:tdb2*`, `rdf4j:native*`, `sparql` |

*Required for variants that need persistent storage or remote connectivity

### Parameter Details

#### `location` Parameter
- **Type**: String
- **Required**: Yes (for persistent/remote variants)
- **Description**: Directory path for persistent storage or URL for remote endpoints
- **Examples**:
  - Jena TDB2: `/data/tdb2`, `./storage`, `/var/lib/jena/tdb2`
  - RDF4J Native: `/data/native`, `./storage`, `/var/lib/rdf4j/native`
  - SPARQL: `http://dbpedia.org/sparql`, `https://query.wikidata.org/sparql`, `http://localhost:8080/sparql`

## Usage Patterns

### Basic In-Memory Repository
```kotlin
// Any of these work for basic in-memory storage
val repo1 = RdfApiRegistry.create(RdfConfig("memory"))
val repo2 = RdfApiRegistry.create(RdfConfig("jena:memory"))
val repo3 = RdfApiRegistry.create(RdfConfig("rdf4j:memory"))
```

### Persistent Storage
```kotlin
// Jena TDB2
val jenaRepo = RdfApiRegistry.create(RdfConfig("jena:tdb2", mapOf("location" to "/data/jena")))

// RDF4J Native
val rdf4jRepo = RdfApiRegistry.create(RdfConfig("rdf4j:native", mapOf("location" to "/data/rdf4j")))
```

### Remote SPARQL Endpoint
```kotlin
val sparqlRepo = RdfApiRegistry.create(RdfConfig("sparql", mapOf("location" to "https://query.wikidata.org/sparql")))
```

### Advanced Features
```kotlin
// Inference
val inferenceRepo = RdfApiRegistry.create(RdfConfig("jena:memory:inference"))

// RDF-star support
val starRepo = RdfApiRegistry.create(RdfConfig("rdf4j:memory:star"))

// SHACL validation
val shaclRepo = RdfApiRegistry.create(RdfConfig("rdf4j:memory:shacl"))
```

## Discovery

You can discover available variants programmatically:

```kotlin
// Get all supported types
val allTypes = RdfApiRegistry.getSupportedTypes()
println("Available types: $allTypes")

// Get providers and their variants
val providers = RdfApiRegistry.discoverProviders()
providers.forEach { provider ->
    println("${provider.name} v${provider.version}: ${provider.getSupportedTypes()}")
}

// Check if a specific type is supported
val isSupported = RdfApiRegistry.isSupported("jena:memory")
```

## Enhanced Parameter Information

The API now provides detailed parameter information:

```kotlin
// Get all configuration variants with detailed parameter info
val variants = RdfApiRegistry.getAllConfigVariants()
variants.forEach { variant ->
    println("${variant.type}: ${variant.description}")
    variant.parameters.forEach { param ->
        println("  ${param.name} (${param.type})${if (param.optional) " [optional]" else " [required]"}")
        println("    Description: ${param.description}")
        if (param.examples.isNotEmpty()) {
            println("    Examples: ${param.examples.joinToString(", ")}")
        }
    }
}

// Get parameter information for a specific parameter
val locationParam = RdfApiRegistry.getParameterInfo("jena:tdb2", "location")
locationParam?.let { param ->
    println("Parameter: ${param.name}")
    println("Type: ${param.type}")
    println("Required: ${!param.optional}")
    println("Description: ${param.description}")
    println("Examples: ${param.examples.joinToString(", ")}")
}

// Get all parameters for a type
val params = RdfApiRegistry.getParameters("sparql")
val requiredParams = RdfApiRegistry.getRequiredParameters("sparql")
val optionalParams = RdfApiRegistry.getOptionalParameters("sparql")
```

## Error Handling

When creating repositories with invalid parameters:

```kotlin
try {
    val repo = RdfApiRegistry.create(RdfConfig("jena:tdb2")) // Missing location
} catch (e: IllegalArgumentException) {
    println("Error: ${e.message}")
    // Output: "No provider found for repository type: jena:tdb2. Available providers: ..."
}
```
