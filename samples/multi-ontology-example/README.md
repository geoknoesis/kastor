# Multi-Ontology Generation Example

This example demonstrates how to generate domain interfaces and wrappers from multiple SHACL and JSON-LD context files using Gradle configuration, each in different packages.

## Features

- **Multiple ontologies** - Generate code from multiple SHACL/JSON-LD pairs
- **Separate packages** - Each ontology generates code in its own package
- **Independent configuration** - Each ontology has its own settings
- **Cross-ontology queries** - Query data across different ontologies
- **Type safety** - Full Kotlin type safety for all generated code

## Project Structure

```
multi-ontology-example/
├── build.gradle.kts                    # Gradle configuration
├── ontologies/
│   ├── dcat-us.shacl.ttl              # DCAT-US SHACL shapes
│   ├── dcat-us.context.jsonld         # DCAT-US JSON-LD context
│   ├── schema.shacl.ttl               # Schema.org SHACL shapes
│   ├── schema.context.jsonld          # Schema.org JSON-LD context
│   ├── foaf.shacl.ttl                 # FOAF SHACL shapes
│   └── foaf.context.jsonld            # FOAF JSON-LD context
├── src/main/kotlin/
│   └── com/example/multiontology/
│       └── MultiOntologyDemo.kt       # Demo application
└── README.md                          # This file
```

## Gradle Configuration

The `build.gradle.kts` file configures multiple ontologies:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.geoknoesis.ontomapper") version "0.1.0"
}

// Configure multiple ontologies
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

// Add generated sources to source sets
sourceSets {
    main {
        kotlin {
            srcDir("build/generated/sources/dcat")
            srcDir("build/generated/sources/schema")
            srcDir("build/generated/sources/foaf")
        }
    }
}
```

## Configuration Options

Each ontology configuration supports the following options:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `shaclPath` | String | Required | Path to SHACL file |
| `contextPath` | String | Required | Path to JSON-LD context file |
| `targetPackage` | String | `com.example.generated` | Target package for generated code |
| `generateInterfaces` | Boolean | `true` | Whether to generate domain interfaces |
| `generateWrappers` | Boolean | `true` | Whether to generate wrapper implementations |
| `outputDirectory` | String | `build/generated/sources/ontomapper` | Output directory for generated files |

## Generated Code Structure

The plugin generates code in separate packages for each ontology:

```
build/generated/sources/
├── dcat/
│   └── com/example/dcatus/generated/
│       ├── Catalog.kt
│       ├── CatalogWrapper.kt
│       ├── Dataset.kt
│       └── DatasetWrapper.kt
├── schema/
│   └── com/example/schema/generated/
│       ├── Person.kt
│       ├── PersonWrapper.kt
│       ├── PostalAddress.kt
│       └── PostalAddressWrapper.kt
└── foaf/
    └── com/example/foaf/generated/
        ├── Person.kt
        ├── PersonWrapper.kt
        ├── Document.kt
        └── DocumentWrapper.kt
```

## Generated Interfaces

### DCAT-US (com.example.dcatus.generated)
```kotlin
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

### Schema.org (com.example.schema.generated)
```kotlin
@RdfClass(iri = "https://schema.org/Person")
interface Person {
    @get:RdfProperty(iri = "https://schema.org/name")
    val name: String
    
    @get:RdfProperty(iri = "https://schema.org/email")
    val email: String
    
    @get:RdfProperty(iri = "https://schema.org/address")
    val address: PostalAddress?
}
```

### FOAF (com.example.foaf.generated)
```kotlin
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: String
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/mbox")
    val mbox: String
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/knows")
    val knows: List<Person>
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
   # Generated files are in build/generated/sources/
   ls -la build/generated/sources/dcat/com/example/dcatus/generated/
   ls -la build/generated/sources/schema/com/example/schema/generated/
   ls -la build/generated/sources/foaf/com/example/foaf/generated/
   ```

## Cross-Ontology Usage

