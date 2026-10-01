# Runtime API Reference

Complete reference for Kastor Gen runtime interfaces and classes.

## Core Interfaces

### RdfProjection

Marker interface for immutable data-class snapshots produced by a generated factory.

```kotlin
interface RdfProjection
```

Every data class generated with `generateDataClass = true` implements `RdfProjection`. This lets code distinguish eagerly-loaded snapshots from live `RdfBacked` wrappers at the type level:

```kotlin
when (instance) {
  is RdfProjection -> { /* immutable snapshot — structural equality, copy() */ }
  is RdfBacked     -> { /* live wrapper — lazy delegates, side-channel access */ }
}
```

`RdfProjection` instances carry **no `RdfHandle`** and have no RDF dependencies in their class body. All data was loaded eagerly at factory time.

---

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
    fun <T : Any> register(type: Class<T>, factory: (RdfHandle) -> T)
    fun <T : Any> register(type: Class<T>, replace: Boolean, factory: (RdfHandle) -> T)
    fun unregister(type: Class<*>): Boolean
    fun isRegistered(type: Class<*>): Boolean
    fun registeredTypes(): Set<Class<*>>

    fun <T : Any> materialize(ref: RdfRef, type: Class<T>): T
    fun <T : Any> materializeValidated(ref: RdfRef, type: Class<T>, validation: ValidationContext): T
    fun initialize(vararg types: Class<*>)
}
```

The factory registry itself is private (a `ConcurrentHashMap`); use the functions below.

**Registration:**
- `register(type, factory)` — registers the factory for `type`. Generated wrappers call it from their `companion object` `init` block and data-class factories from their `object` `init` block (`OntoMapper.register(Person::class.java) { handle -> PersonWrapper(handle) }`). You rarely call it yourself, except to plug in a hand-written implementation. A type has **one** factory, keyed by the interface **class**:
  1. *Each class has its own registration.* An interface defined by two class loaders (two plugins that each bundle the generated code) is two types with independent factories.
  2. *Registering the same factory class again is idempotent.* The same instance is a no-op; another instance of the same factory class (a capturing lambda evaluated again, e.g. in `@BeforeEach`) takes its place.
  3. *A different factory class is a conflict* and throws `IllegalStateException` (for example when two generated modules claim the same interface), because it would silently change how the whole application materializes that type. Use `replace = true` or `unregister` first.
  4. *Class reloading.* When the registered factory is the same-named factory class defined by a *different* class loader (a hot-reload framework re-defined the wrapper while `type` stays in a shared parent loader), the new factory replaces the stale one, and the old class loader is no longer reachable from the registry. This is indistinguishable from two live plugins bundling the same wrapper for a shared interface (the last one wins), so the replacement is logged as a **warning** naming both class loaders.

  The registry holds registered classes weakly (the factory is stored with the class): when a module's class loader is discarded with its interfaces and wrappers, the registry does not keep it alive and no `unregister` is needed. A factory registered for an interface of a longer-lived class loader keeps its own class loader alive until it is replaced or unregistered.
- `register(type, replace = true, factory)` — replaces an existing factory deliberately (tests, plugins). With `replace = false` it behaves like the two-argument form.
- `unregister(type)` — removes a factory; returns `true` if one was registered.
- `isRegistered(type)` / `registeredTypes()` — inspect the registry (a snapshot copy).

**Methods:**
- `materialize` — invokes the registered factory with a provisional handle; generated wrappers typically replace `rdf` so `extras` and validation behave correctly. If the class is not yet registered, `OntoMapper` attempts to class-load `${type.name}Wrapper` and then `${type.name}Factory` before erroring.
- `materializeValidated` — same, with a non-null `ValidationContext` on the provisional handle, then validates the focus node and throws `ValidationException` on violations
- `initialize(Class…​)` — optional eager class-loading of wrapper or factory types to avoid first-hit registration races

**Nested materialization and cycles:** each outermost `materialize` call opens a per-thread scope. Within
that call, a node already materialized as the same type from the same graph is reused (shared nodes are
built once). Re-entering a node that is *still being built* throws a `MaterializationException` naming the
cycle (for example `PersonRecord <a> -> PersonRecord <b> -> PersonRecord <a>`) instead of overflowing the
stack. This only affects eagerly-loaded snapshots generated with `NestedMode.DATA_CLASS` over cyclic data
such as `a foaf:knows b . b foaf:knows a`; live wrappers load nested objects lazily and are unaffected. Use
`NestedMode.INTERFACE` or `NestedMode.IRI_ONLY` for cyclic data.

**Auto-discovery order** (on first `materialize` call for a type not yet registered):

1. Try to load `${type.name}Wrapper` — registers a live `RdfBacked` wrapper.
2. If still unregistered, try to load `${type.name}Factory` — registers an eagerly-loading data-class factory.
3. If still unregistered, throw `IllegalStateException`.

**Usage (prefer extensions at call sites):**
```kotlin
import com.geoknoesis.kastor.gen.runtime.*
import com.geoknoesis.kastor.gen.validation.jena.JenaValidation
import com.geoknoesis.kastor.rdf.*

