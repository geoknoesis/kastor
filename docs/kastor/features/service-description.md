# SPARQL Service Description

Kastor provides comprehensive support for SPARQL Service Description, a W3C standard for describing the capabilities of SPARQL endpoints and services. This enables automatic discovery of service capabilities, supported formats, and available functions.

## 🎯 Overview

SPARQL Service Description allows services to describe:
- **Service Information**: Endpoints, versions, and basic metadata
- **Supported Languages**: Query and update languages
- **Result Formats**: Available output formats
- **Input Formats**: Accepted input formats
- **Extension Functions**: Custom and built-in functions
- **Dataset Information**: Available graphs and datasets
- **SPARQL 1.2 Features**: RDF-star, property paths, aggregation, etc.

## 🚀 Key Features

### 1. Service Description Generation

`SparqlServiceDescriptionGenerator` (module `rdf-sparql-lang`) builds a description from any `ProviderCapabilities`. Providers may also override `RdfProvider.generateServiceDescription`, but the bundled `memory`, `jena`, `rdf4j` and `sparql` providers do not, so for them that method (and `RdfProviderRegistry.generateServiceDescription`) returns `null`.

```kotlin
import com.geoknoesis.kastor.rdf.sparql.SparqlServiceDescriptionGenerator

val provider = RdfProviderRegistry.getProvider("jena") ?: error("rdf-jena is not on the classpath")
val serviceUri = "http://example.org/sparql"
val description = SparqlServiceDescriptionGenerator(serviceUri, provider.getCapabilities())
    .generateServiceDescription()

println("Service Description:")
description.getTriples().forEach { triple ->
    println("${triple.subject} ${triple.predicate} ${triple.obj}")
}
```

### 2. Comprehensive Capability Discovery

```kotlin
val capabilities = provider.getDetailedCapabilities()

// Check SPARQL 1.2 features
println("SPARQL Version: ${capabilities.basic.sparqlVersion}")
println("RDF-star Support: ${capabilities.basic.supportsRdfStar}")
println("Property Paths: ${capabilities.basic.supportsPropertyPaths}")
println("Aggregation: ${capabilities.basic.supportsAggregation}")

// Check extension functions (empty for the bundled providers; see SparqlExtensionFunctionRegistry)
val functions = capabilities.basic.extensionFunctions
println("Available Functions: ${functions.size}")
functions.forEach { func ->
    println("- ${func.name}: ${func.description}")
}
```

### 3. Service Description Vocabulary

Kastor includes complete SPARQL Service Description vocabulary:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.SPARQL_SD
import com.geoknoesis.kastor.rdf.vocab.SPARQL12

// Use vocabulary terms
val service = SPARQL_SD.Service
val endpoint = SPARQL_SD.endpointProp
val sparql12Service = SPARQL12.Sparql12Service
```

## 📊 Service Description Structure

### Basic Service Information

```turtle
@prefix sd: <http://www.w3.org/ns/sparql-service-description#> .
@prefix sparql: <http://www.w3.org/ns/sparql#> .

<http://example.org/sparql> a sd:Service, sparql:Sparql12Service ;
    sd:endpoint <http://example.org/sparql/sparql> ;
    sd:updateEndpoint <http://example.org/sparql/update> ;
    sparql:supportedSparqlVersion "1.2" .
```

### Supported Languages and Formats

```turtle
<http://example.org/sparql> 
    sd:supportedLanguage sd:sparql ;
    sd:supportedLanguage sd:sparql12 ;
    sd:resultFormat <https://www.iana.org/assignments/media-types/application/sparql-results+json> ;
    sd:resultFormat <https://www.iana.org/assignments/media-types/application/sparql-results+xml> ;
    sd:resultFormat <https://www.iana.org/assignments/media-types/text/csv> ;
    sd:inputFormat <https://www.iana.org/assignments/media-types/application/sparql-query> ;
    sd:inputFormat <https://www.iana.org/assignments/media-types/application/sparql-update> .
```

### SPARQL 1.2 Feature Support

```turtle
<http://example.org/sparql> 
    sparql:supportsRdfStar true ;
    sparql:supportsPropertyPaths true ;
    sparql:supportsAggregation true ;
    sparql:supportsSubSelect true ;
    sparql:supportsFederation true ;
    sparql:supportsVersionDeclaration true .
```

### Extension Functions

```turtle
<http://example.org/sparql>
    sd:extensionFunction <http://www.w3.org/ns/sparql#TRIPLE> ;
    sd:extensionFunction <http://www.w3.org/ns/sparql#encodeForUri> .

<http://www.w3.org/ns/sparql#TRIPLE>
    sd:functionName "TRIPLE" ;
    sd:description "Creates a triple term from subject, predicate, and object" .

<http://www.w3.org/ns/sparql#encodeForUri>
    sd:functionName "encodeForUri" ;
    sd:description "Encodes a string for use in URIs" .
