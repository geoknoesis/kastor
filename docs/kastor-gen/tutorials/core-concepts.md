# Core Concepts

This tutorial explains the fundamental concepts behind Kastor Gen's side-channel architecture and how it differs from traditional RDF mapping approaches.

## The Side-Channel Architecture

Kastor Gen uses a unique **side-channel architecture** that separates domain logic from RDF-specific functionality. This design provides several key benefits:

### Traditional RDF Mapping Problems

Most RDF mapping frameworks tightly couple domain objects with RDF:

```kotlin
// Traditional approach - RDF types leak into domain
class Person : RdfResource {
    val name: String by rdfProperty("foaf:name")
    val age: Int by rdfProperty("foaf:age")
    
    // RDF-specific methods mixed with domain logic
    fun getRdfGraph(): RdfGraph = ...
    fun validateShacl(): ValidationResult = ...
}
```

**Problems with this approach:**
- ❌ Domain objects are tightly coupled to RDF
- ❌ Difficult to test domain logic in isolation
- ❌ RDF concepts leak into business logic
- ❌ Hard to mock for unit tests
- ❌ Complex inheritance hierarchies

### Kastor Gen's Side-Channel Solution

Kastor Gen keeps domain interfaces pure and provides RDF access through a side-channel:

```kotlin
// Pure domain interface - no RDF types
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/age")
    val age: List<Int>
}

// RDF access via side-channel
val person: Person = materializeFromRdf(...)
val rdfHandle = person.asRdf()  // Side-channel access
val extras = rdfHandle.extras   // Unmapped properties
```

**Benefits of this approach:**
- ✅ Pure domain interfaces with no RDF dependencies
- ✅ Easy to test and mock
- ✅ RDF power available when needed
- ✅ Clean separation of concerns
- ✅ Flexible and extensible

## Key Components

### 1. Domain Interfaces

Domain interfaces define your business model using pure Kotlin types:

```kotlin
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/age")
    val age: List<Int>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/knows")
    val friends: List<Person>
}
```

**Characteristics:**
- Pure Kotlin types (`String`, `Int`, `List<T>`)
- No RDF-specific imports
- Easy to understand and maintain
- Can be used independently of RDF

### 2. Generated Wrappers

KSP automatically generates wrapper implementations:

```kotlin
// Generated code (simplified)
internal class PersonWrapper(override val rdf: RdfHandle) : Person, RdfBacked {
    override val name: List<String> by lazy {
        KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name)
            .map { it.lexical }
    }
    
    override val age: List<Int> by lazy {
        KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age)
            .mapNotNull { it.lexical.toIntOrNull() }
    }
    
    override val friends: List<Person> by lazy {
        KastorGraphOps.getObjectValues(rdf.graph, rdf.node, FOAF.knows) { child ->
            kastor.gen.materialize(RdfRef(child, rdf.graph), Person::class.java)
        }
    }
}
```

**Features:**
- Implements both domain interface and `RdfBacked`
- Lazy property evaluation
- Automatic type conversion
- Registry-based materialization

### 3. RDF Side-Channel

The `RdfHandle` provides access to RDF functionality:

```kotlin
interface RdfHandle {
    val node: RdfTerm          // The RDF node (IRI or blank node)
    val graph: RdfGraph        // The containing RDF graph
    val extras: PropertyBag    // Unmapped properties
    
    fun validate(): ValidationResult
    fun validateOrThrow()      // SHACL validation
}
```

**Capabilities:**
- Access to underlying RDF node and graph
- Unmapped property access via `PropertyBag`
- SHACL validation support
- Direct RDF operations when needed

### 4. Property Bag

The `PropertyBag` provides typed access to unmapped RDF properties:

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

**Use cases:**
- Accessing properties not mapped to domain interface
- Dynamic property discovery
- RDF-specific operations
- Integration with external RDF tools

## Materialization Process

### 1. Registration

Wrapper factories are registered automatically during KSP compilation:

```kotlin
// Generated registration code
companion object {
    init {
        kastor.gen.registry[Person::class.java] = { handle -> 
            PersonWrapper(handle) 
        }
    }
}
```

### 2. Materialization

