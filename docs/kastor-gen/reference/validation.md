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
    fun validateAll(data: RdfGraph, focuses: Collection<RdfTerm>): Map<RdfTerm, ValidationResult>
    override fun close() {}   // default: nothing to release
}

// one validateAll call per data graph for a collection of wrappers
fun <T : RdfBacked> ValidationContext.validateAll(instances: Iterable<T>): List<Pair<T, ValidationResult>>
```

- `data` — the graph holding the data to check.
- `focus` — the focus node (an `Iri` or `BlankNode`).
- `validateAll` — validates several nodes against **one** state of the graph and returns the result of each
  in the order given. The default implementation calls `validate` per node; the bundled adapters check the
  graph for changes (and, for a graph without a modification stamp, read it) **once** for the whole batch. Use
  it instead of a loop of `validate` calls, see [Graph cache](#graph-cache). The extension for wrappers groups
  the instances by the graph object of their handle and calls `validateAll` once per graph.
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
    constructor(shapes: RdfGraph?, maxCachedGraphs: Int)
    constructor(shapes: RdfGraph? = null, maxCachedGraphs: Int = /* configured */, assumeImmutable: Boolean)
    val maxCachedGraphs: Int
    val assumeImmutable: Boolean
    companion object {
        const val DEFAULT_MAX_CACHED_GRAPHS = 16
        const val MAX_CACHED_GRAPHS_PROPERTY = "kastor.validation.jena.maxCachedGraphs"
        fun fromTurtle(shapesTurtle: String): JenaValidation
    }
    override fun close()               // drops the cached converted graphs
}
```

- Backed by Jena's `ShaclValidator`.
- A **standalone** Jena graph (`JenaBridge.fromJenaModel(...)`, a graph parsed by the Jena provider) is validated
  in place: nothing is copied or cached.
