# Advanced Usage Patterns

This tutorial covers advanced usage patterns, complex scenarios, and sophisticated techniques for working with OntoMapper in production applications.

## Complex Domain Models

### Hierarchical Domain Models

Model complex hierarchical relationships:

```kotlin
@RdfClass(iri = "http://example.org/Organization")
interface Organization {
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://example.org/description")
    val description: List<String>
    
    @get:RdfProperty(iri = "http://example.org/parentOrganization")
    val parentOrganization: List<Organization>
    
    @get:RdfProperty(iri = "http://example.org/subOrganization")
    val subOrganizations: List<Organization>
    
    @get:RdfProperty(iri = "http://example.org/department")
    val departments: List<Department>
    
    @get:RdfProperty(iri = "http://example.org/employee")
    val employees: List<Employee>
}

@RdfClass(iri = "http://example.org/Department")
interface Department {
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://example.org/organization")
    val organization: List<Organization>
    
    @get:RdfProperty(iri = "http://example.org/manager")
    val manager: List<Employee>
    
    @get:RdfProperty(iri = "http://example.org/employee")
    val employees: List<Employee>
}

@RdfClass(iri = "http://example.org/Employee")
interface Employee {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://example.org/employeeId")
    val employeeId: List<String>
    
    @get:RdfProperty(iri = "http://example.org/organization")
    val organization: List<Organization>
    
    @get:RdfProperty(iri = "http://example.org/department")
    val department: List<Department>
    
    @get:RdfProperty(iri = "http://example.org/manager")
    val manager: List<Employee>
    
    @get:RdfProperty(iri = "http://example.org/subordinate")
    val subordinates: List<Employee>
}
```

### Polymorphic Collections

Handle collections of different types:

```kotlin
@RdfClass(iri = "http://example.org/Event")
interface Event {
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://example.org/participant")
    val participants: List<Person>  // Can be Employee, Customer, or Person
}

@RdfClass(iri = "http://example.org/Project")
interface Project {
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://example.org/stakeholder")
    val stakeholders: List<Person>  // Different types of stakeholders
}

// Usage with type checking
fun processEvent(event: Event) {
    event.participants.forEach { participant ->
        when (participant) {
            is Employee -> {
                println("Employee: ${participant.employeeId.firstOrNull()}")
                println("Department: ${participant.department.firstOrNull()?.name?.firstOrNull()}")
            }
            is Customer -> {
                println("Customer: ${participant.customerId.firstOrNull()}")
                println("Loyalty Points: ${participant.loyaltyPoints.firstOrNull()}")
            }
            else -> {
                println("Person: ${participant.name.firstOrNull()}")
            }
        }
    }
}
```

### Temporal Data Modeling

Model temporal aspects of data:

```kotlin
@RdfClass(iri = "http://example.org/Product")
interface Product {
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://example.org/price")
    val price: List<Double>
    
    @get:RdfProperty(iri = "http://example.org/version")
    val version: List<String>
    
    @get:RdfProperty(iri = "http://example.org/effectiveDate")
    val effectiveDate: List<String>
    
    @get:RdfProperty(iri = "http://example.org/expirationDate")
    val expirationDate: List<String>
    
    @get:RdfProperty(iri = "http://example.org/previousVersion")
    val previousVersion: List<Product>
    
    @get:RdfProperty(iri = "http://example.org/nextVersion")
    val nextVersion: List<Product>
}

// Helper functions for temporal operations
fun Product.isCurrentlyValid(): Boolean {
    val now = LocalDate.now()
    val effective = effectiveDate.firstOrNull()?.let { LocalDate.parse(it) }
    val expiration = expirationDate.firstOrNull()?.let { LocalDate.parse(it) }
    
    return when {
        effective != null && now.isBefore(effective) -> false
        expiration != null && now.isAfter(expiration) -> false
        else -> true
    }
}

fun Product.getCurrentPrice(): Double? {
    return if (isCurrentlyValid()) {
        price.firstOrNull()
    } else {
        null
    }
}
```

## Advanced Materialization Patterns

### Custom Materialization Logic

Implement custom materialization for complex scenarios:

