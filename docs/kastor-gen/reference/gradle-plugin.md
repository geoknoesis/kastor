# Kastor Gen Gradle Plugin

{% include version-banner.md %}

## Overview

The Kastor Gen Gradle plugin (`com.geoknoesis.kastor.gen`) provides build-time code generation from SHACL shapes and JSON-LD context files. It generates domain interfaces, wrapper implementations, vocabulary constants, and domain-specific DSL builders without requiring annotations in your source code.

## Plugin ID

```
com.geoknoesis.kastor.gen
```

## Features

- ✅ **Domain Interface Generation** - Pure Kotlin interfaces from SHACL shapes
- ✅ **Wrapper Implementation** - RDF-backed implementations of interfaces
- ✅ **Vocabulary Constants** - Type-safe vocabulary constants (auto-enabled when metadata provided)
- ✅ **Domain DSL Builders** - Type-safe DSL for creating instances
- ✅ **Multiple Ontologies** - Support for generating from multiple ontology files
- ✅ **Incremental Builds** - Only regenerates when ontology files change
- ✅ **Build Cache** - Caches generated code for faster builds

## Installation

### Plugin DSL (Recommended)

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("com.geoknoesis.kastor.gen") version "0.2.0"
}
```

### Legacy Plugin Application

```kotlin
// build.gradle.kts
buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    dependencies {
        classpath("com.geoknoesis.kastor:kastor-gen-gradle-plugin:0.2.0")
    }
}

apply(plugin = "com.geoknoesis.kastor.gen")
```

## Configuration

### Basic Configuration

```kotlin
kastorGen {
    ontologies {
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"
            interfacePackage = "com.example.dcatus.generated"
            wrapperPackage = "com.example.dcatus.generated"
        }
    }
}
```

### Complete Configuration

```kotlin
kastorGen {
    ontologies {
        create("dcat") {
            // Required: Paths to ontology files
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"
            
            // Package configuration
            interfacePackage = "com.example.dcatus.generated.interfaces"
            wrapperPackage = "com.example.dcatus.generated.wrappers"
            vocabularyPackage = "com.example.dcatus.generated.vocab"
            dslPackage = "com.example.dcatus.generated.dsl"
            
            // Generation flags
            generateInterfaces = true      // Default: true
            generateWrappers = true       // Default: true
            generateDsl = true            // Default: false
            
            // Vocabulary configuration (auto-enables vocabulary generation)
            vocabularyName = "DCAT"
            vocabularyNamespace = "http://www.w3.org/ns/dcat#"
            vocabularyPrefix = "dcat"
            // generateVocabulary is automatically set to true when all three above are provided
            
            // DSL configuration (if generateDsl = true)
            dslName = "dcat"              // Optional: auto-derived if not specified
            
            // Output directory
            outputDirectory = "build/generated/sources/kastor-gen"
        }
    }
}
```

## Configuration Properties

### OntologyConfig Properties

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `shaclPath` | String | ✅ Yes | - | Path to SHACL shape file (relative to project root or `src/main/resources`) |
| `contextPath` | String | ✅ Yes | - | Path to JSON-LD context file (relative to project root or `src/main/resources`) |
| `interfacePackage` | String | ❌ No | `"interfaces"` | Package for generated interfaces |
| `wrapperPackage` | String | ❌ No | `"wrappers"` | Package for generated wrappers |
| `vocabularyPackage` | String | ❌ No | `"vocabulary"` | Package for generated vocabulary |
| `dslPackage` | String | ❌ No | `"dsl"` | Package for generated DSL |
| `generateInterfaces` | Boolean | ❌ No | `true` | Whether to generate domain interfaces |
| `generateWrappers` | Boolean | ❌ No | `true` | Whether to generate wrapper implementations |
| `generateVocabulary` | Boolean | ❌ No | Auto-enabled if vocabulary metadata provided | Whether to generate vocabulary constants. Auto-enabled when `vocabularyName`, `vocabularyNamespace`, and `vocabularyPrefix` are all provided |
| `generateDsl` | Boolean | ❌ No | `false` | Whether to generate domain-specific DSL builders |
| `vocabularyName` | String | ❌ No | `""` | Name of vocabulary class (e.g., "DCAT"). If provided along with namespace and prefix, vocabulary generation is auto-enabled |
| `vocabularyNamespace` | String | ❌ No | `""` | Namespace URI for vocabulary (e.g., "http://www.w3.org/ns/dcat#"). If provided along with name and prefix, vocabulary generation is auto-enabled |
| `vocabularyPrefix` | String | ❌ No | `""` | Prefix for vocabulary (e.g., "dcat"). If provided along with name and namespace, vocabulary generation is auto-enabled |
| `dslName` | String | ❌ No | `""` | Name of DSL (auto-derived from context file name if not specified) |
| `outputDirectory` | String | ❌ No | `"build/generated/sources/kastor-gen"` | Output directory for generated code |

## Generated Tasks

The plugin automatically creates Gradle tasks for each configured ontology:

### Individual Ontology Tasks

For each ontology configuration named `"dcat"`, a task `generateOntologyDcat` is created:

```bash
./gradlew generateOntologyDcat
```

### Aggregate Task

A main task `generateOntology` runs all ontology generation tasks:

```bash
./gradlew generateOntology
```

### Automatic Integration

The `generateOntology` task is automatically configured to run before `compileKotlin`, so generated code is available during compilation.

## Generated Code

### Domain Interfaces

Generated interfaces are pure Kotlin with no RDF dependencies:

```kotlin
// Generated: com.example.dcatus.generated.interfaces.Catalog
interface Catalog {
    val title: String
    val description: String?
    val dataset: List<Dataset>
}
```

### Wrapper Implementations

Generated wrappers provide RDF-backed implementations:

```kotlin
// Generated: com.example.dcatus.generated.wrappers.CatalogWrapper
class CatalogWrapper(
    private val graph: RdfGraph,
    private val subject: Iri
) : Catalog {
    override val title: String
        get() = graph.getObjectValue(subject, DCAT.title) as String
    
    override val description: String?
        get() = graph.getObjectValue(subject, DCAT.description) as String?
    
    // ...
}
```

### Vocabulary Constants

Generated vocabulary provides type-safe constants:

```kotlin
// Generated: com.example.dcatus.generated.vocab.DCAT
object DCAT {
    val namespace = "http://www.w3.org/ns/dcat#"
    
