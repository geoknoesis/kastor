# Kastor RDF API Improvements Analysis

## Executive Summary

The current Kastor RDF API provides a solid foundation but has several areas for improvement to make it more Kotlin-idiomatic, easier to use, and better leverage the full capabilities of Jena and RDF4J. This document outlines the key improvements needed.

## Current State Analysis

### ✅ **Strengths**

1. **Well-Structured Architecture**: Clear separation of concerns with provider system
2. **Excellent Triple DSL**: Natural language syntax (`subject has predicate with object`)
3. **Comprehensive Repository Management**: Full multi-repository support
4. **Type Safety**: Good use of sealed interfaces and value classes
5. **Provider Flexibility**: Support for Jena, RDF4J, and SPARQL

### ❌ **Areas for Improvement**

## 1. **API Complexity and Verbosity**

### Current Issues:
- **Too Many Layers**: `RdfApi` → `RdfRepository` → `RdfGraph` creates unnecessary complexity
- **Verbose Factory DSL**: Requires explicit type specification for common cases
- **Inconsistent Method Naming**: `querySelect` vs `select`, `addTriple` vs `add`
- **Legacy Methods**: Old methods mixed with new ones create confusion

### Proposed Solutions:

#### **Simplified Factory DSL**
```kotlin
// Current (verbose)
val api = Rdf.factory {
    type("jena:memory")
    param("inferencing", "true")
}

// Proposed (simplified)
val repo = Rdf.memoryWithInference()
val repo = Rdf.tdb2("/data/storage")
val repo = Rdf.rdf4jNative("/data/rdf4j")
```

#### **Unified Repository Interface**
```kotlin
// Single interface with extension functions
interface RdfRepository {
    fun add(triple: RdfTriple, graph: String? = null)
    fun select(query: String, bindings: Map<String, RdfTerm> = emptyMap()): ResultSet
    fun <T> transaction(block: RdfRepository.() -> T): T
    // ... other methods
}
```

## 2. **Enhanced Triple DSL**

### Current State:
The triple DSL is excellent but can be enhanced with more convenience features.

### Proposed Enhancements:

#### **Batch Operations**
```kotlin
// Add multiple properties at once
val triples = person.properties(
    name to "John",
    age to 30,
    email to "john@example.com"
)
```

#### **Graph Building DSL**
```kotlin
val graph = RdfDsl.graph("http://example.org/people") {
    addTriple(alice has name with "Alice")
    addTriple(alice has age with 25)
    addTriple(bob has name with "Bob")
    addTriple(bob has age with 30)
}
```

## 3. **Enhanced Result Set API**

### Current Issues:
- Basic `ResultSet` interface lacks convenience methods
- No type-safe access to binding values
- Limited functional operations

### Proposed Enhancements:

#### **Enhanced ResultSet**
```kotlin
interface ResultSet {
    fun toList(): List<BindingSet>
    fun first(): BindingSet?
    fun count(): Int
    fun isEmpty(): Boolean
    fun <T> map(transform: (BindingSet) -> T): List<T>
    fun filter(predicate: (BindingSet) -> Boolean): List<BindingSet>
    fun forEach(action: (BindingSet) -> Unit)
}
```

#### **Enhanced BindingSet**
```kotlin
interface BindingSet {
    fun getString(name: String): String?
    fun getInt(name: String): Int?
    fun getDouble(name: String): Double?
    fun getBoolean(name: String): Boolean?
    fun getIri(name: String): Iri?
    operator fun get(name: String): RdfTerm?
}
```

## 4. **Better Provider Capabilities Exposure**

### Current Issues:
- Limited exposure of Jena/RDF4J advanced features
- No easy way to access provider-specific capabilities
- Missing performance optimizations

### Proposed Solutions:

#### **Enhanced Capabilities Enum**
```kotlin
enum class Capability {
    // Query capabilities
    SPARQL_QUERY, SPARQL_UPDATE, SPARQL_1_1,
    
    // Data model capabilities
    RDF_STAR, NAMED_GRAPHS, DATASET_QUERIES,
    
    // Storage capabilities
    TRANSACTIONS, PERSISTENCE, BACKUP_RESTORE,
    
    // Inference capabilities
    RDFS_INFERENCE, OWL_INFERENCE, RULE_BASED_INFERENCE,
    
    // Validation capabilities
    SHACL_VALIDATION, SHACL_CONSTRAINT_VALIDATION,
    
    // Advanced capabilities
    FEDERATION, REASONING, EXPLANATION, MONITORING,
    
    // Performance capabilities
    INDEXING, CACHING, OPTIMIZATION,
    
    // Integration capabilities
    HTTP_SERVER, REST_API, WORKBENCH, CONSOLE
}
```

#### **Provider-Specific Features**
```kotlin
interface RdfApiProvider {
    fun getAdvancedFeatures(): Set<String>
    fun isFeatureSupported(feature: String): Boolean
    fun getCapabilities(): Set<Capability>
}
```

## 5. **Performance Optimizations**

