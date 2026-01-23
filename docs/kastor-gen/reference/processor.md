# Processor API Reference

Complete reference for the Kastor Gen processor public API. This document only covers the **public API surface** - internal implementation details are not documented here.

## Overview

The Kastor Gen processor is a KSP (Kotlin Symbol Processing) plugin that generates Kotlin code from:
- **SHACL shapes** - Defines data structure and constraints
- **JSON-LD context** - Provides type mappings and property definitions

Users interact with the processor through **annotations** - the processor classes themselves are internal implementation details.

## Public API Surface

### Model Classes

These data classes represent the ontology model and generation configuration:

#### `InstanceDslRequest`

Request object for generating instance DSL builders.

```kotlin
data class InstanceDslRequest(
    val dslName: String,
    val ontologyModel: OntologyModel,
    val packageName: String,
    val options: DslGenerationOptions = DslGenerationOptions()
)
```

**Properties:**
- `dslName` - Name of the DSL to generate (e.g., "skos", "dcat")
- `ontologyModel` - Combined SHACL shapes and JSON-LD context
- `packageName` - Target package for generated code
- `options` - Generation configuration options

#### `DslGenerationOptions`

Configuration options for DSL generation.

```kotlin
data class DslGenerationOptions(
    val validation: ValidationConfig = ValidationConfig(),
    val naming: NamingConfig = NamingConfig(),
    val output: OutputConfig = OutputConfig()
)
```

**Nested Configurations:**

##### `ValidationConfig`

```kotlin
data class ValidationConfig(
    val enabled: Boolean = true,
    val mode: ValidationMode = ValidationMode.EMBEDDED,
    val strict: Boolean = false,
    val validateOnBuild: Boolean = true
)
```

##### `NamingConfig`

```kotlin
data class NamingConfig(
    val strategy: NamingStrategy = NamingStrategy.CAMEL_CASE,
    val usePropertyNames: Boolean = true
)
```

##### `OutputConfig`

```kotlin
data class OutputConfig(
    val includeComments: Boolean = true,
    val includeKdoc: Boolean = true,
    val supportLanguageTags: Boolean = true,
    val defaultLanguage: String? = null
)
```

#### `OntologyModel`

Combined model containing SHACL shapes and JSON-LD context.

```kotlin
data class OntologyModel(
    val shapes: List<ShaclShape>,
    val context: JsonLdContext
)
```

#### `ShaclShape`

Represents a SHACL NodeShape.

```kotlin
data class ShaclShape(
    val targetClass: String,
    val properties: List<ShaclProperty>,
    val iri: String? = null
)
```

#### `ShaclProperty`

Represents a property in a SHACL shape.

```kotlin
data class ShaclProperty(
    val path: String,
    val name: String? = null,
    val description: String? = null,
    val datatype: String? = null,
    val classIri: String? = null,
    // ... constraint properties
)
```

#### `JsonLdContext`

JSON-LD context providing type mappings.

```kotlin
data class JsonLdContext(
    val prefixes: Map<String, String>,
    val baseIri: String?,
    val vocabIri: String?,
    val typeMappings: Map<String, JsonLdType>,
    val propertyMappings: Map<String, JsonLdProperty>
)
```

### Builder Functions

#### `dslOptions()`

Creates `DslGenerationOptions` using a fluent DSL builder.

```kotlin
val options = dslOptions {
    validation {
        enabled = true
        mode = ValidationMode.EMBEDDED
        strict = false
    }
    naming {
        strategy = NamingStrategy.CAMEL_CASE
        usePropertyNames = true
    }
    output {
        supportLanguageTags = true
        defaultLanguage = "en"
        includeComments = true
    }
}
```

#### `instanceDslRequest()`

Creates `InstanceDslRequest` using a fluent DSL builder.

```kotlin
val request = instanceDslRequest("skos", "com.example") {
    ontologyModel = model
    options {
        validation { enabled = true }
    }
}
```

### Extension Functions

#### `ShaclShape.contains()`

Checks if a shape contains a property with the given IRI.

```kotlin
operator fun ShaclShape.contains(propertyIri: String): Boolean

// Usage
if ("http://example.org/property" in shape) {
    // Property exists
}
```

### Exception Classes

All exception classes are public for error handling:

- `GenerationException` - Base exception for generation errors
- `MissingShapeException` - Thrown when a required SHACL shape is missing
- `InvalidConfigurationException` - Thrown when configuration is invalid
- `FileNotFoundException` - Thrown when ontology files cannot be found
- `ValidationException` - Thrown when validation fails
- `FileGenerationException` - Thrown when file generation fails
- `ProcessingException` - Thrown when processing fails

## Usage Patterns

### Basic Usage

Users typically interact with the processor through annotations:

```kotlin
@GenerateFromOntology(
    shaclPath = "ontologies/my-ontology.shacl.ttl",
    contextPath = "ontologies/my-ontology.context.jsonld",
    packageName = "com.example.generated"
)
class OntologyGenerator
```

### Advanced Usage

For programmatic generation (rare), users can use the public model classes:

```kotlin
val model = OntologyModel(
    shapes = listOf(/* ... */),
    context = JsonLdContext(/* ... */)
)

val options = dslOptions {
    validation { enabled = true }
}

val request = InstanceDslRequest(
    dslName = "skos",
    ontologyModel = model,
    packageName = "com.example",
    options = options
)
```

## What's NOT in the Public API

The following are **internal implementation details** and should not be used directly:

- ❌ Generator classes (`InstanceDslGenerator`, `InterfaceGenerator`, etc.)
- ❌ Parser classes (`ShaclParser`, `JsonLdContextParser`, etc.)
- ❌ Utility classes (`NamingUtils`, `TypeMapper`, etc.)
- ❌ Internal extension functions
- ❌ Internal sealed classes

These are subject to change without notice and are not part of the public API contract.

## Version Compatibility

- Kotlin: 1.9.24+
- JDK: 17+
- KSP: 1.9.24-1.0.20+

## See Also

- [Annotations Reference](annotations.md) - Annotation API
- [Runtime API](runtime.md) - Runtime interfaces
- [Getting Started Tutorial](../tutorials/getting-started.md) - Usage guide

