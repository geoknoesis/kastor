# Validation with Kastor Gen

This tutorial shows how to check RDF data against SHACL shapes when you work with Kastor Gen domain
objects. The API is summarised in the [Validation API Reference](../reference/validation.md).

## Overview

- Validation is **explicit**: plain `materialize` never validates.
- A `ValidationContext` performs the check. Kastor Gen ships two real SHACL engines:
  `JenaValidation` (Apache Jena `ShaclValidator`) and `Rdf4jValidation` (RDF4J `ShaclSail`).
- Shapes live in a **separate shapes graph** that you pass to the adapter once.
- A validation call reports the results for **one focus node**.

```
 data graph ──┐
              ├─► ValidationContext.validate(data, focus) ─► ValidationResult (Ok | Violations)
 shapes ──────┘        (JenaValidation / Rdf4jValidation)
```

## Step 1: Add an adapter

Inside the Kastor build:

```kotlin
dependencies {
    implementation(project(":kastor-gen:runtime"))
    implementation(project(":kastor-gen:validation-jena"))    // Jena engine (brings rdf:jena)
    // implementation(project(":kastor-gen:validation-rdf4j")) // or the RDF4J engine
}
```

Kastor Gen artifacts are not yet published; outside this repository use `publishToMavenLocal` or a
composite build as described in [Getting Started](getting-started.md).

## Step 2: Write shapes

```turtle
# src/main/resources/person-shape.ttl
@prefix sh:  <http://www.w3.org/ns/shacl#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
@prefix ex:  <http://example.org/> .

ex:PersonShape
    a sh:NodeShape ;
    sh:targetClass ex:Person ;
    sh:property [
        sh:path ex:name ;
        sh:datatype xsd:string ;
        sh:minCount 1 ;
        sh:maxCount 1 ;
        sh:message "A person needs exactly one name" ;
    ] ;
    sh:property [
        sh:path ex:age ;
        sh:datatype xsd:integer ;
        sh:maxCount 1 ;
        sh:minInclusive 0 ;
    ] .
```

The same file can drive code generation (`@file:Rdf(shacl = "person-shape.ttl")`) and runtime validation.

## Step 3: Create the validation context

```kotlin
import com.geoknoesis.kastor.gen.validation.jena.JenaValidation

val shapesTtl = object {}.javaClass.getResource("/person-shape.ttl")!!.readText()
val validation = JenaValidation.fromTurtle(shapesTtl)
```

Equivalent alternatives:

```kotlin
val shapes: RdfGraph = Rdf.parse(shapesTtl, "TURTLE")
val jena = JenaValidation(shapes)

val rdf4j = Rdf4jValidation(shapes)   // AutoCloseable: close() when done, or use { }
```

Create the context once and reuse it: shapes are parsed at construction.

> The no-arg constructors `JenaValidation()` / `Rdf4jValidation()` read shapes from the *data* graph. If
> your data graph holds no shapes, every node validates as `Ok`.

`Rdf4jValidation` loads the data graph into its repository once and reloads it only when you pass a
different graph or the graph's content changed, so validating many nodes of one graph is cheap. It also
evaluates only the shapes that target the focus node.

## Step 4: Validate while materializing

```kotlin
import com.geoknoesis.kastor.gen.runtime.*
import com.geoknoesis.kastor.rdf.*

val repo = Rdf.memory()
val alice = Iri("http://example.org/alice")
repo.add {
    alice - RDF.type - Iri("http://example.org/Person")
    alice - Iri("http://example.org/age") - 30
    // no ex:name
}

try {
    val person: Person = repo.defaultGraph.materializeValidated(alice, validation)
} catch (e: ValidationException) {
    e.violations.forEach { println("${it.path?.value}: ${it.message}") }
    // http://example.org/name: A person needs exactly one name
}
```

The object is built, then the focus node is validated; on violations a `ValidationException` is thrown
carrying the structured `ShaclViolation`s. `RdfRef.asValidatedType(validation)` and
`OntoMapper.materializeValidated(ref, type, validation)` behave the same.

## Step 5: Validate later or without materializing

An object created by `materializeValidated` keeps its context, so you can re-check it after changing the
graph:

```kotlin
val handle = person.asRdf()
if (handle.isValidationConfigured) {
    handle.validateOrThrow()
}
```

Objects created by plain `materialize` have no context (`isValidationConfigured == false`, and
`validate()` throws `IllegalStateException`). Call the context directly instead:

```kotlin
when (val result = validation.validate(repo.defaultGraph, alice)) {
    ValidationResult.Ok -> println("valid")
    is ValidationResult.Violations -> result.items.forEach {
        println("[${it.severity}] ${it.constraintIri.value} on ${it.path?.value}: ${it.message} (value ${it.actualValue})")
    }
}
```

## Focus-node scope

Results are filtered to the focus node you pass. If `ex:alice ex:knows ex:bob` and `ex:bob` violates its
own shape, validating `alice` does not report `bob`'s violation. Validate each node you need:

```kotlin
val people = listOf(alice, bob)
val failures = people.associateWith { validation.validate(graph, it) }
    .filterValues { it is ValidationResult.Violations }
```

## Adding business rules

Wrap an engine adapter to add checks SHACL cannot express, returning the same result type:

```kotlin
class PersonRules(private val shacl: ValidationContext) : ValidationContext {
    override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
        val items = (shacl.validate(data, focus) as? ValidationResult.Violations)?.items.orEmpty().toMutableList()
        val subject = focus as RdfResource
        val emails = data.find(subject, Iri("http://example.org/email")).map { it.obj }
        if (emails.size > 3) {
            items += ShaclViolation(
                focusNode = subject,
                shapeIri = Iri("http://example.org/rules/PersonRules"),
                constraintIri = Iri("http://example.org/rules/maxEmails"),
                path = Iri("http://example.org/email"),
                message = "At most three e-mail addresses",
            )
        }
        return if (items.isEmpty()) ValidationResult.Ok else ValidationResult.Violations(items)
    }
}

val validation = PersonRules(JenaValidation.fromTurtle(shapesTtl))
```

## Testing

```kotlin
class PersonValidationTest {
    private val validation = JenaValidation.fromTurtle(PERSON_SHAPES_TTL)

    @Test
    fun `person without a name is rejected`() {
        val repo = Rdf.memory()
        val p = Iri("http://example.org/p")
        repo.add { p - RDF.type - Iri("http://example.org/Person") }

        val result = validation.validate(repo.defaultGraph, p)
        assertTrue(result is ValidationResult.Violations)
    }
}
```

## Good practice

- Build the context once (per shapes file) and reuse it; every `ValidationContext` is `AutoCloseable`, so
  `close()` it (or `use { }`) when finished. It matters for `Rdf4jValidation`, which holds a repository; for
  `JenaValidation` it is a no-op.
- Keep shapes in their own graph/resource instead of mixing them into the data.
- Validate at trust boundaries (data loaded from outside), not on every property read.
- Inspect `ShaclViolation.severity`: `Warning`/`Info` results are also reported as `Violations`, but
  `orThrow()` fails only on `sh:Violation`. Use `result.orThrow(ShaclSeverity.Warning)` (or `Info`) if your
  code should fail on them too.

## Next Steps

- [Validation API Reference](../reference/validation.md)
- [Runtime API](../reference/runtime.md)
- [Advanced Usage](advanced-usage.md)
- [Best Practices](../best-practices.md)
