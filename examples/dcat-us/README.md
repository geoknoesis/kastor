# DCAT-US 3.0 OntoMapper Example

This example demonstrates how to use OntoMapper to generate interfaces, wrappers, and vocabulary from DCAT-US 3.0 SHACL shapes, and then use the generated code to work with DCAT-US compliant data.

## 🎯 Overview

The [DCAT-US 3.0 specification](https://resources.data.gov/standards/dcat-us/) is the U.S. government's implementation of the W3C Data Catalog Vocabulary (DCAT) standard. This example shows how OntoMapper can automatically generate Kotlin code from the [DCAT-US SHACL shapes](https://github.com/DOI-DO/dcat-us/blob/main/shacl/dcat-us_3.0_shacl_shapes.ttl), making it easy to work with government data catalogs in a type-safe manner.

## 🚀 Features Demonstrated

### 1. **Automatic Code Generation**
- **Interfaces**: Type-safe interfaces for DCAT-US classes
- **Data Classes**: Immutable data classes for DCAT-US entities
- **Wrapper Classes**: Builder-pattern wrappers for easy construction
- **Vocabulary**: Constants for all DCAT-US terms and properties
- **DSL Builders**: Domain-specific language for creating DCAT-US entities

### 2. **DCAT-US 3.0 Compliance**
- Full compliance with [DCAT-US 3.0 SHACL shapes](https://github.com/DOI-DO/dcat-us/blob/main/shacl/dcat-us_3.0_shacl_shapes.ttl)
- Support for all DCAT-US classes and properties
- Automatic validation against SHACL constraints
- Proper namespace handling for DCAT-US extensions

### 3. **Generated Components**

#### **Vocabulary Constants**
```kotlin
// Generated DCAT-US vocabulary
object DCAT_US_Vocabulary {
    // Classes
    val Catalog: Iri = iri("http://www.w3.org/ns/dcat#Catalog")
    val Dataset: Iri = iri("http://www.w3.org/ns/dcat#Dataset")
    val Distribution: Iri = iri("http://www.w3.org/ns/dcat#Distribution")
    val DataService: Iri = iri("http://www.w3.org/ns/dcat#DataService")
    val PublicDataCatalog: Iri = iri("http://resources.data.gov/def/dcat-us/3.0/#PublicDataCatalog")
    
    // Properties
    val title: Iri = iri("http://www.w3.org/ns/dcat#title")
    val description: Iri = iri("http://www.w3.org/ns/dcat#description")
    val keyword: Iri = iri("http://www.w3.org/ns/dcat#keyword")
    val publisher: Iri = iri("http://www.w3.org/ns/dcat#publisher")
    val distribution: Iri = iri("http://www.w3.org/ns/dcat#distribution")
    val accessUrl: Iri = iri("http://www.w3.org/ns/dcat#accessURL")
    val downloadUrl: Iri = iri("http://www.w3.org/ns/dcat#downloadURL")
}
```

#### **Generated Interfaces**
```kotlin
// Generated DCAT-US interfaces
interface Catalog : Resource {
    val title: String
    val description: String
    val publisher: Agent
    val datasets: List<Dataset>
    val dataServices: List<DataService>
}

interface Dataset : Resource {
    val title: String
    val description: String
    val keywords: List<String>
    val themes: List<Iri>
    val distributions: List<Distribution>
    val spatialCoverage: Location?
    val temporalCoverage: PeriodOfTime?
}

interface Distribution : Resource {
    val title: String?
    val description: String?
    val accessUrl: Iri
    val downloadUrl: Iri?
    val mediaType: String?
    val format: Iri?
    val byteSize: Long?
}
```

#### **Generated Data Classes**
```kotlin
// Generated DCAT-US data classes
data class CatalogData(
    val uri: Iri,
    val title: String,
    val description: String,
    val publisher: Agent,
    val datasets: List<Dataset> = emptyList(),
    val dataServices: List<DataService> = emptyList(),
    val issued: XsdDate? = null,
    val modified: XsdDate? = null,
    val license: Iri? = null,
    val language: String? = null
) : Catalog

data class DatasetData(
    val uri: Iri,
    val title: String,
    val description: String,
    val keywords: List<String> = emptyList(),
    val themes: List<Iri> = emptyList(),
    val publisher: Agent,
    val contactPoint: ContactPoint? = null,
    val issued: XsdDate? = null,
    val modified: XsdDate? = null,
    val license: Iri? = null,
    val rights: String? = null,
    val spatialCoverage: Location? = null,
    val temporalCoverage: PeriodOfTime? = null,
    val distributions: List<Distribution> = emptyList()
) : Dataset
```

#### **Generated Builder Classes**
```kotlin
// Generated DCAT-US builder classes
class CatalogBuilder {
    fun uri(uri: Iri): CatalogBuilder
    fun title(title: String): CatalogBuilder
    fun description(description: String): CatalogBuilder
    fun publisher(publisher: Agent): CatalogBuilder
    fun addDataset(dataset: Dataset): CatalogBuilder
    fun addDataService(service: DataService): CatalogBuilder
    fun build(): Catalog
}

class DatasetBuilder {
    fun uri(uri: Iri): DatasetBuilder
    fun title(title: String): DatasetBuilder
    fun description(description: String): DatasetBuilder
    fun addKeyword(keyword: String): DatasetBuilder
    fun addTheme(theme: Iri): DatasetBuilder
    fun addDistribution(distribution: Distribution): DatasetBuilder
    fun build(): Dataset
}
```

#### **Generated DSL**
```kotlin
// Generated DCAT-US DSL
fun dcatCatalog(init: CatalogDslBuilder.() -> Unit): Catalog
fun dcatDataset(init: DatasetDslBuilder.() -> Unit): Dataset
fun dcatDistribution(init: DistributionDslBuilder.() -> Unit): Distribution

// Usage example
val catalog = dcatCatalog {
    title = "Government Data Catalog"
    description = "Comprehensive catalog of government datasets"
    
    publisher = foafAgent {
        name = "Example Agency"
        homepage = iri("https://www.example.gov")
    }
    
    datasets = listOf(
        dcatDataset {
            title = "Population Data"
            description = "Annual population statistics"
            keywords = listOf("population", "demographics")
        }
    )
}
```

### 4. **Validation and Constraints**

The generated code includes automatic validation based on DCAT-US SHACL shapes:

```kotlin
// Automatic validation
val validationResult = DCAT_US_Validator.validate(catalog)

if (!validationResult.isValid) {
    validationResult.errors.forEach { error ->
        println("Validation error: ${error.message}")
    }
}
```

### 5. **Serialization Support**

Generated classes support multiple serialization formats:

```kotlin
// Convert to RDF
val rdfGraph = catalog.toRdfGraph()

// Serialize as Turtle
val turtle = repo.serialize(rdfGraph, "text/turtle")

// Serialize as JSON-LD
val jsonLd = repo.serialize(rdfGraph, "application/ld+json")

// Export with DCAT-US context
val dcatJsonLd = example.exportCatalogAsJsonLd(catalog)
```

## 📋 Example Use Cases

### 1. **Government Data Portal**
Create and manage government data catalogs with full DCAT-US compliance:

```kotlin
val portal = DCAT_US_Example()
val catalog = portal.createCatalogWithDsl()

// Add datasets
val weatherDataset = dcatDataset {
    title = "Weather Data"
    description = "Daily weather measurements"
    keywords = listOf("weather", "climate", "environment")
    
    distributions = listOf(
        dcatDistribution {
            title = "Weather Data - CSV"
            accessUrl = iri("https://data.example.gov/weather.csv")
            format = iri("http://publications.europa.eu/resource/authority/file-type/CSV")
            mediaType = "text/csv"
        }
    )
}

catalog.addDataset(weatherDataset)
```

### 2. **Data Validation**
Ensure data compliance with DCAT-US standards:

```kotlin
val validationResult = portal.validateCatalogData()

if (validationResult.isValid) {
    println("✅ Data is DCAT-US compliant")
} else {
    println("❌ Validation errors:")
    validationResult.errors.forEach { error ->
        println("  - ${error.message}")
    }
}
```

### 3. **API Integration**
Export catalogs for government data portals:

```kotlin
// Export as JSON-LD for web APIs
val jsonLd = portal.exportCatalogAsJsonLd(catalog)

// Export as Turtle for RDF stores
val turtle = repo.serialize(catalog.toRdfGraph(), "text/turtle")
```

## 🔧 Configuration

The example is configured in `build.gradle.kts` using KSP arguments:

```kotlin
ksp {
    arg("ontomapper.source", "https://raw.githubusercontent.com/DOI-DO/dcat-us/main/shacl/dcat-us_3.0_shacl_shapes.ttl")
    arg("ontomapper.format", "turtle")
    arg("ontomapper.packageName", "com.geoknoesis.kastor.examples.dcat.generated")
    arg("ontomapper.generateVocabulary", "true")
    arg("ontomapper.generateInterfaces", "true")
    arg("ontomapper.generateDataClasses", "true")
    arg("ontomapper.generateWrappers", "true")
    arg("ontomapper.generateDsl", "true")
    arg("ontomapper.enableShaclValidation", "true")
    
    // Custom prefix mappings for DCAT-US
    arg("ontomapper.prefix.dcat", "http://www.w3.org/ns/dcat#")
    arg("ontomapper.prefix.dct", "http://purl.org/dc/terms/")
    // ... more prefixes
    
    // Property mappings for better Kotlin naming
    arg("ontomapper.propertyMapping.http://www.w3.org/ns/dcat#title", "title")
    arg("ontomapper.propertyMapping.http://www.w3.org/ns/dcat#description", "description")
    arg("ontomapper.propertyMapping.http://www.w3.org/ns/dcat#keyword", "keywords")
    // ... more mappings
    
    // Required properties for validation
    arg("ontomapper.required.Catalog", "title,description,publisher")
    arg("ontomapper.required.Dataset", "title,description,publisher")
}
```

## 🚀 Running the Example

1. **Generate Code** (from project root):
   ```bash
   ./gradlew :examples:dcat-us:generateOntoMapperCode
   ```

2. **Run Example** (from project root):
   ```bash
   ./gradlew :examples:dcat-us:run
   ```

3. **Build and Test** (from project root):
   ```bash
   ./gradlew :examples:dcat-us:build
   ./gradlew :examples:dcat-us:test
   ```

4. **Or run from example directory**:
   ```bash
   cd examples/dcat-us
   ../../gradlew generateOntoMapperCode
   ../../gradlew run
   ../../gradlew build test
   ```

## 📚 Related Documentation

- [DCAT-US 3.0 Specification](https://resources.data.gov/standards/dcat-us/)
- [DCAT-US SHACL Shapes](https://github.com/DOI-DO/dcat-us/blob/main/shacl/dcat-us_3.0_shacl_shapes.ttl)
- [W3C DCAT Vocabulary](https://www.w3.org/TR/vocab-dcat-3/)
- [OntoMapper Documentation](../../docs/ontomapper/README.md)
- [Kastor RDF API](../../docs/kastor/README.md)

## 🤝 Contributing

This example demonstrates best practices for using OntoMapper with government data standards. Contributions and improvements are welcome!

## 📄 License

This example is provided under the same license as the Kastor framework.

---

*This example is developed by [GeoKnoesis LLC](https://geoknoesis.com) and maintained by Stephane Fellah.*
