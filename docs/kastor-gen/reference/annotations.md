# Annotations API Reference

Complete reference for Kastor Gen annotations used in domain modeling and ontology-driven generation.

## `@Rdf`

Single source-level annotation (`com.geoknoesis.kastor.gen.annotations.Rdf`) used for:

- **Domain interfaces** — mark the RDF **class** IRI (`iri`) and optional **prefix map** (`prefixes`) for expanding **QNames** in this declaration and its properties.
- **Domain properties** — mark the **predicate** IRI or QName for each mapped property.
- **File scope** — `@file:Rdf(prefixes = …)` supplies default prefix bindings for every `@Rdf(iri = …)` in that Kotlin file (merged with per-type `prefixes`, which override on name clash).
- **Ontology entry points** — on a class or file, set `shacl`, optional `context`, and generation flags for SHACL-driven interfaces and wrappers (see tutorials).

Relevant declaration (simplified; see source in `kastor-gen:runtime` for the full signature):

```kotlin
@Target(CLASS, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, FILE)
@Retention(AnnotationRetention.SOURCE)
annotation class Rdf(
  val iri: String = "",
  val prefixes: Array<Prefix> = [],
  val shacl: String = "",
  val context: String = "",
  val packageName: String = "",
  val generateInterfaces: Boolean = true,
  val generateWrappers: Boolean = true,
  val generateDsl: Boolean = false,
  val dslName: String = "",
  val ontologyPath: String = "",
  val validationMode: ValidationMode = ValidationMode.EMBEDDED,
  val validationAnnotations: ValidationAnnotations = ValidationAnnotations.JAKARTA,
  val externalValidatorClass: String = "",
  // ── Data-class generation ────────────────────────────────────────────────
  val generateDataClass: Boolean = false,
  val dataClassSuffix: String = "Record",
  val dataClassImplementsInterface: Boolean = false,
  val nestedMode: NestedMode = NestedMode.INTERFACE,
  val generateWriteSupport: Boolean = false,
)
```

#### Data-class generation fields

