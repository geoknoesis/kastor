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
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory"
}
```

#### `rdf4j:native`
Persistent NativeStore with high-performance disk storage.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "native"
    location = "/data/rdf4j"
}
```

### RDF-star Repositories

#### `rdf4j:memory:star`
In-memory repository with explicit RDF-star support.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory-star"
}
```

#### `rdf4j:native:star`
Persistent repository with explicit RDF-star support.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "native-star"
    location = "/data/rdf4j"
}
```

### Inference Repositories

#### `rdf4j:memory:rdfs`
In-memory repository with RDFS inference enabled.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory-rdfs"
}
```

#### `rdf4j:native:rdfs`
Persistent repository with RDFS inference enabled.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "native-rdfs"
    location = "/data/rdf4j"
}
```

### Validation Repositories

#### `rdf4j:memory:shacl`
In-memory repository with SHACL validation enabled.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory-shacl"
}
```

#### `rdf4j:native:shacl`
Persistent repository with SHACL validation enabled.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "native-shacl"
    location = "/data/rdf4j"
}
```

## Managing Multiple Repositories

Kastor does not ship a dedicated RDF4J repository manager. Use explicit composition:

```kotlin
val repositories = mapOf(
    "users" to Rdf.repository { providerId = "rdf4j"; variantId = "memory" },
    "products" to Rdf.repository {
        providerId = "rdf4j"
        variantId = "native"
        location = "/data/products"
    }
)
```

Close all repositories when done:

```kotlin
repositories.values.forEach { it.close() }
```

## Advanced Features

### Inference Capabilities

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SHACL

// Create repository with RDFS inference
val repo = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory-rdfs"
}

val graphEditor = repo.editDefaultGraph()

// Add RDFS schema
val rdfsSubClassOf = RDFS.subClassOf
val personClass = iri("http://example.org/Person")
val animalClass = iri("http://example.org/Animal")

graphEditor.addTriple(triple(personClass, rdfsSubClassOf, animalClass))

// Query with inference
val results = repo.select(
    SparqlSelectQuery("SELECT ?s WHERE { ?s ${RDFS.subClassOf} ${animalClass} }")
)
// Returns Person class due to inference
```

### Validation Capabilities

```kotlin
// Create repository with SHACL validation
val repo = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory-shacl"
}

// Add SHACL shapes
repo.createGraph(iri("http://example.org/shapes"))
val shapesEditor = repo.editGraph(iri("http://example.org/shapes"))
val shaclTargetClass = SHACL.targetClass
val shaclProperty = SHACL.property
val shaclPath = SHACL.path
val shaclMinCount = SHACL.minCount

val personShape = iri("http://example.org/PersonShape")
val personClass = iri("http://example.org/Person")
val nameProperty = iri("http://example.org/nameProperty")
val namePath = FOAF.name

shapesEditor.addTriple(triple(personShape, shaclTargetClass, personClass))
shapesEditor.addTriple(triple(personShape, shaclProperty, nameProperty))
shapesEditor.addTriple(triple(nameProperty, shaclPath, namePath))
shapesEditor.addTriple(triple(nameProperty, shaclMinCount, int(1)))
```

### Federation and Cross‑Repository Patterns

Kastor does not provide RDF4J‑specific federation utilities. If you need federation,
use a SPARQL endpoint that supports it or compose results in your application.

## Migration from Basic Implementation

Use explicit provider/variant selection:

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory"
}

val manager = Rdf4jRepositoryManagerFactory.create()
manager.createRepository("data", RdfConfig(providerId = "rdf4j", variantId = "memory-rdfs"))
```

## Examples

See the `Rdf4jRepositoryManagerExample.kt` file for comprehensive examples demonstrating all features.




