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
)
```

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
            OntoMapper.registry[Person::class.java] = { handle -> PersonWrapper(handle) }
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

### KSP Configuration

Configure KSP processor in `build.gradle.kts`:

```kotlin
ksp {
    arg("kastor.gen.package", "com.example.mydomain")
    arg("kastor.gen.generate.registry", "true")
    arg("kastor.gen.validation.enabled", "true")
}
```

### Available Arguments

- `kastor.gen.package` - Base package for generated code
- `kastor.gen.generate.registry` - Whether to generate registry entries
- `kastor.gen.validation.enabled` - Whether to enable validation
- `kastor.gen.cache.enabled` - Whether to enable caching

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

1. **Missing IRI:**
   ```
   Error: @Rdf requires an IRI
   ```

2. **Invalid Target:**
   ```
   Error: @Rdf is not applicable here
   ```

3. **Unsupported Type:**
   ```
   Error: Type 'CustomType' is not supported for RDF mapping
   ```

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



