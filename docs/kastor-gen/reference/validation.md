# Validation API Reference

Reference for the Kastor Gen validation types (module `kastor-gen:runtime`) and the two SHACL engine
adapters (`kastor-gen:validation-jena`, `kastor-gen:validation-rdf4j`).

Validation is explicit and optional: nothing is validated unless you pass a `ValidationContext`.

## Core types

All in package `com.geoknoesis.kastor.gen.runtime`.

### ValidationContext

```kotlin
interface ValidationContext {
    fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult
}
```

- `data` — the graph holding the data to check.
- `focus` — the focus node (an `Iri` or `BlankNode`).

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

fun ValidationResult.orThrow()   // throws ValidationException for Violations
```

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
there are violations. `violations` carries the structured results.

## Engine adapters

Both adapters offer the same two ways of supplying shapes:

| Construction | Where shapes come from | Notes |
|---|---|---|
| `JenaValidation(shapes: RdfGraph)` / `Rdf4jValidation(shapes: RdfGraph)` | a **separate shapes graph** (recommended) | shapes are copied/converted once and reused for every call |
| `JenaValidation.fromTurtle(ttl)` / `Rdf4jValidation.fromTurtle(ttl)` | Turtle text | same as above |
| `JenaValidation()` / `Rdf4jValidation()` | shapes **embedded in the data graph** | shapes are re-read from `data` on every call; if the data graph declares no shapes the result is `Ok` |

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

- Backed by RDF4J's `ShaclSail`. With a shapes graph, the shapes are loaded into the SHACL shape-graph
  context of one in-memory `ShaclSail` repository that is reused. Each `validate` call adds the data in a
  transaction, validates, and always rolls back, so no data is retained between calls.
- Calls on one instance are serialized. Call `close()` (or use `use { }`) to release the repository;
  `validate` after `close()` throws `IllegalStateException`.
- The data graph is always converted to RDF4J statements (ShaclSail validates its own store).
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
