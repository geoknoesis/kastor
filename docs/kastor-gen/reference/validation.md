# Validation API Reference

Reference for the Kastor Gen validation types (module `kastor-gen:runtime`) and the two SHACL engine
adapters (`kastor-gen:validation-jena`, `kastor-gen:validation-rdf4j`).

Validation is explicit and optional: nothing is validated unless you pass a `ValidationContext`.

## Core types

All in package `com.geoknoesis.kastor.gen.runtime`.

### ValidationContext

```kotlin
interface ValidationContext : AutoCloseable {
    fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult
    override fun close() {}   // default: nothing to release
}
```

- `data` — the graph holding the data to check.
- `focus` — the focus node (an `Iri` or `BlankNode`).
- `close()` — validators may hold resources (an RDF4J repository, parsed shapes). Whoever creates a
  context should close it (`use { }`); the default implementation does nothing, so custom contexts only
  override it when they own resources.

**Contract:** the result describes the **focus node only**. The bundled adapters evaluate the shapes that
target `focus` and return only the SHACL results whose `sh:focusNode` is `focus`; violations reported for
other nodes in the same graph (including nested nodes reached through `sh:node`) are not included.
Validate each node you care about separately.

### ValidationResult

```kotlin
sealed interface ValidationResult {
    data object Ok : ValidationResult
    data class Violations(val items: List<ShaclViolation>) : ValidationResult
}

fun ValidationResult.orThrow()                                // fails only on sh:Violation
fun ValidationResult.orThrow(minimumSeverity: ShaclSeverity)  // fails on results at least this severe
```

`Violations` holds every SHACL result, whatever its severity. `orThrow()` throws only when at least one
item has severity `sh:Violation`; `sh:Warning` and `sh:Info` results are advisory and ignored. To fail on
them too, pass a minimum severity (`Violation` > `Warning` > `Info`):

```kotlin
result.orThrow(ShaclSeverity.Warning)   // throws on Violation or Warning results
```

The thrown `ValidationException` carries only the items at or above the threshold.

### ShaclViolation

```kotlin
data class ShaclViolation(
    val focusNode: RdfResource,
    val shapeIri: Iri,              // nearest IRI-named shape (blank-node property shapes resolve to their parent)
    val constraintIri: Iri,         // e.g. sh:MinCountConstraintComponent
    val path: Iri? = null,          // simple predicate path, null for node-level constraints
    val actualValue: RdfTerm? = null,
    val expectedValue: RdfTerm? = null, // the constraint parameter, e.g. the sh:datatype value
    val message: String,            // sh:message of the shape if present, else the engine message
    val severity: ShaclSeverity = ShaclSeverity.Violation,
)

enum class ShaclSeverity { Violation, Warning, Info }
```

### ValidationException

