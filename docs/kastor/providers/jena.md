# Enhanced Jena Implementation

The Kastor RDF API now provides a comprehensive Jena implementation that leverages Apache Jena's full repository capabilities, including TDB2, Dataset, and GraphStore features.

## Overview

The enhanced Jena implementation provides:

- **Full Dataset Support**: Complete SPARQL Dataset interface with named graphs
- **TDB2 Persistence**: High-performance persistent storage
- **Transaction Management**: Built-in transaction support
- **Inference Capabilities**: RDFS and OWL inference
- **RDF 1.2 Triple Terms**: Triple terms and `rdf:reifies` support
- **Repository Manager Integration**: Works seamlessly with the RepositoryManager

### Version alignment

Kastor currently integrates with **Apache Jena 6.2.0** (paired with Apache Thrift 0.24.0). If you depend on a different Jena
version in your application, align your dependency set to avoid classpath conflicts.

## Repository Variants

### Memory Repositories

#### `jena:memory`
Basic in-memory Jena dataset with default configuration.

```kotlin
val api = Rdf.repository {
    providerId = "jena"
    variantId = "memory"
}
```

#### `jena:memory:inference`
In-memory Jena dataset with RDFS inference enabled.

```kotlin
val api = Rdf.repository {
    providerId = "jena"
    variantId = "memory-inference"
}
```

### Persistent Repositories

#### `jena:tdb2`
Persistent TDB2 dataset with high-performance storage.

```kotlin
val api = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2"
    location = "/data/tdb2"
}
```

#### `jena:tdb2:inference`
Persistent TDB2 dataset with RDFS inference enabled.

```kotlin
val repo = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2-inference"
    location = "/data/tdb2"
}
```

## Key Features

### 1. Dataset Operations

The enhanced implementation supports full SPARQL Dataset operations:

```kotlin
// Default graph
val defaultGraph = repo.defaultGraph

// Named graphs
val namedGraph = repo.getGraph(iri("http://example.org/graph"))

// List all graph names
val graphNames = repo.listGraphs()

// Create and edit a graph
repo.createGraph(iri("http://example.org/new-graph"))
repo.editGraph(iri("http://example.org/new-graph")).addTriples(graph.getTriples())

// Remove graph
repo.removeGraph(iri("http://example.org/graph"))
```

### 2. SPARQL Queries

Full SPARQL 1.1 support including dataset queries:

```kotlin
// Query default graph
val results = repo.select(
    SparqlSelectQuery("SELECT ?s ?p ?o WHERE { ?s ?p ?o }")
)

// Query named graph
val graphIri = iri("http://example.org/graph")
val results = repo.select(
    SparqlSelectQuery("SELECT ?s ?p ?o WHERE { GRAPH ${graphIri} { ?s ?p ?o } }")
)

// Construct query
val graph = repo.construct(
    SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")
)

// Ask query
val exists = repo.ask(
    SparqlAskQuery("ASK WHERE { ?s ?p ?o }")
)

// Update
repo.update(
    UpdateQuery("INSERT { ?s ?p ?o } WHERE { ?s ?p ?o }")
)
```

### 3. Transaction Management

Built-in transaction support for data consistency:

```kotlin
val result = repo.transaction {
    val graph = defaultGraph
    graph.add(triple(subject, predicate, object))
    graph.add(triple(subject2, predicate2, object2))
    "transaction completed"
}
```

Transactions automatically handle:
- **Begin**: Start a write transaction
- **Commit**: Commit changes on success
- **Rollback**: Rollback changes on failure
- **End**: Clean up transaction resources

### 4. TDB2 Persistence

High-performance persistent storage with TDB2:

```kotlin
// Create persistent repository
val repo = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2"
    location = "/data/tdb2"
}

// Add data
repo.editDefaultGraph().addTriple(triple(subject, predicate, obj))

// Close and reopen
repo.close()

val reopenedRepo = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2"
    location = "/data/tdb2"
}

// Data persists across sessions
val results = reopenedRepo.select(
    SparqlSelectQuery("SELECT ?s ?p ?o WHERE { ?s ?p ?o }")
)
```

### 5. Inference Support

RDFS and OWL inference capabilities:

```kotlin
// Memory with inference
val api = Rdf.repository {
    providerId = "jena"
    variantId = "memory-inference"
}

// TDB2 with inference
val api = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2-inference"
    location = "/data/tdb2"
}
```

