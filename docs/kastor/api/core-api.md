## Core API

### Data model
- **`RdfTerm`**: sealed interface implemented by `Iri`, `BlankNode`, `Literal`, `TripleTerm`.
- **`Iri`**: wraps a String IRI value.
- **`BlankNode`**: wraps an internal identifier.
- **`Literal`**: `lexical: String` and `datatype: Iri`. `LangString` adds `lang` (validated, case preserved as given; `normalizedLang` is the lower-case form) and an optional base `direction`.
- **`RdfTriple`**: `subject: RdfResource`, `predicate: Iri`, `obj: RdfTerm`.

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
    addTriples(cbdTriples)
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
  fun parseFromUrl(url: String, format: String = "TURTLE", options: UrlLoadOptions = UrlLoadOptions.DEFAULT): MutableRdfGraph
}
```

`Rdf.memory()` uses Jena's in-memory store, or RDF4J's if Jena is absent, and throws `RdfProviderException` when neither is on the classpath.

### Behaviour notes
- **Language tags** are validated against the `LANGTAG` grammar and keep their case: `LangString("Hi", "en-US").lang == "en-US"`, `normalizedLang == "en-us"`. Equality ignores tag case, so `LangString("Hi", "en-US") == LangString("Hi", "en-us")`; providers fall back to a case-insensitive match when looking up a stored literal.
- **Literals keep their lexical form.** `Literal("007", XSD.integer)` stays `"007"`. Only the exact `xsd:boolean` forms `"true"`/`"false"` become `TrueLiteral`/`FalseLiteral`; read `"1"`/`"0"` with `Literal.booleanValue()`.
- **Temporal literals** created with `toLiteral()` always include seconds: `LocalTime.of(10, 15).toLiteral()` is `"10:15:00"^^xsd:time`.
- **DSL blank nodes** get opaque labels (`b_<run>_<n>`) that are unique across all `add { }` / `Rdf.graph { }` calls in a JVM run, so separate calls never merge blank nodes. Don't depend on the label text.
- **DSL `triples`** on `GraphDsl` / `TripleDsl` is a read-only `List`; add triples with `triple(...)`, `addTriples(...)` or the infix syntax.
- **`getGraph(name)`** on the memory repository returns a live handle that reflects later writes.
- **Provider selection.**
  - An explicit `providerId` is honoured exactly: an unknown provider, a missing variant or unmet requirements throw `IllegalArgumentException`.
  - Requirement-based selection orders providers by `RdfProvider.priority` (Jena 50, RDF4J 40, memory −100).
- **URL loading** (`Rdf.parseFromUrl*`, `Rdf.parseDatasetFromUrl`) accepts only `http`/`https` by default and caps bodies at 64 MiB (`UrlLoadOptions`, `RdfInputTooLargeException`).

### Triple DSL
The API provides a natural language DSL for creating triples using infix functions:

```kotlin
val graph = Rdf.graph {
    // subject has predicate with object (adds the triple to the graph being built)
    person has name with "John"
    person has age with 30
}
```

See [RDF Terms](../resources/rdfterms.md#triple-dsl-natural-language-for-rdf-statements-) for complete documentation.