    val Catalog = Iri("${namespace}Catalog")
    val Dataset = Iri("${namespace}Dataset")
    val title = Iri("${namespace}title")
    val description = Iri("${namespace}description")
    // ...
}
```

### Domain DSL Builders

Generated DSL provides type-safe instance creation:

```kotlin
// Generated: com.example.dcatus.generated.dsl.DcatDsl
val catalog = dcat {
    catalog("http://example.org/catalog") {
        title("My Data Catalog")
        description("A catalog of datasets")
        dataset("http://example.org/dataset1") {
            title("Dataset 1")
            keyword("data", "example")
        }
    }
}
```

## Multiple Ontologies

Configure multiple ontologies in a single project:

```kotlin
kastorGen {
    ontologies {
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"
            interfacePackage = "com.example.dcatus.generated"
        }
        
        create("schema") {
            shaclPath = "ontologies/schema.shacl.ttl"
            contextPath = "ontologies/schema.context.jsonld"
            interfacePackage = "com.example.schema.generated"
        }
        
        create("foaf") {
            shaclPath = "ontologies/foaf.shacl.ttl"
            contextPath = "ontologies/foaf.context.jsonld"
            interfacePackage = "com.example.foaf.generated"
        }
    }
}
```

Each ontology gets its own generation task:
- `generateOntologyDcat`
- `generateOntologySchema`
- `generateOntologyFoaf`

## Source Set Configuration

Add generated sources to your source sets:

```kotlin
sourceSets {
    main {
        kotlin {
            srcDir("build/generated/sources/kastor-gen")
        }
    }
}
```

**Note**: The plugin automatically configures generated sources, but you may need to add this for IDE support.

## Incremental Builds

The plugin supports incremental builds through Gradle's task input/output tracking:

### Automatic File Tracking

The plugin tracks ontology files as task inputs. When files change, only affected tasks are rerun.

### Explicit File Tracking

For better incremental build support, configure file inputs explicitly:

```kotlin
tasks.named("generateOntologyDcat") {
    inputs.files(
        file("ontologies/dcat-us.shacl.ttl"),
        file("ontologies/dcat-us.context.jsonld")
    )
    outputs.dir("build/generated/sources/kastor-gen/main/kotlin")
}
```

### Build Caching

Enable build caching for faster builds:

```kotlin
tasks.named("generateOntologyDcat") {
    outputs.cacheIf { true }
}
```

See [Incremental Builds Guide](../guides/incremental-builds.md) for detailed information.

## Examples

### Example 1: Basic Interface Generation

```kotlin
// build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.geoknoesis.kastor.gen") version "0.2.0"
}