**Semantic note:** inference is explicit and uses Jena reasoners; it does not introduce
hidden ontology‑model behavior. If you need OntModel APIs, use Jena directly and convert
to a Kastor graph via the Jena Bridge.

Inference models are cached per graph, and reads on inference repositories are serialized.

### Parsing, literals and Jena interop

- **Lexical forms are preserved exactly**: `"007"^^xsd:integer` stays `"007"`. Only the exact lexical forms `"true"` / `"false"` become boolean singletons.
- **`parseGraph` accepts triple formats only**: TriG and N-Quads are rejected with `RdfFormatException`; parse quad formats as a dataset instead.
- **Strict parsing**: syntax errors are not silently skipped. Without a base IRI, relative IRIs are a parse error.
- **Strict bridge reads**: graphs wrapped with `JenaBridge.fromJenaModel(model)`, `JenaBridge.fromJenaGraph(graph)` or `toKastorGraph()` fail a read with `IllegalArgumentException` when they meet a statement Kastor cannot represent (e.g. `xml:lang="en_US"` or an IRI that RFC 3987 rejects). Lenient reads are opt-in: `fromJenaModel(model, strictRead = false)` / `fromJenaGraph(graph, strictRead = false)` skip such statements with one warning per read, and `size()` then counts only the statements reads return (a full scan).
- **Language-tag case**: Jena canonicalises tag case whenever it creates a language-tagged literal, so `"x"@en-us` is stored and read back as `"x"@en-US`. Kastor equality ignores tag case, so lookups by either spelling still match.
- **`JenaBridge.toJenaModel(graph)`** returns the wrapped model for standalone Jena-backed graphs. For a graph that belongs to a repository, it returns a **detached copy** (including inferences for inference variants), because the live store model is only valid inside repository transactions. Use **`JenaBridge.copyToJenaModel(graph)`** when you always need an independent copy.

### 6. Multiple Repository Usage

```kotlin
val repositories = mapOf(
    "users" to Rdf.repository { providerId = "jena"; variantId = "memory" },
    "products" to Rdf.repository {
        providerId = "jena"
        variantId = "tdb2"
        location = "/data/products"
    }
)
```

## Configuration Parameters

### Required Parameters

- **`location`** (for TDB2 variants): Directory path for TDB2 storage

### Optional Parameters

- **`inferencing`**: Enable/disable inference (true/false)

## Capabilities

The enhanced Jena implementation supports:

- ✅ **SPARQL Query**: Full SPARQL 1.1 query support
- ✅ **SPARQL Update**: SPARQL Update operations
- ✅ **Named Graphs**: Multi-graph dataset support
- ✅ **Transactions**: ACID transaction support
- ✅ **Persistence**: TDB2 persistent storage
- ✅ **RDFS Inference**: RDFS reasoning
- ✅ **OWL Inference**: OWL reasoning
- ✅ **Rule-based Inference**: Custom rule support
- ✅ **Forward Chaining**: Forward chaining inference
- ✅ **SHACL Validation**: SHACL constraint validation
- ✅ **RDF 1.2 triple terms**: Triple terms and `rdf:reifies` support

## Performance Considerations

### TDB2 Optimization

- **Indexing**: TDB2 provides automatic indexing for efficient queries
- **Caching**: Built-in caching for frequently accessed data
- **Compression**: Efficient storage compression
- **Concurrency**: Multi-threaded access support

### Memory Management

- **In-memory**: Fast access for temporary data
- **TDB2**: Persistent storage for large datasets
- **Transactions**: Efficient batch operations

## Best Practices

1. **Use TDB2 for Large Datasets**: TDB2 provides better performance for large datasets
2. **Leverage Transactions**: Batch writes (`addTriples` / `removeTriples`) run in one transaction, but each per-triple write outside a transaction costs a transaction of its own. Batch your writes or wrap them in `repo.transaction { }`
3. **Named Graphs**: Use named graphs for data organization
4. **Inference**: Enable inference only when needed
5. **Repository Manager**: Use RepositoryManager for complex multi-repository scenarios

## Migration from Basic Implementation

The enhanced implementation is backward compatible with the basic Jena implementation:

```kotlin
val api = Rdf.repository {
    providerId = "jena"
    variantId = "memory"
}

val persistentApi = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2"
    location = "/data/tdb2"
}
```

## Examples

See the `EnhancedJenaExample.kt` file for comprehensive examples demonstrating all features.