```kotlin
class CustomMaterializationService {
    fun materializeWithContext<T : Any>(
        ref: RdfRef,
        type: Class<T>,
        context: MaterializationContext
    ): T {
        // Create custom handle with context
        val handle = ContextualRdfHandle(
            node = ref.node,
            graph = ref.graph,
            context = context
        )
        
        // Get factory from registry
        val factory = OntoMapper.registry[type]
            ?: error("No wrapper factory registered for ${type.name}")
        
        // Materialize with custom handle
        return factory(handle) as T
    }
}

data class MaterializationContext(
    val user: String? = null,
    val permissions: Set<String> = emptySet(),
    val filters: Map<String, Any> = emptyMap(),
    val options: MaterializationOptions = MaterializationOptions()
)

data class MaterializationOptions(
    val includeInactive: Boolean = false,
    val maxDepth: Int = 10,
    val validate: Boolean = true
)

class ContextualRdfHandle(
    override val node: RdfTerm,
    override val graph: RdfGraph,
    private val context: MaterializationContext
) : RdfHandle {
    override val extras: PropertyBag by lazy {
        ContextualPropertyBag(graph, node, context)
    }
    
    override fun validate(): ValidationResult {
        if (!context.options.validate) return ValidationResult.Ok
        return ShaclValidation.current().validate(graph, node)
    }
}
```

### Lazy Loading with Pagination

Implement lazy loading for large datasets:

```kotlin
class PaginatedMaterializationService {
    fun materializePage<T : Any>(
        graph: RdfGraph,
        type: Class<T>,
        page: Int,
        pageSize: Int
    ): Page<T> {
        val triples = graph.getTriples()
        val typeTriples = triples.filter { 
            it.predicate == RDF.type && it.obj == getTypeIri(type)
        }
        
        val startIndex = page * pageSize
        val endIndex = minOf(startIndex + pageSize, typeTriples.size)
        val pageTriples = typeTriples.subList(startIndex, endIndex)
        
        val items = pageTriples.mapNotNull { triple ->
            try {
                val ref = RdfRef(triple.subject, graph)
                ref.asType<T>()
            } catch (e: Exception) {
                null
            }
        }
        
        return Page(
            items = items,
            page = page,
            pageSize = pageSize,
            totalItems = typeTriples.size,
            totalPages = (typeTriples.size + pageSize - 1) / pageSize
        )
    }
    
    private fun getTypeIri(type: Class<*>): Iri {
        // Extract type IRI from annotation
        val annotation = type.getAnnotation(RdfClass::class.java)
        return iri(annotation.iri)
    }
}

data class Page<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val totalItems: Int,
    val totalPages: Int
)
```

## Advanced RDF Operations

### Graph Traversal

Implement graph traversal algorithms:

```kotlin
class GraphTraversalService {
    fun findShortestPath(
        graph: RdfGraph,
        start: RdfTerm,
        end: RdfTerm,
        predicate: Iri
    ): List<RdfTerm>? {
        val queue = mutableListOf(listOf(start))
        val visited = mutableSetOf<RdfTerm>()
        
        while (queue.isNotEmpty()) {
            val path = queue.removeAt(0)
            val current = path.last()
            
            if (current == end) {
                return path
            }
            
            if (visited.contains(current)) {
                continue
            }
            visited.add(current)
            
            // Find neighbors
            val neighbors = graph.getTriples()
                .filter { it.subject == current && it.predicate == predicate }
                .map { it.obj as? RdfTerm }
                .filterNotNull()
            
            neighbors.forEach { neighbor ->
                if (!visited.contains(neighbor)) {
                    queue.add(path + neighbor)
                }
            }
        }
        
        return null
    }
    
    fun findCycles(graph: RdfGraph, predicate: Iri): List<List<RdfTerm>> {
        val cycles = mutableListOf<List<RdfTerm>>()
        val visited = mutableSetOf<RdfTerm>()
        val recursionStack = mutableSetOf<RdfTerm>()
        
        fun dfs(node: RdfTerm, path: List<RdfTerm>) {
            visited.add(node)
            recursionStack.add(node)
            
            val neighbors = graph.getTriples()
                .filter { it.subject == node && it.predicate == predicate }
                .map { it.obj as? RdfTerm }
                .filterNotNull()
            
            neighbors.forEach { neighbor ->
                if (recursionStack.contains(neighbor)) {
                    // Found a cycle
                    val cycleStart = path.indexOf(neighbor)
                    if (cycleStart >= 0) {
                        cycles.add(path.subList(cycleStart, path.size) + neighbor)
                    }
                } else if (!visited.contains(neighbor)) {
                    dfs(neighbor, path + neighbor)
                }
            }
            
            recursionStack.remove(node)
        }
        
        val allNodes = graph.getTriples()
            .flatMap { listOf(it.subject, it.obj) }
            .filterIsInstance<RdfTerm>()
            .distinct()
        
        allNodes.forEach { node ->
            if (!visited.contains(node)) {
                dfs(node, listOf(node))
            }
        }
        
        return cycles
    }
}
```

