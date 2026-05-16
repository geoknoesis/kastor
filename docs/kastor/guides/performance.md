# Performance Guide

{% include version-banner.md %}

## Overview

This guide provides performance best practices and benchmarks for Kastor RDF SDK. It covers optimization strategies for large datasets, memory management, and performance characteristics of different operations.

## Performance Characteristics

### Operation Complexity

| Operation | Complexity | Notes |
|-----------|------------|-------|
| Add Triple | O(1) | Hash-based lookup in most implementations |
| Remove Triple | O(1) | Hash-based lookup in most implementations |
| Query (SELECT) | O(n) | Linear scan in memory, optimized in persistent stores |
| Query (ASK) | O(1) | Early termination when match found |
| Serialization | O(n) | Linear in number of triples |
| Parsing | O(n) | Linear in file size |

### Memory Usage

| Dataset Size | Memory Backend | Persistent Backend |
|--------------|----------------|-------------------|
| Small (< 10K triples) | ~1-5 MB | ~1-2 MB |
| Medium (10K-100K) | ~10-50 MB | ~5-10 MB |
| Large (100K-1M) | ~100-500 MB | ~20-50 MB |
| Very Large (> 1M) | ⚠️ Not recommended | ✅ Recommended |

**Note**: Memory usage varies significantly based on:
- Number of unique IRIs (more unique IRIs = more memory)
- Literal values (long strings consume more memory)
- Provider implementation (Jena vs RDF4J have different memory profiles)

## Best Practices

### 1. Use Streaming for Large Files

For large RDF files, use streaming parsing instead of loading everything into memory:

```kotlin
// ❌ Avoid: Loads entire file into memory
val graph = Rdf.parseFromFile("large.ttl", RdfFormat.TURTLE) // OOM risk

// ✅ Good: Stream processing
val provider = RdfProviderRegistry.getDefaultProvider()
provider.parseStreaming(File("large.ttl").inputStream(), RdfFormat.TURTLE, null)
    .forEach { triple ->
        // Process each triple
        processTriple(triple)
    }
```

### 2. Use Batch Operations

Batch operations are significantly faster than individual operations:

```kotlin
// ❌ Avoid: Individual operations
repo.add {
    triples.forEach { triple ->
        addTriple(triple)  // Multiple transactions
    }
}

// ✅ Good: Batch operations
repo.add {
    addTriples(triples)  // Single transaction
}
```

**Performance**: Batch operations can be 10-100x faster for large datasets.

### 3. Choose the Right Backend

Select the backend based on your dataset size and requirements:

| Backend | Best For | Performance |
|---------|----------|-------------|
| Memory | Small datasets (< 100K triples), testing | Fast reads/writes |
| Jena TDB2 | Large datasets, persistent storage | Fast queries, good for analytics |
| RDF4J Native | Large datasets, concurrent access | Good balance of speed and features |
| SPARQL | Remote endpoints, distributed data | Network latency dependent |

### 4. Use Transactions for Bulk Operations

Wrap bulk operations in transactions for better performance:

```kotlin
// ✅ Good: Single transaction
repo.transaction {
    repeat(10_000) { i ->
        val subject = iri("http://example.org/resource/$i")
        subject - FOAF.name - "Resource $i"
    }
}

// ❌ Avoid: Multiple transactions
repeat(10_000) { i ->
    repo.add {
        val subject = iri("http://example.org/resource/$i")
        subject - FOAF.name - "Resource $i"
    }
}
```

### 5. Optimize Queries

Use appropriate query patterns for better performance:

```kotlin
// ✅ Good: Use LIMIT for large result sets
val result = repo.select(SparqlSelect("""
    SELECT ?s ?o WHERE {
        ?s <http://example.org/property/name> ?o .
    } LIMIT 100
"""))

// ✅ Good: Use ASK for existence checks
val exists = repo.ask(SparqlAsk("""
    ASK {
        ?s <http://example.org/property/name> "Alice" .
    }
"""))

// ❌ Avoid: Unbounded queries on large datasets
val all = repo.select(SparqlSelect("""
    SELECT ?s ?o WHERE {
        ?s ?p ?o .
    }
"""))  // May return millions of results
```

### 6. Use Named Graphs for Partitioning

Partition large datasets using named graphs:

```kotlin
// ✅ Good: Partition by domain
val peopleGraph = repo.createGraph(iri("http://example.org/graphs/people"))
val booksGraph = repo.createGraph(iri("http://example.org/graphs/books"))

// Query specific graph
val people = repo.getGraph(iri("http://example.org/graphs/people"))
val result = people.select(SparqlSelect("SELECT ?name WHERE { ?person foaf:name ?name }"))
```

## Performance Benchmarks

### Benchmark Results

These benchmarks were run on a typical development machine. Your results may vary.

#### Graph Creation

