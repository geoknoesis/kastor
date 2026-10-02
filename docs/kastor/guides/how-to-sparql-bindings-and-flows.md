# How to use typed SPARQL bindings and Flow APIs

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** SPARQL solutions and streaming pipelines → [SPARQL fundamentals](../concepts/sparql-fundamentals.md). **Reference:** `BindingSet`, `asFlow`, streaming parse → [Core API](../api/core-api.md).

## Problem

- Read **`SELECT`** rows with **`getAs` / `getAsOrThrow`** instead of unchecked **`get("var")`** casts.
- Enforce that each solution binds **`?s ?p ?o`** (or any projection) with **`requireVariables`** or **`SparqlQueryResult.asFlow("s", "p", "o")`**.
- Stream **`Rdf.parseStreaming`** and in-memory triple lists through **`Flow`** for cancellation-friendly pipelines.

## Prerequisites

- **`rdf-core`** on the classpath (**`SparqlSelectQuery`** and related marker types come transitively via **`rdf-sparql-contract`** when you use published Maven coordinates).
- Any **`import com.geoknoesis.kastor.rdf.sparql.*`** (`getAs`, **`asFlow`**, etc.) requires **`rdf-sparql-lang`** (`com.geoknoesis.kastor:rdf-sparql-lang`). **`rdf-sparql-lang`** brings **kotlinx-coroutines** for **`Flow`** helpers it exposes.

## Steps

### Step 1: Read typed bindings from `SELECT` rows

Variable names are **without** the `?` prefix (same as `BindingSet.get`).

```kotlin
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.sparql.getAs
import com.geoknoesis.kastor.rdf.sparql.getAsOrThrow
import com.geoknoesis.kastor.rdf.sparql.requireVariables

repo.select(SparqlSelectQuery("SELECT ?s ?label WHERE { ?s rdfs:label ?label }")).forEach { row ->
    row.requireVariables("s", "label")
    val subject: Iri? = row.getAs<Iri>("s")
    val label: String = row.getAsOrThrow<String>("label") // literal lexical
    // ...
}
```

#### Supported type arguments for `getAs` / `getAsOrThrow`

| Type | Meaning |
|------|--------|
| `Iri` | IRI resource |
| `BlankNode` | Blank node |
| `Literal` | Any literal |
| `TripleTerm` | RDF 1.2 quoted triple term |
| `RdfResource` | IRI or blank node |
| `RdfTerm` | Any term |
| `String` | **Lexical** form of a literal of any datatype (not an IRI string) |
| `Int`, `Long` | A literal of datatype `xsd:integer` or a type derived from it (`xsd:long`, `xsd:int`, `xsd:nonNegativeInteger`, `xsd:unsignedInt`, ...) whose value fits the type asked for |
| `Double` | A literal of a numeric datatype (the integer types, `xsd:decimal`, `xsd:float`, `xsd:double`), `INF`, `-INF` and `NaN` included |
| `Boolean` | An `xsd:boolean` literal (`true`, `false`, `1`, `0`) |

Use **`getAsOrThrow`** when a variable must be present and typed; use **`getAs`** when absence or mismatch should yield **`null`**.

The **datatype decides**, not the look of the text: `"42"` (an `xsd:string`), `"42"@en` and `"42"^^xsd:date` are not numbers, `"42"^^xsd:decimal` is not an `Int`, and `"3000000000"^^xsd:integer` is a `Long` but not an `Int` (`getAs<Int>` returns `null`, `getAsOrThrow<Int>` throws). This is stricter than `BindingSet.getInt`, which parses the lexical form of any literal. A type argument that is not in the table (`Short`, `BigInteger`, ...) is a mistake in the call: both functions throw `IllegalArgumentException` naming the supported types, instead of returning `null` as if the value were absent.

### Step 2: Enforce columns across the whole result

**`SparqlQueryResult.asFlow("s", "p", "o")`** emits each **`BindingSet`** and throws **`IllegalStateException`** if any row is missing one of the listed variables (useful right after a **`SELECT ?s ?p ?o`**).

```kotlin
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.sparql.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

val rows = runBlocking {
    repo.select(SparqlSelectQuery("SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 1000"))
        .asFlow("s", "p", "o")
        .toList()
}
```

Collecting the flow honours **structured concurrency**: cancel the coroutine scope to stop consuming further rows.

- **Where the rows are read**: reading a result is blocking I/O, so `asFlow(...)` reads it on `Dispatchers.IO`, whatever dispatcher the collector runs on (`Dispatchers.Main`, a single-threaded dispatcher, ...). Pass a dispatcher as the first argument to choose another one: `result.asFlow(myBlockingDispatcher, "s", "p", "o")`.
- **Cancellation**: when the collector is cancelled while a read is blocked, the reading thread is interrupted and a result that is `AutoCloseable` is closed, which ends a read that waits for the network. The collector sees the cancellation, not whatever the aborted read threw.
- **Closing**: a result that is `AutoCloseable` is closed when the flow ends, by completion, failure or cancellation. Collect the flow once.

### Step 3: Stream triples with `Flow`

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.asRdfTriplesFlow
import com.geoknoesis.kastor.rdf.parseStreamingFlow
import kotlinx.coroutines.flow.fold

// Large file: triples are still produced lazily by the provider parser; the Flow forwards them one-by-one.
suspend fun countTriples(input: java.io.InputStream, format: String): Int =
    Rdf.parseStreamingFlow(input, format).fold(0) { acc, _ -> acc + 1 }

// In-memory batch
val flow = graph.getTriples().asRdfTriplesFlow()
```

You are still responsible for **closing** the input stream when the flow completes (for example **`input.use { … }`** around the collector).

## Validation

- **`requireVariables`** / **`asFlow(...)`** fail fast if a column is missing—run a tiny **`SELECT`** against known data to confirm.
- For **`parseStreamingFlow`**, cancellation should stop work promptly when the collector scope is cancelled.

## Troubleshooting

- **Wrong variable names:** Use **`"s"`**, not **`"?s"`**, in **`getAs`** / **`requireVariables`** / **`asFlow`**.
- **Streams not closed:** Wrap **`InputStream`** usage in **`use`** or an equivalent try/finally when driving **`parseStreamingFlow`**.

## Related

- [SPARQL fundamentals](../concepts/sparql-fundamentals.md)
- [Kastor Query DSL tutorial](kastor-query-dsl-tutorial.md)
- [Core API](../api/core-api.md)
