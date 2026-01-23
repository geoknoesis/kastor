# 📖 API Reference

Complete reference documentation for the Kastor RDF API.

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
interface RdfRepository : Closeable {
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
    fun editDefaultGraph(): GraphEditor
    fun editGraph(graphName: Iri): GraphEditor

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

### GraphEditor

Mutable graph operations.

```kotlin
interface GraphEditor {
    fun addTriple(triple: RdfTriple)
    fun addTriples(triples: Collection<RdfTriple>)
    fun removeTriple(triple: RdfTriple): Boolean
    fun removeTriples(triples: Collection<RdfTriple>): Boolean
    fun clear(): Boolean
}
```

### MutableRdfGraph

```kotlin
interface MutableRdfGraph : RdfGraph, GraphEditor
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
}
```

### RepositoryManager

Interface for managing multiple repositories.

```kotlin
interface RepositoryManager : Closeable {
    fun createRepository(name: String, config: RdfConfig): RdfRepository
    fun getRepository(name: String): RdfRepository?
    fun listRepositories(): List<String>
    fun removeRepository(name: String)
    fun federatedQuery(sparql: String, repositories: List<String>): SparqlQueryResult
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

### RdfConfigVariant

Describes available configuration options.

```kotlin
data class RdfConfigVariant(
    val name: String,
    val description: String,
    val parameters: List<RdfConfigParam> = emptyList()
)
```

### RdfConfigParam

Describes a configuration parameter.

```kotlin
data class RdfConfigParam(
    val name: String,
    val description: String,
    val required: Boolean = false,
    val defaultValue: Any? = null,
    val type: String = "String"
)
```

### SubjectAndPredicate

Helper class for DSL operations.

```kotlin
data class SubjectAndPredicate(
    val subject: RdfResource,
    val predicate: Iri
)
```

## 🏭 Factory Methods

### Rdf Object

Main entry point for creating repositories.

```kotlin
object Rdf {
    // Simple factory methods
    fun memory(): RdfRepository
    fun persistent(name: String): RdfRepository
    fun memoryWithInference(): RdfRepository
    
    // Advanced factory
    fun repository(configure: RdfRepositoryBuilder.() -> Unit): RdfRepository
    
    // Manager factory
    fun manager(configure: ManagerBuilder.() -> Unit): RepositoryManager
    
    // Registry
    val registry: RdfProviderRegistry
}
```

### RdfRepositoryBuilder

Builder for configuring individual repositories.

```kotlin
class RdfRepositoryBuilder {
    var type: String = "memory"
    val params: MutableMap<String, Any> = mutableMapOf()
    
    fun param(name: String, value: Any)
    fun build(): RdfRepository
}
```

### ManagerBuilder

Builder for configuring repository managers.

```kotlin
class ManagerBuilder {
    val repositories: MutableMap<String, RdfConfig> = mutableMapOf()
    
    fun repository(name: String, configure: RepositoryBuilder.() -> Unit)
    fun build(): RepositoryManager
}
```

## 🎨 DSL Classes

### TripleDsl

DSL for building RDF triples.

```kotlin
class TripleDsl {
    val triples: MutableList<RdfTriple>
    
    // Ultra-compact syntax
    operator fun RdfResource.set(predicate: Iri, value: RdfTerm)
    operator fun RdfResource.set(predicate: Iri, value: String)
    operator fun RdfResource.set(predicate: Iri, value: Int)
    operator fun RdfResource.set(predicate: Iri, value: Double)
    operator fun RdfResource.set(predicate: Iri, value: Boolean)
    operator fun RdfResource.set(predicate: String, value: RdfTerm)
    operator fun RdfResource.set(predicate: String, value: String)
    operator fun RdfResource.set(predicate: String, value: Int)
    operator fun RdfResource.set(predicate: String, value: Double)
    operator fun RdfResource.set(predicate: String, value: Boolean)
    
    // Natural language syntax
    infix fun RdfResource.has(predicate: Iri): SubjectAndPredicate
    infix fun SubjectAndPredicate.with(obj: RdfTerm): RdfTriple
    infix fun SubjectAndPredicate.with(value: String): RdfTriple
    infix fun SubjectAndPredicate.with(value: Int): RdfTriple
    infix fun SubjectAndPredicate.with(value: Double): RdfTriple
    infix fun SubjectAndPredicate.with(value: Boolean): RdfTriple
    