### RDF Inference

Implement basic RDF inference:

```kotlin
class InferenceService {
    fun inferTransitiveProperties(
        graph: RdfGraph,
        predicate: Iri
    ): RdfGraph {
        val inferredGraph = Rdf.memory()
        val triples = graph.getTriples()
        
        // Copy original triples
        inferredGraph.add {
            triples.forEach { triple ->
                triple.subject - triple.predicate - triple.obj
            }
        }
        
        // Infer transitive properties
        val transitiveTriples = mutableSetOf<RdfTriple>()
        val processed = mutableSetOf<RdfTerm>()
        
        fun inferTransitive(node: RdfTerm) {
            if (processed.contains(node)) return
            processed.add(node)
            
            val directTargets = triples
                .filter { it.subject == node && it.predicate == predicate }
                .map { it.obj as? RdfTerm }
                .filterNotNull()
            
            directTargets.forEach { target ->
                // Add direct relationship
                transitiveTriples.add(RdfTriple(node, predicate, target))
                
                // Recursively infer transitive relationships
                inferTransitive(target)
                
                // Add transitive relationships
                val transitiveTargets = triples
                    .filter { it.subject == target && it.predicate == predicate }
                    .map { it.obj as? RdfTerm }
                    .filterNotNull()
                
                transitiveTargets.forEach { transitiveTarget ->
                    transitiveTriples.add(RdfTriple(node, predicate, transitiveTarget))
                }
            }
        }
        
        // Start inference from all nodes
        val allNodes = triples
            .flatMap { listOf(it.subject, it.obj) }
            .filterIsInstance<RdfTerm>()
            .distinct()
        
        allNodes.forEach { node ->
            inferTransitive(node)
        }
        
        // Add inferred triples
        inferredGraph.add {
            transitiveTriples.forEach { triple ->
                triple.subject - triple.predicate - triple.obj
            }
        }
        
        return inferredGraph.defaultGraph
    }
}
```

## Performance Optimization

### Caching Strategies

Implement intelligent caching:

