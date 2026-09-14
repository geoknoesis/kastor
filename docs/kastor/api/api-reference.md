# 📖 API Reference

Complete reference documentation for the Kastor RDF API.

**Terms:** Shared vocabulary (**repository**, **graph**, **literal**, **provider**, …) lives in the [**Glossary**](../concepts/glossary.md). For how reference pages relate to tutorials and how-tos, see [**How documentation fits together**](../getting-started/documentation-guide.md).

## 📋 Table of Contents

- [Core Interfaces](#-core-interfaces)
- [Data Classes](#-data-classes)
- [Factory Methods](#-factory-methods)
- [DSL Classes](#-dsl-classes)
- [DSL Functions](#-dsl-functions)
- [Extension Functions](#-extension-functions)
- [Exception Classes](#-exception-classes)
- [Configuration](#-configuration)
- [Query Results](#-query-results)
- [Performance](#-performance)

## 🎯 Core Interfaces

### RdfRepository

The main interface for RDF repository operations.

```kotlin
interface RdfRepository : Dataset, SparqlMutable {
    val defaultGraph: RdfGraph

    // Query operations
    fun select(query: SparqlSelect): SparqlQueryResult
    fun ask(query: SparqlAsk): Boolean
    fun construct(query: SparqlConstruct): Sequence<RdfTriple>
    fun describe(query: SparqlDescribe): Sequence<RdfTriple>
    fun update(query: UpdateQuery)

    // Transaction operations
    fun transaction(operations: RdfRepository.() -> Unit)
    fun readTransaction(operations: RdfRepository.() -> Unit)

    // Graph operations
    fun getGraph(graphName: Iri): RdfGraph
    fun createGraph(graphName: Iri): RdfGraph
    fun listGraphs(): List<Iri>
    fun removeGraph(graphName: Iri): Boolean
    fun editDefaultGraph(): MutableRdfGraph
    fun editGraph(graphName: Iri): MutableRdfGraph

    // Utility operations
    fun clear(): Boolean
    fun isClosed(): Boolean
    fun getCapabilities(): ProviderCapabilities
}
```

### RdfGraph

Read-only graph interface.

```kotlin
interface RdfGraph {
    fun hasTriple(triple: RdfTriple): Boolean
    fun getTriples(): List<RdfTriple>
    fun size(): Int
}
```

### MutableRdfGraph

Mutable RDF graph operations. Provides both read and write operations.

```kotlin
interface MutableRdfGraph : RdfGraph {
    fun addTriple(triple: RdfTriple)
    fun addTriples(triples: Collection<RdfTriple>)
    fun removeTriple(triple: RdfTriple): Boolean
    fun removeTriples(triples: Collection<RdfTriple>): Boolean
    fun clear(): Boolean
}
```

### RdfProvider

Interface for creating RDF repositories.

```kotlin
interface RdfProvider {
    val id: String
    val name: String
    val version: String
    fun variants(): List<RdfVariant>
    fun defaultVariantId(): String
    fun createRepository(variantId: String, config: RdfConfig): RdfRepository
    fun getCapabilities(variantId: String? = null): ProviderCapabilities
    val priority: Int                       // selection order when no providerId is given (default 0)
    fun supportsInputFormat(format: String): Boolean
    fun supportsOutputFormat(format: String): Boolean
}
```

## 📊 Data Classes

### RdfTriple

Represents an RDF triple.

```kotlin
data class RdfTriple(
    val subject: RdfResource,
    val predicate: Iri,
    val obj: RdfTerm
)
```

### RdfConfig

Configuration for RDF repositories.

```kotlin
data class RdfConfig(
    val providerId: String? = null,
    val variantId: String? = null,
    val options: Map<String, String> = emptyMap(),
    val requirements: ProviderRequirements? = null
)
```

### RdfVariant

A provider variant, listed by `RdfProvider.variants()`. Pass `id` as `variantId`.

```kotlin
data class RdfVariant(
    val id: String,
    val description: String = "",
    val defaultOptions: Map<String, String> = emptyMap()
)
```

### SubjectPredicateChain

Returned by `subject has predicate` and `subject - predicate` inside the DSLs, and completed by `with value` or `- value`.

## 🏭 Factory Methods

### Rdf Object

Main entry point for creating repositories and parsing.

```kotlin
object Rdf {
    // SPARQL-capable factories: Jena, else RDF4J; throw RdfProviderException if neither is present
    fun memory(): RdfRepository
    fun memoryWithInference(): RdfRepository
    fun persistent(location: String = "data"): RdfRepository

    // Configurable factory
    fun repository(configure: RdfRepositoryBuilder.() -> Unit): RdfRepository
    fun repository(registry: ProviderRegistry, configure: RdfRepositoryBuilder.() -> Unit): RdfRepository

    // Graphs and parsing
    fun graph(configure: GraphDsl.() -> Unit): MutableRdfGraph
    fun parse(data: String, format: String = "TURTLE"): MutableRdfGraph
    fun parseFromUrl(url: String, format: String = "TURTLE", options: UrlLoadOptions = UrlLoadOptions.DEFAULT): MutableRdfGraph

    // Default provider, used when a configuration names neither a provider nor requirements
    fun setDefaultProvider(provider: String)
    fun getDefaultProvider(): String
}
```

### RdfRepositoryBuilder

Builder used by `Rdf.repository { }`.

```kotlin
class RdfRepositoryBuilder {
    var providerId: String?                  // honoured exactly: an unknown provider/variant throws IllegalArgumentException
    var variantId: String?
    var requirements: ProviderRequirements   // used for priority-based selection when providerId is null
    var location: String?                    // storage location for persistent variants
    var inference: Boolean
    var registry: ProviderRegistry
    fun build(): RdfRepository
}
```

## 🎨 DSL Classes

### TripleDsl / GraphDsl

`repo.add { }` uses `TripleDsl` and `Rdf.graph { }` uses `GraphDsl`. Both extend `TripleBuilderDsl`:

```kotlin
abstract class TripleBuilderDsl<D : TripleBuilderDsl<D>> {
    val triples: List<RdfTriple>                                  // read-only view of the collected triples

    // Prefixes and QNames (built in: rdf, rdfs, owl, sh, xsd, obo, skos, prov, dcat, dcterms, void, geo, time)
    fun prefixes(configure: MutableMap<String, String>.() -> Unit)
    fun prefix(name: String, namespace: String)
    fun qname(iriOrQName: String): Iri

    // Ultra-compact syntax (predicates are Iri values)
    operator fun RdfResource.set(predicate: Iri, value: RdfTerm)  // also String, Int, Long, Double, Float, Boolean, RdfResource

    // Natural language syntax
    infix fun RdfResource.has(predicate: Iri): SubjectPredicateChain
    infix fun SubjectPredicateChain.with(value: RdfTerm)          // also String, Int, Long, Double, Float, Boolean, RdfResource
    infix fun RdfResource.`is`(type: RdfResource)                  // rdf:type

    // Minus operator
    infix operator fun RdfResource.minus(predicate: Iri): SubjectPredicateChain
    infix operator fun SubjectPredicateChain.minus(value: RdfTerm) // see Minus Operator Overloads below

    // Explicit triples, literals and RDF 1.2 reifiers
    fun triple(subject: RdfResource, predicate: Iri, obj: RdfTerm)
    fun addTriples(newTriples: Collection<RdfTriple>)
    fun lang(value: String, language: String): Literal
    fun reifies(subject: RdfResource, predicate: Iri, obj: RdfTerm /* , reifier, configure */): RdfResource
}
```

Blank nodes created by the DSLs get opaque, run-unique labels (`b_<run>_<n>`), so separate `add { }` calls never share blank nodes.

## 🎯 DSL Functions

### Multiple Values Functions

The DSL provides intuitive functions for creating multiple triples and RDF lists:

```kotlin
// Create multiple individual triples using curly braces syntax
fun values(vararg values: RdfTerm): MultipleIndividualValues   // also String, Int, Long, Double, Float, Boolean

// Create RDF lists using parentheses syntax  
fun list(vararg values: RdfTerm): RdfListValues               // same overloads as values()

// Create RDF containers
fun bag(vararg values: RdfTerm): RdfBagValues     // rdf:Bag (same overloads)
fun seq(vararg values: RdfTerm): RdfSeqValues     // rdf:Seq (same overloads)
fun alt(vararg values: RdfTerm): RdfAltValues     // rdf:Alt (same overloads)
```

**Examples:**

```kotlin
// Multiple individual triples
person - FOAF.knows - values(friend1, friend2, friend3)
// Creates: person knows friend1, person knows friend2, person knows friend3

// RDF List
person - FOAF.mbox - list("alice@example.com", "alice@work.com")
// Creates: person mbox -> RDF List with proper rdf:first, rdf:rest, rdf:nil structure

// Mixed types: pass RdfTerm values
person - DCTERMS.subject - values(string("Technology"), string("Programming"), int(42), boolean(true))
// Creates individual triples with proper type conversion

// RDF Bag (unordered, duplicates allowed)
person - DCTERMS.subject - bag("Technology", "AI", "RDF", "Technology")
// Creates: person subject -> rdf:Bag with rdf:_1, rdf:_2, rdf:_3, rdf:_4

// RDF Seq (ordered container)
person - FOAF.knows - seq(friend1, friend2, friend3)
// Creates: person knows -> rdf:Seq with rdf:_1, rdf:_2, rdf:_3

// RDF Alt (alternative options)
person - FOAF.mbox - alt("alice@example.com", "alice@work.com")
// Creates: person mbox -> rdf:Alt with rdf:_1, rdf:_2
```

### Minus Operator Overloads

The minus operator (`-`) supports multiple value types:

```kotlin
// Single values
infix operator fun SubjectPredicateChain.minus(value: String): Unit
infix operator fun SubjectPredicateChain.minus(value: Int): Unit
infix operator fun SubjectPredicateChain.minus(value: Double): Unit
infix operator fun SubjectPredicateChain.minus(value: Boolean): Unit
infix operator fun SubjectPredicateChain.minus(value: RdfTerm): Unit

// Multiple individual values
infix operator fun SubjectPredicateChain.minus(values: MultipleIndividualValues): Unit

// RDF lists
infix operator fun SubjectPredicateChain.minus(values: RdfListValues): Unit

// RDF containers
infix operator fun SubjectPredicateChain.minus(values: RdfBagValues): Unit
infix operator fun SubjectPredicateChain.minus(values: RdfSeqValues): Unit
infix operator fun SubjectPredicateChain.minus(values: RdfAltValues): Unit

// Arrays (create individual triples)
infix operator fun SubjectPredicateChain.minus(values: Array<out RdfTerm>): Unit  // also Array<String|Int|Long|Double|Float|Boolean>

// Lists (create an RDF List)
infix operator fun SubjectPredicateChain.minus(values: List<out RdfTerm>): Unit   // also List<String|Int|Long|Double|Float|Boolean>
```

### Container Classes

```kotlin
// Container for multiple individual values
class MultipleIndividualValues(val values: List<RdfTerm>)

// Container for RDF list values
class RdfListValues(val values: List<RdfTerm>)

// Containers for RDF containers
class RdfBagValues(val values: List<RdfTerm>)     // rdf:Bag
class RdfSeqValues(val values: List<RdfTerm>)     // rdf:Seq
class RdfAltValues(val values: List<RdfTerm>)     // rdf:Alt
```

## 🔧 Extension Functions

### Number Extensions

```kotlin
fun Int.toLiteral(): Literal
fun Long.toLiteral(): Literal
fun Double.toLiteral(): Literal
fun Float.toLiteral(): Literal
fun Boolean.toLiteral(): Literal
```

### Repository Extensions

```kotlin
fun RdfRepository.add(configure: TripleDsl.() -> Unit)
fun RdfRepository.addToGraph(graphName: Iri, configure: TripleDsl.() -> Unit)
fun RdfRepository.addTriple(triple: RdfTriple)
fun RdfRepository.addTriples(triples: Collection<RdfTriple>)
fun RdfRepository.addTriples(graphName: Iri?, triples: Collection<RdfTriple>)
fun RdfRepository.addTriple(graphName: Iri?, triple: RdfTriple)
fun RdfRepository.removeTriple(triple: RdfTriple): Boolean
fun RdfRepository.removeTriples(triples: Collection<RdfTriple>): Boolean
fun RdfRepository.hasTriple(triple: RdfTriple): Boolean
fun RdfRepository.getTriples(): List<RdfTriple>
```

### Triple DSL Members

`triple(subject, predicate: Iri, obj: RdfTerm)` and `addTriples(...)` are members of the DSL receiver (see [TripleDsl / GraphDsl](#tripledsl--graphdsl)). There are no string-predicate overloads and no `->` operator; build standalone triples with `RdfTriple(subject, predicate, obj)`.

## 🚨 Exception Classes

All RDF exceptions extend the sealed `RdfException` (package `com.geoknoesis.kastor.rdf`), which carries an `errorCode: RdfErrorCode` and an optional `context` map.

```kotlin
sealed class RdfException(message: String, val errorCode: RdfErrorCode = RdfErrorCode.UNKNOWN_ERROR, cause: Throwable? = null) : Exception(message, cause)

class RdfQueryException(
    message: String,
    errorCode: RdfErrorCode = RdfErrorCode.QUERY_EXECUTION_ERROR,
    val query: String? = null,
    val bindings: Map<String, RdfTerm>? = null,
    cause: Throwable? = null
) : RdfException(message, errorCode, cause)

// Same (message, errorCode, cause) shape:
class RdfTransactionException
class RdfProviderException
class RdfValidationException
class RdfRepositoryException
class RdfGraphException
class RdfFederationException
class RdfInferenceException
class RdfConfigurationException

sealed class RdfFormatException   // ParseError(parseError), UnsupportedFormat(format, availableFormats), Generic(message)
```

URL loading can also throw `RdfInputTooLargeException` (an `IOException`) when a body exceeds `UrlLoadOptions.maxBytes`, and invalid configurations or disallowed URL schemes throw `IllegalArgumentException`.

## ⚙️ Configuration

### Supported Repository Types

#### Jena Backend

```kotlin
// In-memory repository
providerId = "jena", variantId = "memory"

// TDB2 persistent repository
providerId = "jena", variantId = "tdb2"

// In-memory with inference
providerId = "jena", variantId = "memory-inference"
```

#### RDF4J Backend

```kotlin
// In-memory repository
providerId = "rdf4j", variantId = "memory"

// Native persistent repository
providerId = "rdf4j", variantId = "native"

// SPARQL endpoint
providerId = "sparql", variantId = "sparql"
```

### Configuration Parameters

#### Jena TDB2

```kotlin
RdfConfig(
    providerId = "jena",
    variantId = "tdb2",
    options = mapOf("location" to "/path/to/storage")   // the only option read by the Jena provider
)
```

#### RDF4J Native

```kotlin
RdfConfig(
    providerId = "rdf4j",
    variantId = "native",
    options = mapOf(
        "location" to "/path/to/storage",
        "lenientRead" to "true"   // optional: skip statements Kastor cannot represent on graph reads (default: strict)
    )
)
```

#### SPARQL Endpoint

```kotlin
RdfConfig(
    providerId = "sparql",
    variantId = "sparql",
    options = mapOf(
        "location" to "https://dbpedia.org/sparql",
        "updateLocation" to "https://example.org/update",
        "requestTimeoutMillis" to "30000"
    )   // all options: see providers/sparql.md
)
```

## 📊 Query Results

### QueryResult

Interface for SPARQL query results.

```kotlin
interface SparqlQueryResult : Iterable<BindingSet> {
    fun first(): BindingSet?
    fun toList(): List<BindingSet>
    fun asSequence(): Sequence<BindingSet>
}
```

### BindingSet

Interface for individual query result bindings.

```kotlin
interface BindingSet {
    fun get(variable: String): RdfTerm?
    fun getVariableNames(): Set<String>
    fun hasBinding(variable: String): Boolean
    fun getString(variable: String): String?
    fun getInt(variable: String): Int?
    fun getDouble(variable: String): Double?
    fun getBoolean(variable: String): Boolean?
    fun getStringOr(variable: String, default: String): String
    fun getIntOr(variable: String, default: Int): Int
    fun getDoubleOr(variable: String, default: Double): Double
    fun getBooleanOr(variable: String, default: Boolean): Boolean
    fun getStringOrThrow(variable: String): String
    fun getIntOrThrow(variable: String): Int
}
```

Query results provide `first()`, `toList()`, and `asSequence()` for common access patterns.

## 🔍 RDF Terms

### Iri

Represents an Internationalized Resource Identifier.

```kotlin
value class Iri(val value: String) : RdfResource
```

### Literal

Represents an RDF literal value.

```kotlin
sealed interface Literal : RdfTerm {
    val lexical: String
    val datatype: Iri
}
```

### Literal Factory Functions

```kotlin
fun string(value: String): Literal
fun lang(value: String, lang: String): Literal                       // tag validated and lower-cased
fun lang(value: String, lang: String, direction: Direction): Literal
fun int(value: Int): Literal
fun decimal(value: BigDecimal): Literal                               // also Double, Float
fun boolean(value: Boolean): Literal
Literal(lexical: String, datatype: Iri = XSD.string)                  // lexical form preserved exactly
fun Literal.booleanValue(): Boolean?                                  // "true"/"1" -> true, "false"/"0" -> false
fun normalizeLanguageTag(tag: String): String
```

## 🎯 Convenience Functions

### Global Functions

```kotlin
fun iri(value: String): Iri
fun bnode(id: String): BlankNode
fun string(value: String): Literal
fun lang(value: String, lang: String): Literal
fun int(value: Int): Literal
fun decimal(value: Double): Literal
fun boolean(value: Boolean): Literal
fun quoted(triple: RdfTriple): TripleTerm
fun var_(name: String): Var
```

Standalone triples are built with `RdfTriple(subject, predicate, obj)`; inside the DSLs use `triple(...)` or the infix syntax.

## 📚 Registry

### RdfProviderRegistry

Default registry used by the factory DSL and `Rdf.repository`. You can supply a custom
registry for tests or isolation by passing a registry instance to `Rdf.repository(...)`
or by swapping the delegate.

```kotlin
interface ProviderRegistry {
    fun discoverProviders(): List<RdfProvider>
    fun getProvider(providerId: String): RdfProvider?
    fun getSupportedTypes(): List<String>
    fun supports(providerId: String): Boolean
    fun supportsVariant(providerId: String, variantId: String): Boolean
    fun selectProvider(requirements: ProviderRequirements): ProviderSelection?
    fun register(provider: RdfProvider)
    fun create(config: RdfConfig): RdfRepository
}
```

```kotlin
// Use a custom registry for tests
val registry = DefaultProviderRegistry(autoDiscover = false)
registry.register(CustomProvider())
val repo = Rdf.repository(registry) {
    providerId = "custom"
    variantId = "memory"
}
```

## 🔧 Error Handling

### Exception Hierarchy

```kotlin
RdfException (sealed base)
├── RdfQueryException
├── RdfTransactionException
├── RdfProviderException
├── RdfValidationException
├── RdfFormatException (ParseError, UnsupportedFormat, Generic)
├── RdfRepositoryException
├── RdfGraphException
├── RdfFederationException
├── RdfInferenceException
└── RdfConfigurationException
```

### Best Practices

1. **Always close repositories** using `use` or `close()`
2. **Handle exceptions** with try-catch blocks
3. **Use transactions** for atomic operations
4. **Check return values** for null safety
5. **Validate inputs** before operations

## 📖 Usage Examples

### Basic Usage

```kotlin
// Create repository
val repo = Rdf.memory()
val namePred = iri("http://example.org/person/name")
val agePred = iri("http://example.org/person/age")

// Add data
repo.add {
    val person = iri("http://example.org/person/alice")
    person[namePred] = "Alice"
    person[agePred] = 30
}

// Query data
val results = repo.select(SparqlSelectQuery("""
    SELECT ?name ?age WHERE { 
        ?person ${namePred} ?name ;
                ${agePred} ?age 
    }
"""))

// Process results
results.forEach { binding ->
    println("${binding.getString("name")} is ${binding.getInt("age")} years old")
}

// Clean up
repo.close()
```

### Advanced Usage

```kotlin
// Repository operations
val repo = Rdf.memory()
val namePred = iri("http://example.org/person/name")
repo.add {
    val person = iri("http://example.org/person/alice")
    person[namePred] = "Alice"
}

val results = repo.select(SparqlSelectQuery("SELECT ?name WHERE { ?person ${namePred} ?name }"))
results.forEach { binding ->
    println("Found: ${binding.getString("name")}")
}

repo.clear()
repo.close()

// Performance monitoring
val started = System.nanoTime()
repo.select(SparqlSelectQuery("""
    SELECT ?name WHERE { ?person ${namePred} ?name }
"""))
val durationMs = (System.nanoTime() - started) / 1_000_000
println("Query took: ${durationMs}ms")

// Bulk operations
repo.add {
    for (i in 1..10000) {
        val person = iri("http://example.org/person/person$i")
        person[namePred] = "Person $i"
    }
}
```

## 🎯 Next Steps

- **[Quick Start Guide](../getting-started/quick-start.md)** - Get started quickly
- **[Examples Guide](../examples/README.md)** - See real-world usage
- **[Compact DSL Guide](compact-dsl-guide.md)** - DSL syntaxes and patterns

## 📞 Need Help?

- **Documentation**: [Documentation hub](../../README.md)
- **Examples**: [Examples](../examples/README.md)
- **Issues**: [GitHub Issues](https://github.com/geoknoesis/kastor/issues)
- **Discussions**: [GitHub Discussions](https://github.com/geoknoesis/kastor/discussions)

---

**🎉 This completes the comprehensive API reference for Kastor RDF!**



