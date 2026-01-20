# Runtime API Reference

Complete reference for Kastor Gen runtime interfaces and classes.

## Core Interfaces

### RdfBacked

Marker interface for domain instances backed by an RDF node.

```kotlin
interface RdfBacked {
    val rdf: RdfHandle
}
```

**Properties:**
- `rdf: RdfHandle` - Side-channel handle for RDF access

**Usage:**
```kotlin
val person: Person = materializeFromRdf(...)
val rdfHandle = person.asRdf()  // Extension function
```

### RdfHandle

Side-channel handle for RDF power without polluting domain API.

```kotlin
interface RdfHandle {
    val node: RdfTerm          // Iri or BlankNode
    val graph: RdfGraph        // Kastor Graph (Jena/RDF4J under the hood)
    val extras: PropertyBag    // Unmapped triples (lazy & memoized)
    
    fun validate(): ValidationResult
    fun validateOrThrow()      // Validate against SHACL shapes
}
```

**Properties:**
- `node: RdfTerm` - The RDF node (IRI or blank node)
- `graph: RdfGraph` - The containing RDF graph
- `extras: PropertyBag` - Unmapped properties

**Methods:**
- `validate()` - Validate and return `ValidationResult`
- `validateOrThrow()` - Validate and throw on violations

**Usage:**
```kotlin
val rdfHandle = person.asRdf()
val node = rdfHandle.node
val graph = rdfHandle.graph
val extras = rdfHandle.extras

// Validate
rdfHandle.validateOrThrow()
```

### PropertyBag

Strongly typed property bag for unmapped RDF properties.

```kotlin
interface PropertyBag {
    fun predicates(): Set<Iri>
    fun values(pred: Iri): List<RdfTerm>
    fun literals(pred: Iri): List<Literal>
    fun strings(pred: Iri): List<String>
    fun iris(pred: Iri): List<Iri>
    fun <T : Any> objects(pred: Iri, asType: Class<T>): List<T>
}
```

**Methods:**
- `predicates(): Set<Iri>` - Get all unmapped predicates
- `values(pred: Iri): List<RdfTerm>` - Get all values for a predicate
- `literals(pred: Iri): List<Literal>` - Get literal values only
- `strings(pred: Iri): List<String>` - Get string values
- `iris(pred: Iri): List<Iri>` - Get IRI values
- `objects(pred: Iri, asType: Class<T>): List<T>` - Materialize object values

**Usage:**
```kotlin
val extras = person.asRdf().extras

// Get all unmapped predicates
val predicates = extras.predicates()

// Get string values
val altLabels = extras.strings(SKOS.altLabel)

// Get materialized objects
val relatedPeople = extras.objects(FOAF.knows, Person::class.java)
```

## Core Classes

### OntoMapper

Central materializer populated by generated registration code.

```kotlin
object OntoMapper {
    val registry: MutableMap<Class<*>, (RdfHandle) -> Any>
    
    fun <T: Any> materialize(ref: RdfRef, type: Class<T>, validation: ValidationContext? = null): T
    fun <T: Any> materializeValidated(ref: RdfRef, type: Class<T>, validation: ValidationContext? = null): T
}
```

**Properties:**
- `registry: MutableMap<Class<*>, (RdfHandle) -> Any>` - Registry of wrapper factories

**Methods:**
- `materialize(ref: RdfRef, type: Class<T>, validation: ValidationContext? = null): T` - Materialize RDF data into domain object
- `materializeValidated(ref: RdfRef, type: Class<T>, validation: ValidationContext? = null): T` - Materialize and validate

**Usage:**
```kotlin
// Register wrapper factory (usually done by generated code)
kastor.gen.registry[Person::class.java] = { handle -> PersonWrapper(handle) }

// Materialize
val validation = JenaValidation()
val person = kastor.gen.materializeValidated(ref, Person::class.java, validation)
```

### RdfRef

Reference to an RDF node in a specific graph.

```kotlin
data class RdfRef(val node: RdfTerm, val graph: RdfGraph)
```

**Properties:**
- `node: RdfTerm` - The RDF node
- `graph: RdfGraph` - The containing graph

**Usage:**
```kotlin
val ref = RdfRef(iri("http://example.org/person"), graph)
val person: Person = ref.asType()
```

### DefaultRdfHandle

Default implementation of `RdfHandle`.

```kotlin
class DefaultRdfHandle(
    override val node: RdfTerm,
    override val graph: RdfGraph,
    private val known: Set<Iri>
) : RdfHandle
```

**Constructor Parameters:**
- `node: RdfTerm` - The RDF node
- `graph: RdfGraph` - The containing graph
- `known: Set<Iri>` - Set of known predicates to exclude from extras

**Usage:**
```kotlin
val handle = DefaultRdfHandle(node, graph, setOf(FOAF.name, FOAF.age))
```