```kotlin
class CachingMaterializationService {
    private val materializationCache = LruCache<CacheKey, Any>(1000)
    private val propertyCache = LruCache<PropertyKey, Any>(10000)
    
    fun materializeWithCache<T : Any>(
        ref: RdfRef,
        type: Class<T>
    ): T {
        val cacheKey = CacheKey(ref.node, ref.graph, type)
        
        return materializationCache.get(cacheKey) {
            val factory = OntoMapper.registry[type]
                ?: error("No wrapper factory registered for ${type.name}")
            
            val handle = CachingRdfHandle(ref.node, ref.graph)
            factory(handle) as T
        } as T
    }
    
    private inner class CachingRdfHandle(
        override val node: RdfTerm,
        override val graph: RdfGraph
    ) : RdfHandle {
        override val extras: PropertyBag by lazy {
            CachingPropertyBag(graph, node)
        }
        
        override fun validate(): ValidationResult {
            return ShaclValidation.current().validate(graph, node)
        }
    }
    
    private inner class CachingPropertyBag(
        private val graph: RdfGraph,
        private val node: RdfTerm
    ) : PropertyBag {
        private val cache = mutableMapOf<Iri, List<RdfTerm>>()
        
        override fun predicates(): Set<Iri> {
            return getCachedTriples().keys.toSet()
        }
        
        override fun values(pred: Iri): List<RdfTerm> {
            return getCachedTriples()[pred] ?: emptyList()
        }
        
        override fun literals(pred: Iri): List<Literal> {
            return values(pred).filterIsInstance<Literal>()
        }
        
        override fun strings(pred: Iri): List<String> {
            return literals(pred).map { it.lexical }
        }
        
        override fun iris(pred: Iri): List<Iri> {
            return values(pred).filterIsInstance<Iri>()
        }
        
        override fun <T : Any> objects(pred: Iri, asType: Class<T>): List<T> {
            return values(pred).mapNotNull { term ->
                when (term) {
                    is Iri, is BlankNode -> {
                        try {
                            OntoMapper.materialize(RdfRef(term, graph), asType)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    else -> null
                }
            }
        }
        
        private fun getCachedTriples(): Map<Iri, List<RdfTerm>> {
            if (cache.isEmpty()) {
                val triples = graph.getTriples()
                    .filter { it.subject == node }
                    .groupBy { it.predicate }
                    .mapValues { (_, triples) -> triples.map { it.obj } }
                
                cache.putAll(triples)
            }
            
            return cache
        }
    }
    
    data class CacheKey(
        val node: RdfTerm,
        val graph: RdfGraph,
        val type: Class<*>
    )
    
    data class PropertyKey(
        val node: RdfTerm,
        val graph: RdfGraph,
        val predicate: Iri
    )
}
```

### Async Materialization

Implement asynchronous materialization:

```kotlin
class AsyncMaterializationService {
    private val executor = Executors.newFixedThreadPool(10)
    
    fun materializeAsync<T : Any>(
        ref: RdfRef,
        type: Class<T>
    ): CompletableFuture<T> {
        return CompletableFuture.supplyAsync({
            ref.asType<T>()
        }, executor)
    }
    
    fun materializeBatchAsync<T : Any>(
        refs: List<RdfRef>,
        type: Class<T>
    ): CompletableFuture<List<T>> {
        val futures = refs.map { ref ->
            materializeAsync(ref, type)
        }
        
        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply { futures.map { it.get() } }
    }
    
    fun shutdown() {
        executor.shutdown()
    }
}
```

## Integration Patterns

### Repository Pattern with OntoMapper

```kotlin
interface Repository<T, ID> {
    fun findById(id: ID): T?
    fun findAll(): List<T>
    fun save(entity: T): T
    fun delete(id: ID): Boolean
}

class OntoMapperRepository<T : Any>(
    private val type: Class<T>,
    private val repository: RdfRepository
) : Repository<T, Iri> {
    
    override fun findById(id: Iri): T? {
        return try {
            val ref = RdfRef(id, repository.defaultGraph)
            ref.asType<T>()
        } catch (e: Exception) {
            null
        }
    }
    
    override fun findAll(): List<T> {
        val graph = repository.defaultGraph
        val typeIri = getTypeIri(type)
        
        val entities = graph.getTriples()
            .filter { it.predicate == RDF.type && it.obj == typeIri }
            .map { it.subject as? Iri }
            .filterNotNull()
        
        return entities.mapNotNull { iri ->
            try {
                RdfRef(iri, graph).asType<T>()
            } catch (e: Exception) {
                null
            }
        }
    }
    
    override fun save(entity: T): T {
        val rdfHandle = (entity as RdfBacked).rdf
        val graph = rdfHandle.graph
        
        // Add or update triples
        graph.add {
            val node = rdfHandle.node
            // Add type triple
            node - RDF.type - getTypeIri(type)
            
            // Add property triples based on entity
            addPropertyTriples(node, entity)
        }
        
        return entity
    }
    
    override fun delete(id: Iri): Boolean {
        val graph = repository.defaultGraph
        val triplesToRemove = graph.getTriples()
            .filter { it.subject == id }
        
        triplesToRemove.forEach { triple ->
            graph.remove(triple)
        }
        
        return triplesToRemove.isNotEmpty()
    }
    
    private fun getTypeIri(type: Class<*>): Iri {
        val annotation = type.getAnnotation(RdfClass::class.java)
        return iri(annotation.iri)
    }
    
    private fun addPropertyTriples(node: RdfTerm, entity: T) {
        // Implementation depends on entity structure
        // This is a simplified version
    }
}
```