```

Each function also gets `sd:returnType` when `returnType` is set, and `sd:isAggregate true` for aggregates. Media types in `supportedResultFormats` / `supportedInputFormats` are emitted as IANA media-type IRIs.

## 🔧 Service Description Generator

### Basic Usage

```kotlin
val generator = SparqlServiceDescriptionGenerator(
    serviceUri = "http://example.org/sparql",
    capabilities = provider.getCapabilities()
)

val description = generator.generateServiceDescription()
```

### Custom Capabilities

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF

val customCapabilities = ProviderCapabilities(
    sparqlVersion = "1.2",
    supportsRdfStar = true,
    supportsPropertyPaths = true,
    supportsAggregation = true,
    supportsFederation = true,
    supportedLanguages = listOf("sparql", "sparql12"),
    supportedResultFormats = listOf(
        "application/sparql-results+json",
        "application/sparql-results+xml",
        "text/csv"
    ),
    extensionFunctions = listOf(
        SparqlExtensionFunction(
            iri = "http://www.w3.org/ns/sparql#TRIPLE",
            name = "TRIPLE",
            description = "Creates a triple term",
            returnType = "rdf:TripleTerm"
        )
    )
)

val generator = SparqlServiceDescriptionGenerator(
    serviceUri = "http://example.org/sparql",
    capabilities = customCapabilities
)
```

### Output Formats

```kotlin
val generator = SparqlServiceDescriptionGenerator(serviceUri, capabilities)

// Generate as RDF graph
val graph = generator.generateServiceDescription()

// Generate as SPARQL results
val sparqlResults = generator.generateAsSparqlResult()

// Generate as Turtle
val turtle = generator.generateAsTurtle()

// Generate as JSON-LD
val jsonLd = generator.generateAsJsonLd()
```

## 📋 Registry Integration

### Discovering All Service Descriptions

```kotlin
val baseUri = "http://example.org"
val allDescriptions = RdfProviderRegistry.getAllServiceDescriptions(baseUri)

allDescriptions.forEach { (providerType, description) ->
    println("Provider: $providerType")
    println("Triples: ${description.getTriples().size}")
}
```

### Provider-Specific Service Descriptions

```kotlin
// Delegates to RdfProvider.generateServiceDescription: null for providers that do not implement it
// (including the bundled memory, jena, rdf4j and sparql providers)
val customDescription = RdfProviderRegistry.generateServiceDescription(
    providerId = "custom",
    serviceUri = "http://example.org/custom",
    variantId = null
)
```

`getAllServiceDescriptions(baseUri)` returns one entry per registered provider, with an empty graph for providers that do not generate descriptions.

### Capability Discovery

```kotlin
// Discover all provider capabilities
val allCapabilities = RdfProviderRegistry.discoverAllCapabilities()

// Check specific features across providers
val hasRdfStarSupport = RdfProviderRegistry.hasProviderWithFeature("RDF-star")
val hasFederationSupport = RdfProviderRegistry.hasProviderWithFeature("Federation")

// Get supported features by provider
val supportedFeatures = RdfProviderRegistry.getSupportedFeatures()
supportedFeatures.forEach { (provider, features) ->
    println("$provider supports: $features")
}
```

## 🎨 Specialized Providers

### SPARQL Provider

```kotlin
import com.geoknoesis.kastor.rdf.sparql.SparqlProvider
import com.geoknoesis.kastor.rdf.sparql.SparqlServiceDescriptionGenerator

val sparqlProvider = SparqlProvider()

// Capabilities describe the HTTP adapter (SPARQL 1.1, no federation)
val capabilities = sparqlProvider.getCapabilities()
println("Federation Support: ${capabilities.supportsFederation}")          // false
println("Service Description: ${capabilities.supportsServiceDescription}") // true

val description = SparqlServiceDescriptionGenerator("http://example.org/sparql", capabilities)
    .generateServiceDescription()
```

### Reasoners and SHACL Validators

Reasoners and SHACL validators are not `RdfProvider`s, so they have no service description. Discover them with `RdfReasoning.reasonerProviders()` and `ShaclValidation.validator(...)` instead (see [Enhanced Providers](enhanced-providers.md)).

## 📊 Provider Statistics

```kotlin
val statistics = RdfProviderRegistry.getProviderStatistics()

statistics.forEach { (category, count) ->
    println("$category: $count providers")
}

// Example output with memory, jena, rdf4j and sparql on the classpath:
// RDF_STORE: 3 providers
// SPARQL_ENDPOINT: 1 providers
```

## 🔍 Extension Function Registry

### Built-in Functions

```kotlin
val builtInFunctions = SparqlExtensionFunctionRegistry.getBuiltInFunctions()

builtInFunctions.forEach { func ->
    println("Function: ${func.name}")
    println("Description: ${func.description}")
    println("Return Type: ${func.returnType}")
    println("Is Aggregate: ${func.isAggregate}")
    println("---")
}
```

### Custom Functions