val person: Person = graph.materialize(node)

val validation: ValidationContext = JenaValidation.fromTurtle(shapesTtl) // optional module `kastor-gen:validation-jena`
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
    fun getValues(graph: RdfGraph, subj: RdfTerm, pred: Iri): List<RdfTerm>

    // Policy-aware readers: values of an unexpected term kind follow MaterializationPolicy.unexpectedTerm
    fun getIriValues(graph: RdfGraph, subj: RdfTerm, pred: Iri, property: String): List<Iri>
    fun getResourceValues(graph: RdfGraph, subj: RdfTerm, pred: Iri, property: String): List<RdfResource>
    fun getLiteralValues(graph: RdfGraph, subj: RdfTerm, pred: Iri, property: String): List<Literal>
    fun <T : Any> getObjectValues(graph: RdfGraph, subj: RdfTerm, pred: Iri, property: String, factory: (RdfTerm) -> T): List<T>

    fun hasNodeKind(term: RdfTerm, nodeKind: Iri): Boolean
    fun isInstanceOf(graph: RdfGraph, term: RdfTerm, cls: Iri): Boolean
}
```

**Methods:**
- `extras(graph: RdfGraph, subj: RdfTerm, exclude: Set<Iri>): PropertyBag` - Create property bag
- `getLiteralValues(graph: RdfGraph, subj: RdfTerm, pred: Iri): List<Literal>` - Get literal values
- `getRequiredLiteralValue(graph: RdfGraph, subj: RdfTerm, pred: Iri): Literal` - Get required literal value
- `getObjectValues(graph: RdfGraph, subj: RdfTerm, pred: Iri, factory: (RdfTerm) -> T): List<T>` - Materialize IRI and blank-node objects (literal objects are skipped). Failures are never silently dropped: `Error`, `ValidationException` and `MaterializationException` from `factory` propagate unchanged; any other exception is rethrown as a `MaterializationException` naming the subject, predicate and object
- `getValues(graph, subj, pred): List<RdfTerm>` - All objects (IRIs, blank nodes, literals, triple terms)
- `hasNodeKind(term, nodeKind): Boolean` - SHACL `sh:nodeKind` test (`sh:IRI`, `sh:BlankNode`, `sh:Literal`, `sh:BlankNodeOrIRI`, `sh:BlankNodeOrLiteral`, `sh:IRIOrLiteral`); any other IRI throws `IllegalArgumentException`
- `isInstanceOf(graph, term, cls): Boolean` - SHACL `sh:class` test: an `rdf:type` equal to `cls` or a transitive `rdfs:subClassOf` of it; literals are never instances

The last three are used by generated embedded validation.

The overloads taking a `property` label (used in error messages) never drop values silently: a value of an
unexpected term kind is passed to [`MaterializationPolicy.unexpectedTerm`](#materializationpolicy), which
throws `MaterializationException` by default and skips the value under `IllTypedValueHandling.SKIP`.

- `getIriValues(graph, subj, pred, property): List<Iri>` - IRI values; blank nodes, literals and triple terms are unexpected
- `getResourceValues(graph, subj, pred, property): List<RdfResource>` - IRI and blank-node values (`sh:BlankNodeOrIRI`); literals and triple terms are unexpected
- `getLiteralValues(graph, subj, pred, property): List<Literal>` - literal values; IRIs, blank nodes and triple terms are unexpected
- `getObjectValues(graph, subj, pred, property, factory): List<T>` - like `getObjectValues` above, but literals and triple terms are unexpected instead of skipped

Generated SHACL wrappers and data-class factories, and wrappers for hand-written `Iri` / `RdfResource`
members, read through these overloads.

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
inline fun <reified T: Any> RdfRef.asType(): T
inline fun <reified T: Any> RdfRef.asValidatedType(validation: ValidationContext): T

inline fun <reified T : Any> RdfGraph.materialize(node: RdfTerm): T
inline fun <reified T : Any> RdfGraph.materializeValidated(node: RdfTerm, validation: ValidationContext): T
```

