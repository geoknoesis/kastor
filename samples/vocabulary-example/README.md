# Vocabulary Generation Example

This example demonstrates how to generate vocabulary files from SHACL and JSON-LD context files using the OntoMapper Gradle plugin, following the Kastor vocabulary pattern.

## Features

- **Vocabulary Generation** - Generate type-safe vocabulary constants from ontology files
- **Kastor Pattern** - Follows the established Kastor vocabulary structure
- **Type Safety** - All vocabulary terms are strongly typed as `Iri` objects
- **Lazy Initialization** - Terms are only created when first accessed
- **Multiple Vocabularies** - Generate multiple vocabulary files from different ontologies
- **Cross-Vocabulary Usage** - Use generated vocabularies together in queries

## Project Structure

```
vocabulary-example/
├── build.gradle.kts                    # Gradle configuration
├── ontologies/
│   ├── dcat-us.shacl.ttl              # DCAT-US SHACL shapes
│   ├── dcat-us.context.jsonld         # DCAT-US JSON-LD context
│   ├── schema.shacl.ttl               # Schema.org SHACL shapes
│   ├── schema.context.jsonld          # Schema.org JSON-LD context
│   ├── foaf.shacl.ttl                 # FOAF SHACL shapes
│   └── foaf.context.jsonld            # FOAF JSON-LD context
├── src/main/kotlin/
│   └── com/example/vocabulary/
│       └── VocabularyDemo.kt          # Demo application
└── README.md                          # This file
```

## Gradle Configuration

The `build.gradle.kts` file configures vocabulary generation:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.geoknoesis.ontomapper") version "0.1.0"
}

// Configure vocabulary generation
ontomapper {
    ontologies {
        // DCAT-US vocabulary
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
        
        // FOAF vocabulary
        create("foaf") {
            shaclPath.set("ontologies/foaf.shacl.ttl")
            contextPath.set("ontologies/foaf.context.jsonld")
            targetPackage.set("com.example.foaf.vocab")
            generateInterfaces.set(false)
            generateWrappers.set(false)
            generateVocabulary.set(true)
            vocabularyName.set("FOAF")
            vocabularyNamespace.set("http://xmlns.com/foaf/0.1/")
            vocabularyPrefix.set("foaf")
            outputDirectory.set("build/generated/sources/foaf-vocab")
        }
    }
}
```

## Configuration Options

Each vocabulary configuration supports the following options:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `shaclPath` | String | Required | Path to SHACL file |
| `contextPath` | String | Required | Path to JSON-LD context file |
| `targetPackage` | String | `com.example.generated` | Target package for generated vocabulary |
| `generateVocabulary` | Boolean | `false` | Whether to generate vocabulary file |
| `vocabularyName` | String | `Vocabulary` | Name of the vocabulary object |
| `vocabularyNamespace` | String | `http://example.org/vocab#` | Namespace URI for the vocabulary |
| `vocabularyPrefix` | String | `vocab` | Prefix for the vocabulary |
| `outputDirectory` | String | `build/generated/sources/ontomapper` | Output directory for generated files |

## Generated Vocabulary Structure

The plugin generates vocabulary files following the Kastor pattern:

```
build/generated/sources/
├── dcat-vocab/
│   └── com/example/dcatus/vocab/
│       └── DCAT.kt
├── schema-vocab/
│   └── com/example/schema/vocab/
│       └── SCHEMA.kt
└── foaf-vocab/
    └── com/example/foaf/vocab/
        └── FOAF.kt
```

## Generated Vocabulary Code

### DCAT Vocabulary Example
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

### Schema.org Vocabulary Example
```kotlin
package com.example.schema.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * SCHEMA vocabulary.
 * Generated from ontology files.
 */
object SCHEMA : Vocabulary {
    override val namespace: String = "https://schema.org/"
    override val prefix: String = "schema"
    
    // Classes
    val Person: Iri by lazy { term("Person") }
    val PostalAddress: Iri by lazy { term("PostalAddress") }
    
    // Properties
    val name: Iri by lazy { term("name") }
    val email: Iri by lazy { term("email") }
    val address: Iri by lazy { term("address") }
    val streetAddress: Iri by lazy { term("streetAddress") }
    val addressLocality: Iri by lazy { term("addressLocality") }
    val addressCountry: Iri by lazy { term("addressCountry") }
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

3. **View generated vocabularies**:
   ```bash
   # Generated files are in build/generated/sources/
   ls -la build/generated/sources/dcat-vocab/com/example/dcatus/vocab/
   ls -la build/generated/sources/schema-vocab/com/example/schema/vocab/
   ls -la build/generated/sources/foaf-vocab/com/example/foaf/vocab/
   ```

## Vocabulary Usage Examples

### Basic Usage
```kotlin
import com.example.dcatus.vocab.DCAT
import com.example.schema.vocab.SCHEMA
import com.example.foaf.vocab.FOAF