```kotlin
class ValidationException(
    message: String,
    val violations: List<ShaclViolation> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

Thrown by `ValidationResult.orThrow()`, `RdfHandle.validateOrThrow()` and `materializeValidated` when
there are failing results (by default, results with severity `sh:Violation`). `violations` carries the
structured results.

### SharedValidators

```kotlin
object SharedValidators {
    fun <V : ValidationContext> get(type: Class<V>, create: () -> V): V
    fun close(type: Class<out ValidationContext>): Boolean
    fun closeAll()
}
```

Process-wide validators, one instance per validator class. Wrappers generated with
`validationMode = EXTERNAL` name a `ValidationContext` class with a no-argument constructor and obtain it with
`SharedValidators.get(Validator::class.java) { Validator() }`, so every wrapper type that names the same class
uses one shared instance instead of one each. This matters for `Rdf4jValidation`, which keeps a copy of the
last validated graph in an in-memory store.

Lifecycle:

- `get(type, create)` returns the shared validator of `type`, creating it with `create` on first use.
- `close(type)` closes and removes the validator of `type`; it returns `false` when there was none. The next
  `get` creates a fresh instance.
- `closeAll()` closes and removes every shared validator. A failure to close one does not prevent closing the
  others; the first failure is rethrown with the later ones suppressed.

Call `closeAll()` when the application, a test or a reloadable module shuts down. Do not close a shared
validator directly while wrappers may still use it; use `SharedValidators.close(type)` instead.

## Engine adapters

Both adapters offer the same two ways of supplying shapes:

| Construction | Where shapes come from | Notes |
|---|---|---|
| `JenaValidation(shapes: RdfGraph)` / `Rdf4jValidation(shapes: RdfGraph)` | a **separate shapes graph** (recommended) | shapes are copied/converted once and reused for every call |
| `JenaValidation.fromTurtle(ttl)` / `Rdf4jValidation.fromTurtle(ttl)` | Turtle text | same as above |
| `JenaValidation()` / `Rdf4jValidation()` | shapes **embedded in the data graph** | shapes are read from `data` (Jena: on every call; RDF4J: again only when the graph instance or its content changes); if the data graph declares no shapes the result is `Ok` |

With the no-arg constructors, a data graph that contains no SHACL shapes always validates as `Ok`. Supply
the shapes explicitly unless your data really carries them.

Engine failures (malformed shapes, a literal passed as focus, …) are thrown, never reported as `Ok` or
as a violation.

### JenaValidation

Package `com.geoknoesis.kastor.gen.validation.jena`, module `kastor-gen:validation-jena`
(depends on `kastor-gen:runtime`, `rdf:jena` and `org.apache.jena:jena-shacl`).

```kotlin
class JenaValidation : ValidationContext {
    constructor()                      // shapes read from the data graph
    constructor(shapes: RdfGraph)      // separate shapes graph
    companion object {
        fun fromTurtle(shapesTurtle: String): JenaValidation
    }
    // close() is inherited from ValidationContext and does nothing
}
```

- Backed by Jena's `ShaclValidator`.
- Jena-backed Kastor graphs are validated in place; other graphs are converted to a Jena graph per call.

### Rdf4jValidation

Package `com.geoknoesis.kastor.gen.validation.rdf4j`, module `kastor-gen:validation-rdf4j`
(depends on `kastor-gen:runtime`, `rdf:rdf4j` and `org.eclipse.rdf4j:rdf4j-shacl`).

```kotlin
class Rdf4jValidation : ValidationContext, AutoCloseable {
    constructor()                      // shapes read from the data graph
    constructor(shapes: RdfGraph)      // separate shapes graph
    companion object {
        fun fromTurtle(shapesTurtle: String): Rdf4jValidation
    }
    override fun close()
}
```

- Backed by RDF4J's `ShaclSail`. A shapes graph is converted once at construction.
- **One repository per validator.** ShaclSail validates data held in its own store, so the Kastor graph is
  converted to RDF4J statements and loaded into a single in-memory repository. It is reloaded only when
  `validate` receives a different graph instance or the graph's content changed. Changes are detected with
  an order-independent digest of the triples: the SHA-256 of an unambiguous encoding of each triple, summed
  modulo 2^256, together with the triple count. Computing it costs one pass over the triples but no
  conversion or store writes, and unlike a sum of `hashCode()`s it does not miss changes such as a literal
  `"Aa"` becoming `"BB"`. Validating many nodes of one graph therefore converts and loads it once. With the
  no-arg constructor the embedded shapes are extracted at the same time.
- **Reloads are atomic.** The statements and embedded shapes are prepared first, then the store content is
  replaced in one transaction (rolled back on failure). The loaded graph, its digest and its embedded shapes
  are updated together only after the commit, so a failed conversion or load leaves the previously loaded
  state intact.
- **Only shapes that target the focus node are evaluated.** The target declarations (`sh:targetClass`,
  including `rdfs:subClassOf` instances and implicit class targets, `sh:targetNode`, `sh:targetSubjectsOf`,
  `sh:targetObjectsOf`) are resolved for the focus node, and only those shapes are validated, in a
  transaction that is always rolled back. Shapes reached through `sh:node`, `sh:property` etc. are
  evaluated as usual. When no shape targets the focus node the result is `Ok` without running the engine.
- Fallbacks: for a **blank-node** focus (RDF4J does not accept a blank node as `sh:targetNode`) the selected
  shapes keep their own target declarations; shapes with other target kinds (e.g. SPARQL-based `sh:target`)
  are validated for all their targets. In both cases the report is filtered to the focus node.
- Calls on one instance are serialized, so a validator can be shared between threads. Call `close()` (or
  use `use { }`) to release the repository and the loaded data; `validate` after `close()` throws
  `IllegalStateException`.
- RDF4J 5.x has no base-direction support, so the direction of an RDF 1.2 directional language string is
  dropped (the language tag is kept).

## Usage

### Materialize with validation

```kotlin
import com.geoknoesis.kastor.gen.runtime.*
import com.geoknoesis.kastor.gen.validation.jena.JenaValidation
import com.geoknoesis.kastor.rdf.*