```kotlin
// Register custom function
import com.geoknoesis.kastor.rdf.vocab.XSD

val customFunction = SparqlExtensionFunction(
    iri = "http://example.org/functions#customFunction",
    name = "customFunction",
    description = "A custom SPARQL function",
    argumentTypes = listOf(XSD.string.value),
    returnType = XSD.string.value,
    isAggregate = false,
    isBuiltIn = false
)

SparqlExtensionFunctionRegistry.register(customFunction)
```

### Function Discovery

```kotlin
// Get all registered functions
val allFunctions = SparqlExtensionFunctionRegistry.getAllFunctions()

// Get functions by name
val tripleFunctions = SparqlExtensionFunctionRegistry.getFunctionsByName("TRIPLE")

// Check if function is registered
val isRegistered = SparqlExtensionFunctionRegistry.isRegistered(
    "http://www.w3.org/ns/sparql#TRIPLE"
)
```

## 🎯 Best Practices

### 1. Service Description Generation

```kotlin
// Always provide a meaningful service URI
val serviceUri = "https://api.example.org/sparql/v1"

// Include comprehensive capabilities
val capabilities = ProviderCapabilities(
    sparqlVersion = "1.2",
    supportsRdfStar = true,
    supportsPropertyPaths = true,
    supportsAggregation = true,
    supportsFederation = true,
    supportedLanguages = listOf("sparql", "sparql12"),
    supportedResultFormats = listOf(
        "application/sparql-results+json",
        "application/sparql-results+xml",
        "text/csv"
    ),
    extensionFunctions = SparqlExtensionFunctionRegistry.getBuiltInFunctions()
)
```

### 2. Capability Discovery

```kotlin
// Check capabilities before using features
val provider = RdfProviderRegistry.getProvider("memory")
val capabilities = provider.getDetailedCapabilities()

if (capabilities.basic.supportsRdfStar) {
    // Use RDF-star features
}

if (capabilities.basic.supportsFederation) {
    // Use federation features
}
```

### 3. Service Description Validation

```kotlin
// Validate service description
val description: RdfGraph? = SparqlServiceDescriptionGenerator(serviceUri, provider.getCapabilities()).generateServiceDescription()
if (description != null) {
    val triples = description.getTriples()
    println("Service description contains ${triples.size} triples")
    
    // Check for required elements
    val hasServiceType = triples.any { 
        it.predicate == RDF.type && it.obj == SPARQL_SD.Service
    }
    val hasEndpoint = triples.any { 
        it.predicate == SPARQL_SD.endpointProp 
    }
    
    println("Has service type: $hasServiceType")
    println("Has endpoint: $hasEndpoint")
}
```

## 📖 Complete Example

```kotlin
fun serviceDescriptionExample() {
    // Get a provider
    val provider = RdfProviderRegistry.getProvider("jena") ?: return
    
    // Generate service description
    val serviceUri = "http://example.org/sparql"
    val description: RdfGraph? = SparqlServiceDescriptionGenerator(serviceUri, provider.getCapabilities())
        .generateServiceDescription()
    
    if (description != null) {
        // Print basic information
        println("Service Description for: $serviceUri")
        println("Total triples: ${description.getTriples().size}")
        
        // Print as Turtle
        val generator = SparqlServiceDescriptionGenerator(serviceUri, provider.getCapabilities())
        val turtle = generator.generateAsTurtle()
        println("\nTurtle Format:")
        println(turtle)
        
        // Print as JSON-LD
        val jsonLd = generator.generateAsJsonLd()
        println("\nJSON-LD Format:")
        println(jsonLd)
    }
    
    // Discover capabilities
    val capabilities = provider.getDetailedCapabilities()
    println("\nProvider Capabilities:")
    println("Category: ${capabilities.providerCategory}")
    println("SPARQL Version: ${capabilities.basic.sparqlVersion}")
    println("RDF-star Support: ${capabilities.basic.supportsRdfStar}")
    println("Extension Functions: ${capabilities.basic.extensionFunctions.size}")
    
    // Check specific features
    val supportedFeatures = RdfProviderRegistry.getSupportedFeatures()
    println("\nSupported Features by Provider:")
    supportedFeatures.forEach { (providerType, features) ->
        println("$providerType: $features")
    }
}
```

## 🔗 Related Documentation

- [SPARQL 1.2 Support](sparql-1.2.md)
- [Provider Capabilities](provider-capabilities.md)
- [Enhanced Providers](enhanced-providers.md)
- [Extension Functions](extension-functions.md)
- [Registry Management](registry-management.md)

## 📞 Support

For questions about SPARQL Service Description in Kastor:

- **Email**: stephanef@geoknoesis.com
- **Issues**: GitHub Issues
- **Documentation**: [Kastor Docs](https://docs.kastor.org)

---

*Kastor SPARQL Service Description support is developed by [GeoKnoesis LLC](https://geoknoesis.com) and maintained by Stephane Fellah.*



