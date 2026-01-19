## Core API

### Data model
- **`RdfTerm`**: sealed interface implemented by `Iri`, `BlankNode`, `Literal`, `TripleTerm`.
- **`Iri`**: wraps a String IRI value.
- **`BlankNode`**: wraps an internal identifier.
- **`Literal`**: `lexical: String`, optional `lang: String?`, optional `datatype: Iri?`.
- **`RdfTriple`**: `subject: RdfTerm`, `predicate: Iri`, ``object``: `RdfTerm`.

### Query results
- **`ResultBinding(name: String, value: RdfTerm)`**
- **`RdfSelectRow(bindings: List<ResultBinding>)`** with `operator fun get(name: String): RdfTerm?`
- **`ResultSet(rows: List<RdfSelectRow>)`**

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

### Repository abstraction
```kotlin
interface RdfRepository {
  // Transactions
  fun beginTransaction(write: Boolean = true)
  fun commit()
  fun rollback()
  fun end()

  // SPARQL
  fun querySelect(query: String, bindings: Map<String, RdfTerm> = emptyMap()): ResultSet
  fun queryConstruct(query: String, bindings: Map<String, RdfTerm> = emptyMap()): RdfGraph
  fun queryAsk(query: String, bindings: Map<String, RdfTerm> = emptyMap()): Boolean
  fun update(update: String, bindings: Map<String, RdfTerm> = emptyMap())

  // Data operations
  fun addTriple(graph: Iri?, triple: RdfTriple)
  fun readGraph(graph: Iri?, input: java.io.InputStream, format: String)
  fun writeGraph(graph: Iri?, output: java.io.OutputStream, format: String)
}
```

### Factory DSL
```kotlin
object Rdf {
  fun factory(block: Builder.() -> Unit): RdfApi
  fun iri(value: String): Iri
  fun bnode(id: String): BlankNode
  fun literal(lexical: String, lang: String? = null, datatype: Iri? = null): Literal
}
```

### Enhanced Configuration System
The API provides rich parameter metadata for all configuration variants:

```kotlin
// Parameter information structure
data class ConfigParameter(
    val name: String,           // Parameter name (e.g., "location")
    val description: String,     // Human-readable description
    val type: String = "String", // Data type (default: "String")
    val optional: Boolean = false, // Whether parameter is optional
    val defaultValue: String? = null, // Default value if optional
    val examples: List<String> = emptyList() // Example values
)

// Configuration variant information
data class ConfigVariant(
    val type: String,
    val description: String,
    val parameters: List<ConfigParameter> = emptyList()
)

// Registry methods for parameter discovery
object RdfApiRegistry {
    fun getAllConfigVariants(): List<ConfigVariant>
    fun getConfigVariant(type: String): ConfigVariant?
    fun getParameters(type: String): List<ConfigParameter>
    fun getRequiredParameters(type: String): List<ConfigParameter>
    fun getOptionalParameters(type: String): List<ConfigParameter>
    fun getParameterInfo(type: String, paramName: String): ConfigParameter?
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

Use the `Builder` to set the `type` (e.g., `jena:memory`, `rdf4j:native`, `sparql`) and provider-specific params. The builder constructs an `RdfApi` with a `repository` instance.

### Formats
`RdfFormats` defines common names. Pass strings like `"TURTLE"`, `"NTRIPLES"`, `"RDFXML"`, `"JSONLD"`, `"TRIG"`, `"NQUADS"` to `readGraph`/`writeGraph`.




