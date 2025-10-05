# Separate Packages Example

This example demonstrates how to generate vocabularies, interfaces, and wrappers in separate packages using the OntoMapper Gradle plugin, providing better organization and separation of concerns.

## Features

- **Separate Packages** - Different packages for vocabularies, interfaces, and wrappers
- **Clear Organization** - Better code organization and separation of concerns
- **No Naming Conflicts** - Each component type has its own namespace
- **Flexible Configuration** - Independent package configuration for each component
- **Better IDE Support** - Improved navigation and autocomplete
- **Maintainable Structure** - Easier to maintain and update

## Project Structure

```
separate-packages-example/
├── build.gradle.kts                    # Gradle configuration
├── ontologies/
│   ├── dcat-us.shacl.ttl              # DCAT-US SHACL shapes
│   ├── dcat-us.context.jsonld         # DCAT-US JSON-LD context
│   ├── schema.shacl.ttl               # Schema.org SHACL shapes
│   ├── schema.context.jsonld          # Schema.org JSON-LD context
│   ├── foaf.shacl.ttl                 # FOAF SHACL shapes
│   └── foaf.context.jsonld            # FOAF JSON-LD context
├── src/main/kotlin/
│   └── com/example/separatepackages/
│       └── SeparatePackagesDemo.kt    # Demo application
└── README.md                          # This file
```

## Gradle Configuration

The `build.gradle.kts` file configures separate packages for each component type:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.geoknoesis.ontomapper") version "0.1.0"
}

// Configure separate packages for different generated components
ontomapper {
    ontologies {
        // DCAT-US with separate packages
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            
            // Separate packages for each component
            interfacePackage.set("com.example.dcatus.interfaces")
            wrapperPackage.set("com.example.dcatus.wrappers")
            vocabularyPackage.set("com.example.dcatus.vocab")
            
            // Generate all components
            generateInterfaces.set(true)
            generateWrappers.set(true)
            generateVocabulary.set(true)
            
            // Vocabulary configuration
            vocabularyName.set("DCAT")
            vocabularyNamespace.set("http://www.w3.org/ns/dcat#")
            vocabularyPrefix.set("dcat")
            
            outputDirectory.set("build/generated/sources/dcat")
        }
        
        // Schema.org with separate packages
        create("schema") {
            shaclPath.set("ontologies/schema.shacl.ttl")
            contextPath.set("ontologies/schema.context.jsonld")
            
            // Separate packages for each component
            interfacePackage.set("com.example.schema.interfaces")
            wrapperPackage.set("com.example.schema.wrappers")
            vocabularyPackage.set("com.example.schema.vocab")
            
            // Generate all components
            generateInterfaces.set(true)
            generateWrappers.set(true)
            generateVocabulary.set(true)
            
            // Vocabulary configuration
            vocabularyName.set("SCHEMA")
            vocabularyNamespace.set("https://schema.org/")
            vocabularyPrefix.set("schema")
            
            outputDirectory.set("build/generated/sources/schema")
        }
        
        // FOAF with separate packages
        create("foaf") {
            shaclPath.set("ontologies/foaf.shacl.ttl")
            contextPath.set("ontologies/foaf.context.jsonld")
            
            // Separate packages for each component
            interfacePackage.set("com.example.foaf.interfaces")
            wrapperPackage.set("com.example.foaf.wrappers")
            vocabularyPackage.set("com.example.foaf.vocab")
            
            // Generate all components
            generateInterfaces.set(true)
            generateWrappers.set(true)
            generateVocabulary.set(true)
            
            // Vocabulary configuration
            vocabularyName.set("FOAF")
            vocabularyNamespace.set("http://xmlns.com/foaf/0.1/")
            vocabularyPrefix.set("foaf")
            
            outputDirectory.set("build/generated/sources/foaf")
        }
    }
}
```

## Configuration Options

Each ontology configuration supports separate package options:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `interfacePackage` | String | `targetPackage` | Package for generated interfaces |
| `wrapperPackage` | String | `targetPackage` | Package for generated wrappers |
| `vocabularyPackage` | String | `targetPackage` | Package for generated vocabulary |
| `targetPackage` | String | `com.example.generated` | Legacy base package (deprecated) |

## Generated File Structure

The plugin generates files in separate packages:

```
build/generated/sources/
├── dcat/
│   ├── com/example/dcatus/interfaces/
│   │   ├── Catalog.kt
│   │   └── Dataset.kt
│   ├── com/example/dcatus/wrappers/
│   │   ├── CatalogWrapper.kt
│   │   └── DatasetWrapper.kt
│   └── com/example/dcatus/vocab/
│       └── DCAT.kt
├── schema/
│   ├── com/example/schema/interfaces/
│   │   ├── Person.kt
│   │   └── PostalAddress.kt
│   ├── com/example/schema/wrappers/
│   │   ├── PersonWrapper.kt
│   │   └── PostalAddressWrapper.kt
│   └── com/example/schema/vocab/
│       └── SCHEMA.kt
└── foaf/
    ├── com/example/foaf/interfaces/
    │   ├── Person.kt
    │   └── Document.kt
    ├── com/example/foaf/wrappers/
    │   ├── PersonWrapper.kt
    │   └── DocumentWrapper.kt
    └── com/example/foaf/vocab/
        └── FOAF.kt