**Parameters:**
- `validation: ValidationContext` - Validation context (required for the `…Validated…` variants)

**Returns:**
- `T` - Materialized domain object

**Usage:**
```kotlin
val validation = JenaValidation.fromTurtle(shapesTtl) // shapesTtl: your SHACL shapes as Turtle text
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

### Write-Support Extensions

Graph mutation helpers for integrating `toTriples` output into a live `MutableRdfGraph`. Declared in `WriteSupport.kt` in the `kastor-gen:runtime` module.

```kotlin
fun MutableRdfGraph.replaceValues(
    subject: RdfResource,
    predicate: Iri,
    newTriples: Collection<RdfTriple>,
)

fun MutableRdfGraph.replaceResource(
    subject: RdfResource,
    triples: Collection<RdfTriple>,
)
```

**`replaceValues`** — targeted predicate-level update. Removes all existing triples for the `(subject, predicate)` pair, then adds `newTriples`. Use this when you have updated a single property and want to replace only those triples without touching others.

**`replaceResource`** — full-resource swap. Removes **all** triples for `subject`, then adds `triples`. Use this after `toTriples()` returns the complete new description for a resource.

**Usage:**

```kotlin
// Targeted: replace only the name values
val nameTriples = listOf(RdfTriple(subject, NAME_IRI, Literal("New Name")))
graph.replaceValues(subject, NAME_IRI, nameTriples)

// Full swap: replace everything about a resource
val allTriples = PersonRecordFactory.toTriples(updatedRecord, subject)
graph.replaceResource(subject, allTriples)
```

Both helpers are no-ops for the removal step when there are no matching existing triples, and no-ops for the add step when `newTriples` / `triples` is empty.

---

### Serialization Extensions (live wrappers)

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
class ValidationException(
    message: String,
    val violations: List<ShaclViolation> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

**Usage:**
```kotlin
try {
    rdfHandle.validateOrThrow()
} catch (e: ValidationException) {
    e.violations.forEach { println("${it.path?.value}: ${it.message}") }
}
```

See the [Validation API Reference](validation.md).

### MaterializationException

```kotlin
class MaterializationException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
```

Thrown when materializing a domain object fails: a required value is missing, a literal value is ill-typed
for its member under `IllTypedValueHandling.THROW` (see [MaterializationPolicy](#materializationpolicy)), a
value has an unexpected term kind (for example a literal where an IRI is expected) under the same policy, a
nested value could not be converted, a factory threw (wrapped by `KastorGraphOps.getObjectValues`), or an
eagerly-loaded `NestedMode.DATA_CLASS` snapshot graph is cyclic (the message names the cycle). Because it
extends `IllegalStateException`, existing `catch (e: IllegalStateException)` blocks still catch it. Earlier
versions silently dropped nested values that failed to materialize and ill-typed literal values; they now
surface as this exception.

### MaterializationPolicy

```kotlin
enum class IllTypedValueHandling { THROW, SKIP }

object MaterializationPolicy {
    @JvmStatic var illTypedValues: IllTypedValueHandling      // default THROW; see "Scope" below
    @JvmStatic fun <T> withIllTypedValues(handling: IllTypedValueHandling, block: () -> T): T