### Service Layer Pattern

```kotlin
class PersonService(
    private val personRepository: OntoMapperRepository<Person, Iri>,
    private val validationService: ValidationService
) {
    fun createPerson(name: String, age: Int, email: String): Person {
        val repo = Rdf.memory()
        val personId = iri("http://example.org/person/${UUID.randomUUID()}")
        
        repo.add {
            personId - RDF.type - FOAF.Person
            personId - FOAF.name - name
            personId - FOAF.age - age
            personId - FOAF.mbox - email
            personId - DCTERMS.created - "2024-01-15"^^XSD.date
        }
        
        val person = RdfRef(personId, repo.defaultGraph).asType<Person>()
        
        // Validate
        validationService.validatePerson(person)
        
        return person
    }
    
    fun updatePerson(person: Person, updates: Map<String, Any>): Person {
        val rdfHandle = person.asRdf()
        val graph = rdfHandle.graph
        
        graph.add {
            val node = rdfHandle.node
            updates.forEach { (key, value) ->
                when (key) {
                    "name" -> node - FOAF.name - value.toString()
                    "age" -> node - FOAF.age - (value as Int)
                    "email" -> node - FOAF.mbox - value.toString()
                }
            }
            node - DCTERMS.modified - "2024-01-16"^^XSD.date
        }
        
        // Validate
        validationService.validatePerson(person)
        
        return person
    }
    
    fun findPersonByEmail(email: String): Person? {
        val graph = personRepository.repository.defaultGraph
        val personTriples = graph.getTriples()
            .filter { it.predicate == FOAF.mbox && it.obj == Literal(email) }
        
        return personTriples.firstOrNull()?.let { triple ->
            try {
                RdfRef(triple.subject, graph).asType<Person>()
            } catch (e: Exception) {
                null
            }
        }
    }
}
```

## Error Handling and Resilience

### Circuit Breaker Pattern

```kotlin
class CircuitBreakerMaterializationService {
    private val circuitBreaker = CircuitBreaker.ofDefaults("materialization")
    
    fun materializeWithCircuitBreaker<T : Any>(
        ref: RdfRef,
        type: Class<T>
    ): T {
        return circuitBreaker.executeSupplier {
            ref.asType<T>()
        }
    }
    
    fun materializeWithFallback<T : Any>(
        ref: RdfRef,
        type: Class<T>,
        fallback: () -> T
    ): T {
        return try {
            materializeWithCircuitBreaker(ref, type)
        } catch (e: Exception) {
            fallback()
        }
    }
}
```

### Retry Pattern

```kotlin
class RetryMaterializationService {
    private val retryTemplate = RetryTemplate.builder()
        .maxAttempts(3)
        .exponentialBackoff(1000, 2, 10000)
        .retryOn(Exception::class.java)
        .build()
    
    fun materializeWithRetry<T : Any>(
        ref: RdfRef,
        type: Class<T>
    ): T {
        return retryTemplate.execute { context ->
            try {
                ref.asType<T>()
            } catch (e: Exception) {
                if (context.retryCount >= 2) {
                    throw e
                }
                throw e
            }
        }
    }
}
```

## Best Practices Summary

### ✅ Do

- Use appropriate collection types for your use case
- Implement caching for performance-critical operations
- Handle errors gracefully with fallbacks
- Use async operations for I/O-intensive tasks
- Implement proper validation
- Design for extensibility
- Use repository pattern for data access
- Implement circuit breakers for resilience

### ❌ Don't

- Ignore performance implications
- Mix RDF operations with domain logic
- Assume operations always succeed
- Create deep object graphs without limits
- Ignore memory usage with large datasets
- Skip validation in production
- Use blocking operations in async contexts

## Next Steps

- **See [Practical Examples](../examples/README.md)** - Real-world use cases
- **Review [API Reference](../reference/README.md)** - Complete API documentation
- **Learn about [Best Practices](../best-practices.md)** - Guidelines for effective usage
- **Check out [FAQ](../faq.md)** - Common questions and answers