When you call `asType()`, the following happens:

```kotlin
val person: Person = rdfRef.asType()
```

**Process:**
1. `RdfRef.asType()` calls `kastor.gen.materialize()`
2. `OntoMapper` looks up the factory in the registry
3. Factory creates a `DefaultRdfHandle` with the RDF node and graph
4. Factory creates the wrapper instance with the handle
5. Wrapper implements both `Person` and `RdfBacked` interfaces

### 3. Property Access

When you access a property, it's evaluated lazily:

```kotlin
val name = person.name  // Triggers lazy evaluation
```

**Process:**
1. Wrapper's lazy property is accessed
2. `KastorGraphOps.getLiteralValues()` queries the RDF graph
3. Values are converted to the expected type
4. Result is cached for subsequent access

## Type System

### Supported Types

Kastor Gen supports various Kotlin types:

```kotlin
interface Example {
    // Primitive types
    val name: String
    val age: Int
    val height: Double
    val isActive: Boolean
    
    // Collections
    val names: List<String>
    val ages: List<Int>
    val friends: List<Person>
    
    // Optional values (using List with firstOrNull())
    val optionalName: List<String>  // Use firstOrNull() for optional access
}
```

### Type Conversion

Automatic type conversion is provided:

```kotlin
// RDF literal "30" -> Kotlin Int 30
val age: Int = person.age.firstOrNull() ?: 0

// RDF literal "true" -> Kotlin Boolean true
val isActive: Boolean = person.isActive.firstOrNull() ?: false

// Multiple values
val allNames: List<String> = person.names
```

### Custom Types

For complex types, use the side-channel:

```kotlin
// Custom type not directly supported
val customData = person.asRdf().extras.objects(CUSTOM_PREDICATE, CustomType::class.java)
```

## Error Handling

### Materialization Errors

```kotlin
try {
    val person: Person = rdfRef.asType()
} catch (e: IllegalStateException) {
    // No wrapper factory registered
    println("Type not supported: ${e.message}")
}
```

### Validation Errors

```kotlin
try {
    val person: Person = rdfRef.asType(validate = true)
} catch (e: ValidationException) {
    // SHACL validation failed
    println("Validation failed: ${e.message}")
}
```

### Type Conversion Errors

```kotlin
val age: Int? = person.age.mapNotNull { 
    try {
        it.lexical.toInt()
    } catch (e: NumberFormatException) {
        null  // Skip invalid values
    }
}.firstOrNull()
```

## Best Practices

### 1. Keep Interfaces Pure

```kotlin
// ✅ Good - Pure domain interface
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    val name: List<String>
    val age: List<Int>
}

// ❌ Bad - RDF types in domain interface
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    val name: List<String>
    val rdfNode: RdfTerm  // Don't do this!
}
```

### 2. Use Side-Channel for RDF Operations

```kotlin
// ✅ Good - Use side-channel for RDF operations
val person: Person = materializeFromRdf(...)
val rdfHandle = person.asRdf()
val extras = rdfHandle.extras
val unmappedProperties = extras.predicates()

// ❌ Bad - Don't expose RDF in domain interface
interface Person {
    val name: List<String>
    val RdfGraph: RdfGraph  // Don't do this!
}
```

### 3. Handle Optional Values Gracefully

```kotlin
// ✅ Good - Handle optional values
val name = person.name.firstOrNull() ?: "Unknown"
val age = person.age.firstOrNull() ?: 0

// ❌ Bad - Assume values always exist
val name = person.name.first()  // May throw exception
```

### 4. Use Validation Appropriately

```kotlin
// ✅ Good - Validate when needed
val person: Person = rdfRef.asType(validate = true)

// Or validate manually
val rdfHandle = person.asRdf()
rdfHandle.validateOrThrow()
```

## Next Steps

Now that you understand the core concepts:

- **Learn about [Domain Modeling](domain-modeling.md)** - Best practices for domain interfaces
- **Explore [RDF Integration](rdf-integration.md)** - Advanced side-channel usage
- **Check out [Validation](validation.md)** - SHACL validation patterns
- **See [Practical Examples](../examples/README.md)** - Real-world use cases