// Access vocabulary terms
val catalogClass = DCAT.Catalog
val datasetClass = DCAT.Dataset
val personClass = SCHEMA.Person
val nameProperty = SCHEMA.name
```

### Creating RDF Data
```kotlin
val repo = Rdf.memory()

repo.add {
    val catalog = iri("http://example.org/catalog")
    catalog - RDF.type - DCAT.Catalog
    catalog - DCTERMS.title - "My Data Catalog"
    
    val dataset = iri("http://example.org/dataset")
    dataset - RDF.type - DCAT.Dataset
    dataset - DCTERMS.title - "Sample Dataset"
    
    catalog - DCAT.datasetProp - dataset
}
```

### Querying with Vocabularies
```kotlin
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

### Vocabulary Interface Methods
```kotlin
// Check if terms belong to vocabularies
val isDcatTerm = DCAT.contains(DCAT.Catalog)
val isSchemaTerm = SCHEMA.contains(FOAF.Person)

// Get local names
val localName = DCAT.localname(DCAT.Catalog) // "Catalog"

// Create terms dynamically
val customTerm = DCAT.term("customProperty")
```

## Benefits

### 1. **Type Safety**
- All vocabulary terms are strongly typed as `Iri` objects
- Compile-time checking ensures correct term usage
- IDE autocomplete for all vocabulary terms

### 2. **Performance**
- Lazy initialization - terms are only created when first accessed
- Memory efficient - unused terms don't consume memory
- Fast startup - no unnecessary object creation

### 3. **Consistency**
- Follows established Kastor vocabulary pattern
- Uniform interface across all generated vocabularies
- Consistent naming and structure

### 4. **Maintainability**
- Generated code is easy to understand and modify
- Centralized vocabulary definitions
- Automatic updates when ontology files change

## Advanced Configuration

### Custom Vocabulary Names
```kotlin
ontomapper {
    ontologies {
        create("custom") {
            shaclPath.set("ontologies/custom.shacl.ttl")
            contextPath.set("ontologies/custom.context.jsonld")
            targetPackage.set("com.example.custom.vocab")
            generateVocabulary.set(true)
            vocabularyName.set("CUSTOM_VOCAB")
            vocabularyNamespace.set("http://example.org/custom#")
            vocabularyPrefix.set("custom")
        }
    }
}
```

### Mixed Generation
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

### Environment-specific Configuration
```kotlin
ontomapper {
    ontologies {
        create("dcat") {
            shaclPath.set("ontologies/dcat-us.shacl.ttl")
            contextPath.set("ontologies/dcat-us.context.jsonld")
            targetPackage.set("com.example.dcatus.vocab")
            generateVocabulary.set(project.hasProperty("generateVocab"))
            vocabularyName.set("DCAT")
            vocabularyNamespace.set("http://www.w3.org/ns/dcat#")
            vocabularyPrefix.set("dcat")
        }
    }
}
```

## Troubleshooting

### Common Issues

1. **Vocabulary not generated**:
   - Ensure `generateVocabulary.set(true)` is configured
   - Check that SHACL and JSON-LD files exist
   - Verify file paths are correct

2. **Missing terms**:
   - Check SHACL shapes include target classes
   - Verify JSON-LD context has proper mappings
   - Review ontology file syntax

3. **Import errors**:
   - Ensure source sets include generated directories
   - Check package names in generated code
   - Verify dependencies are correct

### Debug Mode
```bash
# Run with debug logging
./gradlew generateOntology --debug

# Check individual vocabulary generation
./gradlew generateOntologyDcat --debug
./gradlew generateOntologySchema --debug
./gradlew generateOntologyFoaf --debug
```

## Comparison with Manual Vocabulary Creation

| Feature | Generated Vocabularies | Manual Vocabularies |
|---------|----------------------|-------------------|
| **Type Safety** | ✅ Automatic | ✅ Manual |
| **Consistency** | ✅ Guaranteed | ⚠️ Manual |
| **Maintenance** | ✅ Automatic | ❌ Manual |
| **Updates** | ✅ Automatic | ❌ Manual |
| **Error-prone** | ✅ No | ⚠️ Yes |
| **Time** | ✅ Fast | ❌ Slow |

## Next Steps

- Explore the generated vocabulary files in `build/generated/sources/`
- Modify the SHACL and JSON-LD files to see how generation changes
- Add more ontologies and vocabulary configurations
- Use generated vocabularies in your RDF applications
- Integrate with existing Kastor vocabulary infrastructure

For more information, see:
- [Gradle Configuration Tutorial](../docs/ontomapper/tutorials/gradle-configuration.md)
- [Kastor Vocabularies](../docs/kastor/vocabularies.md)
- [Vocabulary Interface](../docs/kastor/vocabularies-index.md)
- [Best Practices](../docs/ontomapper/best-practices.md)
