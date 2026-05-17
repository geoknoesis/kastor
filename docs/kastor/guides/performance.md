# Performance Guide

{% include version-banner.md %}

> **Documentation mode: Explanation** — typical complexity and provider tradeoffs (not a substitute for profiling your data). **Tasks:** streaming parse → [How to Parse RDF](how-to-parse-rdf.md), serialization → [How to Serialize RDF](how-to-serialize-rdf.md), `Flow` pipelines → [Typed SPARQL bindings & Flow](how-to-sparql-bindings-and-flows.md).

## Problem

- Avoid **out-of-memory** failures on large inputs.
- Speed up **bulk loads**, **queries**, and **serialization**.
- Choose a **provider/back-end** that fits dataset size and concurrency.

## Complexity and memory (reference)

### Operation complexity

| Operation | Complexity | Notes |
|-----------|------------|-------|
| Add triple | O(1) | Hash-based lookup in most implementations |
| Remove triple | O(1) | Hash-based lookup in most implementations |
| Query (SELECT) | O(n) | Linear scan in memory; persistent stores often indexed |
| Query (ASK) | O(1) amortized | Early termination when a match is found |
| Serialization | O(n) | Linear in number of triples |
| Parsing | O(n) | Linear in input size |

### Memory usage (order-of-magnitude)

| Dataset size | Memory backend | Persistent backend |
|--------------|----------------|-------------------|
| Small (< 10K triples) | ~1–5 MB | ~1–2 MB |
| Medium (10K–100K) | ~10–50 MB | ~5–10 MB |
| Large (100K–1M) | ~100–500 MB | ~20–50 MB |
| Very large (> 1M) | Not recommended | Preferred |

Memory depends strongly on unique **IRIs**, **literal size**, and **provider** (Jena vs RDF4J profiles differ).

## Steps

### Step 1: Stream large parses

Avoid loading an entire file into a graph when you only need to scan triples once.

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import java.io.File

// Risky for huge files: entire graph in heap
// val graph = Rdf.parseFromFile("large.ttl", RdfFormat.TURTLE)

File("large.ttl").inputStream().use { input ->
    Rdf.parseStreaming(input, RdfFormat.TURTLE).forEach { triple ->
        // Handle each triple (aggregate, filter, enqueue, …)
    }
}
```

### Step 2: Prefer batch graph APIs

Batching reduces per-operation overhead inside the graph implementation.

```kotlin
// Slower: many small flushes
repo.add {
    triples.forEach { triple ->
        addTriple(triple)
    }
}

// Faster: one batch
repo.add {
    addTriples(triples)
}
```

Batching is often dramatically faster at scale (see benchmarks below).

### Step 3: Match the backend to the workload

| Backend | Best for | Notes |
|---------|----------|--------|
| Memory | Small graphs, tests | Fast; bounded by heap |
| Jena TDB2 | Large persistent datasets | Strong query performance when indexed |
| RDF4J Native | Large datasets, concurrency | Balanced footprint and features |
| SPARQL | Remote data | Dominated by network and endpoint limits |

### Step 4: Scope bulk writes

Avoid starting a new **`add { }`** per row when one block or one **`transaction { }`** batch will do.

```kotlin
import com.geoknoesis.kastor.rdf.iri
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.transaction {
    add {
        repeat(10_000) { i ->
            val subject = iri("http://example.org/resource/$i")
            subject has FOAF.name with "Resource $i"
        }
    }
}
```

Remote SPARQL repositories may not implement real transactional semantics; still fewer round-trips when the provider batches updates.

### Step 5: Bound or specialize queries

```kotlin
import com.geoknoesis.kastor.rdf.SparqlAskQuery
import com.geoknoesis.kastor.rdf.SparqlSelectQuery

val limited = repo.select(SparqlSelectQuery("""
    SELECT ?s ?o WHERE {
        ?s <http://example.org/property/name> ?o .
    }
    LIMIT 100
"""))

val exists = repo.ask(SparqlAskQuery("""
    ASK {
        ?s <http://example.org/property/name> "Alice" .
    }
"""))

