# Incremental Builds

{% include version-banner.md %}

## Overview

Kastor Gen uses Kotlin Symbol Processing (KSP) for code generation. Understanding how incremental builds work is crucial for optimizing build performance and ensuring code is regenerated when needed.

## How Incremental Builds Work

### KSP Incremental Compilation

KSP supports incremental compilation, which means:

- **Only changed files are reprocessed**: If you modify one ontology file, only that file's generated code is regenerated
- **Dependency tracking**: KSP tracks dependencies between source files and generated code
- **Build cache**: Gradle caches compilation results for faster subsequent builds

### Gradle Task Inputs and Outputs

Kastor Gen's `OntologyGenerationTask` is configured with the following inputs and outputs:

#### Task Inputs

| Input | Type | Description |
|-------|------|-------------|
| `shaclPath` | `@Input` | Path to SHACL shape file |
| `contextPath` | `@Input` | Path to JSON-LD context file |
| `interfacePackage` | `@Input @Optional` | Package for generated interfaces |
| `wrapperPackage` | `@Input @Optional` | Package for generated wrappers |
| `vocabularyPackage` | `@Input @Optional` | Package for generated vocabulary |
| `generateInterfaces` | `@Input @Optional` | Whether to generate interfaces |
| `generateWrappers` | `@Input @Optional` | Whether to generate wrappers |
| `generateVocabulary` | `@Input @Optional` | Whether to generate vocabulary |
| `vocabularyName` | `@Input @Optional` | Name of vocabulary class |
| `vocabularyNamespace` | `@Input @Optional` | Namespace URI for vocabulary |
| `vocabularyPrefix` | `@Input @Optional` | Prefix for vocabulary |

#### Task Outputs

| Output | Type | Description |
|--------|------|-------------|
| `outputDirectory` | `@OutputDirectory` | Directory where generated code is written |

### Current Limitations

**⚠️ Important**: The current task implementation uses `@Input` for file paths (strings) rather than `@InputFile` or `@InputFiles`. This means:

- ✅ **Configuration changes** trigger regeneration (package names, generation flags)
- ⚠️ **File content changes** may not always trigger regeneration (depends on Gradle's file tracking)

**Best Practice**: Use `--rerun-tasks` or clean build if ontology files change but code isn't regenerated.

## Configuring Incremental Builds

### Automatic File Tracking

To ensure ontology file changes trigger regeneration, configure task inputs explicitly:

```kotlin
kastorGen {
    ontologies {
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            targetPackage.set("com.example.dcatus.generated")
        }
    }
}

// Explicitly configure task inputs for file tracking
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

**Benefits**:
- Faster builds when inputs haven't changed
- Shared cache across team members (with remote cache)
- CI/CD performance improvements

### Incremental Compilation

KSP automatically handles incremental compilation:

```kotlin
// KSP configuration (usually in build.gradle.kts)
ksp {
    // Incremental compilation is enabled by default
    // No additional configuration needed
}
```

**How it works**:
1. KSP tracks which source files are processed
2. On subsequent builds, only changed files are reprocessed
3. Generated code is updated incrementally

## Best Practices

### 1. Use Explicit File Inputs

Always configure file inputs explicitly:

```kotlin
tasks.named("generateOntologyDcat") {
    inputs.files(
        file("ontologies/dcat-us.shacl.ttl"),
        file("ontologies/dcat-us.context.jsonld")
    )
}
```

### 2. Organize Ontology Files

Keep ontology files organized for easier tracking:

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

### 3. Use Version Control

Track ontology files in version control:

```bash
# .gitignore should NOT exclude ontology files
# ontologies/**/*.ttl
# ontologies/**/*.jsonld
```

### 4. Monitor Build Performance

Check build performance with Gradle's build scan:

```bash
./gradlew generateOntology --scan
```

### 5. Clean Builds When Needed

If incremental builds aren't working correctly:

```bash
# Clean and rebuild
./gradlew clean generateOntology

# Or force regeneration
./gradlew generateOntology --rerun-tasks
```

## Troubleshooting

### Generated Code Not Updating

**Symptom**: Changes to ontology files don't trigger code regeneration.

**Solutions**:
1. **Explicit file inputs**: Add `inputs.files()` to task configuration
2. **Clean build**: Run `./gradlew clean generateOntology`
3. **Force rerun**: Use `./gradlew generateOntology --rerun-tasks`

### Slow Builds

**Symptom**: Builds are slow even with incremental compilation.

**Solutions**:
1. **Enable build cache**: Add `outputs.cacheIf { true }`
2. **Check file sizes**: Large ontology files slow generation
3. **Use remote cache**: Configure Gradle remote cache for CI/CD

### Stale Generated Code

**Symptom**: Generated code doesn't match ontology files.

**Solutions**:
1. **Clean build**: `./gradlew clean`
2. **Check file paths**: Verify ontology file paths are correct
3. **Verify inputs**: Check that task inputs are configured correctly

## KSP Incremental Compilation Details

### How KSP Tracks Changes

KSP tracks changes at multiple levels:

1. **Source Files**: Which Kotlin source files are processed
2. **Symbols**: Which symbols (classes, functions, properties) are accessed
3. **Dependencies**: Dependencies between source files and generated code

### Incremental Processing

When a source file changes:

1. KSP identifies which processors need to run
2. Only affected processors are executed
3. Only affected generated files are updated

### Generated Code Location

Generated code is placed in:

```
build/generated/sources/kastor-gen/main/kotlin/
└── com/example/generated/
    ├── Catalog.kt
    ├── Dataset.kt
    └── ...
```

**Note**: The exact location depends on your `targetPackage` configuration.

## Advanced Configuration

### Custom Input Tracking

For complex scenarios, use custom input tracking:

```kotlin
tasks.named("generateOntologyDcat") {
    inputs.property("ontologyVersion", "1.0.0")
    inputs.files(
        fileTree("ontologies/dcat-us") {
            include("**/*.ttl")
            include("**/*.jsonld")
        }
    )
}
```

### Output Caching

Configure output caching for better performance:

```kotlin
tasks.named("generateOntologyDcat") {
    outputs.cacheIf { 
        // Cache if ontology files haven't changed
        inputs.files.every { it.exists() }
    }
}
```

### Parallel Execution

Enable parallel task execution:

```kotlin
// gradle.properties
org.gradle.parallel=true
org.gradle.caching=true
```

## Related Documentation

- [Gradle Configuration](../tutorials/gradle-configuration.md) - Complete Gradle setup guide
- [KSP Documentation](https://kotlinlang.org/docs/ksp-overview.html) - Official KSP documentation
- [Gradle Build Cache](https://docs.gradle.org/current/userguide/build_cache.html) - Gradle build cache guide

