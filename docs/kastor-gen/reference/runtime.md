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
import com.geoknoesis.kastor.gen.runtime.*
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm

val node: RdfTerm = /* subject IRI or blank node */
val graph: RdfGraph = /* graph containing that subject's triples */
val person: Person = graph.materialize(node)
val rdfHandle = person.asRdf()  // Extension on RdfBacked
```

### RdfHandle

Side-channel handle for RDF power without polluting domain API.

```kotlin
interface RdfHandle {
    val node: RdfTerm          // Iri or BlankNode
    val graph: RdfGraph        // Kastor Graph (Jena/RDF4J under the hood)
    val extras: PropertyBag    // Unmapped triples (lazy & memoized)
    val isValidationConfigured: Boolean  // false unless built with a ValidationContext

    fun validate(): ValidationResult
    fun validateOrThrow()      // Validate against SHACL shapes when configured
}
```

**Properties:**
- `node: RdfTerm` - The RDF node (IRI or blank node)
- `graph: RdfGraph` - The containing RDF graph
- `extras: PropertyBag` - Unmapped properties
- `isValidationConfigured` - When false, `validate()` / `validateOrThrow()` will error; use `materializeValidated` (or a handle constructed with a `ValidationContext`) to enable SHACL

**Methods:**
- `validate()` - Validate and return `ValidationResult`
- `validateOrThrow()` - Validate and throw on violations

**Usage:**
```kotlin
val rdfHandle = person.asRdf()
val node = rdfHandle.node
val graph = rdfHandle.graph
val extras = rdfHandle.extras

if (rdfHandle.isValidationConfigured) {
    rdfHandle.validateOrThrow()
}
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

    fun <T : Any> materialize(ref: RdfRef, type: Class<T>): T
    fun <T : Any> materializeValidated(ref: RdfRef, type: Class<T>, validation: ValidationContext): T
    fun initialize(vararg types: Class<*>)
}
```

**Properties:**
- `registry` — maps each domain interface class to a wrapper factory installed by generated `companion object` blocks

**Methods:**
- `materialize` — invokes the registered factory with a provisional handle; generated wrappers typically replace `rdf` so `extras` and validation behave correctly
- `materializeValidated` — same, with a non-null `ValidationContext` on the provisional handle, then validates after materialization
- `initialize(Class…​)` — optional eager class-loading of wrapper types to avoid first-hit registration races

**Usage (prefer extensions at call sites):**
```kotlin
import com.geoknoesis.kastor.gen.runtime.*
import com.geoknoesis.kastor.gen.validation.jena.JenaValidation
import com.geoknoesis.kastor.rdf.*

val person: Person = graph.materialize(node)

val validation: ValidationContext = JenaValidation() // optional module: `kastor-gen:validation-jena`
val person2: Person = graph.materializeValidated(node, validation)

val person3 = OntoMapper.materialize(ref, Person::class.java)
val person4 = OntoMapper.materializeValidated(ref, Person::class.java, validation)
```

| API | When to use |
|-----|-------------|
| `graph.materialize<T>(node)` | Default Kotlin call site |
| `repo.materialize<T>(node)` | When you already hold a repository |
| `node.materializeIn<T>(graph)` | Reads left-to-right after building an IRI |
| `RdfRef.asType<T>()` | You are passing `(node, graph)` around |
| `OntoMapper.materialize` | Non-reified `Class<T>` or Java callers |

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
    private val known: Set<Iri>,
    internal val validationContext: ValidationContext? = null,
) : RdfHandle
```

**Constructor parameters:**
- `node` — subject focus
- `graph` — backing graph
- `known` — predicates mapped on the wrapper; excluded from `extras`
- `validationContext` — when non-null, powers `validate()` / `validateOrThrow()` on that handle

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
- `getObjectValues(graph: RdfGraph, subj: RdfTerm, pred: Iri, factory: (RdfTerm) -> T): List<T>` - Get object values; `IllegalStateException` and `ValidationException` from `factory` propagate; other exceptions omit that object

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

### Serialization Extensions

```kotlin
fun <T : RdfBacked> T.writeToGraph(
    targetGraph: MutableRdfGraph,
    subject: Iri? = null
)
```

**Parameters:**
- `targetGraph: MutableRdfGraph` - The mutable graph to write triples to
- `subject: Iri? = null` - Optional subject IRI. If not provided, uses `rdf.node` as Iri

**Throws:**
- `IllegalArgumentException` - If subject is required but not available

