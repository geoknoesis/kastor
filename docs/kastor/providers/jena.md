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

**Dataset of queries and updates.** Outside `GRAPH`, queries and the `WHERE` clause of updates read the default graph only (the store is not a union-default-graph dataset); inside `GRAPH` they read the named graphs. `DESCRIBE` describes resources from the default graph of the query's dataset: the store's default graph or, with `FROM` clauses, the merge of the `FROM` graphs (empty with only `FROM NAMED`). **Behaviour change:** `describe` used to return the triples of the described resource from every named graph of the store as well (the behaviour of Jena's own describe handler); query named graphs with `DESCRIBE <r> FROM <graph>`, or with `CONSTRUCT ... WHERE { GRAPH ?g { ... } }`. The RDF4J provider follows the same contract.

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

#### How inference reads are served

The `*-inference` variants answer reads from a lazy RDFS inference view of the store (backward chaining; nothing is
materialised on the heap). The view of a committed snapshot is shared by every reader of that snapshot and is owned
by one worker thread that holds a read transaction on it. What follows from that design:

- **Reads of one snapshot are serialised on one thread.** Readers hand the worker small steps (open a find, fetch
  the next chunk of results, a lookup); steps of concurrent readers interleave, but only one runs at a time. A find
  starts with 16 results per step and quadruples up to 1024, so a query that stops early (`LIMIT`, `ASK`) costs
  little, and a long scan makes other readers wait for at most one step. Inference reads therefore do not scale
  with the number of reader threads. For read-heavy parallel workloads, materialise the entailments once (a
  reasoner from `rdf-jena-reasoning`, then store the result in a plain `memory` / `tdb2` repository) instead of
  querying an inference variant.
- **A view is prepared per snapshot.** Preparing a graph runs the RDFS forward rules over its schema; the
  backward-chaining tables then grow with the goals queried.
  - *In-memory stores* keep the view across commits that did not write to a graph the view has prepared: graph
    edits (`editGraph(..).addTriples(..)`, `removeTriples`, `clear` of one graph) and `removeGraph` are attributed
    to their graph, so writing to graph A does not cost the prepared inferences of graph B. SPARQL updates, dataset
    loads (`parseDataset`) and `repo.clear()` are treated as writing every graph and always retire the view.
  - *TDB2 stores* retire the view on every commit (other repositories and clients of the location can write any
    graph; only the data version tells snapshots apart).
  Batch writes into one transaction when inference reads alternate with writes to the same graph.
- **An idle view is released** after `viewIdleTimeoutMillis` (default 1000) without readers, so that its worker's
  read transaction does not pin the store version (for TDB2 this can delay compaction). The next read prepares a
  new view. Raise the option when reads come in bursts further apart than the default and the store is rarely
  written.
- **Inside a write transaction** reads use a private view of the transaction (so uncommitted changes are visible),
  rebuilt after each of its writes.

#### Query timeouts and cancelled readers

A query timeout (`withSelectRows(query, bindings, timeout)`) or an interrupted reader stops the reasoner. What that
costs the other readers of the same view depends on where the step stops:

| The cancelled step ... | Effect |
|---|---|
| was still waiting for the worker | none: it never runs |
| stops before calling the reasoner, between two results, or while preparing a graph nobody has read yet | none: the view stays healthy |
| stops inside the reasoner (at a store read of backward chaining), or fails there | the view is *poisoned* and replaced |
| is still running 100 ms after the cancellation | the view is poisoned and replaced; the step's thread and read transaction are released when it reaches its next store read or result |

When a view is poisoned, lookups (`hasTriple`, `size`) and finds/queries that have **not returned a result yet**
continue transparently on a fresh view of the same snapshot. An iterator that has already returned results fails
with `RdfInferenceException` (the remaining results could not be told apart from a fresh run's); retrying the read
is safe. **A failed iterator stays failed:** once a step of an inference iterator has failed or was cancelled, every
further `hasNext()` / `next()` throws the same failure again (after the results fetched before it are consumed), so
code that catches the exception and goes on iterating never takes a truncated result for a complete one. A fresh view costs a new worker, read transaction and RDFS preparation, so a reader with very tight
timeouts on expensive inference queries still slows the others down: prefer timeouts that the common query meets.

#### Closing

`close()` waits for read transactions that still hold an inference view, so that no read outlives the store:
up to `closeTimeoutMillis` (default 10000) for the readers, then, after stopping their views (their further reads
fail with `REPOSITORY_CLOSED`), up to `closeGraceMillis` (default 2000) for the workers. `0` means "do not wait".
`close()` calls made concurrently all return only after the store is closed.

```kotlin
val repo = JenaProvider().createRepository(
    "tdb2-inference",
    RdfConfig(options = mapOf(
        "location" to "/data/tdb2",
        "viewIdleTimeoutMillis" to "5000",
        "closeTimeoutMillis" to "30000",
        "closeGraceMillis" to "2000",
    )),
)
// or: JenaRepository.Tdb2RepositoryWithInference(location, viewIdleTimeout, closeTimeout, closeGrace)
```

### Graph handles: identity and modification stamps

The graphs a Jena repository hands out (`defaultGraph`, `getGraph(name)`, `editGraph(name)`, ...) are handles:

- **Identity.** Two handles are equal (and have the same hash code) when they come from the same repository
  *instance* and name the same graph, so a handle is a valid cache key. Handles of two repositories are never
  equal, also when both are connected to the same TDB2 location.
- **Modification stamp.** Every handle implements `VersionedRdfGraph`: `modificationStamp` changes whenever the
  content may have changed, and a value is never reused. Read the stamp **before** the content it is meant to
  describe.
  - It identifies the committed snapshot the caller reads: the TDB2 data version (shared by every repository and
    client of the location, so a commit through another instance changes it), or the commit generation of an
    in-memory store. A read transaction older than a commit does not get the stamp of the newer content.
  - Inside a write transaction it is private to the transaction and changes with each of its writes.
  - Every commit and every rollback through the repository changes it; it is the stamp of the **store**, so a
    write to any graph changes the stamp of all handles. For inference variants it is the stamp of the underlying
    store as well.
  - Reading it is cheap: inside a transaction of the caller it comes from that transaction's state; outside one,
    an in-memory store reads its commit generation without beginning a transaction (so do `defaultGraph` and
    `getGraph(name)`), while a TDB2 store begins one read transaction to learn the data version of the location.
  - **Writes through the Jena API count too.** The store's own dataset, models and graphs are never handed out:
    `JenaBridge.getJenaModel(graph)` / `getJenaGraph(graph)` (and the `getJenaModel()` / `getJenaGraph()`
    extensions) return, for a repository graph, a live Jena view of the asserted graph whose every mutation
    (`add`, `remove`, `removeAll`, `Graph.delete` / `remove` / `clear`, `GraphUtil` bulk operations, statement and
    resource mutators) is a write transaction of the repository: its own one outside `repository.transaction { }`,
    the enclosing one inside it. Such a write therefore moves the stamp and is seen by inference views like any
    Kastor write. `Model.executeInTxn { }` / `calculateInTxn { }` run in a repository write transaction;
    `Model.begin()` / `commit()` / `abort()` throw `UnsupportedOperationException` (use
    `repository.transaction { }`), iterators of the view do not support `remove()`, and closing the view does not
    close the store. **Behaviour change:** these functions used to return the store's own model, whose writes
    bypassed the repository (stale stamps, stale inference views) and which exposed the dataset.
- Graphs that are **not** owned by a repository claim no stamp: graphs wrapped with `JenaBridge.fromJenaModel` /
  `fromJenaGraph` / `toKastorGraph()` (the model can change behind Kastor's back) and graphs returned by
  `parseGraph`.

```kotlin
val graph = repo.getGraph(name)
val stamp = (graph as? VersionedRdfGraph)?.modificationStamp   // 1. stamp
if (stamp == null || stamp != cached?.stamp) {
    cached = Cached(stamp, derive(graph))                       // 2. content, stored under the stamp read before it
}
```

### Jena's special graph names

Jena reserves the graph names `urn:x-arq:...`. A Jena repository treats them as follows:

- `urn:x-arq:DefaultGraph` (and `urn:x-arq:DefaultGraphNode`) **is the default graph**: `getGraph` / `editGraph`
  return the default graph's handle (equal to `defaultGraph`), a write through it is a write to the default graph,
  `hasGraph` answers for the default graph, and `removeGraph` rejects the name with `IllegalArgumentException`
  (clear the default graph instead).
- `urn:x-arq:UnionGraph` is exposed as a **read-only** graph: the union of every named graph (the default graph is
  not part of it), with the RDFS entailments of that union on an inference repository. It is available through
  `getGraph(Iri("urn:x-arq:UnionGraph"))` and in queries (`GRAPH <urn:x-arq:UnionGraph> { ... }`), and is never
  listed by `listGraphs()`. Writing through its handle (or through its Jena view) fails with
  `UnsupportedOperationException`; `removeGraph` rejects the name. The union graph depends on **every** graph: any
  commit that wrote something retires an inference view that has prepared it, and a view kept across such a commit
  never serves it.
- Any other `urn:x-arq:` name is handled with the same caution (a write through it counts as a write to every graph).

**Behaviour change:** the default-graph alias used to be a handle of its own (not equal to `defaultGraph`), and on
an in-memory inference repository a commit to a member graph (or a write through the alias) could leave an inference
view serving the union graph (or the default graph) as it was before the commit.

### Blank-node graph names (skolem graphs)

Repositories name graphs by IRI. When `parseDataset` loads a TriG / N-Quads document whose graphs are named by blank
nodes (`_:g { ... }`, an N-Quads graph label `_:g`), each such name is replaced by a *skolem graph name*:

```
urn:kastor:skolem:<load>:<id>
```

- `<load>`: 32 lowercase hexadecimal digits, a random 128-bit id drawn once per `parseDataset` call. The same
  blank node names the same graph within one load; two loads never share a skolem graph, also when they load the
  same document.
- `<id>`: the id of the blank node as UTF-8, where every byte other than `A-Z a-z 0-9 . _ -` is written as `%`
  followed by two **uppercase** hexadecimal digits. It is never empty.

A blank node that also occurs inside triples stays a blank node there; its `BlankNode.id` is the decoded `<id>`.
`blankNodeIdOfSkolemGraph(graphName)` (package `com.geoknoesis.kastor.rdf.jena`) recognises and decodes such a name
and returns `null` for any other IRI. The RDF4J provider produces names of the same form, so the function decodes
those too.

```kotlin
for (graph in repo.listGraphs()) {
    val blankNodeId = blankNodeIdOfSkolemGraph(graph) ?: continue   // an ordinary named graph
    // BlankNode(blankNodeId) is the node that named this graph in the loaded document
}
```

**SPARQL `UPDATE` does the same.** Jena itself lets an update request create a graph named by a blank node: `LOAD`
of a quad document (TriG, N-Quads) with blank graph labels, and `INSERT { GRAPH ?g { ... } }` with `?g` bound to a
blank node. Such a graph would be invisible to `listGraphs()` and unreachable through `getGraph()`, so when the
request ends, in the same transaction, every graph it created under a blank-node name is moved to
`urn:kastor:skolem:<load>:<id>` (one random `<load>` id per update request; `<id>` is the blank node's id), exactly
as the RDF4J provider does. Only the graph name changes: where the blank node is used as a subject or object it
stays a blank node. A repository created by Kastor therefore never holds a blank-node graph; blank-node graphs that
other software wrote to a TDB2 location before the request are left alone. Requests that cannot create such a graph
(no `LOAD` without `INTO GRAPH`, no `INSERT` template with a graph variable) are not inspected. **Behaviour
change:** such graphs used to stay blank-node graphs that `listGraphs()` did not list.

### Parsing, literals and Jena interop

- **Lexical forms are preserved exactly**: `"007"^^xsd:integer` stays `"007"`. Only the exact lexical forms `"true"` / `"false"` become boolean singletons.
- **`parseGraph` accepts triple formats only**: TriG and N-Quads are rejected with `RdfFormatException`; parse quad formats as a dataset instead.
- **Strict parsing**: syntax errors are not silently skipped. Without a base IRI, relative IRIs are a parse error.
- **Strict bridge reads**: graphs wrapped with `JenaBridge.fromJenaModel(model)`, `JenaBridge.fromJenaGraph(graph)` or `toKastorGraph()` fail a read with `IllegalArgumentException` when they meet a statement Kastor cannot represent (e.g. `xml:lang="en_US"` or an IRI that RFC 3987 rejects). Lenient reads are opt-in: `fromJenaModel(model, strictRead = false)` / `fromJenaGraph(graph, strictRead = false)` skip such statements with one warning per read, and `size()` then counts only the statements reads return (a full scan).
- **Language-tag case**: Jena canonicalises tag case whenever it creates a language-tagged literal, so `"x"@en-us` is stored and read back as `"x"@en-US`. Kastor equality ignores tag case, so lookups by either spelling still match.
- **`JenaBridge.toJenaModel(graph)`** returns the wrapped model for standalone Jena-backed graphs. For a graph that belongs to a repository, it returns a **detached copy** (including inferences for inference variants), because the live store model is only valid inside repository transactions. Use **`JenaBridge.copyToJenaModel(graph)`** when you always need an independent copy, and **`JenaBridge.getJenaModel(graph)`** for a live view of the asserted graph whose writes go through the repository (see "Graph handles: identity and modification stamps").

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

Options of the `*-inference` variants (`RdfConfig.options`, values are numbers of milliseconds):

| Option | Default | Meaning |
|---|---|---|
| `viewIdleTimeoutMillis` | `1000` | An inference view without readers for this long is released (must be positive). |
| `closeTimeoutMillis` | `10000` | Longest time `close()` waits for readers that still hold an inference view (`0`: do not wait). |
| `closeGraceMillis` | `2000` | Longest time `close()` then waits for the workers of the views it stopped (`0`: do not wait). |

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