### Current Issues:
- No batch operations for better performance
- No prepared statements for repeated queries
- Limited statistics and monitoring

### Proposed Solutions:

#### **Batch Operations**
```kotlin
fun RdfRepository.addBatch(triples: Iterable<RdfTriple>, graph: String? = null)
fun RdfRepository.removeBatch(triples: Iterable<RdfTriple>, graph: String? = null)
```

#### **Prepared Statements**
```kotlin
fun RdfRepository.prepareQuery(query: String): PreparedQuery
fun RdfRepository.prepareUpdate(query: String): PreparedUpdate
```

#### **Statistics and Monitoring**
```kotlin
data class RepositoryStatistics(
    val tripleCount: Long,
    val graphCount: Int,
    val queryCount: Long,
    val updateCount: Long,
    val averageQueryTime: Double,
    val memoryUsage: Long
)
```

## 6. **Enhanced Error Handling**

### Current Issues:
- Generic exceptions make debugging difficult
- No specific error types for different operations

### Proposed Solutions:

#### **Specific Exception Types**
```kotlin
sealed class RdfException(message: String, cause: Throwable? = null) : Exception(message, cause)

class RdfQueryException(message: String, cause: Throwable? = null) : RdfException(message, cause)
class RdfTransactionException(message: String, cause: Throwable? = null) : RdfException(message, cause)
class RdfProviderException(message: String, cause: Throwable? = null) : RdfException(message, cause)
class RdfValidationException(message: String, cause: Throwable? = null) : RdfException(message, cause)
```

## 7. **Simplified Usage Patterns**

### Current Usage (Verbose):
```kotlin
val api = Rdf.factory {
    type("jena:memory")
    param("inferencing", "true")
}
val repo = api.repository
repo.beginTransaction()
repo.addTriple(null, triple(person, name, "John"))
repo.commit()
repo.end()
val results = repo.querySelect("SELECT ?s WHERE { ?s ?p ?o }")
```

### Proposed Usage (Simplified):
```kotlin
val repo = Rdf.memoryWithInference()
repo.transaction {
    addTriple(person has name with "John")
    addTriple(person has age with 30)
}
val results = repo.query("SELECT ?s WHERE { ?s ?p ?o }")
results.forEach { binding ->
    println(binding.getString("s"))
}
```

## 8. **Better Jena/RDF4J Integration**

### Current Issues:
- Limited exposure of advanced features
- No easy access to native capabilities
- Missing performance optimizations

### Proposed Solutions:

#### **Enhanced Jena Integration**
```kotlin
// Access to Jena-specific features
val jenaRepo = repo as? JenaRepository
jenaRepo?.dataset?.let { dataset ->
    // Access to Jena Dataset features
    dataset.listModelNames()
    dataset.getNamedModel("http://example.org/graph")
}
```

#### **Enhanced RDF4J Integration**
```kotlin
// Access to RDF4J-specific features
val rdf4jRepo = repo as? Rdf4jRepository
rdf4jRepo?.repository?.let { repository ->
    // Access to RDF4J Repository features
    repository.connection.use { conn ->
        conn.getStatements(null, null, null)
    }
}
```

## 9. **Migration Strategy**

### Backward Compatibility:
- Keep existing interfaces with `@Deprecated` annotations
- Provide migration guides and examples
- Gradual deprecation over multiple versions

### Migration Path:
```kotlin
// Old way (deprecated but still works)
val api = Rdf.factory { type("jena:memory") }
val repo = api.repository

// New way (recommended)
val repo = Rdf.memory()
```

## 10. **Implementation Priority**

### Phase 1 (High Priority):
1. Simplified factory DSL
2. Unified repository interface
3. Enhanced ResultSet API
4. Basic error handling improvements

### Phase 2 (Medium Priority):
1. Enhanced triple DSL
2. Performance optimizations
3. Better provider capabilities exposure
4. Advanced error handling

### Phase 3 (Low Priority):
1. Advanced monitoring and statistics
2. Provider-specific integrations
3. Advanced features (HTTP server, etc.)

## Benefits of Proposed Improvements

### 1. **Easier to Use**
- Reduced boilerplate code
- More intuitive API
- Better defaults and conventions

### 2. **More Kotlin-Idiomatic**
- Extension functions
- Operator overloads
- Functional programming patterns
- Type-safe operations

### 3. **Better Performance**
- Batch operations
- Prepared statements
- Optimized data structures

### 4. **Enhanced Capabilities**
- Better exposure of Jena/RDF4J features
- Advanced inference and validation
- Monitoring and statistics

### 5. **Future-Proof**
- Extensible design
- Plugin architecture
- Easy to add new providers

## Conclusion

The proposed improvements will make the Kastor RDF API significantly more user-friendly, performant, and capable while maintaining backward compatibility. The changes focus on reducing complexity, improving developer experience, and better leveraging the full capabilities of underlying RDF engines like Jena and RDF4J.

The implementation should be done incrementally with proper migration paths to ensure existing users can adopt the new API at their own pace.
