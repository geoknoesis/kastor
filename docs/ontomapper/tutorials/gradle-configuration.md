# Gradle Configuration for Ontology Generation

This tutorial explains how to generate domain interfaces and wrappers from SHACL and JSON-LD context files using only Gradle configuration, without requiring any annotations in your source code.

## Table of Contents

- [Overview](#overview)
- [Plugin Setup](#plugin-setup)
- [Configuration](#configuration)
- [Generated Code](#generated-code)
- [Advanced Usage](#advanced-usage)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

## Overview

The OntoMapper Gradle plugin provides a declarative way to generate domain interfaces and wrappers from ontology files. This approach offers several advantages over annotation-based generation:

### Benefits

- **No source code modifications** - Configuration is entirely in Gradle
- **Build-time generation** - Code is generated during the build process
- **Flexible configuration** - Easy to change ontology files and settings
- **Multiple ontologies** - Support for generating from multiple ontology files
- **IDE integration** - Generated code is automatically included in source sets

### When to Use

- **New projects** - Start with Gradle configuration for cleaner architecture
- **Multiple ontologies** - Easier to manage multiple ontology files
- **Build automation** - Better integration with CI/CD pipelines
- **Team collaboration** - Configuration is visible to all team members

## Plugin Setup

### 1. Apply the Plugin

Add the OntoMapper plugin to your `build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.geoknoesis.ontomapper") version "0.1.0"
}
```

### 2. Add Dependencies

Include the required runtime dependencies:

```kotlin
dependencies {
    implementation("com.geoknoesis.kastor:ontomapper-runtime:0.1.0")
    implementation("com.geoknoesis.kastor:rdf-core:0.1.0")
    
    // Optional: Specific backends
    implementation("com.geoknoesis.kastor:rdf-jena:0.1.0")
    implementation("com.geoknoesis.kastor:rdf-rdf4j:0.1.0")
}
```

### 3. Configure the Plugin

Configure ontology generation in your `build.gradle.kts`:

```kotlin
ontomapper {
    shaclPath.set("ontologies/dcat-us.shacl.ttl")
    contextPath.set("ontologies/dcat-us.context.jsonld")
    targetPackage.set("com.example.dcatus.generated")
    generateInterfaces.set(true)
    generateWrappers.set(true)
    outputDirectory.set("build/generated/sources/ontomapper")
}
```

### 4. Add Generated Sources

Include the generated sources in your source sets:

```kotlin
sourceSets {
    main {
        kotlin {
            srcDir("build/generated/sources/ontomapper")
        }
    }
}
```

## Configuration

### Single Ontology Configuration

For a single ontology, you can use the legacy configuration format:

```kotlin
ontomapper {
    shaclPath.set("ontologies/dcat-us.shacl.ttl")
    contextPath.set("ontologies/dcat-us.context.jsonld")
    targetPackage.set("com.example.dcatus.generated")
}
```

### Multiple Ontology Configuration

For multiple ontologies, use the `ontologies` container:

```kotlin
ontomapper {
    ontologies {
        // DCAT-US ontology
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            targetPackage.set("com.example.dcatus.generated")
            outputDirectory.set("build/generated/sources/dcat")
        }
        
        // Schema.org ontology
        create("schema") {
            shaclPath.set("ontologies/schema.shacl.ttl")
            contextPath.set("ontologies/schema.context.jsonld")
            targetPackage.set("com.example.schema.generated")
            outputDirectory.set("build/generated/sources/schema")
        }
        
        // FOAF ontology
        create("foaf") {
            shaclPath.set("ontologies/foaf.shacl.ttl")
            contextPath.set("ontologies/foaf.context.jsonld")
            targetPackage.set("com.example.foaf.generated")
            outputDirectory.set("build/generated/sources/foaf")
        }
    }
}
```

### Separate Packages Configuration

For better organization, you can specify different packages for each component type:

```kotlin
ontomapper {
    ontologies {
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            
            // Separate packages for each component
            interfacePackage.set("com.example.dcatus.interfaces")
            wrapperPackage.set("com.example.dcatus.wrappers")
            vocabularyPackage.set("com.example.dcatus.vocab")
            
            generateInterfaces.set(true)
            generateWrappers.set(true)
            generateVocabulary.set(true)
            
            vocabularyName.set("DCAT")
            vocabularyNamespace.set("http://www.w3.org/ns/dcat#")
            vocabularyPrefix.set("dcat")
            
            outputDirectory.set("build/generated/sources/dcat")
        }
        
        create("schema") {
            shaclPath.set("ontologies/schema.shacl.ttl")
            contextPath.set("ontologies/schema.context.jsonld")
            
            // Separate packages for each component
            interfacePackage.set("com.example.schema.interfaces")
            wrapperPackage.set("com.example.schema.wrappers")
            vocabularyPackage.set("com.example.schema.vocab")
            
            generateInterfaces.set(true)
            generateWrappers.set(true)
            generateVocabulary.set(true)
            
            vocabularyName.set("SCHEMA")
            vocabularyNamespace.set("https://schema.org/")
            vocabularyPrefix.set("schema")
            
            outputDirectory.set("build/generated/sources/schema")
        }
    }
}
```

### Vocabulary Generation Configuration

For generating vocabulary files following the Kastor pattern:

```kotlin
ontomapper {
    ontologies {
        // DCAT vocabulary
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            targetPackage.set("com.example.dcatus.vocab")
            generateInterfaces.set(false)
            generateWrappers.set(false)
            generateVocabulary.set(true)
            vocabularyName.set("DCAT")
            vocabularyNamespace.set("http://www.w3.org/ns/dcat#")
            vocabularyPrefix.set("dcat")
            outputDirectory.set("build/generated/sources/dcat-vocab")
        }
        
        // Schema.org vocabulary
        create("schema") {
            shaclPath.set("ontologies/schema.shacl.ttl")
            contextPath.set("ontologies/schema.context.jsonld")
            targetPackage.set("com.example.schema.vocab")
            generateInterfaces.set(false)
            generateWrappers.set(false)
            generateVocabulary.set(true)
            vocabularyName.set("SCHEMA")
            vocabularyNamespace.set("https://schema.org/")
            vocabularyPrefix.set("schema")
            outputDirectory.set("build/generated/sources/schema-vocab")
        }
    }
}
```

### Configuration Options

Each ontology configuration supports the following options:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `shaclPath` | String | Required | Path to SHACL file (relative to project root) |
| `contextPath` | String | Required | Path to JSON-LD context file (relative to project root) |
| `targetPackage` | String | `com.example.generated` | Legacy base package (deprecated) |
| `interfacePackage` | String | `targetPackage` | Package for generated interfaces |
| `wrapperPackage` | String | `targetPackage` | Package for generated wrappers |
| `vocabularyPackage` | String | `targetPackage` | Package for generated vocabulary |
| `generateInterfaces` | Boolean | `true` | Whether to generate domain interfaces |
| `generateWrappers` | Boolean | `true` | Whether to generate wrapper implementations |
| `generateVocabulary` | Boolean | `false` | Whether to generate vocabulary file |
| `vocabularyName` | String | `Vocabulary` | Name of the vocabulary object |
| `vocabularyNamespace` | String | `http://example.org/vocab#` | Namespace URI for the vocabulary |
| `vocabularyPrefix` | String | `vocab` | Prefix for the vocabulary |
| `outputDirectory` | String | `build/generated/sources/ontomapper` | Output directory for generated files |

### Advanced Configuration

```kotlin
ontomapper {
    ontologies {
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            targetPackage.set("com.example.dcatus.generated")
            generateInterfaces.set(true)
            generateWrappers.set(true)
            outputDirectory.set("src/generated/kotlin")
        }
    }
}
```

### Conditional Configuration

```kotlin
ontomapper {
    ontologies {
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            targetPackage.set("com.example.dcatus.generated")
            
            // Only generate interfaces in development
            generateInterfaces.set(project.hasProperty("dev"))
            
            // Only generate wrappers for production
            generateWrappers.set(project.hasProperty("prod"))
        }
    }
}
```

## Generated Code

### Vocabulary Files

When `generateVocabulary` is enabled, the plugin generates vocabulary files following the Kastor pattern:

```kotlin
package com.example.dcatus.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * DCAT vocabulary.
 * Generated from ontology files.
 */
object DCAT : Vocabulary {
    override val namespace: String = "http://www.w3.org/ns/dcat#"
    override val prefix: String = "dcat"
    
    // Classes
    val Catalog: Iri by lazy { term("Catalog") }
    val Dataset: Iri by lazy { term("Dataset") }
    val Distribution: Iri by lazy { term("Distribution") }
    
    // Properties
    val datasetProp: Iri by lazy { term("dataset") }
    val distributionProp: Iri by lazy { term("distribution") }
    val downloadURL: Iri by lazy { term("downloadURL") }
    val mediaType: Iri by lazy { term("mediaType") }
}
```

**Benefits of Generated Vocabularies:**
- **Type Safety**: All terms are strongly typed as `Iri` objects
- **Lazy Initialization**: Terms are only created when first accessed
- **Consistency**: Follows established Kastor vocabulary pattern
- **IDE Support**: Full autocomplete and type checking
- **Performance**: Memory efficient and fast

### Domain Interfaces

The plugin generates clean domain interfaces based on SHACL shapes:

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

@RdfClass(iri = "http://www.w3.org/ns/dcat#Dataset")
interface Dataset {
    @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
    val title: String
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/description")
    val description: String
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#distribution")
    val distribution: List<Distribution>
}
```

### Wrapper Implementations

Generated wrapper implementations provide RDF-backed functionality:

```kotlin
internal class CatalogWrapper(
    override val rdf: RdfHandle
) : Catalog, RdfBacked {
    
    override val title: String by lazy {
        rdf.graph.getObjects(rdf.node, Iri("http://purl.org/dc/terms/title"))
            .firstOrNull()?.asLiteral()?.lexical ?: ""
    }
    
    override val description: String by lazy {
        rdf.graph.getObjects(rdf.node, Iri("http://purl.org/dc/terms/description"))
            .firstOrNull()?.asLiteral()?.lexical ?: ""
    }
    
    override val publisher: Agent by lazy {
        val publisherNode = rdf.graph.getObjects(rdf.node, Iri("http://purl.org/dc/terms/publisher"))
            .firstOrNull()?.asIri()
        if (publisherNode != null) {
            RdfRef(publisherNode, rdf.graph).asType()
        } else {
            throw IllegalStateException("Publisher not found")
        }
    }
    
    override val dataset: List<Dataset> by lazy {
        rdf.graph.getObjects(rdf.node, Iri("http://www.w3.org/ns/dcat#dataset"))
            .map { obj -> RdfRef(obj.asIri(), rdf.graph).asType() }
    }
    
    companion object {
        init {
            OntoMapper.registry[Catalog::class.java] = { handle -> CatalogWrapper(handle) }
        }
    }
}
```

## Advanced Usage

### Multiple Ontologies

The plugin automatically creates separate tasks for each ontology configuration:

```kotlin
ontomapper {
    ontologies {
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            targetPackage.set("com.example.dcatus.generated")
            outputDirectory.set("build/generated/sources/dcat")
        }
        
        create("schema") {
            shaclPath.set("ontologies/schema.shacl.ttl")
            contextPath.set("ontologies/schema.context.jsonld")
            targetPackage.set("com.example.schema.generated")
            outputDirectory.set("build/generated/sources/schema")
        }
    }
}

// The plugin automatically creates:
// - generateOntologyDcat task
// - generateOntologySchema task
// - generateOntology task (depends on all individual tasks)
```

### Custom File Locations

```kotlin
ontomapper {
    ontologies {
        create("dcat") {
            shaclPath.set("src/main/resources/ontologies/dcat-us.shacl.ttl")
            contextPath.set("src/main/resources/ontologies/dcat-us.context.jsonld")
            targetPackage.set("com.example.dcatus.generated")
            outputDirectory.set("src/generated/kotlin")
        }
    }
}
```

### Environment-specific Configuration

```kotlin
val environment = project.findProperty("environment") as String? ?: "development"

ontomapper {
    ontologies {
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            targetPackage.set("com.example.dcatus.generated")
            
            when (environment) {
                "development" -> {
                    generateInterfaces.set(true)
                    generateWrappers.set(true)
                }
                "production" -> {
                    generateInterfaces.set(true)
                    generateWrappers.set(false) // Use runtime materialization
                }
                "testing" -> {
                    generateInterfaces.set(true)
                    generateWrappers.set(true)
                }
            }
        }
    }
}
```

### Build Variants

```kotlin
android {
    buildTypes {
        debug {
            // Generate full code for debugging
            project.ext.set("ontomapper.generateInterfaces", true)
            project.ext.set("ontomapper.generateWrappers", true)
        }
        release {
            // Generate minimal code for production
            project.ext.set("ontomapper.generateInterfaces", true)
            project.ext.set("ontomapper.generateWrappers", false)
        }
    }
}

ontomapper {
    ontologies {
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            targetPackage.set("com.example.dcatus.generated")
            
            generateInterfaces.set(project.findProperty("ontomapper.generateInterfaces") as Boolean? ?: true)
            generateWrappers.set(project.findProperty("ontomapper.generateWrappers") as Boolean? ?: true)
        }
    }
}
```

## Best Practices

### 1. File Organization

```
project/
├── ontologies/                    # Ontology files
│   ├── dcat-us.shacl.ttl         # DCAT-US SHACL shapes
│   ├── dcat-us.context.jsonld    # DCAT-US JSON-LD context
│   ├── schema.shacl.ttl          # Schema.org SHACL shapes
│   ├── schema.context.jsonld     # Schema.org JSON-LD context
│   ├── foaf.shacl.ttl            # FOAF SHACL shapes
│   └── foaf.context.jsonld       # FOAF JSON-LD context
├── src/main/kotlin/              # Source code
└── build/generated/sources/      # Generated code
    ├── dcat/                     # DCAT-US generated code
    ├── schema/                   # Schema.org generated code
    └── foaf/                     # FOAF generated code
```

### 2. Package Structure

```kotlin
ontomapper {
    ontologies {
        create("dcat") {
            targetPackage.set("com.example.dcatus.generated")
        }
        
        create("schema") {
            targetPackage.set("com.example.schema.generated")
        }
        
        create("foaf") {
            targetPackage.set("com.example.foaf.generated")
        }
    }
}
```

### 3. Version Management

```kotlin
// Use version catalogs for dependency management
dependencies {
    implementation(libs.ontomapper.runtime)
    implementation(libs.kastor.rdf.core)
}
```

### 4. Build Optimization

```kotlin
// Cache generated files for each ontology
tasks.named("generateOntologyDcat") {
    outputs.cacheIf { true }
}

tasks.named("generateOntologySchema") {
    outputs.cacheIf { true }
}

// Only regenerate if ontology files change
tasks.named("generateOntologyDcat") {
    inputs.files(
        file("ontologies/dcat-us.shacl.ttl"),
        file("ontologies/dcat-us.context.jsonld")
    )
}

tasks.named("generateOntologySchema") {
    inputs.files(
        file("ontologies/schema.shacl.ttl"),
        file("ontologies/schema.context.jsonld")
    )
}
```

### 5. Testing Generated Code

```kotlin
// Test generated code
tasks.test {
    dependsOn("generateOntology")
    useJUnitPlatform()
}

// Integration tests
tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Run integration tests with generated code"
    dependsOn("generateOntology")
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
}
```

## Troubleshooting

### Common Issues

#### 1. File Not Found

**Error**: `File not found: ontologies/dcat-us.shacl.ttl`

**Solution**: Check file paths are correct and files exist:

```bash
# Verify files exist
ls -la ontologies/
# Should show:
# dcat-us.shacl.ttl
# dcat-us.context.jsonld
```

#### 2. Generation Fails

**Error**: `Ontology generation failed`

**Solution**: Check SHACL and JSON-LD syntax:

```bash
# Validate SHACL syntax
./gradlew generateOntology --debug

# Check for syntax errors in logs
```

#### 3. Generated Code Not Found

**Error**: `Unresolved reference: Catalog`

**Solution**: Ensure source sets include generated directory:

```kotlin
sourceSets {
    main {
        kotlin {
            srcDir("build/generated/sources/ontomapper")
        }
    }
}
```

#### 4. Build Order Issues

**Error**: `Task 'compileKotlin' depends on task 'generateOntology'`

**Solution**: Ensure proper task dependencies:

```kotlin
tasks.compileKotlin {
    dependsOn("generateOntology")
}
```

### Debug Mode

Enable debug logging for troubleshooting:

```bash
# Run with debug logging
./gradlew generateOntology --debug

# Check build logs
./gradlew generateOntology --info
```

### Validation

Validate your configuration:

```bash
# Check if plugin is applied
./gradlew tasks --group=ontomapper

# Should show:
# generateOntology - Generate domain interfaces and wrappers from SHACL and JSON-LD context files
```

### Performance Issues

If generation is slow:

1. **Check file sizes** - Large ontology files can slow generation
2. **Use caching** - Enable build caching for generated files
3. **Incremental builds** - Only regenerate when ontology files change

```kotlin
tasks.generateOntology {
    outputs.cacheIf { true }
    inputs.files(
        file("ontologies/dcat-us.shacl.ttl"),
        file("ontologies/dcat-us.context.jsonld")
    )
}
```

## Comparison with Annotation-based Approach

| Feature | Gradle Configuration | Annotation-based |
|---------|---------------------|------------------|
| **Configuration** | Build script | Source code |
| **Flexibility** | High | Medium |
| **Build-time** | Yes | Yes |
| **Runtime deps** | None | None |
| **Multiple ontologies** | Easy | Complex |
| **IDE support** | Good | Good |
| **Team collaboration** | Excellent | Good |
| **CI/CD integration** | Excellent | Good |
| **Version control** | Clean | Mixed |

## Vocabulary Generation

### Vocabulary-Only Configuration

For generating only vocabulary files (no interfaces or wrappers):

```kotlin
ontomapper {
    ontologies {
        create("vocab") {
            shaclPath.set("ontologies/vocab.shacl.ttl")
            contextPath.set("ontologies/vocab.context.jsonld")
            targetPackage.set("com.example.vocab")
            generateInterfaces.set(false)
            generateWrappers.set(false)
            generateVocabulary.set(true)
            vocabularyName.set("MY_VOCAB")
            vocabularyNamespace.set("http://example.org/vocab#")
            vocabularyPrefix.set("myvocab")
        }
    }
}
```

### Mixed Generation

Generate interfaces, wrappers, and vocabulary together:

```kotlin
ontomapper {
    ontologies {
        create("mixed") {
            shaclPath.set("ontologies/mixed.shacl.ttl")
            contextPath.set("ontologies/mixed.context.jsonld")
            targetPackage.set("com.example.mixed.generated")
            generateInterfaces.set(true)
            generateWrappers.set(true)
            generateVocabulary.set(true)
            vocabularyName.set("MIXED")
            vocabularyNamespace.set("http://example.org/mixed#")
            vocabularyPrefix.set("mixed")
        }
    }
}
```

### Using Generated Components from Separate Packages

```kotlin
// Import from separate packages
import com.example.dcatus.interfaces.*
import com.example.dcatus.wrappers.*
import com.example.dcatus.vocab.DCAT
import com.example.schema.interfaces.*
import com.example.schema.wrappers.*
import com.example.schema.vocab.SCHEMA

// Access vocabulary terms from separate vocab packages
val catalogClass = DCAT.Catalog
val personClass = SCHEMA.Person
val nameProperty = SCHEMA.name

// Create RDF data using vocabularies
repo.add {
    val catalog = iri("http://example.org/catalog")
    catalog - RDF.type - DCAT.Catalog
    catalog - DCTERMS.title - "My Catalog"
    
    val person = iri("http://example.org/person")
    person - RDF.type - SCHEMA.Person
    person - SCHEMA.name - "John Doe"
}

// Materialize using interfaces from separate interface packages
val catalogRef = RdfRef(iri("http://example.org/catalog"), repo.defaultGraph)
val catalog: Catalog = catalogRef.asType()

// Use generated wrappers directly from separate wrapper packages
val catalogHandle = catalogRef.rdf
val catalogWrapper = CatalogWrapper(catalogHandle)

// Query with vocabularies from separate packages
val results = repo.query {
    select("?name") where {
        "?person" - RDF.type - SCHEMA.Person
        "?person" - SCHEMA.name - "?name"
    }
}
```

## Conclusion

The Gradle configuration approach for ontology generation provides a clean, declarative way to generate domain interfaces, wrappers, and vocabulary files from SHACL and JSON-LD context files. It offers excellent flexibility, team collaboration, and CI/CD integration while maintaining the same type safety and performance benefits as the annotation-based approach.

For new projects or when managing multiple ontologies, the Gradle configuration approach is recommended. For existing projects with simple ontology requirements, the annotation-based approach may be more convenient.

For more information, see:
- [Ontology Generation](ontology-generation.md) - Annotation-based approach
- [Domain Modeling](domain-modeling.md) - Creating domain interfaces
- [Best Practices](best-practices.md) - Guidelines for effective usage
- [FAQ](faq.md) - Frequently asked questions
