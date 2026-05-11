# API Reference

Complete API reference for Kastor Gen components and interfaces.

## Table of Contents

- [Gradle Plugin](gradle-plugin.md) - Gradle plugin for build-time code generation
- [Runtime API](runtime.md) - Core runtime interfaces and classes
- [Annotations](annotations.md) - RDF mapping annotations
- [Validation API](validation.md) - Validation interfaces and adapters
- [Processor API](processor.md) - KSP processor interfaces
- [Examples](examples.md) - Code examples and usage patterns

## Quick Reference

### Core Interfaces

```kotlin
// Domain interface marker
interface RdfBacked {
    val rdf: RdfHandle
}

// RDF side-channel access
interface RdfHandle {
    val node: RdfTerm
    val graph: RdfGraph
    val extras: PropertyBag
    fun validate(): ValidationResult
    fun validateOrThrow()
}

// Unmapped property access
interface PropertyBag {
    fun predicates(): Set<Iri>
    fun values(pred: Iri): List<RdfTerm>
    fun literals(pred: Iri): List<Literal>
    fun strings(pred: Iri): List<String>
    fun iris(pred: Iri): List<Iri>
    fun <T : Any> objects(pred: Iri, asType: Class<T>): List<T>
}
```

### Annotations

See [annotations.md](annotations.md) for the full `@Rdf` / `@file:Rdf` / `Prefix` model. In short, **`@Rdf(iri = …)`** is used on both domain interfaces and their properties (QName-friendly when combined with `prefixes`).

### Materialization

```kotlin
object OntoMapper {
    val registry: MutableMap<Class<*>, (RdfHandle) -> Any>
    fun <T : Any> materialize(ref: RdfRef, type: Class<T>): T
    fun <T : Any> materializeValidated(ref: RdfRef, type: Class<T>, validation: ValidationContext): T
}

fun RdfGraph.ref(node: RdfTerm): RdfRef
inline fun <reified T : Any> RdfGraph.materialize(node: RdfTerm): T
inline fun <reified T : Any> RdfGraph.materializeValidated(node: RdfTerm, validation: ValidationContext): T
inline fun <reified T : Any> RdfTerm.materializeIn(graph: RdfGraph): T
inline fun <reified T : Any> Iterable<RdfTerm>.materializeIn(graph: RdfGraph): List<T>
inline fun <reified T : Any> RdfRepository.materialize(node: RdfTerm, graph: RdfGraph = defaultGraph): T

inline fun <reified T : Any> RdfRef.asType(): T
inline fun <reified T : Any> RdfRef.asValidatedType(validation: ValidationContext): T
inline fun <reified T : Any> T.asRdf(): RdfHandle
fun <T : RdfBacked> T.writeToGraph(targetGraph: MutableRdfGraph, subject: Iri? = null)
```

### Validation

```kotlin
interface ValidationContext {
    fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult
}
```

## Module Structure

### kastor-gen-runtime
Core runtime interfaces and utilities.

### kastor-gen-processor
KSP processor for code generation.

### kastor-gen-validation-jena
Jena-based SHACL validation adapter.

### kastor-gen-validation-rdf4j
RDF4J-based SHACL validation adapter.

## Dependencies

### Required
- `com.geoknoesis.kastor:rdf-core`
- `org.slf4j:slf4j-api`

### Optional
- `com.geoknoesis.kastor:rdf-jena` (for Jena backend)
- `com.geoknoesis.kastor:rdf-rdf4j` (for RDF4J backend)
- `com.geoknoesis.kastor:rdf-sparql` (for SPARQL backend)

## Version Compatibility

- Kotlin: 1.9.24+
- JDK: 17+
- KSP: 1.9.24-1.0.20+

## Getting Started

1. Add dependencies to your `build.gradle.kts`
2. Configure KSP plugin
3. Define domain interfaces with annotations
4. Build to generate wrapper classes
5. Use materialization in your code

See the [Getting Started Tutorial](../tutorials/getting-started.md) for detailed instructions.