- **Every other graph, including the graphs of a `JenaRepository`**, is copied into a Jena graph (a repository's
  store is only readable inside a transaction, so the engine needs a detached copy), and the copy - together
  with the shapes parsed from it, for embedded shapes - is cached with the rules described under
  [Graph cache](#graph-cache): it is copied (and its shapes parsed) again only when the graph is new or its
  content changed.

### Rdf4jValidation

Package `com.geoknoesis.kastor.gen.validation.rdf4j`, module `kastor-gen:validation-rdf4j`
(depends on `kastor-gen:runtime`, `rdf:rdf4j` and `org.eclipse.rdf4j:rdf4j-shacl`).

```kotlin
class Rdf4jValidation : ValidationContext, AutoCloseable {
    constructor()                      // shapes read from the data graph
    constructor(shapes: RdfGraph)      // separate shapes graph
    constructor(shapes: RdfGraph?, maxCachedGraphs: Int)
    constructor(shapes: RdfGraph? = null, maxCachedGraphs: Int = /* configured */, assumeImmutable: Boolean)
    val maxCachedGraphs: Int
    val assumeImmutable: Boolean
    companion object {
        const val DEFAULT_MAX_CACHED_GRAPHS = 16
        const val MAX_CACHED_GRAPHS_PROPERTY = "kastor.validation.rdf4j.maxCachedGraphs"
        fun fromTurtle(shapesTurtle: String): Rdf4jValidation
    }
    override fun close()
}
```

- Backed by RDF4J's `ShaclSail`. A shapes graph is converted once at construction.
- **One repository per data graph.** ShaclSail validates data held in its own store, so the Kastor graph is
  converted to RDF4J statements and loaded into an in-memory repository, which is kept and reloaded only when
  the graph's content changed (see [Graph cache](#graph-cache)). Validating many nodes of one graph therefore
  converts and loads it once. With the no-arg constructor the embedded shapes are extracted at the same time.
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
- Calls for one data graph are serialized on that graph's repository; calls for different graphs run
  concurrently, so a validator can be shared between threads. Call `close()` (or use `use { }`) to release
  the repositories and the loaded data (a repository still used by a call is released when that call
  returns); `validate` after `close()` throws `IllegalStateException`.
- RDF4J 5.x has no base-direction support, so the direction of an RDF 1.2 directional language string is
  dropped (the language tag is kept).

### Graph cache

Both adapters keep the converted copy of recently validated data graphs (`GraphStateCache` in
`kastor-gen:runtime`; the Jena adapter for every graph except standalone Jena graphs).

- **What a call costs.**
  - Graphs that implement `VersionedRdfGraph` (`MemoryGraph`, the named graphs of the memory repository, the
    graphs of repositories whose provider stamps them) are checked in O(1) by their modification stamp; the
    triples are not read.
  - **Graphs without a stamp are read in full on every `validate` call**: `getTriples()` - a complete download
    for a SPARQL-endpoint graph - plus a digest of every triple (one SHA-256 each), because nothing cheaper
    proves that the content is unchanged. Only the conversion, the store writes and the shapes parse are saved
    when the digest matches. A loop of N `validate` calls over such a graph therefore reads it N times. Two
    ways to avoid that:
    - `validator.validateAll(graph, nodes)` (or `validator.validateAll(wrappers)`): **one** read for all nodes;
    - `Rdf4jValidation(shapes, assumeImmutable = true)` / `JenaValidation(shapes, assumeImmutable = true)`:
      you guarantee that graphs without a stamp do not change while the validator lives. A graph that is
      passed again (the same object, or an equal handle) is then not read again. A change of such a graph is
      **not detected** - results keep describing the content first seen - so use it for immutable snapshots
      and batch jobs only. Graphs with a stamp are still checked.
- **Which copy belongs to a graph.** First by handle: the same graph object, or an *equal handle*
  (`repository.getGraph(name)` returns a new object per call). Handle equality is an explicit contract: a graph
  is compared with `equals` only when it overrides `equals`/`hashCode` and implements `VersionedRdfGraph` or
  `HandleEqualGraph` (a marker interface in `kastor-gen:runtime` for your own graph classes), or is one of
  Kastor's own graph classes. Data classes, Java records and other foreign classes are never compared with
  `equals`. `equals` never runs under a lock of the cache. Then, for graphs without a stamp, by content: a
  copy built for the same digest is reused whichever handle it was built for.
- **A changing graph keeps one copy** when it is found by handle: the copy is replaced in place. A changing
  graph without a stamp that is read through fresh handles without handle equality cannot be linked to its
  previous copy; the obsolete copies are evicted first when the cache is full and expire after a minute
  without use once their handle was garbage collected.
- **The digest** is the sum modulo 2^256 of `SHA-256(salt || encoding of the triple)` over the triples, plus the
  triple count: order independent, one pass, and unlike a sum of `hashCode()`s it does not miss changes such as
  a literal `"Aa"` becoming `"BB"`. A plain sum of hashes can be attacked with crafted data (generalised
  birthday attack), so each validator uses its own random 32-byte salt that never leaves the process: the
  per-triple values cannot be computed by someone who only controls the data, and a collision cannot be
  prepared in advance. The digest is not a stable or public fingerprint.
- **Size.** At most `maxCachedGraphs` copies are kept (default 16). Set it with the constructor argument, or
  process-wide - which is how to size the validators shared by generated wrappers - with the system properties
  `kastor.validation.rdf4j.maxCachedGraphs` / `kastor.validation.jena.maxCachedGraphs` (a positive integer; any
  other value, such as `0` or `abc`, is reported once with a WARN entry on the `System.Logger`
  `com.geoknoesis.kastor.gen.runtime.GraphStateCache` and the default is used). When the cache is full the
  least recently used copy that no call is using is released, copies whose handle was garbage collected first.
- **Saturation.** When every copy is in use, a call validates in a private temporary copy that is released
  when the call returns, instead of waiting for the validation of another graph. Temporary copies are bounded
  too: at most `maxCachedGraphs` at a time. Beyond that (more than `2 x maxCachedGraphs` distinct graphs being
  validated at the same moment) a call waits up to 10 seconds for a copy to become free and then fails with
  `GraphStateCacheSaturatedException` (an `IllegalStateException`) that says so. Size the cache to the number of
  graphs that are validated repeatedly (every miss converts and loads the whole graph).
- **Release.** Copies that can no longer be found by their handle are released on the next call: those of
  stamped graphs and of handle-equal handles once the handle was garbage collected (handle-equal handles are
  softly referenced, i.e. dropped under memory pressure), and content-identified copies whose handle was
  garbage collected after a minute without use. The rest stays until it is evicted or the validator is closed.
- **Locking.** The data graph is read only while the validator holds no lock, so `validate` can be called
  inside `repository.transaction { }` while other threads validate the same graph.

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