dependencies {
    implementation("com.geoknoesis.kastor:kastor-gen-runtime:0.2.0")
    implementation("com.geoknoesis.kastor:rdf-core:0.2.0")
}

kastorGen {
    ontologies {
        create("person") {
            shaclPath = "src/main/resources/person-shape.ttl"
            contextPath = "src/main/resources/person-context.jsonld"
            interfacePackage = "com.example.generated"
        }
    }
}
```

### Example 2: Full Generation (Interfaces + Wrappers + Vocabulary + DSL)

```kotlin
kastorGen {
    ontologies {
        create("dcat") {
            shaclPath = "ontologies/dcat-us.shacl.ttl"
            contextPath = "ontologies/dcat-us.context.jsonld"
            
            interfacePackage = "com.example.dcatus.interfaces"
            wrapperPackage = "com.example.dcatus.wrappers"
            vocabularyPackage = "com.example.dcatus.vocab"
            dslPackage = "com.example.dcatus.dsl"
            
            generateInterfaces = true
            generateWrappers = true
            generateVocabulary = true
            generateDsl = true
            
            vocabularyName = "DCAT"
            vocabularyNamespace = "http://www.w3.org/ns/dcat#"
            vocabularyPrefix = "dcat"
            
            dslName = "dcat"
        }
    }
}
```

### Example 4: DSL Only

```kotlin
kastorGen {
    ontologies {
        create("skos") {
            shaclPath = "ontologies/skos.shacl.ttl"
            contextPath = "ontologies/skos.context.jsonld"
            
            generateInterfaces = false
            generateWrappers = false
            generateVocabulary = false
            generateDsl = true
            
            dslPackage = "com.example.skos.dsl"
            dslName = "skos"
        }
    }
}
```

## Troubleshooting

### Generated Code Not Found

**Problem**: IDE or compiler can't find generated code.

**Solution**: 
1. Run `./gradlew generateOntology` to generate code
2. Refresh Gradle project in IDE
3. Ensure `sourceSets` includes generated directory

### Task Not Running

**Problem**: Generation task doesn't run automatically.

**Solution**:
```kotlin
// Explicitly configure task dependency
tasks.named("compileKotlin") {
    dependsOn("generateOntology")
}
```

### File Not Found

**Problem**: Plugin can't find SHACL or context files.

**Solution**: 
1. Check file paths are relative to project root
2. Try absolute paths for debugging
3. Ensure files exist in `src/main/resources/` if using relative paths

### DSL Name Validation Error

**Problem**: `dslName` must be a valid Kotlin identifier.

**Solution**: Use only letters, numbers, and underscores. Must start with a letter:
- ✅ Valid: `"dcat"`, `"skos"`, `"myVocab"`
- ❌ Invalid: `"dcat-us"`, `"123vocab"`, `"my-vocab"`

## Best Practices

### 1. Organize Ontology Files

```
project/
├── ontologies/
│   ├── dcat-us/
│   │   ├── shapes.ttl
│   │   └── context.jsonld
│   └── schema/
│       ├── shapes.ttl
│       └── context.jsonld
└── build.gradle.kts
```

### 2. Use Separate Packages

```kotlin
kastorGen {
    ontologies {
        create("dcat") {
            interfacePackage = "com.example.dcatus.interfaces"
            wrapperPackage = "com.example.dcatus.wrappers"
            vocabularyPackage = "com.example.dcatus.vocab"
            dslPackage = "com.example.dcatus.dsl"
        }
    }
}
```

### 3. Version Control Generated Code

**Option 1**: Don't commit generated code (recommended)
```gitignore
build/generated/
```

**Option 2**: Commit generated code for stability
- Useful for libraries
- Ensures consistent API across versions

### 4. Use Build Cache

```kotlin
tasks.withType<OntologyGenerationTask> {
    outputs.cacheIf { true }
}
```

## Related Documentation

- [Gradle Configuration Tutorial](../tutorials/gradle-configuration.md) - Detailed tutorial
- [Incremental Builds Guide](../guides/incremental-builds.md) - Build optimization
- [Ontology Generation](../tutorials/ontology-generation.md) - Understanding generation
- [Processor Reference](processor.md) - Low-level API documentation

