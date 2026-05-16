## Core API

### Data model
- **`RdfTerm`**: sealed interface implemented by `Iri`, `BlankNode`, `Literal`, `TripleTerm`.
- **`Iri`**: wraps a String IRI value.
- **`BlankNode`**: wraps an internal identifier.
- **`Literal`**: `lexical: String`, optional `lang: String?`, optional `datatype: Iri?`.
- **`RdfTriple`**: `subject: RdfTerm`, `predicate: Iri`, `object`: `RdfTerm`.

### Query results
- **`SparqlQueryResult`**: iterable of `BindingSet` rows with `first()`, `toList()`, and `asSequence()`; use **`asFlow("s", "p", "o")`** (from `com.geoknoesis.kastor.rdf.sparql`) to stream rows as a Kotlin **`Flow`** and optionally require that each solution binds the listed variables.
- **`BindingSet`**: lookup by variable name via `get("name")`, plus typed accessors like `getString`, `getInt`, `getDouble`. Prefer **`getAs<Iri>("s")`** / **`getAsOrThrow<String>("label")`** and **`requireVariables("s", "p", "o")`** (`com.geoknoesis.kastor.rdf.sparql`) for compile-time typed reads and shape checks — see [How to use typed SPARQL bindings and Flow APIs](../guides/how-to-sparql-bindings-and-flows.md).
- **Streaming RDF**: `Rdf.parseStreaming` is still a **`Sequence<RdfTriple>`**; wrap with **`Rdf.parseStreamingFlow(...)`** or **`getTriples().asRdfTriplesFlow()`** (`com.geoknoesis.kastor.rdf`) for **`Flow`**-based pipelines with cooperative cancellation.

### Graph abstraction
```kotlin
interface RdfGraph {
  fun hasTriple(triple: RdfTriple): Boolean
  fun getTriples(): List<RdfTriple>
  fun size(): Int
}

interface MutableRdfGraph : RdfGraph {
  fun addTriple(triple: RdfTriple)
  fun addTriples(triples: Collection<RdfTriple>)
  fun removeTriple(triple: RdfTriple): Boolean
  fun removeTriples(triples: Collection<RdfTriple>): Boolean
  fun clear(): Boolean
}
```

### Graph utilities

#### CBD Closure

**CBD (Concise Bounded Description)** is a standard RDF pattern for extracting a complete description of a resource.

```kotlin
fun RdfGraph.getCbdClosure(resource: RdfResource): Set<RdfTriple>
```

**What it includes:**
1. All triples where the resource is the subject (direct properties)
2. Recursively, for any blank node object, all triples where that blank node is the subject

**Key characteristics:**
- ✅ Follows blank nodes recursively (complete anonymous resource descriptions)
- ✅ Does not follow IRIs (IRI objects remain as references)
- ✅ Prevents cycles (uses visited set to avoid infinite recursion)

**Example:**
```kotlin
import com.geoknoesis.kastor.rdf.getCbdClosure

// Extract CBD closure for a resource
val cbdTriples = graph.getCbdClosure(Iri("http://example.org/person"))

// Create a new graph with CBD closure
val cbdGraph = Rdf.graph {
    cbdTriples.forEach { add(it) }
}
```

**See also:**
- [Serializing Domain Instances](../../kastor-gen/guides/serializing-domain-instances.md) - Using CBD closure with domain instances
- [Runtime API](../../kastor-gen/reference/runtime.md#cbd-closure) - CBD closure in Kastor Gen runtime

### Repository abstraction
```kotlin
interface RdfRepository : Dataset, SparqlMutable {
  val defaultGraph: RdfGraph

  fun getGraph(name: Iri): RdfGraph
  fun listGraphs(): List<Iri>
  fun createGraph(name: Iri): RdfGraph
  fun removeGraph(name: Iri): Boolean
  fun editDefaultGraph(): MutableRdfGraph
  fun editGraph(name: Iri): MutableRdfGraph

  fun select(query: SparqlSelect): SparqlQueryResult
  fun ask(query: SparqlAsk): Boolean
  fun construct(query: SparqlConstruct): Sequence<RdfTriple>
  fun describe(query: SparqlDescribe): Sequence<RdfTriple>
  fun update(query: UpdateQuery)

  fun transaction(operations: RdfRepository.() -> Unit)
  fun readTransaction(operations: RdfRepository.() -> Unit)

  fun clear(): Boolean
  fun isClosed(): Boolean
  fun getCapabilities(): ProviderCapabilities
}
```

**Note:** `RdfRepository` implements `Dataset`, so every repository is a SPARQL‑compliant
dataset (default graph + named graphs). Use `Dataset` for read‑only query scope, and
`RdfRepository` when you need mutations.

### Factory DSL
```kotlin
object Rdf {
  fun memory(): RdfRepository
  fun memoryWithInference(): RdfRepository
  fun persistent(location: String = "data"): RdfRepository
  fun repository(configure: RdfRepositoryBuilder.() -> Unit): RdfRepository
  fun graph(configure: GraphDsl.() -> Unit): MutableRdfGraph
}
```

### Triple DSL
The API provides a natural language DSL for creating triples using infix functions:

```kotlin
// subject has predicate with object
val triple = person has name with "John"
val ageTriple = person has age with 30
```

See [RDF Terms](../resources/rdfterms.md#triple-dsl-natural-language-for-rdf-statements-) for complete documentation.