    @JvmStatic fun illTyped(literal: Literal, property: String, expected: String): Nothing?   // called by generated code
    @JvmStatic fun unexpectedTerm(value: RdfTerm, property: String, expected: String): Nothing? // called by generated code
    @JvmStatic fun missingRequired(property: String): Nothing                               // called by generated code
}
```

Rules applied by every generated reader: SHACL live wrappers, SHACL data-class factories, and wrappers
generated for hand-written `@Rdf` interfaces.

1. **Missing required values always throw** a `MaterializationException` with the message
   `Required value missing for <member> <path>`, followed by `of shape <shapeIri>` for SHACL-generated types.
   A member is required when it is a single-valued member with `sh:minCount >= 1`, a list member with
   `sh:minCount >= 1` that is empty, or a non-null member of a hand-written `@Rdf` interface. There is no
   lenient option. A nullable member without a value reads `null`; an optional list reads empty.
2. **Values that fail to decode** (e.g. `"abc"^^xsd:integer` read as a number) follow `illTypedValues`:
   - `THROW` (default): reading fails with a `MaterializationException` naming the value, its datatype, the
     member and the expected type.
   - `SKIP`: the value is left out of the result and a warning is logged; the remaining values are read.

   For hand-written `@Rdf` wrappers this covers read-only decoded lists, nullable members, non-null members
   and mutable getters, which used to skip such values silently.
3. **Values of an unexpected term kind** (a literal for an IRI or object member, an IRI for a literal member)
   go through `unexpectedTerm` and follow the same handling: `THROW` (default) raises a
   `MaterializationException` naming the value, the member and the expected kind; `SKIP` leaves the value out
   and logs a warning. This applies to SHACL-generated wrappers and data-class factories, and to `Iri`,
   `RdfResource` and IRI-valued `sh:in` enum members of hand-written `@Rdf` interfaces. Literal members of
   hand-written interfaces (including `String`) read literals only.
4. **Under `SKIP` the rules combine:** if every value of a required member was skipped, the member counts as
   missing and rule 1 throws.

```kotlin
MaterializationPolicy.illTypedValues = IllTypedValueHandling.SKIP  // e.g. at startup, for messy data
```

#### Scope

`illTypedValues` is the process-wide default. `withIllTypedValues(handling) { … }` overrides it for the
**current thread only** while the block runs, then restores the previous handling; nested scopes restore
the enclosing value. Tests and request handlers can therefore use a different policy without racing other
threads.

```kotlin
val catalog = MaterializationPolicy.withIllTypedValues(IllTypedValueHandling.SKIP) {
    graph.materialize<Catalog>(node)        // the wrapper is created inside the block and keeps SKIP
}
catalog.title                               // read later, on any thread: still SKIP
```

**A wrapper uses the handling in effect when it was created.** Wrapper members are read on demand, so each
wrapper captures the handling at creation (`OntoMapper.materialize` or a factory call) and applies it to every
later read of every member: `val` members (lazy, read once) and the `var` members of `@Rdf` wrappers (read
from the graph on every access) alike, after the block has ended and on other threads too. The converse also
holds: a scope around a *read* does not change a wrapper that already exists.

```kotlin
val strict = graph.materialize<Catalog>(node)                       // created under THROW
MaterializationPolicy.withIllTypedValues(IllTypedValueHandling.SKIP) {
    strict.title                                                    // still THROW: wrap materialize() instead
}
```

Wrappers reached through an object member are created when that member is read, under the handling of the
wrapper they are read from. Data-class factories read every value eagerly, under the handling in effect
during the factory call.

The override itself is thread-local: it does **not** propagate to other threads or coroutines, so a wrapper
*created* on an executor thread, or in a coroutine that resumed on another thread, captures the process-wide
default. Assigning `illTypedValues` changes the process-wide default; a scope running on the current thread
still wins.

The generic literal delegates in `com.geoknoesis.kastor.gen.runtime.delegates` apply the same policy:

- `rdfLiteral(predicate, decoder)`: the first decoded value; throws `MaterializationException` when no value
  (remains) to return.
- `rdfLiteralOrNull(predicate, decoder)`: the first decoded value or `null`.
- `rdfLiterals(predicate, decoder)`: all decoded values as a list.

A value the `decoder` rejects (returns `null` for) follows `illTypedValues`. Decoders from `XsdLiterals`
fit directly, e.g. `rdfLiteral(DCTERMS.title, XsdLiterals::string)`.

The fixed-type delegates `rdfString`, `rdfInt`, `rdfDouble` and `rdfBoolean` are **deprecated**: they invent
a default (`""`, `0`, `0.0`, `false`) when the value is missing instead of throwing. So are `rdfIntOrNull`,
`rdfDoubleOrNull`, `rdfBooleanOrNull` and the list variants `rdfInts`, `rdfDoubles` and `rdfBooleans`, which
silently drop values they cannot decode instead of following `MaterializationPolicy`. Each deprecation
carries a replacement:

| Deprecated | Replacement |
|---|---|
| `rdfString(p)` | `rdfLiteral(p, XsdLiterals::string)` |
| `rdfInt(p)` / `rdfIntOrNull(p)` / `rdfInts(p)` | `rdfLiteral` / `rdfLiteralOrNull` / `rdfLiterals` with `XsdLiterals::int` |
| `rdfDouble(p)` / `rdfDoubleOrNull(p)` / `rdfDoubles(p)` | `rdfLiteral` / `rdfLiteralOrNull` / `rdfLiterals` with `XsdLiterals::double` |
| `rdfBoolean(p)` / `rdfBooleanOrNull(p)` / `rdfBooleans(p)` | `rdfLiteral` / `rdfLiteralOrNull` / `rdfLiterals` with `XsdLiterals::boolean` |

Generated code does not use the deprecated delegates. `rdfObject` and `rdfIri` throw
`MaterializationException` when the required value is missing.

## Type System

### Supported Types

- **Hand-written domain interfaces** (`@Rdf(iri = …)`, no `shacl`), single, nullable or `List` members of:
  - literal types `String`, `Int`, `Long`, `Float`, `Double`, `Boolean`, `java.math.BigInteger`,
    `java.math.BigDecimal`, `java.time.LocalDate` and `com.geoknoesis.kastor.rdf.LangString`, read through
    the policy-aware `XsdLiterals` decoders (`String` members read literals only);
  - Kotlin `enum class` types, read by constant name, and enums generated from `sh:in`, read through their
    `from` factory (literal codes or IRIs);
  - the RDF terms `Iri` and `RdfResource` (IRI or blank node);
  - other `@Rdf` domain interfaces.

  A missing value of a non-null member throws `MaterializationException`. See the
  [annotations reference](annotations.md#processing-rules).
- **SHACL-generated types**: literal types follow the shape's `sh:datatype` — see the
  [type-mapping table](../tutorials/ontology-generation.md#type-mapping) (`xsd:integer` → `BigInteger`,
  `xsd:decimal` → `BigDecimal`, `xsd:date` → `LocalDate`, `rdf:langString` → `LangString`, …).

### XsdLiterals

`com.geoknoesis.kastor.gen.runtime.XsdLiterals` holds the lexical codecs used by generated wrappers,
data-class factories, writers and DSL builders. You can use it in hand-written code too:

```kotlin
object XsdLiterals {
    fun string(literal: Literal): String
    fun int(literal: Literal): Int?
    fun long(literal: Literal): Long?
    fun bigInteger(literal: Literal): BigInteger?
    fun bigDecimal(literal: Literal): BigDecimal?
    fun float(literal: Literal): Float?          // accepts INF, -INF, NaN
    fun double(literal: Literal): Double?        // accepts INF, -INF, NaN
    fun boolean(literal: Literal): Boolean?      // "true"/"1" and "false"/"0"
    fun booleanLexical(lexical: String): Boolean?
    fun localDate(literal: Literal): LocalDate?  // optional timezone suffix accepted and dropped
    fun langString(literal: Literal): LangString?
    fun encode(value: Any, datatype: Iri): Literal

