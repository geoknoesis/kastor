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
    // Basic operations
    fun add(configure: TripleDsl.() -> Unit)
    fun addToGraph(graphName: Iri, configure: TripleDsl.() -> Unit)
    fun addTriples(triples: Collection<RdfTriple>)
    fun addTriple(triple: RdfTriple)
    
    // Query operations
    fun query(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap()): QueryResult
    fun ask(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap()): Boolean
    fun construct(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap()): List<RdfTriple>
    fun describe(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap()): List<RdfTriple>
    fun update(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap())
    
    // Transaction operations
    fun transaction(operations: RdfRepository.() -> Unit)
    fun readTransaction(operations: RdfRepository.() -> Unit)
    
    // Graph operations
    fun getGraph(graphName: Iri): RdfGraph
    fun createGraph(graphName: Iri): RdfGraph
    fun listGraphs(): List<Iri>
    fun removeGraph(graphName: Iri)
    
    // Utility operations
    fun clear()
    fun isEmpty(): Boolean
    fun size(): Long
    fun isClosed(): Boolean
    
    // Statistics and monitoring
    fun getStatistics(): RepositoryStatistics
    fun getPerformanceMonitor(): PerformanceMonitor
}
```

### RdfGraph

Interface for managing named graphs.

```kotlin
interface RdfGraph {
    val name: Iri
    