## Utility Classes

### KastorGraphOps

Utility object for graph operations.

```kotlin
object KastorGraphOps {
    fun extras(graph: RdfGraph, subj: RdfTerm, exclude: Set<Iri>): PropertyBag
    fun getLiteralValues(graph: RdfGraph, subj: RdfTerm, pred: Iri): List<Literal>
    fun getRequiredLiteralValue(graph: RdfGraph, subj: RdfTerm, pred: Iri): Literal
    fun <T: Any> getObjectValues(graph: RdfGraph, subj: RdfTerm, pred: Iri, factory: (RdfTerm) -> T): List<T>
}
```

**Methods:**
- `extras(graph: RdfGraph, subj: RdfTerm, exclude: Set<Iri>): PropertyBag` - Create property bag
- `getLiteralValues(graph: RdfGraph, subj: RdfTerm, pred: Iri): List<Literal>` - Get literal values
- `getRequiredLiteralValue(graph: RdfGraph, subj: RdfTerm, pred: Iri): Literal` - Get required literal value
- `getObjectValues(graph: RdfGraph, subj: RdfTerm, pred: Iri, factory: (RdfTerm) -> T): List<T>` - Get object values

**Usage:**
```kotlin
val literals = KastorGraphOps.getLiteralValues(graph, node, FOAF.name)
val required = KastorGraphOps.getRequiredLiteralValue(graph, node, FOAF.name)
val objects = KastorGraphOps.getObjectValues(graph, node, FOAF.knows) { term ->
    materializeObject(term)
}
```

### PropertyBagImpl

Internal implementation of `PropertyBag`.

```kotlin
internal class PropertyBagImpl(
    private val graph: RdfGraph,
    private val subj: RdfTerm,
    private val exclude: Set<Iri>
) : PropertyBag
```

**Constructor Parameters:**
- `graph: RdfGraph` - The RDF graph
- `subj: RdfTerm` - The subject node
- `exclude: Set<Iri>` - Predicates to exclude

## Extension Functions

### Materialization Extensions

```kotlin
inline fun <reified T: Any> RdfRef.asType(validation: ValidationContext? = null): T
inline fun <reified T: Any> RdfRef.asValidatedType(validation: ValidationContext? = null): T
```

**Parameters:**
- `validation: ValidationContext? = null` - Validation context to use (optional)

**Returns:**
- `T` - Materialized domain object

**Usage:**
```kotlin
val validation = JenaValidation()
val person: Person = ref.asValidatedType(validation)
```

### RDF Access Extensions

```kotlin
inline fun <reified T: Any> T.asRdf(): RdfHandle
```

**Returns:**
- `RdfHandle` - RDF side-channel handle

**Usage:**
```kotlin
val rdfHandle = person.asRdf()
```

## Error Handling

### ValidationException

Exception thrown when SHACL validation fails.

```kotlin
class ValidationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

**Constructor Parameters:**
- `message: String` - Error message
- `cause: Throwable? = null` - Optional cause

**Usage:**
```kotlin
try {
    rdfHandle.validateOrThrow()
} catch (e: ValidationException) {
    println("Validation failed: ${e.message}")
}
```

## Type System

### Supported Types

OntoMapper supports various Kotlin types in domain interfaces:

- **Primitive types**: `String`, `Int`, `Double`, `Boolean`
- **Collections**: `List<T>` where T is a supported type
- **Domain objects**: Interfaces annotated with `@RdfClass`

### Type Conversion

Automatic type conversion is provided for:
- RDF literals to Kotlin primitives
- RDF objects to domain interfaces
- Collections of RDF values to Kotlin collections

### Custom Types

For custom types not directly supported, use the side-channel:

```kotlin
val customData = person.asRdf().extras.objects(CUSTOM_PREDICATE, CustomType::class.java)
```

## Performance Considerations

### Lazy Evaluation

Properties are evaluated lazily and cached:

```kotlin
val person: Person = materializeFromRdf(...)
// No RDF queries yet

val name = person.name.firstOrNull()  // Now RDF query is executed
val name2 = person.name.firstOrNull()  // Uses cached result
```

### Memory Usage

- Property bags are lazy and memoized
- Large graphs should be processed in batches
- Consider using pagination for large datasets

## Thread Safety

- `kastor.gen.registry` is not thread-safe
- `RdfHandle` instances are not thread-safe
- `PropertyBag` instances are not thread-safe
- Use synchronization for concurrent access

## Best Practices

### ✅ Do

- Use `List<T>` for all properties (single or multiple values)
- Access single values with `firstOrNull()`
- Use side-channel for RDF-specific operations
- Handle validation errors gracefully
- Cache expensive operations when appropriate

### ❌ Don't

- Include RDF types in domain interfaces
- Assume properties always have values
- Mix RDF operations with domain logic
- Ignore validation errors
- Access side-channel in tight loops