```

## Generated Code Examples

### Vocabulary (com.example.dcatus.vocab)
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
    
    // Properties
    val datasetProp: Iri by lazy { term("dataset") }
    val downloadURL: Iri by lazy { term("downloadURL") }
}
```

### Interface (com.example.dcatus.interfaces)
```kotlin
package com.example.dcatus.interfaces

import com.example.dcatus.vocab.DCAT
import com.geoknoesis.kastor.rdf.*

@RdfClass(iri = "http://www.w3.org/ns/dcat#Catalog")
interface Catalog {
    @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
    val title: String
    
    @get:RdfProperty(iri = "http://purl.org/dc/terms/description")
    val description: String
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#dataset")
    val dataset: List<Dataset>
}
```

### Wrapper (com.example.dcatus.wrappers)
```kotlin
package com.example.dcatus.wrappers

import com.example.dcatus.interfaces.Catalog
import com.geoknoesis.kastor.rdf.*

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

## Usage

1. **Build the project**:
   ```bash
   ./gradlew build
   ```

2. **Run the demo**:
   ```bash
   ./gradlew run
   ```

3. **View generated files**:
   ```bash
   # Generated files are in build/generated/sources/
   ls -la build/generated/sources/dcat/com/example/dcatus/interfaces/
   ls -la build/generated/sources/dcat/com/example/dcatus/wrappers/
   ls -la build/generated/sources/dcat/com/example/dcatus/vocab/
   ```

## Usage Examples

### Importing from Separate Packages
```kotlin
// Import from separate packages
import com.example.dcatus.interfaces.*
import com.example.dcatus.wrappers.*
import com.example.dcatus.vocab.DCAT
import com.example.schema.interfaces.*
import com.example.schema.wrappers.*
import com.example.schema.vocab.SCHEMA
```

### Using Vocabularies
```kotlin
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
```

### Using Interfaces
```kotlin
// Materialize using interfaces from separate interface packages
val catalogRef = RdfRef(iri("http://example.org/catalog"), repo.defaultGraph)
val catalog: Catalog = catalogRef.asType()

println("Title: ${catalog.title}")
println("Description: ${catalog.description}")
println("Dataset count: ${catalog.dataset.size}")
```

### Using Wrappers
```kotlin
// Use generated wrappers directly from separate wrapper packages
val catalogHandle = catalogRef.rdf
val catalogWrapper = CatalogWrapper(catalogHandle)