    fun addTriples(triples: Collection<RdfTriple>)
    fun addTriple(triple: RdfTriple)
    fun removeTriples(triples: Collection<RdfTriple>)
    fun removeTriple(triple: RdfTriple)
    fun clear()
    fun isEmpty(): Boolean
    fun size(): Long
}
```

### RdfApiProvider

Interface for creating RDF repositories.

```kotlin
interface RdfApiProvider {
    fun create(config: RdfConfig): RdfRepository
    fun getSupportedTypes(): List<String>
    fun getConfigVariants(): List<RdfConfigVariant>
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
    fun federatedQuery(sparql: String, repositories: List<String>): QueryResult
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
    val type: String,
    val params: Map<String, Any> = emptyMap()
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

### RepositoryStatistics

Repository performance and usage statistics.

```kotlin
data class RepositoryStatistics(
    val totalTriples: Long,
    val graphCount: Int,
    val sizeBytes: Long,
    val lastModified: Long,
    val queryCount: Long,
    val averageQueryTime: Double
)
```

### PerformanceMonitor

Performance monitoring data.

```kotlin
data class PerformanceMonitor(
    val queryCount: Long,
    val averageQueryTime: Double,
    val cacheHitRate: Double,
    val memoryUsage: Long,
    val diskUsage: Long
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
    fun factory(configure: RepositoryBuilder.() -> Unit): RdfRepository
    
    // Manager factory
    fun manager(configure: ManagerBuilder.() -> Unit): RepositoryManager
    
    // Registry
    val registry: RdfApiRegistry
}
```

### RepositoryBuilder

Builder for configuring individual repositories.

```kotlin
class RepositoryBuilder {
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

### RdfOperations

Fluent interface for repository operations.

```kotlin
class RdfOperations(private val repo: RdfRepository) {
    fun add(configure: TripleDsl.() -> Unit): RdfOperations
    fun addToGraph(graphName: Iri, configure: TripleDsl.() -> Unit): RdfOperations
    fun query(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap()): QueryResult
    fun ask(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap()): Boolean
    fun construct(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap()): List<RdfTriple>
    fun describe(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap()): List<RdfTriple>
    fun update(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap()): RdfOperations
    fun transaction(operations: RdfRepository.() -> Unit): RdfOperations
    fun readTransaction(operations: RdfRepository.() -> Unit): RdfOperations
    fun clear(): RdfOperations
    fun statistics(): RepositoryStatistics
    fun performance(): PerformanceMonitor
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

### String Extensions

```kotlin
fun String.toIri(): Iri
fun String.toResource(): RdfResource
fun String.toLiteral(): RdfLiteral
```

### Number Extensions

```kotlin
fun Int.toLiteral(): RdfLiteral
fun Double.toLiteral(): RdfLiteral
fun Boolean.toLiteral(): RdfLiteral
```

### Repository Extensions

```kotlin
fun RdfRepository.fluent(): RdfOperations
fun RdfRepository.queryFirst(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap()): BindingSet?
fun RdfRepository.queryList(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap()): List<BindingSet>
fun RdfRepository.queryMap(sparql: String, keySelector: (BindingSet) -> String, valueSelector: (BindingSet) -> String, initialBindings: Map<String, RdfTerm> = emptyMap()): Map<String, String>
fun RdfRepository.queryTimed(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap()): Pair<QueryResult, Duration>
fun RdfRepository.operationTimed(operations: RdfRepository.() -> Unit): Pair<Unit, Duration>
fun RdfRepository.addBatch(batchSize: Int = 1000, configure: TripleDsl.() -> Unit)
fun RdfRepository.addBatchToGraph(graphName: Iri, batchSize: Int = 1000, configure: TripleDsl.() -> Unit)
fun RdfRepository.hasTriples(): Boolean
fun RdfRepository.tripleCount(): Int
fun RdfRepository.sizeFormatted(): String
fun RdfRepository.statisticsFormatted(): String
```

### Graph Extensions

```kotlin
fun RdfGraph.addTriplesAndReturn(triples: Collection<RdfTriple>): RdfGraph
fun RdfGraph.removeTriplesAndReturn(triples: Collection<RdfTriple>): RdfGraph
fun RdfGraph.clearAndReturn(): RdfGraph
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

### Query Result Extensions

```kotlin
inline fun <reified T> QueryResult.firstAs(): T?
inline fun <reified T> QueryResult.mapAs(): List<T>
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
"jena:memory"

// TDB2 persistent repository
"jena:tdb2"

// In-memory with inference
"jena:memory:inference"
```

#### RDF4J Backend

```kotlin
// In-memory repository
"rdf4j:memory"

// Native persistent repository
"rdf4j:native"

// SPARQL endpoint
"rdf4j:sparql"
```

### Configuration Parameters

#### Jena TDB2

```kotlin
RdfConfig(
    type = "jena:tdb2",
    params = mapOf(
        "location" to "/path/to/storage",
        "syncMode" to "WRITE_METADATA",
        "unionDefaultGraph" to true
    )
)
```

#### RDF4J Native

```kotlin
RdfConfig(
    type = "rdf4j:native",
    params = mapOf(
        "location" to "/path/to/storage",
        "syncDelay" to 1000L,
        "tripleIndexes" to listOf("spoc", "posc", "psoc")
    )
)
```

#### SPARQL Endpoint

```kotlin
RdfConfig(
    type = "rdf4j:sparql",
    params = mapOf(
        "queryEndpoint" to "https://dbpedia.org/sparql",
        "updateEndpoint" to "https://example.org/update",
        "timeout" to 30000
    )
)
```

## 📊 Query Results

### QueryResult

Interface for SPARQL query results.

```kotlin
interface QueryResult : Iterable<BindingSet> {
    fun count(): Long
    fun firstOrNull(): BindingSet?
    fun toList(): List<BindingSet>
    fun toMap(keySelector: (BindingSet) -> String, valueSelector: (BindingSet) -> String): Map<String, String>
}
```

### BindingSet

Interface for individual query result bindings.

```kotlin
interface BindingSet {
    fun get(name: String): RdfTerm?
    fun getString(name: String): String?
    fun getInt(name: String): Int?
    fun getDouble(name: String): Double?
    fun getBoolean(name: String): Boolean?
    fun getIri(name: String): Iri?
    fun getResource(name: String): RdfResource?
    fun getNames(): Set<String>
    fun hasBinding(name: String): Boolean
}
```

### Type-Safe Extensions

```kotlin
inline fun <reified T> QueryResult.firstAs(): T?
inline fun <reified T> QueryResult.mapAs(): List<T>
```

## ⚡ Performance

### Batch Operations

```kotlin
fun RdfRepository.addBatch(batchSize: Int = 1000, configure: TripleDsl.() -> Unit)
fun RdfRepository.addBatchToGraph(graphName: Iri, batchSize: Int = 1000, configure: TripleDsl.() -> Unit)
```

### Performance Monitoring

```kotlin
fun RdfRepository.queryTimed(sparql: String, initialBindings: Map<String, RdfTerm> = emptyMap()): Pair<QueryResult, Duration>
fun RdfRepository.operationTimed(operations: RdfRepository.() -> Unit): Pair<Unit, Duration>
fun RdfRepository.getStatistics(): RepositoryStatistics
fun RdfRepository.getPerformanceMonitor(): PerformanceMonitor
```

### Statistics Formatting

```kotlin
fun RdfRepository.sizeFormatted(): String
fun RdfRepository.statisticsFormatted(): String
```

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

### RdfLiteral

Represents an RDF literal value.

```kotlin
sealed class RdfLiteral : RdfTerm {
    data class StringLiteral(val value: String, val language: String? = null, val datatype: Iri? = null) : RdfLiteral()
    data class IntegerLiteral(val value: Int) : RdfLiteral()
    data class DoubleLiteral(val value: Double) : RdfLiteral()
    data class BooleanLiteral(val value: Boolean) : RdfLiteral()
}
```

### Literal Factory Functions

```kotlin
fun string(value: String, language: String? = null, datatype: Iri? = null): RdfLiteral
fun integer(value: Int): RdfLiteral
fun double(value: Double): RdfLiteral
fun boolean(value: Boolean): RdfLiteral
fun literal(value: String): RdfLiteral
fun literal(value: Int): RdfLiteral
fun literal(value: Double): RdfLiteral
fun literal(value: Boolean): RdfLiteral
```

## 🎯 Convenience Functions

### Global Functions

```kotlin
fun resource(iri: String): RdfResource
fun iri(value: String): Iri
fun literal(value: String): RdfLiteral
fun literal(value: Int): RdfLiteral
fun literal(value: Double): RdfLiteral
fun literal(value: Boolean): RdfLiteral
fun triple(subject: RdfResource, predicate: Iri, obj: RdfTerm): RdfTriple
fun triple(subject: RdfResource, predicate: String, obj: RdfTerm): RdfTriple
fun triple(subject: RdfResource, predicate: Iri, obj: String): RdfTriple
fun triple(subject: RdfResource, predicate: Iri, obj: Int): RdfTriple
fun triple(subject: RdfResource, predicate: Iri, obj: Double): RdfTriple
fun triple(subject: RdfResource, predicate: Iri, obj: Boolean): RdfTriple
```

## 📚 Registry

### RdfApiRegistry

Service loader for discovering RDF providers.

```kotlin
object RdfApiRegistry {
    fun discoverProviders(): List<RdfApiProvider>
    fun getProvider(type: String): RdfApiProvider?
    fun getSupportedTypes(): List<String>
    fun getConfigVariants(): Map<String, List<RdfConfigVariant>>
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

// Add data
repo.add {
    val person = "http://example.org/person/alice".toResource()
    person["http://example.org/person/name"] = "Alice"
    person["http://example.org/person/age"] = 30
}

// Query data
val results = repo.query("""
    SELECT ?name ?age WHERE { 
        ?person <http://example.org/person/name> ?name ;
                <http://example.org/person/age> ?age 
    }
""")

// Process results
results.forEach { binding ->
    println("${binding.getString("name")} is ${binding.getInt("age")} years old")
}

// Clean up
repo.close()
```

### Advanced Usage

```kotlin
// Fluent interface
Rdf.memory().fluent()
    .add {
        val person = "http://example.org/person/alice".toResource()
        person["http://example.org/person/name"] = "Alice"
    }
    .query("SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }")
    .forEach { binding ->
        println("Found: ${binding.getString("name")}")
    }
    .clear()
    .close()

// Performance monitoring
val (results, duration) = repo.queryTimed("""
    SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }
""")
println("Query took: $duration")

// Batch operations
repo.addBatch(batchSize = 1000) {
    for (i in 1..10000) {
        val person = "http://example.org/person/person$i".toResource()
        person["http://example.org/person/name"] = "Person $i"
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
