## Core API

### Data model
- **`RdfTerm`**: sealed interface implemented by `Iri`, `BlankNode`, `Literal`, `TripleTerm`.
- **`Iri`**: wraps a String IRI value.
- **`BlankNode`**: wraps an internal identifier.
- **`Literal`**: `lexical: String`, optional `lang: String?`, optional `datatype: Iri?`.
- **`RdfTriple`**: `subject: RdfTerm`, `predicate: Iri`, `object`: `RdfTerm`.

### Query results
- **`SparqlQueryResult`**: iterable of `BindingSet` rows with `first()`, `toList()`, and `asSequence()`.
- **`BindingSet`**: lookup by variable name via `get("name")`, plus typed accessors like `getString`, `getInt`, `getDouble`.

### Graph abstraction
```kotlin
interface RdfGraph {
  fun hasTriple(triple: RdfTriple): Boolean
  fun getTriples(): List<RdfTriple>
  fun size(): Int
}

interface GraphEditor {
  fun addTriple(triple: RdfTriple)
  fun addTriples(triples: Collection<RdfTriple>)
  fun removeTriple(triple: RdfTriple): Boolean
  fun removeTriples(triples: Collection<RdfTriple>): Boolean
  fun clear(): Boolean
}

interface MutableRdfGraph : RdfGraph, GraphEditor
```

### Repository abstraction
```kotlin
interface RdfRepository : SparqlRepository {
  val defaultGraph: RdfGraph

  fun getGraph(name: Iri): RdfGraph
  fun listGraphs(): List<Iri>
  fun createGraph(name: Iri): RdfGraph
  fun removeGraph(name: Iri): Boolean
  fun editDefaultGraph(): GraphEditor
  fun editGraph(name: Iri): GraphEditor

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

See [RDF Terms](rdfterms.md#triple-dsl-natural-language-for-rdf-statements-) for complete documentation.