println("Title: ${catalogWrapper.title}")
println("Description: ${catalogWrapper.description}")
```

### Cross-Package Queries
```kotlin
// Query using vocabularies from separate packages
val results = repo.query {
    select("?name", "?email", "?type") where {
        "?person" - RDF.type - "?type"
        "?person" - "?nameProp" - "?name"
        "?person" - "?emailProp" - "?email"
        filter {
            "?nameProp" in (SCHEMA.name, FOAF.name)
            "?emailProp" in (SCHEMA.email, FOAF.mbox)
        }
    }
}
```

## Benefits

### 1. **Clear Separation of Concerns**
- **Vocabularies** - RDF term constants in dedicated vocab packages
- **Interfaces** - Domain interfaces in dedicated interface packages
- **Wrappers** - RDF-backed implementations in dedicated wrapper packages

### 2. **Better Organization**
- Logical grouping of related components
- Easier navigation in IDEs
- Clear package structure

### 3. **No Naming Conflicts**
- Each component type has its own namespace
- No conflicts between vocabularies, interfaces, and wrappers
- Cleaner imports and usage

### 4. **Improved Maintainability**
- Easier to locate and modify specific component types
- Clear separation makes updates simpler
- Better code organization

### 5. **Enhanced IDE Support**
- Better autocomplete and navigation
- Clearer package structure
- Improved code organization

## Advanced Configuration

### Custom Package Names
```kotlin
ontomapper {
    ontologies {
        create("custom") {
            shaclPath.set("ontologies/custom.shacl.ttl")
            contextPath.set("ontologies/custom.context.jsonld")
            
            // Custom package names
            interfacePackage.set("com.mycompany.custom.api")
            wrapperPackage.set("com.mycompany.custom.impl")
            vocabularyPackage.set("com.mycompany.custom.vocab")
            
            generateInterfaces.set(true)
            generateWrappers.set(true)
            generateVocabulary.set(true)
        }
    }
}
```

### Mixed Package Configuration
```kotlin
ontomapper {
    ontologies {
        create("mixed") {
            shaclPath.set("ontologies/mixed.shacl.ttl")
            contextPath.set("ontologies/mixed.context.jsonld")
            
            // Some components in separate packages, others together
            interfacePackage.set("com.example.mixed.api")
            wrapperPackage.set("com.example.mixed") // Same as base
            vocabularyPackage.set("com.example.mixed.vocab")
            
            generateInterfaces.set(true)
            generateWrappers.set(true)
            generateVocabulary.set(true)
        }
    }
}
```

### Environment-specific Packages
```kotlin
ontomapper {
    ontologies {
        create("env") {
            shaclPath.set("ontologies/env.shacl.ttl")
            contextPath.set("ontologies/env.context.jsonld")
            
            val environment = project.findProperty("environment") as String? ?: "dev"
            
            when (environment) {
                "dev" -> {
                    interfacePackage.set("com.example.dev.api")
                    wrapperPackage.set("com.example.dev.impl")
                    vocabularyPackage.set("com.example.dev.vocab")
                }
                "prod" -> {
                    interfacePackage.set("com.example.prod.api")
                    wrapperPackage.set("com.example.prod.impl")
                    vocabularyPackage.set("com.example.prod.vocab")
                }
            }
            
            generateInterfaces.set(true)
            generateWrappers.set(true)
            generateVocabulary.set(true)
        }
    }
}
```

## Comparison with Single Package

| Feature | Single Package | Separate Packages |
|---------|---------------|------------------|
| **Organization** | Mixed | Clear separation |
| **Naming conflicts** | Possible | Avoided |
| **IDE navigation** | Good | Excellent |
| **Maintainability** | Moderate | Excellent |
| **Flexibility** | Limited | High |
| **Complexity** | Simple | Moderate |

## Troubleshooting

### Common Issues

1. **Import errors**:
   - Ensure source sets include all generated directories
   - Check package names in generated code
   - Verify import statements match generated packages

2. **Package not found**:
   - Check package configuration in build.gradle.kts
   - Verify generated files exist in expected locations
   - Ensure output directories are included in source sets

3. **Naming conflicts**:
   - Use separate packages to avoid conflicts
   - Check for duplicate class names across packages
   - Verify package naming conventions

### Debug Mode
```bash
# Run with debug logging
./gradlew generateOntology --debug

# Check individual ontology generation
./gradlew generateOntologyDcat --debug
./gradlew generateOntologySchema --debug
./gradlew generateOntologyFoaf --debug
```

## Best Practices

### 1. **Package Naming**
- Use descriptive package names
- Follow Java/Kotlin package conventions
- Use consistent naming patterns

### 2. **Organization**
- Group related components together
- Use logical package hierarchies
- Maintain consistent structure

### 3. **Configuration**
- Use separate packages for better organization
- Configure packages based on project needs
- Consider team preferences and conventions

### 4. **Maintenance**
- Keep package structure consistent
- Document package organization
- Update package names when needed

## Next Steps

- Explore the generated files in `build/generated/sources/`
- Modify the package configuration to suit your needs
- Add more ontologies with separate package configurations
- Integrate with your existing project structure
- Use the generated components in your applications

For more information, see:
- [Gradle Configuration Tutorial](../docs/ontomapper/tutorials/gradle-configuration.md)
- [Multiple Ontology Example](../samples/multi-ontology-example/README.md)
- [Vocabulary Generation Example](../samples/vocabulary-example/README.md)
- [Best Practices](../docs/ontomapper/best-practices.md)
