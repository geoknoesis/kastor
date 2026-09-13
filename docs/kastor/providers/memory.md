# Memory Provider

The Memory provider provides in-memory RDF storage, ideal for development, testing, and small to medium-sized datasets.

> **`Rdf.memory()` is not this provider.** `Rdf.memory()` returns a SPARQL-capable in-memory Jena (or RDF4J) repository and throws if neither module is on the classpath. The `memory` provider described here is selected explicitly with `providerId = "memory"`. It stores RDF 1.2 terms, including triple terms, with serializable transactions, but it cannot parse, serialize or run SPARQL. It also has the lowest selection priority (−100).

## Features

- **Fast Performance**: In-memory operations are extremely fast
- **No Persistence**: Data is lost when the application exits
- **Thread-Safe**: Concurrent access is supported
- **Zero Configuration**: Works out of the box
- **RDF 1.2 Terms**: Triple terms round-trip (`supportsTripleTerms = true`)
- **Live Graph Handles**: `getGraph(name)` returns a handle that reflects later writes
- **Memory Efficient**: Optimized for memory usage

## Quick Start

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF

// In-memory repository with SPARQL (Jena or RDF4J)
val repo = Rdf.memory()

// The graph-only memory provider itself, selected explicitly (no SPARQL)
val graphOnly = Rdf.repository {
    providerId = "memory"
    variantId = "memory"
}

// Add some data
repo.add {
    val person = iri("http://example.org/alice")
    person - RDF.type - FOAF.Person
    person - FOAF.name - "Alice"
    person - FOAF.age - 30
}

// Query the data
val results = repo.select(
    SparqlSelectQuery("SELECT ?name WHERE { ?s ${FOAF.name} ?name }")
)
results.forEach { binding ->
    println(binding.getString("name"))
}
```

## Performance Characteristics

### Strengths
- **Speed**: Extremely fast read/write operations
- **Simplicity**: No external dependencies or setup
- **Testing**: Perfect for unit tests and development

### Limitations
- **Memory Usage**: Limited by available RAM
- **Persistence**: Data is lost on application restart
- **Scalability**: Not suitable for very large datasets

## Thread Safety

The memory provider is thread-safe and supports concurrent operations:

```kotlin
val repo = Rdf.memory()

// Multiple threads can safely access the repository
val futures = (1..10).map { i ->
    GlobalScope.async {
        repo.add {
            val person = iri("http://example.org/person$i")
            person - RDF.type - FOAF.Person
            person - FOAF.name - "Person $i"
        }
    }
}

// Wait for all operations to complete
futures.forEach { it.await() }
```

## Transactions

```kotlin
val repo = Rdf.memory()

repo.transaction {
    add {
        val person = iri("http://example.org/person/alice")
        person - FOAF.name - "Alice"
    }
}
```

## Best Practices

### Development
- Use for unit tests and development
- Perfect for prototyping and experimentation
- Great for small datasets that fit in memory

### Production
- Only use for small, non-critical datasets
- Consider persistence requirements carefully
- Monitor memory usage in production

### Performance
- Use appropriate data structures for your access patterns
- Consider indexing for complex queries
- Monitor memory usage and GC pressure

## Migration to Persistent Storage

When your data grows beyond memory capacity, migrate to persistent storage:

```kotlin
// Start with memory
val memoryRepo = Rdf.memory()
// ... populate with data ...

// Migrate to persistent storage
val persistentRepo = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2"
    location = "./data/storage"
}

// Copy all data
val data = memoryRepo.defaultGraph.serialize(RdfFormat.TURTLE)
val graph = Rdf.parse(data, RdfFormat.TURTLE)
persistentRepo.addTriples(graph.getTriples())

// Switch to persistent repository
// memoryRepo can now be discarded
```

## Limitations and Considerations

1. **Memory Limits**: Limited by available RAM
2. **No Persistence**: Data lost on restart
3. **Single Process**: Cannot share data between processes
4. **Backup**: No automatic backup or recovery
5. **Scalability**: Not suitable for very large datasets

## When to Use Memory Provider

✅ **Good for:**
- Development and testing
- Small datasets (< 1GB)
- Temporary data processing
- Prototyping and experimentation
- Unit tests

❌ **Not suitable for:**
- Large datasets (> 1GB)
- Production systems requiring persistence
- Multi-process applications
- Long-running services with critical data