    fun isWellFormed(literal: Literal): Boolean          // lexical form valid for the literal's own datatype
    fun hasDatatype(term: RdfTerm, datatype: Iri): Boolean // SHACL sh:datatype
    fun compareNumeric(term: RdfTerm, bound: String): Int? // exact comparison for sh:minInclusive & co.
}
```

- Decoders return `null` for ill-typed lexical forms and follow XML Schema lexical rules rather than
  Kotlin's:
  - integers (`int`, `long`, `bigInteger`) accept `[+-]?digits`, so a leading `+` is valid; `int`/`long`
    are range-checked;
  - `bigDecimal` accepts `[+-]?(digits[.digits] | .digits)`; exponents (`1e3`) are not decimal syntax and
    are rejected;
  - `localDate` accepts years with more than four digits (`12345-06-07`, without the `+` ISO-8601 would
    require) and negative years (`-0044-03-15`); `encode` writes such dates back in the same form, so
    years above 9999 round-trip.
- `isWellFormed` checks XSD numeric, boolean and date datatypes, including the ranges of the derived integer
  types; other datatypes are accepted. `hasDatatype` requires a literal of exactly that datatype with a
  well-formed lexical form. `compareNumeric` returns the sign of `value - bound`, or `null` when the value
  is not a well-formed numeric literal or is `NaN`. Generated embedded validation uses these three.
- `encode` always produces a literal with the **declared** datatype (literals such as `LangString` are
  returned unchanged), so values round-trip: a `String` property declared `xsd:dateTime` is written back
  as `"…"^^xsd:dateTime`, not as a plain string.

### Regenerate after upgrading

Generated wrappers, factories and DSL builders call these runtime APIs (`OntoMapper.register`,
`XsdLiterals`, …). Code generated by earlier Kastor Gen versions (which wrote to `OntoMapper.registry`
directly) does not compile against this runtime: regenerate it (clean build / re-run the generation task)
when upgrading.

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

- `OntoMapper` registration and lookup are thread-safe (the registry is a concurrent map); call `OntoMapper.initialize(...)` at startup to avoid first-hit class-loading races
- The cycle-detection scope of `materialize` is per thread
- Wrapper property delegates are memoized with synchronized one-shot initialization; the underlying graph's own thread-safety rules still apply

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