val shapesTtl = javaClass.getResource("/person-shape.ttl")!!.readText()
val validation = JenaValidation.fromTurtle(shapesTtl)

// Throws ValidationException if the focus node violates its shapes.
val person: Person = graph.materializeValidated(personIri, validation)
```

`materializeValidated` (and `RdfRef.asValidatedType`, `OntoMapper.materializeValidated`) builds the
object, then validates the focus node and throws on violations. The resulting handle keeps the context,
so `person.asRdf().validate()` can be called again later (for example after mutating the graph).

### Validate a node directly

```kotlin
when (val result = validation.validate(graph, personIri)) {
    ValidationResult.Ok -> println("valid")
    is ValidationResult.Violations -> result.items.forEach {
        println("${it.severity} ${it.path?.value}: ${it.message}")
    }
}
```

### Separate shapes graph

```kotlin
val shapes: RdfGraph = Rdf.parse(shapesTtl, "TURTLE")
Rdf4jValidation(shapes).use { validation ->
    validation.validate(graph, personIri).orThrow()
}
```

### Handle without validation

A handle created by plain `materialize` has no context: `rdf.isValidationConfigured` is `false` and
`validate()` / `validateOrThrow()` throw `IllegalStateException`. Either materialize with
`materializeValidated` or call `validation.validate(graph, node)` yourself.

### Custom contexts

`ValidationContext` is a single-method interface, so business rules can be layered on top of an engine
adapter:

```kotlin
class WithBusinessRules(private val shacl: ValidationContext) : ValidationContext {
    override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
        val shaclResult = shacl.validate(data, focus)
        val extra = mutableListOf<ShaclViolation>()
        // ... add ShaclViolation(focusNode = focus as RdfResource, shapeIri = ..., constraintIri = ..., message = ...)
        val all = (shaclResult as? ValidationResult.Violations)?.items.orEmpty() + extra
        return if (all.isEmpty()) ValidationResult.Ok else ValidationResult.Violations(all)
    }
}
```

## Dependencies

Inside the Kastor build (artifacts are not yet published to a public repository; see
[Getting Started](../tutorials/getting-started.md) for `publishToMavenLocal` / composite builds):

```kotlin
dependencies {
    implementation(project(":kastor-gen:validation-jena"))   // brings rdf:jena
    // or
    implementation(project(":kastor-gen:validation-rdf4j"))  // brings rdf:rdf4j
}
```

Published artifact ids: `com.geoknoesis.kastor:kastor-gen-validation-jena` and
`com.geoknoesis.kastor:kastor-gen-validation-rdf4j`.

## Troubleshooting

- **Always `Ok`** — you used the no-arg constructor and the data graph contains no shapes. Pass the shapes
  (`JenaValidation(shapesGraph)` / `fromTurtle`).
- **Violation on a nested node not reported** — results are filtered to the focus node; validate the
  nested node as its own focus.
- **`Validation context not configured for this handle`** — the object was created with `materialize`,
  not `materializeValidated`.
- **`Rdf4jValidation has been closed`** — the adapter was used after `close()`.