// Risky on large datasets: unbounded ?s ?p ?o
// repo.select(SparqlSelectQuery("SELECT ?s ?p ?o WHERE { ?s ?p ?o }"))
```

### Step 6: Partition with named graphs

Use named graphs so queries can target a subset of the dataset instead of scanning everything.

```kotlin
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.iri
import com.geoknoesis.kastor.rdf.vocab.FOAF

val peopleGraph = iri("http://example.org/graphs/people")
repo.createGraph(peopleGraph)
repo.addToGraph(peopleGraph) {
    val alice = iri("http://example.org/person/alice")
    alice has FOAF.name with "Alice"
}

val names = repo.select(SparqlSelectQuery("""
    SELECT ?name WHERE {
        GRAPH <${peopleGraph.value}> {
            ?person ${FOAF.name} ?name .
        }
    }
"""))
```

## Benchmarks

Illustrative runs on a typical developer machine—**measure on your hardware and data**.

### Graph creation (in-memory)

| Triples | Time | Throughput |
|---------|------|------------|
| 1,000 | ~5 ms | ~200 triples/ms |
| 10,000 | ~50 ms | ~200 triples/ms |
| 100,000 | ~500 ms | ~200 triples/ms |
| 1,000,000 | ~5,000 ms | ~200 triples/ms |

### Queries

| Dataset size | SELECT | ASK |
|--------------|--------|-----|
| 1,000 triples | ~1 ms | ~0.5 ms |
| 10,000 triples | ~5 ms | ~1 ms |
| 100,000 triples | ~50 ms | ~5 ms |

Depends on query shape, indexes (persistent stores), and result size.

### Serialization

| Triples | Turtle | N-Triples | JSON-LD |
|---------|--------|-----------|---------|
| 1,000 | ~10 ms | ~5 ms | ~20 ms |
| 10,000 | ~100 ms | ~50 ms | ~200 ms |
| 100,000 | ~1,000 ms | ~500 ms | ~2,000 ms |

### Batch vs individual adds

| Operations | Individual | Batch | Speedup |
|------------|------------|-------|---------|
| 1,000 | ~50 ms | ~5 ms | ~10× |
| 10,000 | ~500 ms | ~20 ms | ~25× |
| 100,000 | ~5,000 ms | ~200 ms | ~25× |

### Running benchmarks locally

```bash
./gradlew :rdf:core:test --tests "*PerformanceBenchmarkTest" -DenableBenchmarks=true

./gradlew :rdf:core:test --tests "*PerformanceBenchmarkTest.benchmarkLargeGraphCreation" -DenableBenchmarks=true
```

Benchmarks are off by default; opt in with **`-DenableBenchmarks=true`** (resource-intensive).

## Memory management

1. **Prefer persistent stores** when graphs exceed comfortable heap headroom (see table above).
2. **`Closeable` repositories:** use **`use { }`** or **`close()`** when the integration owns lifecycle.
3. **Avoid materializing full graphs** when a **`SELECT`** / **`LIMIT`** / streaming approach suffices.

## Provider notes

### Jena

Suited to medium–large datasets, TDB2-backed queries, and heavier analytics-style workloads.

### RDF4J

Often favorable memory footprint and concurrent access patterns.

### SPARQL HTTP

Latency and server limits dominate; optimize round-trips and payload size.

## Troubleshooting

### Slow queries

- Add **`LIMIT`**, narrow triple patterns, use **`ASK`** for existence.
- On persistent stores, ensure indexes and storage tuning match the workload.

### High memory use / `OutOfMemoryError`

- Move off pure in-memory stores for large graphs; stream parses; avoid **`getTriples()`** on huge graphs when possible.

### Slow serialization

- Prefer **N-Triples** for raw speed when JSON/Turtle human-readability is unnecessary.
- Avoid unnecessary pretty-printing on large exports.

## Related

- [Core API](../api/core-api.md)
- [Repository reference](../reference/repository.md)
- [Providers](../providers/README.md)
