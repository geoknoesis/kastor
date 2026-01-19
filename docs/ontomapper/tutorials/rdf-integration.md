# RDF Integration with Side-Channels

This tutorial covers advanced RDF integration using OntoMapper's side-channel architecture to access RDF-specific functionality when needed.

## Understanding the Side-Channel

The side-channel provides access to RDF functionality without polluting your domain interfaces:

```kotlin
// Pure domain interface
interface Person {
    val name: List<String>
    val age: List<Int>
}

// RDF access via side-channel
val person: Person = materializeFromRdf(...)
val rdfHandle = person.asRdf()  // Side-channel access
```

## RdfHandle Interface

The `RdfHandle` provides access to RDF-specific functionality:

```kotlin
interface RdfHandle {
    val node: RdfTerm          // The RDF node (IRI or blank node)
    val graph: RdfGraph        // The containing RDF graph
    val extras: PropertyBag    // Unmapped properties
    
    fun validate(): ValidationResult
    fun validateOrThrow()      // SHACL validation
}
```

### Accessing the Underlying RDF Node

```kotlin
val person: Person = materializeFromRdf(...)
val rdfHandle = person.asRdf()

// Get the RDF node
val node = rdfHandle.node
when (node) {
    is Iri -> println("Person IRI: ${node.value}")
    is BlankNode -> println("Person blank node: ${node.id}")
}

// Get the containing graph
val graph = rdfHandle.graph
println("Graph contains ${graph.getTriples().size} triples")
```

### Direct RDF Operations

```kotlin
val person: Person = materializeFromRdf(...)
val rdfHandle = person.asRdf()

// Add new triples to the graph
val graph = rdfHandle.graph
graph.add {
    val node = rdfHandle.node
    node - DCTERMS.modified - "2024-01-15"^^XSD.date
    node - SKOS.note - "Updated via side-channel"
}

// Query the graph directly
val triples = graph.getTriples()
    .filter { it.subject == rdfHandle.node }
    .filter { it.predicate == FOAF.name }

println("Name triples: ${triples.size}")
```

## PropertyBag for Unmapped Properties

The `PropertyBag` provides typed access to properties not mapped in your domain interface:

```kotlin
interface PropertyBag {
    fun predicates(): Set<Iri>                    // All unmapped predicates
    fun values(pred: Iri): List<RdfTerm>          // All values for a predicate
    fun literals(pred: Iri): List<Literal>        // Literal values only
    fun strings(pred: Iri): List<String>          // String values
    fun iris(pred: Iri): List<Iri>                // IRI values
    fun <T : Any> objects(pred: Iri, asType: Class<T>): List<T>  // Materialized objects
}
```

### Discovering Unmapped Properties

```kotlin
val person: Person = materializeFromRdf(...)
val extras = person.asRdf().extras

// Get all unmapped predicates
val unmappedPredicates = extras.predicates()
println("Unmapped properties:")
unmappedPredicates.forEach { pred ->
    println("  ${pred.value}")
}

// Check if specific properties exist
if (extras.predicates().contains(SKOS.altLabel)) {
    println("Person has alternative labels")
}
```

### Accessing Unmapped Values

```kotlin
val person: Person = materializeFromRdf(...)
val extras = person.asRdf().extras

// Get string values
val altLabels = extras.strings(SKOS.altLabel)
println("Alternative labels: ${altLabels.joinToString(", ")}")

// Get literal values
val descriptions = extras.literals(DCTERMS.description)
descriptions.forEach { literal ->
    println("Description: ${literal.lexical}")
    if (literal is LangString) {
        println("Language: ${literal.lang}")
    }
}

// Get IRI values
val relatedIris = extras.iris(DCTERMS.relation)
relatedIris.forEach { iri ->
    println("Related: ${iri.value}")
}
```

### Materializing Unmapped Objects

```kotlin
val person: Person = materializeFromRdf(...)
val extras = person.asRdf().extras

// Materialize related objects
val relatedPeople = extras.objects(FOAF.knows, Person::class.java)
relatedPeople.forEach { relatedPerson ->
    println("Related person: ${relatedPerson.name.firstOrNull()}")
}

// Materialize different types
val organizations = extras.objects(FOAF.member, Organization::class.java)
val events = extras.objects(FOAF.attended, Event::class.java)
```

## Advanced RDF Operations

### Custom RDF Queries

