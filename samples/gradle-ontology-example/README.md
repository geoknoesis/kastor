# Gradle Ontology Generation Example

This example demonstrates how to generate domain interfaces and wrappers from SHACL and JSON-LD context files using only Gradle configuration, without requiring any annotations in the source code.

## Features

- **Gradle-only configuration** - No annotations required
- **SHACL parsing** - Generates interfaces from SHACL shapes
- **JSON-LD context** - Maps properties and types
- **Automatic code generation** - Interfaces and wrappers generated at build time
- **Type safety** - Full Kotlin type safety for generated code

## Project Structure

```
gradle-ontology-example/
├── build.gradle.kts                    # Gradle configuration
├── ontologies/
│   ├── dcat-us.shacl.ttl              # SHACL shapes
│   └── dcat-us.context.jsonld         # JSON-LD context
├── src/main/kotlin/
│   └── com/example/gradleontology/
│       └── GradleOntologyDemo.kt      # Demo application
└── README.md                          # This file
```

## Gradle Configuration

The `build.gradle.kts` file configures the OntoMapper plugin:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.geoknoesis.ontomapper") version "0.1.0"
}

// Configure OntoMapper plugin
ontomapper {
    shaclPath.set("ontologies/dcat-us.shacl.ttl")
    contextPath.set("ontologies/dcat-us.context.jsonld")
    targetPackage.set("com.example.dcatus.generated")
    generateInterfaces.set(true)
    generateWrappers.set(true)
    outputDirectory.set("build/generated/sources/ontomapper")
}

// Add generated sources to source sets
sourceSets {
    main {
        kotlin {
            srcDir("build/generated/sources/ontomapper")
        }
    }
}
```

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `shaclPath` | String | Required | Path to SHACL file |
| `contextPath` | String | Required | Path to JSON-LD context file |
| `targetPackage` | String | `com.example.generated` | Target package for generated code |
| `generateInterfaces` | Boolean | `true` | Whether to generate domain interfaces |
| `generateWrappers` | Boolean | `true` | Whether to generate wrapper implementations |
| `outputDirectory` | String | `build/generated/sources/ontomapper` | Output directory for generated files |

## Generated Code

The plugin generates the following files:

### Domain Interfaces
```kotlin
@RdfClass(iri = "http://www.w3.org/ns/dcat#Catalog")
interface Catalog {
    @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
    val title: String
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/description")
    val description: String
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/publisher")
    val publisher: Agent
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#dataset")
    val dataset: List<Dataset>
}
```

### Wrapper Implementations
```kotlin
internal class CatalogWrapper(
    override val rdf: RdfHandle
) : Catalog, RdfBacked {
    
    override val title: String by lazy {
        rdf.graph.getObjects(rdf.node, Iri("http://purl.org/dc/terms/title"))
            .firstOrNull()?.asLiteral()?.lexical ?: ""
    }
    
    // ... other properties
}
```

## Usage

1. **Build the project**:
   ```bash
   ./gradlew build
   ```

2. **Run the demo**:
   ```bash
   ./gradlew run
   ```

3. **View generated code**:
   ```bash
   # Generated files are in build/generated/sources/ontomapper/
   ls -la build/generated/sources/ontomapper/com/example/dcatus/generated/
   ```

## Benefits

### 1. **No Annotations Required**
- Configuration is entirely in Gradle
- No need to modify source code
- Clean separation of concerns

### 2. **Build-time Generation**
- Code is generated during the build process
- No runtime dependencies on annotation processors
- Faster startup times

### 3. **Flexible Configuration**
- Easy to change ontology files
- Support for multiple ontologies
- Configurable output locations

### 4. **Type Safety**
- Generated code is fully type-safe
- Compile-time validation
- IDE support for generated code

## Comparison with Annotation-based Approach

| Feature | Gradle Configuration | Annotation-based |
|---------|---------------------|------------------|
| **Configuration** | Build script | Source code |
| **Flexibility** | High | Medium |
| **Build-time** | Yes | Yes |
| **Runtime deps** | None | None |
| **Multiple ontologies** | Easy | Complex |
| **IDE support** | Good | Good |

## Advanced Configuration

### Multiple Ontologies
```kotlin
ontomapper {
    // Primary ontology
    shaclPath.set("ontologies/dcat-us.shacl.ttl")
    contextPath.set("ontologies/dcat-us.context.jsonld")
    targetPackage.set("com.example.dcatus.generated")
    
    // Additional ontologies can be configured via custom tasks
}
```

### Custom Output Directory
```kotlin
ontomapper {
    outputDirectory.set("src/generated/kotlin")
}
```

### Conditional Generation
```kotlin
ontomapper {
    generateInterfaces.set(project.hasProperty("generateInterfaces"))
    generateWrappers.set(project.hasProperty("generateWrappers"))
}
```

## Troubleshooting

### Common Issues

1. **File not found**:
   - Ensure SHACL and JSON-LD files exist
   - Check file paths are relative to project root

2. **Generation fails**:
   - Check SHACL syntax
   - Verify JSON-LD context format
   - Review build logs for errors

3. **Generated code not found**:
   - Ensure source sets include generated directory
   - Check output directory configuration

### Debug Mode
```bash
# Run with debug logging
./gradlew generateOntology --debug
```

## Next Steps

- Explore the generated code in `build/generated/sources/ontomapper/`
- Modify the SHACL and JSON-LD files to see how generation changes
- Add more complex ontologies and shapes
- Integrate with your existing RDF data and queries
