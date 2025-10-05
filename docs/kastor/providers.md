## Providers

Each provider implements `RdfRepositoryProvider` and exposes one or more `type` variants with rich parameter metadata. Construct via `RdfApiRegistry.create(RdfConfig(...))` or discover parameters programmatically.

### Enhanced Parameter Discovery

All providers now expose detailed parameter information:

```kotlin
// Get all available variants with parameter details
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
val jenaTdb2 = RdfApiRegistry.getConfigVariant("jena:tdb2")
val locationParam = RdfApiRegistry.getParameterInfo("jena:tdb2", "location")
```

### Jena (`rdf-jena`)
- **Id**: `jena`
- **Variants**:
  - `jena:memory`: Basic in-memory Jena repository
  - `jena:memory:inference`: In-memory Jena repository with RDFS inference
  - `jena:tdb2`: Persistent TDB2 repository
    - **Parameters**:
      - `location` (String, required): Directory path for TDB2 storage
        - Examples: `/data/tdb2`, `./storage`, `/var/lib/jena/tdb2`
  - `jena:tdb2:inference`: Persistent TDB2 repository with RDFS inference
    - **Parameters**:
      - `location` (String, required): Directory path for TDB2 storage
        - Examples: `/data/tdb2`, `./storage`, `/var/lib/jena/tdb2`

Example:
```kotlin
// In-memory repository
val repo = RdfApiRegistry.create(RdfConfig("jena:memory"))

// Persistent TDB2 repository
val repo = RdfApiRegistry.create(RdfConfig("jena:tdb2", mapOf("location" to "/data/tdb2")))

// Get parameter information
val variant = RdfApiRegistry.getConfigVariant("jena:tdb2")
val locationParam = RdfApiRegistry.getParameterInfo("jena:tdb2", "location")
println("Location parameter: ${locationParam?.description}")
println("Examples: ${locationParam?.examples?.joinToString(", ")}")
```

Notes:
- **Dataset Support**: Full SPARQL Dataset interface with named graphs
- **Transaction Management**: Built-in transaction support with begin/commit/rollback
- **TDB2 Persistence**: High-performance persistent storage
- **Inference Support**: RDFS and OWL inference capabilities
- **RDF-star Support**: Embedded triples support
- **Cross-repository Operations**: Works with RepositoryManager for federation

### RDF4J (`rdf-rdf4j`)
- **Id**: `rdf4j`
- **Variants**:
  - `rdf4j:memory`: Basic in-memory RDF4J repository
  - `rdf4j:native`: Persistent NativeStore repository
    - **Parameters**:
      - `location` (String, required): Directory path for NativeStore storage
        - Examples: `/data/native`, `./storage`, `/var/lib/rdf4j/native`
  - `rdf4j:memory:star`: In-memory repository with RDF-star support
  - `rdf4j:native:star`: Persistent repository with RDF-star support
    - **Parameters**:
      - `location` (String, required): Directory path for NativeStore storage
        - Examples: `/data/native`, `./storage`, `/var/lib/rdf4j/native`
  - `rdf4j:memory:rdfs`: In-memory repository with RDFS inference
  - `rdf4j:native:rdfs`: Persistent repository with RDFS inference
    - **Parameters**:
      - `location` (String, required): Directory path for NativeStore storage
        - Examples: `/data/native`, `./storage`, `/var/lib/rdf4j/native`
  - `rdf4j:memory:shacl`: In-memory repository with SHACL validation
  - `rdf4j:native:shacl`: Persistent repository with SHACL validation
    - **Parameters**:
      - `location` (String, required): Directory path for NativeStore storage
        - Examples: `/data/native`, `./storage`, `/var/lib/rdf4j/native`

Example:
```kotlin
// Basic in-memory repository
val repo = RdfApiRegistry.create(RdfConfig("rdf4j:memory"))

// Persistent native repository
val repo = RdfApiRegistry.create(RdfConfig("rdf4j:native", mapOf("location" to "./store")))

// Repository with RDFS inference
val repo = RdfApiRegistry.create(RdfConfig("rdf4j:memory:rdfs"))

// Repository with SHACL validation
val repo = RdfApiRegistry.create(RdfConfig("rdf4j:memory:shacl"))

// Get all RDF4J variants
val rdf4jVariants = RdfApiRegistry.getConfigVariantsForProvider("rdf4j")
rdf4jVariants.forEach { variant ->
    println("${variant.type}: ${variant.description}")
}
```

Notes:
- **Repository Management**: Full repository management with centralized control
- **Transaction Support**: ACID transactions with begin/commit/rollback/end lifecycle
- **Inference Support**: RDFS and OWL inference capabilities
- **Validation Support**: SHACL constraint validation
- **RDF-star Support**: Embedded triples support
- **Federation**: Cross-repository query federation
- **Statistics**: Repository performance monitoring
- **Cross-repository Operations**: Works with RepositoryManager for federation

### SPARQL over HTTP (`rdf-sparql`)
- **Id**: `sparql`
- **Variants**:
  - `sparql`: Remote SPARQL endpoint repository
    - **Parameters**:
      - `location` (String, required): SPARQL endpoint URL
        - Examples: `http://dbpedia.org/sparql`, `https://query.wikidata.org/sparql`, `http://localhost:8080/sparql`

Example:
```kotlin
// Remote SPARQL endpoint
val repo = RdfApiRegistry.create(RdfConfig("sparql", mapOf("location" to "https://dbpedia.org/sparql")))

// Get SPARQL parameter information
val sparqlVariant = RdfApiRegistry.getConfigVariant("sparql")
val locationParam = RdfApiRegistry.getParameterInfo("sparql", "location")
println("SPARQL endpoint examples: ${locationParam?.examples?.joinToString(", ")}")
```

Notes:
- Transactions are no-ops for remote endpoints
- `addTriple` is not supported for remote; use `update()` with SPARQL Update
- RDF-star support depends on the remote endpoint capabilities