```kotlin
val person: Person = materializeFromRdf(...)
val rdfHandle = person.asRdf()

// Custom SPARQL query
val query = """
    SELECT ?predicate ?object WHERE {
        <${rdfHandle.node}> ?predicate ?object .
        FILTER(?predicate != <http://xmlns.com/foaf/0.1/name>)
    }
""".trimIndent()

val results = rdfHandle.graph.query(query)
results.forEach { bindingSet ->
    val predicate = bindingSet.getValue("predicate") as Iri
    val obj = bindingSet.getValue("object")
    println("${predicate.value}: ${obj}")
}
```

### RDF Graph Manipulation

```kotlin
val person: Person = materializeFromRdf(...)
val rdfHandle = person.asRdf()

// Add new properties
rdfHandle.graph.add {
    val node = rdfHandle.node
    node - DCTERMS.created - "2024-01-15"^^XSD.date
    node - SKOS.note - "Created via OntoMapper"
}

// Remove properties
val triplesToRemove = rdfHandle.graph.getTriples()
    .filter { it.subject == rdfHandle.node }
    .filter { it.predicate == SKOS.note }

triplesToRemove.forEach { triple ->
    rdfHandle.graph.remove(triple)
}

// Update properties
rdfHandle.graph.add {
    val node = rdfHandle.node
    node - DCTERMS.modified - "2024-01-16"^^XSD.date
}
```

### Working with Multiple Graphs

```kotlin
val person: Person = materializeFromRdf(...)
val rdfHandle = person.asRdf()

// Get the default graph
val defaultGraph = rdfHandle.graph

// Access named graphs if available
val repository = defaultGraph.repository
val namedGraphs = repository.listNamedGraphs()

namedGraphs.forEach { graphName ->
    val namedGraph = repository.getNamedGraph(graphName)
    val personInNamedGraph = namedGraph.getTriples()
        .filter { it.subject == rdfHandle.node }
        .firstOrNull()
    
    if (personInNamedGraph != null) {
        println("Person found in named graph: ${graphName}")
    }
}
```

## Validation Integration

### SHACL Validation

```kotlin
val person: Person = materializeFromRdf(...)
val rdfHandle = person.asRdf()

// Validate against SHACL shapes
try {
    rdfHandle.validateOrThrow()
    println("Validation passed")
} catch (e: ValidationException) {
    println("Validation failed: ${e.message}")
}

// Materialize with validation
val validatedPerson: Person = rdfRef.asType(validate = true)
```

### Custom Validation

```kotlin
val person: Person = materializeFromRdf(...)
val rdfHandle = person.asRdf()

// Custom validation logic
fun validatePerson(person: Person): List<String> {
    val errors = mutableListOf<String>()
    val rdfHandle = person.asRdf()
    
    // Check required properties
    if (person.name.isEmpty()) {
        errors.add("Person must have a name")
    }
    
    // Check RDF-specific constraints
    val age = person.age.firstOrNull()
    if (age != null && age < 0) {
        errors.add("Age cannot be negative")
    }
    
    // Check for required RDF properties
    val extras = rdfHandle.extras
    if (!extras.predicates().contains(DCTERMS.created)) {
        errors.add("Person must have a creation date")
    }
    
    return errors
}

// Usage
val validationErrors = validatePerson(person)
if (validationErrors.isNotEmpty()) {
    println("Validation errors:")
    validationErrors.forEach { error ->
        println("  - $error")
    }
}
```

## Performance Optimization

### Lazy Evaluation

```kotlin
val person: Person = materializeFromRdf(...)

// Properties are evaluated lazily
println("Before accessing name")  // No RDF queries yet
val name = person.name.firstOrNull()  // Now RDF query is executed
println("After accessing name")

// Results are cached
val name2 = person.name.firstOrNull()  // Uses cached result
```

### Batch Operations

```kotlin
val people: List<Person> = materializeMultipleFromRdf(...)

// Process in batches to avoid memory issues
people.chunked(100).forEach { batch ->
    batch.forEach { person ->
        val rdfHandle = person.asRdf()
        val extras = rdfHandle.extras
        
        // Process unmapped properties
        val unmappedProps = extras.predicates()
        // ... process properties
    }
}
```

### Selective Property Access

```kotlin
val person: Person = materializeFromRdf(...)

// Only access needed properties
val name = person.name.firstOrNull()
val age = person.age.firstOrNull()

// Access side-channel only when needed
if (needsRdfOperations) {
    val rdfHandle = person.asRdf()
    val extras = rdfHandle.extras
    // ... RDF operations
}
```

## Integration Patterns

### Repository Pattern