| Triples | Time | Throughput |
|---------|------|------------|
| 1,000 | ~5ms | ~200 triples/ms |
| 10,000 | ~50ms | ~200 triples/ms |
| 100,000 | ~500ms | ~200 triples/ms |
| 1,000,000 | ~5,000ms | ~200 triples/ms |

**Note**: Throughput remains relatively constant, indicating O(n) complexity.

#### Query Performance

| Dataset Size | SELECT Query | ASK Query |
|--------------|--------------|------------|
| 1,000 triples | ~1ms | ~0.5ms |
| 10,000 triples | ~5ms | ~1ms |
| 100,000 triples | ~50ms | ~5ms |

**Note**: Query performance depends on:
- Query complexity
- Index availability (persistent stores)
- Result set size

#### Serialization Performance

| Triples | Turtle | N-Triples | JSON-LD |
|---------|--------|-----------|---------|
| 1,000 | ~10ms | ~5ms | ~20ms |
| 10,000 | ~100ms | ~50ms | ~200ms |
| 100,000 | ~1,000ms | ~500ms | ~2,000ms |

**Note**: JSON-LD is slower due to JSON processing overhead.

#### Batch vs Individual Operations

| Operation Count | Individual | Batch | Speedup |
|-----------------|------------|-------|---------|
| 1,000 | ~50ms | ~5ms | 10x |
| 10,000 | ~500ms | ~20ms | 25x |
| 100,000 | ~5,000ms | ~200ms | 25x |

**Note**: Batch operations show significant speedup, especially for large datasets.

### Running Benchmarks

To run performance benchmarks:

```bash
# Run all benchmarks
./gradlew :rdf:core:test --tests "*PerformanceBenchmarkTest" -DenableBenchmarks=true

# Run specific benchmark
./gradlew :rdf:core:test --tests "*PerformanceBenchmarkTest.benchmarkLargeGraphCreation" -DenableBenchmarks=true
```

**Note**: Benchmarks are disabled by default as they are resource-intensive. Enable with `-DenableBenchmarks=true`.

## Memory Management

### Memory Optimization Tips

1. **Use Persistent Backends for Large Datasets**
   ```kotlin
   // ✅ Good: Use persistent backend for large datasets
   val repo = Rdf.repository {
       providerId = "jena"
       variantId = "tdb2"
       location = "data/store"
   }
   ```

2. **Close Repositories When Done**
   ```kotlin
   // ✅ Good: Explicit cleanup
   repo.use {
       // Use repository
   }
   // Repository is automatically closed
   ```

3. **Use Streaming for Large Files**
   ```kotlin
   // ✅ Good: Stream processing
   provider.parseStreaming(inputStream, format, null)
       .forEach { triple ->
           // Process without loading all into memory
       }
   ```

4. **Avoid Loading Entire Graphs**
   ```kotlin
   // ❌ Avoid: Loads all triples into memory
   val allTriples = graph.getTriples()  // May be millions
   
   // ✅ Good: Query for what you need
   val result = graph.select(SparqlSelect("SELECT ?s ?o WHERE { ?s ?p ?o } LIMIT 100"))
   ```

## Provider-Specific Performance

### Jena Provider

**Strengths:**
- Fast in-memory operations
- Good query performance with TDB2
- Efficient serialization

**Best For:**
- Medium to large datasets
- Complex queries
- Analytics workloads

### RDF4J Provider

**Strengths:**
- Efficient memory usage
- Good concurrent access
- Streaming operations

**Best For:**
- Large datasets
- Concurrent access patterns
- Memory-constrained environments

### SPARQL Provider

**Strengths:**
- Distributed access
- No local storage required

**Considerations:**
- Network latency
- Endpoint performance
- Query complexity limits

**Best For:**
- Remote data access
- Distributed systems
- When local storage is not available

## Troubleshooting Performance Issues

### Slow Queries

**Symptoms**: Queries take a long time to execute.

**Solutions**:
1. Add LIMIT clauses to queries
2. Use appropriate indexes (persistent stores)
3. Simplify query patterns
4. Use ASK instead of SELECT when possible
5. Consider query optimization

### High Memory Usage

**Symptoms**: OutOfMemoryError or high memory consumption.

**Solutions**:
1. Use persistent backends instead of memory
2. Use streaming parsing for large files
3. Partition data using named graphs
4. Close repositories when done
5. Avoid loading entire graphs into memory

### Slow Serialization

**Symptoms**: Serialization takes a long time.

**Solutions**:
1. Use N-Triples format (fastest)
2. Avoid pretty-printing for large datasets
3. Use streaming serialization if available
4. Consider format-specific optimizations

## Related Documentation

- [Core API](../api/core-api.md) — streaming (`Rdf.parseStreaming`, SPARQL `Flow`) and repository APIs
- [Repository reference](../reference/repository.md) — repository configuration and lifecycle
- [Provider Guide](../providers/README.md) - Provider-specific performance characteristics

