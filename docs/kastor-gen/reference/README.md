# API Reference

Complete API reference for Kastor Gen components and interfaces.

## Table of Contents

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

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RdfClass(val iri: String = "")

@Target(AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.SOURCE)
annotation class RdfProperty(val iri: String)
```

### Materialization

```kotlin
object OntoMapper {
    val registry: MutableMap<Class<*>, (RdfHandle) -> Any>
    fun <T: Any> materialize(ref: RdfRef, type: Class<T>, validate: Boolean = false): T
}

inline fun <reified T: Any> RdfRef.asType(validate: Boolean = false): T
inline fun <reified T: Any> T.asRdf(): RdfHandle
```

### Validation

```kotlin
interface ShaclValidator {
    fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult
}

object ShaclValidation {
    fun register(port: ShaclValidator)
    fun current(): ShaclValidator
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