**Description:**

Writes the CBD (Concise Bounded Description) closure of this RDF-backed instance to the target graph. CBD includes:

1. All triples where the resource is the subject (direct properties)
2. Recursively, for any blank node object, all triples where that blank node is the subject

This method extracts the complete resource description from the backing graph and writes it to the target graph, following blank nodes recursively but not following IRIs.

**Usage:**
```kotlin
import com.geoknoesis.kastor.gen.runtime.writeToGraph
import com.geoknoesis.kastor.rdf.Rdf

val person: Person = // ... your instance
val targetGraph = Rdf.graph()

// Write CBD closure (uses rdf.node as subject)
person.writeToGraph(targetGraph)

// Write to different subject
person.writeToGraph(targetGraph, subject = Iri("http://example.org/copy"))

// Serialize the CBD closure
val turtle = targetGraph.serialize(RdfFormat.TURTLE)
```

**Note**: The `writeToGraph()` method is available both as:
- An extension function on `RdfBacked` types (recommended)
- A generated method on wrapper classes (for direct wrapper access)

**See also:**
- [Serializing Domain Instances](../guides/serializing-domain-instances.md) - Complete guide to serialization
- [CBD Closure](#cbd-closure) - Understanding Concise Bounded Description

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
- **Domain objects**: Interfaces annotated with `@Rdf` (domain mode: non-blank `iri`, no `shacl`)

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
import com.geoknoesis.kastor.gen.runtime.RdfRef

val person: Person = graph.materialize(node)
// No RDF queries yet (until property delegates run)

val name = person.name.firstOrNull()  // Now RDF query is executed
val name2 = person.name.firstOrNull()  // Uses cached result
```

### Memory Usage

- Property bags are lazy and memoized
- Large graphs should be processed in batches
- Consider using pagination for large datasets

## Thread Safety

- `OntoMapper.registry` is not thread-safe
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

## CBD Closure

### What is CBD?

**CBD (Concise Bounded Description)** is a standard RDF pattern for extracting a complete description of a resource. It includes:

1. **Direct properties**: All triples where the resource is the subject
2. **Recursive blank nodes**: For any blank node object, all triples where that blank node is the subject (recursively)

### Key Characteristics

- ✅ **Follows blank nodes recursively**: Complete anonymous resource descriptions
- ✅ **Does not follow IRIs**: IRI objects remain as references (not expanded)
- ✅ **Prevents cycles**: Uses visited set to avoid infinite recursion
- ✅ **Complete descriptions**: Includes all nested anonymous structures

### Example

```kotlin
// Graph structure:
// :alice foaf:name "Alice"
// :alice foaf:knows _:b1
// _:b1 foaf:name "Bob"
// _:b1 foaf:email "bob@example.com"
// _:b1 foaf:knows _:b2
// _:b2 foaf:name "Charlie"

val person: Person = // ... alice instance

val targetGraph = Rdf.graph()
person.writeToGraph(targetGraph)

// targetGraph now contains all 6 triples:
// - :alice foaf:name "Alice"          (direct property)
// - :alice foaf:knows _:b1            (direct property, blank node object)
// - _:b1 foaf:name "Bob"              (blank node property, followed recursively)
// - _:b1 foaf:email "bob@example.com" (blank node property, followed recursively)
// - _:b1 foaf:knows _:b2              (blank node property, blank node object)
// - _:b2 foaf:name "Charlie"          (nested blank node property, followed recursively)
```

### When to Use CBD

**Use CBD closure** when:
- ✅ You need the complete resource description (including blank node properties)
- ✅ Exporting a single resource with all its nested anonymous structures
- ✅ Copying an instance to a different graph
- ✅ Implementing RDFBeans-like bidirectional conversion (domain object ↔ RDF)

**Don't use CBD closure** when:
- ❌ You need only direct properties (no blank node recursion)
- ❌ You need custom filtering logic
- ❌ You need to include incoming references (triples where instance is object)

### Implementation

CBD closure extraction is implemented as an extension function on `RdfGraph`:

```kotlin
fun RdfGraph.getCbdClosure(resource: RdfResource): Set<RdfTriple>
```

This function is used internally by `writeToGraph()` but can also be used directly:

```kotlin
import com.geoknoesis.kastor.rdf.getCbdClosure

val cbdTriples = graph.getCbdClosure(Iri("http://example.org/person"))
val cbdGraph = Rdf.graph {
    cbdTriples.forEach { add(it) }
}
```