| Field | Default | Description |
|---|---|---|
| `generateDataClass` | `false` | Emit an immutable Kotlin `data class` (and its factory) alongside or instead of the interface+wrapper pair. |
| `dataClassSuffix` | `"Record"` | Suffix appended to the shape name for the generated data class (`Person` → `PersonRecord`). An empty suffix is rejected when interfaces or wrappers are also generated (or `dataClassImplementsInterface = true`), because the data class would collide with the interface. |
| `dataClassImplementsInterface` | `false` | When `true`, the generated data class also implements the generated interface, providing structural alignment. |
| `nestedMode` | `INTERFACE` | How `sh:class` object properties are typed inside the data class — see [`NestedMode`](#nestedmode) below. |
| `generateWriteSupport` | `false` | Adds a `toTriples(record, subject): List<RdfTriple>` function to the factory object, serializing the data class back to RDF triples. Requires `generateDataClass = true`. |

**Example — generate both modes:**

```kotlin
@Rdf(
  shacl = "ontologies/person.shacl.ttl",
  context = "ontologies/person.context.jsonld",
  packageName = "com.example.generated",
  generateInterfaces = true,
  generateWrappers = true,
  generateDataClass = true,
  dataClassSuffix = "Record",
  dataClassImplementsInterface = true,
  nestedMode = NestedMode.DATA_CLASS,
)
class OntologyGenerator
```

The processor emits (for each SHACL shape):
- `Person` — generated domain interface
- `PersonWrapper` — live RDF-backed wrapper (lazy delegates)
- `PersonRecord` — immutable data class snapshot
- `PersonRecordFactory` — object that loads `PersonRecord` eagerly and registers in `OntoMapper`

**Example — data class with write support:**

```kotlin
@Rdf(
  shacl = "ontologies/person.shacl.ttl",
  packageName = "com.example.generated",
  generateInterfaces = false,
  generateWrappers = false,
  generateDataClass = true,
  dataClassSuffix = "Record",
  nestedMode = NestedMode.IRI_ONLY,
  generateWriteSupport = true,
)
class OntologyGenerator
```

With `generateWriteSupport = true` the factory gains a `toTriples` function alongside `from`:

```kotlin
// Generated (simplified):
object PersonRecordFactory {
    init { OntoMapper.register(PersonRecord::class.java) { handle -> from(handle) } }

    fun from(handle: RdfHandle): PersonRecord { ... }

    fun toTriples(record: PersonRecord, subject: Iri): List<RdfTriple> {
        val triples = mutableListOf<RdfTriple>()
        triples += RdfTriple(subject, RDF.type, Iri("http://example.org/Person"))
        triples += RdfTriple(subject, Iri("http://example.org/name"), Literal(record.name))
        // … one statement per property …
        return triples
    }
}
```

Callers integrate it with the `replaceValues` / `replaceResource` runtime helpers:

```kotlin
val updated = record.copy(name = "New Name")
val newTriples = PersonRecordFactory.toTriples(updated, subject)
graph.replaceResource(subject, newTriples)   // atomic swap of all subject triples
```

**Example — data class only (no live wrappers):**

```kotlin
@Rdf(
  shacl = "ontologies/person.shacl.ttl",
  packageName = "com.example.generated",
  generateInterfaces = false,
  generateWrappers = false,
  generateDataClass = true,
  dataClassSuffix = "Record",
)
class OntologyGenerator
```

### `NestedMode`

Controls how `sh:class` object properties are typed inside a generated data class. Has no effect on interface or wrapper generation.

```kotlin
enum class NestedMode {
  INTERFACE,   // property type is the generated interface (default)
  DATA_CLASS,  // property type is the generated data class (fully recursive snapshot)
  IRI_ONLY,    // property type is String (IRI value; no sub-object materialisation)
}
```

| Value | Resulting property type | When to use |
|---|---|---|
| `INTERFACE` | `Dataset` (the generated interface) | Default; the data class holds references typed by the same interface the wrapper uses. |
| `DATA_CLASS` | `DatasetRecord` (the generated data class) | When you want a fully recursive, self-contained snapshot with no live wrappers anywhere in the object graph. |
| `IRI_ONLY` | `String` (IRI value) | When you only need the IRI of the related resource, not a materialised sub-object. Avoids recursive loading. |

### `Prefix`

```kotlin
annotation class Prefix(val name: String, val namespace: String)
```

Used inside `@Rdf(prefixes = [Prefix("dcat", "http://www.w3.org/ns/dcat#"), …])` or `@file:Rdf(prefixes = […])`.

### Domain modeling conventions

- Put **`@Rdf(iri = …)` on the property line** (preferred). You may still use **`@get:Rdf(iri = …)`** or **`@set:Rdf(iri = …)`** if you need use-site targets; the processor resolves `iri` from **property, then getter, then setter**.
- **`iri`** may be an absolute IRI or a **QName** (`prefix:local`) when the prefix is bound on **`@file:Rdf`** or on the **interface** `@Rdf(prefixes = …)`.
- Use **`val`** for read-only generated accessors (delegates). Use **`var`** only when you need a **mutable** wrapper: supported for scalar literals (`String`, `Int`, `Double`, `Boolean`) and a **single object** reference; **`List<…>` stays read-only** even with `var`. Mutation requires a **`MutableRdfGraph`** backing the same handle the wrapper reads from.

**Class example:**

```kotlin
@Rdf(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
  // ...
}
```

**Property example (absolute IRI or QName):**

```kotlin
@file:Rdf(
  prefixes = [
    Prefix("foaf", "http://xmlns.com/foaf/0.1/"),
  ],
)

package com.example

import com.geoknoesis.kastor.gen.annotations.Prefix
import com.geoknoesis.kastor.gen.annotations.Rdf

@Rdf(iri = "foaf:Person")
interface Person {
  @Rdf(iri = "foaf:name")
  val name: List<String>
}
```

**Notes:**

- Apply `@Rdf` with a **non-blank `iri`** and **no `shacl`** on the interface to opt into **OntoMapper** wrapper generation for that domain type.
- Prefer **interfaces**, not concrete classes, for generated wrappers.

## Annotation Processing

### KSP Processor

The Kastor Gen KSP processor scans for these annotations and generates wrapper classes:

```kotlin
// Input: Domain interface
@Rdf(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @Rdf(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
}

// Output: Generated wrapper class (simplified)
internal class PersonWrapper(override val rdf: RdfHandle) : Person, RdfBacked {
    override val name: List<String> by rdfStrings(Iri("http://xmlns.com/foaf/0.1/name"))

    companion object {
        init {
            OntoMapper.register(Person::class.java) { handle -> PersonWrapper(handle) }
        }
    }
}
```

### Processing rules

1. **Type processing**
   - Only Kotlin **interfaces** annotated with `@Rdf` and a **non-blank `iri`**, with **`shacl` left blank**, are treated as **domain** types for OntoMapper wrapper generation.
   - Inheritance is preserved in generated wrappers.

2. **Property processing**
   - Mapped properties are those carrying `@Rdf` with an **`iri`** on the **property**, **getter**, or **setter** (checked in that order).
   - Supported shapes: literals and literal lists (`String`, `Int`, `Double`, `Boolean`), single object references, and lists of domain objects (`List<YourInterface>`).

3. **Type support**
   - Literals: `String`, `Int`, `Double`, `Boolean` (and `List` of those for multi-valued literals).
   - Objects: other `@Rdf` domain interfaces.
   - Single values may be declared nullable (`String?`, `Int?`, `Organization?`).

4. **Missing values**
   - Missing values are never replaced by invented defaults (`""`, `0`, `false`).
   - A **non-null** property throws `MaterializationException` (an `IllegalStateException`) with the message
     `Required value missing for <member> <path>` when its value is missing. There is no lenient option.
   - A value that cannot be decoded (e.g. `"abc"` for an `Int`) follows `MaterializationPolicy.illTypedValues`:
     `THROW` (default) raises `MaterializationException` naming the value, its datatype, the member and the
     expected type; `SKIP` omits the value and logs a warning. This applies to lists, nullable and non-null
     members, and mutable getters. Under `SKIP`, a non-null property whose only values were skipped counts
     as missing and throws.
   - A **nullable** property returns `null` when the value is missing; assigning `null` to a nullable `var`
     removes the triples for that predicate.
   - Lists are empty when there are no values.
   - See the [runtime reference](runtime.md#materializationpolicy).

## Common Patterns

Materialize a domain view with **`graph.materialize<YourType>(node)`** (recommended), **`node.materializeIn(graph)`**, or **`repo.materialize<YourType>(node)`** when using a repository. These use **`OntoMapper`** and the same path as **`RdfRef(node, graph).asType()`**. Here `node` is the RDF subject (`Iri` or `BlankNode`) and `graph` is the `RdfGraph` that contains its triples.

### Single Value Properties

Use `List<T>` and access with `firstOrNull()`:

```kotlin
@Rdf(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @Rdf(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>  // Single name
    
    @Rdf(iri = "http://xmlns.com/foaf/0.1/age")
    val age: List<Int>      // Single age
}

// Usage
val person: Person = graph.materialize(node)
val name = person.name.firstOrNull() ?: "Unknown"
val age = person.age.firstOrNull() ?: 0
```

### Multiple Value Properties

Use `List<T>` for multiple values:

```kotlin
@Rdf(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @Rdf(iri = "http://xmlns.com/foaf/0.1/name")
    val names: List<String>     // Multiple names
    
    @Rdf(iri = "http://xmlns.com/foaf/0.1/mbox")
    val emails: List<String>    // Multiple email addresses
}

// Usage
val person: Person = graph.materialize(node)
val allNames = person.names
val primaryEmail = person.emails.firstOrNull()
```

### Object Properties

Map to domain interfaces:

```kotlin
@Rdf(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @Rdf(iri = "http://xmlns.com/foaf/0.1/knows")
    val friends: List<Person>   // Related Person objects
    
    @Rdf(iri = "http://example.org/employer")
    val employer: List<Organization>  // Related Organization objects
}

// Usage
val person: Person = graph.materialize(node)
val friends = person.friends
val employer = person.employer.firstOrNull()
```

### Inheritance

Inheritance is supported:

```kotlin
@Rdf(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @Rdf(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
}

@Rdf(iri = "http://example.org/Employee")
interface Employee : Person {
    @Rdf(iri = "http://example.org/employeeId")
    val employeeId: List<String>
    
    @Rdf(iri = "http://example.org/salary")
    val salary: List<Double>
}
```

## Configuration

Generation is configured through the `@Rdf` fields above; the processor needs no KSP arguments.

### Resolving `shacl`, `context` and `ontologyPath`

A relative path such as `@Rdf(shacl = "person-shape.ttl")` is resolved, in order, against:

1. the resource directories of the source set containing the annotated file
   (`src/<set>/kotlin/…/File.kt` → `src/<set>/resources`, then `src/main/resources`, then the project directory);
2. the directories listed in the KSP option `kastor.gen.resources` (separated by the platform path separator);
3. the processor class path (legacy behaviour).

Absolute paths are used as-is.

```kotlin
ksp {
    // Optional: extra directories to search for ontology files
    arg("kastor.gen.resources", "${projectDir}/ontologies")
}
```

Relative `kastor.gen.resources` entries are resolved against the KSP option `kastor.gen.projectDir`
(`arg("kastor.gen.projectDir", projectDir.absolutePath)`); without it, a relative entry that does not exist
relative to the compiler's working directory fails generation. See the
[processor options](processor.md#processor-options).

### Incremental builds

KSP regenerates when the annotated Kotlin source changes, but it **does not see edits to the SHACL or
JSON-LD files** themselves, and the processor warns about every ontology file it reads. Declare
`src/main/resources` as an input of the `kspKotlin` task and set `kastor.gen.resources.tracked=true`
(as the repository examples do; see [Incremental Builds](../guides/incremental-builds.md#ksp-processor)),
or use the [Gradle plugin](gradle-plugin.md), which tracks those files as task inputs.

## Best Practices

### ✅ Do

- Use descriptive IRIs or QNames with an explicit **`@file:Rdf(prefixes = …)`** or **`@Rdf(prefixes = …)`** map.
- Apply **`@Rdf` to interfaces**, not concrete classes, for generated wrappers.
- Prefer **`@Rdf(iri = …)` on the property** itself.
- Use **`List<T>`** for properties that may have multiple RDF objects.
- Group related properties logically and use inheritance where it matches the ontology.

### ❌ Don't

- Use invalid IRIs or QNames without a prefix binding.
- Annotate concrete classes when you expect the Kastor OntoMapper wrapper to be generated.
- Rely on **`var` + `List<…>`** for mutation (wrappers keep lists read-only; use a mutable graph and replace triples via RDF APIs if you need bulk updates).
- Mix unrelated RDF predicates into a single domain type without a clear model.

## Error Handling

### Common Errors

Ontology-driven generation fails the build (rather than silently producing partial or empty output) when:

1. **The SHACL file is not valid Turtle** — reported as `invalid Turtle: …`.
2. **Generated names collide** — two classes map to the same type name (compared case-insensitively, since
   file names must differ on case-insensitive file systems), a class has more than one node shape, or two
   properties of one shape map to the same Kotlin name. The message lists the IRIs involved; fix it with
   distinct JSON-LD context terms or `sh:name` values.
3. **`dataClassSuffix` is empty** while interfaces/wrappers are generated.

Constructs the generator cannot represent are skipped with a **warning** naming the shape and property
(complex property paths, properties without `sh:datatype`/`sh:class`/`sh:node`/`sh:nodeKind`, shapes
without a target class, …); the rest of the ontology is still generated.

### Troubleshooting

1. **Annotations not processed:**
   - Check KSP configuration
   - Ensure annotations are imported correctly
   - Verify build completes successfully

2. **Properties not mapped:**
   - Ensure `@Rdf(iri = …)` is present on the property or its getter/setter.
   - Verify `iri` is a valid IRI or expandable QName.
   - Ensure the property uses a supported `List<…>` or scalar type (for `var`, see mutability rules above).

3. **Inheritance issues:**
   - Check parent class annotations
   - Verify type compatibility
   - Ensure proper interface inheritance

## Examples

### Complete Example

```kotlin
// Domain interfaces
@Rdf(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @Rdf(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    @Rdf(iri = "http://xmlns.com/foaf/0.1/age")
    val age: List<Int>
    
    @Rdf(iri = "http://xmlns.com/foaf/0.1/mbox")
    val email: List<String>
    
    @Rdf(iri = "http://xmlns.com/foaf/0.1/knows")
    val friends: List<Person>
}

@Rdf(iri = "http://example.org/Organization")
interface Organization {
    @Rdf(iri = "http://example.org/name")
    val name: List<String>
    
    @Rdf(iri = "http://example.org/employee")
    val employees: List<Person>
}

// Usage
val person: Person = graph.materialize(node)
val name = person.name.firstOrNull() ?: "Unknown"
val friends = person.friends
val employer = person.asRdf().extras.objects(EMPLOYER, Organization::class.java).firstOrNull()
```

### Complex Example

```kotlin
@Rdf(iri = "http://example.org/Project")
interface Project {
    @Rdf(iri = "http://example.org/name")
    val name: List<String>
    
    @Rdf(iri = "http://example.org/description")
    val description: List<String>
    
    @Rdf(iri = "http://example.org/startDate")
    val startDate: List<String>
    
    @Rdf(iri = "http://example.org/endDate")
    val endDate: List<String>
    
    @Rdf(iri = "http://example.org/manager")
    val manager: List<Person>
    
    @Rdf(iri = "http://example.org/teamMember")
    val teamMembers: List<Person>
    
    @Rdf(iri = "http://example.org/task")
    val tasks: List<Task>
    
    @Rdf(iri = "http://example.org/budget")
    val budget: List<Double>
    
    @Rdf(iri = "http://example.org/isActive")
    val isActive: List<Boolean>
}

@Rdf(iri = "http://example.org/Task")
interface Task {
    @Rdf(iri = "http://example.org/name")
    val name: List<String>
    
    @Rdf(iri = "http://example.org/status")
    val status: List<String>
    
    @Rdf(iri = "http://example.org/assignee")
    val assignee: List<Person>
    
    @Rdf(iri = "http://example.org/dueDate")
    val dueDate: List<String>
}
```

This comprehensive annotation system provides a clean, type-safe way to map RDF data to Kotlin domain objects while maintaining the purity of domain interfaces.