The generated code allows you to work with data from different ontologies:

```kotlin
// Materialize objects from different ontologies
val catalog: Catalog = catalogRef.asType()           // DCAT-US
val person: Person = personRef.asType()              // Schema.org
val foafPerson: foaf.Person = foafPersonRef.asType() // FOAF

// Query across ontologies
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

### 1. **Separation of Concerns**
- Each ontology generates code in its own package
- No naming conflicts between ontologies
- Clear separation of domain concepts

### 2. **Independent Configuration**
- Each ontology can have different settings
- Different output directories
- Flexible package naming

### 3. **Cross-Ontology Queries**
- Query data across different ontologies
- Unified RDF repository
- Type-safe access to all data

### 4. **Build Optimization**
- Parallel generation of different ontologies
- Independent task execution
- Better caching and incremental builds

## Advanced Configuration

### Environment-specific Ontologies
```kotlin
ontomapper {
    ontologies {
        if (project.hasProperty("dev")) {
            create("dcat") {
                shaclPath.set("ontologies/dcat-us.shacl.ttl")
                contextPath.set("ontologies/dcat-us.context.jsonld")
                targetPackage.set("com.example.dcatus.generated")
                generateInterfaces.set(true)
                generateWrappers.set(true)
            }
        }
        
        if (project.hasProperty("prod")) {
            create("schema") {
                shaclPath.set("ontologies/schema.shacl.ttl")
                contextPath.set("ontologies/schema.context.jsonld")
                targetPackage.set("com.example.schema.generated")
                generateInterfaces.set(true)
                generateWrappers.set(false) // Use runtime materialization
            }
        }
    }
}
```

### Conditional Generation
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

### Custom Output Directories
```kotlin
ontomapper {
    ontologies {
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            targetPackage.set("com.example.dcatus.generated")
            outputDirectory.set("src/generated/kotlin/dcat")
        }
        
        create("schema") {
            shaclPath.set("ontologies/schema.shacl.ttl")
            contextPath.set("ontologies/schema.context.jsonld")
            targetPackage.set("com.example.schema.generated")
            outputDirectory.set("src/generated/kotlin/schema")
        }
    }
}
```

## Troubleshooting

### Common Issues

1. **Package conflicts**:
   - Ensure different ontologies use different target packages
   - Check for naming conflicts in generated interfaces

2. **File not found**:
   - Verify SHACL and JSON-LD file paths
   - Check that files exist in the specified locations

3. **Generation fails**:
   - Check SHACL and JSON-LD syntax
   - Review build logs for specific errors

4. **Import issues**:
   - Ensure source sets include all generated directories
   - Check package names in generated code

### Debug Mode
```bash
# Run with debug logging
./gradlew generateOntology --debug

# Check individual ontology generation
./gradlew generateOntologyDcat --debug
./gradlew generateOntologySchema --debug
./gradlew generateOntologyFoaf --debug
```

## Comparison with Single Ontology

| Feature | Single Ontology | Multiple Ontologies |
|---------|----------------|-------------------|
| **Configuration** | Simple | More complex |
| **Package separation** | Single package | Multiple packages |
| **Naming conflicts** | Possible | Avoided |
| **Build performance** | Good | Better (parallel) |
| **Cross-ontology queries** | N/A | Supported |
| **Maintenance** | Easy | Moderate |

## Next Steps

- Explore the generated code in `build/generated/sources/`
- Modify the SHACL and JSON-LD files to see how generation changes
- Add more ontologies and configurations
- Integrate with your existing RDF data and queries
- Use cross-ontology queries for complex data analysis

For more information, see:
- [Gradle Configuration Tutorial](../docs/ontomapper/tutorials/gradle-configuration.md)
- [Ontology Generation](ontology-generation.md) - Annotation-based approach
- [Domain Modeling](domain-modeling.md) - Creating domain interfaces
- [Best Practices](best-practices.md) - Guidelines for effective usage