```kotlin
class PersonRepository(private val repository: RdfRepository) {
    fun findById(id: Iri): Person? {
        val graph = repository.defaultGraph
        val personRef = RdfRef(id, graph)
        return try {
            personRef.asType<Person>()
        } catch (e: IllegalStateException) {
            null
        }
    }
    
    fun findAll(): List<Person> {
        val graph = repository.defaultGraph
        val personIris = graph.getTriples()
            .filter { it.predicate == RDF.type && it.obj == FOAF.Person }
            .map { it.subject as Iri }
        
        return personIris.mapNotNull { iri ->
            try {
                RdfRef(iri, graph).asType<Person>()
            } catch (e: IllegalStateException) {
                null
            }
        }
    }
    
    fun save(person: Person) {
        val rdfHandle = person.asRdf()
        val graph = rdfHandle.graph
        
        // Add or update triples
        graph.add {
            val node = rdfHandle.node
            person.name.forEach { name ->
                node - FOAF.name - name
            }
            person.age.forEach { age ->
                node - FOAF.age - age
            }
        }
    }
}
```

### Service Layer Pattern

```kotlin
class PersonService(private val repository: PersonRepository) {
    fun createPerson(name: String, age: Int): Person {
        val repo = Rdf.memory()
        val personId = iri("http://example.org/person/${UUID.randomUUID()}")
        
        repo.add {
            personId - RDF.type - FOAF.Person
            personId - FOAF.name - name
            personId - FOAF.age - age
            personId - DCTERMS.created - "2024-01-15"^^XSD.date
        }
        
        return RdfRef(personId, repo.defaultGraph).asType()
    }
    
    fun updatePerson(person: Person, updates: Map<String, Any>) {
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
    }
    
    fun getPersonWithMetadata(person: Person): PersonWithMetadata {
        val rdfHandle = person.asRdf()
        val extras = rdfHandle.extras
        
        return PersonWithMetadata(
            person = person,
            created = extras.strings(DCTERMS.created).firstOrNull(),
            modified = extras.strings(DCTERMS.modified).firstOrNull(),
            notes = extras.strings(SKOS.note),
            tags = extras.strings(SKOS.altLabel)
        )
    }
}

data class PersonWithMetadata(
    val person: Person,
    val created: String?,
    val modified: String?,
    val notes: List<String>,
    val tags: List<String>
)
```

## Error Handling

### Graceful Degradation

```kotlin
fun processPerson(person: Person): ProcessedPerson {
    val rdfHandle = person.asRdf()
    val extras = rdfHandle.extras
    
    return ProcessedPerson(
        name = person.name.firstOrNull() ?: "Unknown",
        age = person.age.firstOrNull() ?: 0,
        email = person.email.firstOrNull(),
        metadata = try {
            PersonMetadata(
                created = extras.strings(DCTERMS.created).firstOrNull(),
                notes = extras.strings(SKOS.note)
            )
        } catch (e: Exception) {
            PersonMetadata() // Default metadata
        }
    )
}
```

### Validation Error Handling

```kotlin
fun validateAndProcess(person: Person): ProcessingResult {
    return try {
        // Try validation first
        person.asRdf().validateOrThrow()
        
        // Process if validation passes
        val processed = processPerson(person)
        ProcessingResult.Success(processed)
    } catch (e: ValidationException) {
        ProcessingResult.ValidationError(e.message ?: "Validation failed")
    } catch (e: Exception) {
        ProcessingResult.ProcessingError(e.message ?: "Processing failed")
    }
}

sealed class ProcessingResult {
    data class Success(val person: ProcessedPerson) : ProcessingResult()
    data class ValidationError(val message: String) : ProcessingResult()
    data class ProcessingError(val message: String) : ProcessingResult()
}
```

## Best Practices

### ✅ Do

- Use side-channel only when needed
- Cache expensive RDF operations
- Handle validation errors gracefully
- Use typed access methods (`strings()`, `iris()`, etc.)
- Implement proper error handling
- Consider performance implications
- Use batch operations for large datasets

### ❌ Don't

- Access side-channel in tight loops
- Ignore validation errors
- Mix RDF operations with domain logic
- Assume properties always exist
- Perform expensive operations synchronously
- Ignore memory usage with large graphs

## Next Steps

- **Learn about [Validation](validation.md)** - SHACL validation patterns
- **Check out [Advanced Usage](advanced-usage.md)** - Complex scenarios
- **See [Practical Examples](../examples/README.md)** - Real-world use cases
- **Review [API Reference](../reference/README.md)** - Complete API documentation