    // Generic infix operator
    infix fun RdfResource.`is`(predicate: Iri): SubjectAndPredicate
}
```

## 🎯 DSL Functions

### Multiple Values Functions

The DSL provides intuitive functions for creating multiple triples and RDF lists:

```kotlin
// Create multiple individual triples using curly braces syntax
fun values(vararg values: Any): MultipleIndividualValues

// Create RDF lists using parentheses syntax  
fun list(vararg values: Any): RdfListValues

// Create RDF containers
fun bag(vararg values: Any): RdfBagValues     // rdf:Bag
fun seq(vararg values: Any): RdfSeqValues     // rdf:Seq
fun alt(vararg values: Any): RdfAltValues     // rdf:Alt
```

**Examples:**

```kotlin
// Multiple individual triples
person - FOAF.knows - values(friend1, friend2, friend3)
// Creates: person knows friend1, person knows friend2, person knows friend3

// RDF List
person - FOAF.mbox - list("alice@example.com", "alice@work.com")
// Creates: person mbox -> RDF List with proper rdf:first, rdf:rest, rdf:nil structure

// Mixed types
person - DCTERMS.subject - values("Technology", "Programming", 42, true)
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

// Arrays (creates individual triples)
infix operator fun SubjectPredicateChain.minus(array: Array<*>): Unit

// Lists (creates RDF List)
infix operator fun SubjectPredicateChain.minus(list: List<*>): Unit
```

### Container Classes

```kotlin
// Container for multiple individual values
class MultipleIndividualValues(val values: List<Any>)

// Container for RDF list values
class RdfListValues(val values: List<Any>)

// Containers for RDF containers
class RdfBagValues(val values: List<Any>)     // rdf:Bag
class RdfSeqValues(val values: List<Any>)     // rdf:Seq
class RdfAltValues(val values: List<Any>)     // rdf:Alt
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
fun RdfRepository.addTriple(graphName: Iri?, triple: RdfTriple)
fun RdfRepository.removeTriple(triple: RdfTriple): Boolean
fun RdfRepository.removeTriples(triples: Collection<RdfTriple>): Boolean
fun RdfRepository.hasTriple(triple: RdfTriple): Boolean
fun RdfRepository.getTriples(): List<RdfTriple>
```

### Graph Extensions

```kotlin
```

### Triple DSL Extensions

```kotlin
fun TripleDsl.addTriples(triples: Collection<RdfTriple>)
fun TripleDsl.triple(subject: RdfResource, predicate: Iri, obj: RdfTerm)
fun TripleDsl.triple(subject: RdfResource, predicate: String, obj: RdfTerm)
fun TripleDsl.triple(subject: RdfResource, predicate: Iri, obj: String)
fun TripleDsl.triple(subject: RdfResource, predicate: Iri, obj: Int)
fun TripleDsl.triple(subject: RdfResource, predicate: Iri, obj: Double)
fun TripleDsl.triple(subject: RdfResource, predicate: Iri, obj: Boolean)
```

### Operator Overloads

```kotlin
infix fun RdfResource.`->`(pair: Pair<Iri, RdfTerm>): RdfTriple
infix fun RdfResource.`->`(pair: Pair<String, RdfTerm>): RdfTriple
infix fun RdfResource.`->`(pair: Pair<Iri, String>): RdfTriple
infix fun RdfResource.`->`(pair: Pair<String, String>): RdfTriple
```

## 🚨 Exception Classes

### RdfException

Base exception for RDF operations.

```kotlin
sealed class RdfException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

### RdfQueryException

Exception for query-related errors.

```kotlin
class RdfQueryException(message: String, cause: Throwable? = null) : RdfException(message, cause)
```

### RdfUpdateException

Exception for update operation errors.

```kotlin
class RdfUpdateException(message: String, cause: Throwable? = null) : RdfException(message, cause)
```

### RdfTransactionException

Exception for transaction-related errors.

```kotlin
class RdfTransactionException(message: String, cause: Throwable? = null) : RdfException(message, cause)
```

### RdfConfigurationException

Exception for configuration errors.

```kotlin
class RdfConfigurationException(message: String, cause: Throwable? = null) : RdfException(message, cause)
```

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
    options = mapOf(
        "location" to "/path/to/storage",
        "syncMode" to "WRITE_METADATA",
        "unionDefaultGraph" to "true"
    )
)
```

#### RDF4J Native

```kotlin
RdfConfig(
    providerId = "rdf4j",
    variantId = "native",
    options = mapOf(
        "location" to "/path/to/storage",
        "syncDelay" to "1000",
        "tripleIndexes" to "spoc,posc,psoc"
    )
)
```

#### SPARQL Endpoint

```kotlin
RdfConfig(
    providerId = "sparql",
    variantId = "sparql",
    options = mapOf(
        "queryEndpoint" to "https://dbpedia.org/sparql",
        "updateEndpoint" to "https://example.org/update",
        "timeout" to "30000"
    )
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
data class Iri(val value: String) : RdfResource {
    fun isValid(): Boolean
    fun resolve(relative: String): Iri
    fun relativize(base: Iri): String?
}
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
fun lang(value: String, lang: String): Literal
fun int(value: Int): Literal
fun double(value: Double): Literal
fun decimal(value: Double): Literal
fun boolean(value: Boolean): Literal
fun Literal(lexical: String, datatype: Iri = XSD.string): Literal
```

## 🎯 Convenience Functions

### Global Functions

```kotlin
fun resource(iri: String): RdfResource
fun iri(value: String): Iri
fun string(value: String): Literal
fun lang(value: String, lang: String): Literal
fun int(value: Int): Literal
fun double(value: Double): Literal
fun decimal(value: Double): Literal
fun boolean(value: Boolean): Literal
fun triple(subject: RdfResource, predicate: Iri, obj: RdfTerm): RdfTriple
fun triple(subject: RdfResource, predicate: String, obj: RdfTerm): RdfTriple
fun triple(subject: RdfResource, predicate: Iri, obj: String): RdfTriple
fun triple(subject: RdfResource, predicate: Iri, obj: Int): RdfTriple
fun triple(subject: RdfResource, predicate: Iri, obj: Double): RdfTriple
fun triple(subject: RdfResource, predicate: Iri, obj: Boolean): RdfTriple
```

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
RdfException (base)
├── RdfQueryException
├── RdfUpdateException
├── RdfTransactionException
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

- **[Quick Start Guide](quick-start.md)** - Get started quickly
- **[Examples Guide](examples.md)** - See real-world usage
- **[Super Sleek API Guide](super-sleek-api-guide.md)** - Advanced features
- **[Compact DSL Guide](compact-dsl-guide.md)** - DSL syntaxes

## 📞 Need Help?

- **Documentation**: [docs/](docs/)
- **Examples**: [examples/](examples/)
- **Issues**: [GitHub Issues](https://github.com/geoknoesis/kastor-rdf/issues)
- **Discussions**: [GitHub Discussions](https://github.com/geoknoesis/kastor-rdf/discussions)

---

**🎉 This completes the comprehensive API reference for Kastor RDF!**



