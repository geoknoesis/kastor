# Provider Capabilities

Kastor provides a comprehensive capability system that allows providers to advertise their features and limitations. This system enables automatic discovery of provider capabilities, intelligent feature selection, and proper service description generation.

## 🎯 Overview

The provider capability system includes:

- **Enhanced Capabilities**: Comprehensive feature descriptions
- **SPARQL 1.2 Support**: Latest SPARQL features and functions
- **Service Description Integration**: Automatic capability advertisement
- **Detailed Capabilities**: Granular feature information
- **Capability Discovery**: Easy provider capability exploration
- **Feature Checking**: Runtime capability validation

## 🚀 Core Capabilities

### ProviderCapabilities

The main capability data class with comprehensive feature descriptions:

```kotlin
data class ProviderCapabilities(
    // === BASIC CAPABILITIES ===
    val supportsInference: Boolean = false,
    val supportsTransactions: Boolean = false,
    val supportsNamedGraphs: Boolean = false,
    val supportsUpdates: Boolean = false,
    val supportsRdfStar: Boolean = false,
    val maxMemoryUsage: Long = Long.MAX_VALUE,
    
    // === SPARQL 1.2 CAPABILITIES ===
    val sparqlVersion: String = "1.2",
    val supportsPropertyPaths: Boolean = true,
    val supportsAggregation: Boolean = true,
    val supportsSubSelect: Boolean = true,
    val supportsFederation: Boolean = false,
    val supportsVersionDeclaration: Boolean = true,
    val supportsServiceDescription: Boolean = true,
    
    // === SERVICE DESCRIPTION CAPABILITIES ===
    val supportedLanguages: List<String> = listOf("sparql", "sparql12"),
    val supportedResultFormats: List<String> = listOf(
        "application/sparql-results+json",
        "application/sparql-results+xml",
        "text/csv",
        "text/tab-separated-values"
    ),
    val supportedInputFormats: List<String> = listOf(
        "application/sparql-query",
        "application/sparql-update"
    ),
    val extensionFunctions: List<SparqlExtensionFunction> = emptyList(),
    val entailmentRegimes: List<String> = emptyList(),
    val namedGraphs: List<String> = emptyList(),
    val defaultGraphs: List<String> = emptyList()
)
```

### DetailedProviderCapabilities

More granular capability information for advanced use cases:

```kotlin
data class DetailedProviderCapabilities(
    val basic: ProviderCapabilities,
    val providerCategory: ProviderCategory,
    val supportedSparqlFeatures: Map<String, Boolean>,
    val customExtensionFunctions: List<SparqlExtensionFunction>
)
```

## 📊 Capability Categories

### 1. Basic RDF Capabilities

Core RDF functionality support:

```kotlin
val capabilities = provider.getCapabilities()

// Basic RDF features
println("Inference Support: ${capabilities.supportsInference}")
println("Transaction Support: ${capabilities.supportsTransactions}")
println("Named Graphs: ${capabilities.supportsNamedGraphs}")
println("Updates: ${capabilities.supportsUpdates}")
println("RDF-star: ${capabilities.supportsRdfStar}")
println("Max Memory: ${capabilities.maxMemoryUsage}")
```

### 2. SPARQL 1.2 Capabilities

Advanced SPARQL features:

```kotlin
val capabilities = provider.getCapabilities()

// SPARQL version and features
println("SPARQL Version: ${capabilities.sparqlVersion}")
println("Property Paths: ${capabilities.supportsPropertyPaths}")
println("Aggregation: ${capabilities.supportsAggregation}")
println("Sub-selects: ${capabilities.supportsSubSelect}")
println("Federation: ${capabilities.supportsFederation}")
println("Version Declaration: ${capabilities.supportsVersionDeclaration}")
println("Service Description: ${capabilities.supportsServiceDescription}")
```

### 3. Service Description Capabilities

Service advertisement features:

```kotlin
val capabilities = provider.getCapabilities()

// Supported languages
println("Supported Languages: ${capabilities.supportedLanguages}")
// [sparql, sparql12]

// Result formats
println("Result Formats: ${capabilities.supportedResultFormats}")
// [application/sparql-results+json, application/sparql-results+xml, text/csv, ...]

// Input formats
println("Input Formats: ${capabilities.supportedInputFormats}")
// [application/sparql-query, application/sparql-update]

// Extension functions
println("Extension Functions: ${capabilities.extensionFunctions.size}")
capabilities.extensionFunctions.forEach { func ->
    println("- ${func.name}: ${func.description}")
}

// Entailment regimes
println("Entailment Regimes: ${capabilities.entailmentRegimes}")
// [RDFS, OWL-RL, OWL-EL, ...]
```

## 🔧 Provider Categories

### ProviderCategory Enum

Providers are categorized by their primary function:

```kotlin
enum class ProviderCategory {
    RDF_STORE,           // Basic RDF storage
    SPARQL_ENDPOINT,     // Remote SPARQL endpoints
    REASONER,           // Inference engines
    SHACL_VALIDATOR,    // SHACL validation
    SERVICE_DESCRIPTION, // Service description generation
    FEDERATION          // Federated query support
}
```

### Category-Specific Capabilities

Different provider categories have different default capabilities:

#### RDF Store Providers
```kotlin
val capabilities = ProviderCapabilities(
    supportsInference = false,
    supportsTransactions = true,
    supportsNamedGraphs = true,
    supportsUpdates = true,
    supportsRdfStar = false,
    sparqlVersion = "1.2",
    supportsPropertyPaths = true,
    supportsAggregation = true,
    supportsSubSelect = true,
    supportsFederation = false,
    supportsServiceDescription = true
)
```

#### SPARQL Endpoint Providers
```kotlin
val capabilities = ProviderCapabilities(
    supportsInference = false,
    supportsTransactions = false,
    supportsNamedGraphs = true,
    supportsUpdates = true,
    supportsRdfStar = true,
    sparqlVersion = "1.2",
    supportsPropertyPaths = true,
    supportsAggregation = true,
    supportsSubSelect = true,
    supportsFederation = true,
    supportsServiceDescription = true,
    supportedLanguages = listOf("sparql", "sparql12"),
    extensionFunctions = SparqlExtensionFunctionRegistry.getBuiltInFunctions(),
    entailmentRegimes = listOf("RDFS", "OWL-RL")
)
```

#### Reasoner Providers
```kotlin
val capabilities = ProviderCapabilities(
    supportsInference = true,
    supportsTransactions = true,
    supportsNamedGraphs = true,
    supportsUpdates = true,
    supportsRdfStar = true,
    sparqlVersion = "1.2",
    supportsPropertyPaths = true,
    supportsAggregation = true,
    supportsSubSelect = true,
    supportsVersionDeclaration = true,
    supportsServiceDescription = true,
    extensionFunctions = SparqlExtensionFunctionRegistry.getBuiltInFunctions(),
    entailmentRegimes = listOf("RDFS", "OWL-RL", "OWL-EL", "OWL-Q")
)
```

#### SHACL Validator Providers
```kotlin
val capabilities = ProviderCapabilities(
    supportsInference = false,
    supportsTransactions = false,
    supportsNamedGraphs = false,
    supportsUpdates = false,
    supportsRdfStar = true,
    supportsValidation = true,
    sparqlVersion = "1.2",
    supportsPropertyPaths = true,
    supportsAggregation = true,
    supportsSubSelect = true,
    supportsVersionDeclaration = true,
    supportsServiceDescription = true
)
```

## 🎨 Capability Discovery

### Registry-Level Discovery

```kotlin
// Discover all provider capabilities
val allCapabilities = RdfApiRegistry.discoverAllCapabilities()

allCapabilities.forEach { (providerType, capabilities) ->
    println("Provider: $providerType")
    println("Category: ${capabilities.providerCategory}")
    println("SPARQL Version: ${capabilities.basic.sparqlVersion}")
    println("RDF-star: ${capabilities.basic.supportsRdfStar}")
    println("---")
}
```

### Feature-Specific Discovery

```kotlin
// Check if any provider supports a specific feature
val hasRdfStarSupport = RdfApiRegistry.hasProviderWithFeature("supportsRdfStar")
val hasFederationSupport = RdfApiRegistry.hasProviderWithFeature("supportsFederation")
val hasInferenceSupport = RdfApiRegistry.hasProviderWithFeature("supportsInference")

println("RDF-star support available: $hasRdfStarSupport")
println("Federation support available: $hasFederationSupport")
println("Inference support available: $hasInferenceSupport")
```

### Provider-Specific Feature Checking

```kotlin
// Check specific provider capabilities
val provider = RdfApiRegistry.getProvider("memory")
if (provider != null) {
    val supportsFeature = RdfApiRegistry.supportsFeature("memory", "supportsRdfStar")
    println("Memory provider supports RDF-star: $supportsFeature")
}
```

### Supported Features by Provider

```kotlin
val supportedFeatures = RdfApiRegistry.getSupportedFeatures()

supportedFeatures.forEach { (providerType, features) ->
    println("$providerType supports: $features")
}

// Example output:
// memory: [supportsPropertyPaths, supportsAggregation, supportsSubSelect, ...]
// jena: [supportsRdfStar, supportsPropertyPaths, supportsAggregation, ...]
// sparql-endpoint: [supportsRdfStar, supportsPropertyPaths, supportsFederation, ...]
```

## 📋 Provider Statistics

### Category Statistics

```kotlin
val statistics = RdfApiRegistry.getProviderStatistics()

statistics.forEach { (category, count) ->
    println("$category: $count providers")
}

// Example output:
// RDF_STORE: 3 providers
// SPARQL_ENDPOINT: 1 providers
// REASONER: 1 providers
// SHACL_VALIDATOR: 1 providers
```

### Capability Statistics

```kotlin
val allCapabilities = RdfApiRegistry.discoverAllCapabilities()

// Count providers by capability
val rdfStarProviders = allCapabilities.values.count { it.basic.supportsRdfStar }
val federationProviders = allCapabilities.values.count { it.basic.supportsFederation }
val inferenceProviders = allCapabilities.values.count { it.basic.supportsInference }

println("RDF-star providers: $rdfStarProviders")
println("Federation providers: $federationProviders")
println("Inference providers: $inferenceProviders")
```

## 🔍 Detailed Capability Information

### Getting Detailed Capabilities

```kotlin
val provider = RdfApiRegistry.getProvider("memory")
val detailedCapabilities = provider.getDetailedCapabilities()

println("Provider Category: ${detailedCapabilities.providerCategory}")
println("Basic Capabilities: ${detailedCapabilities.basic.sparqlVersion}")

// SPARQL features
println("SPARQL Features:")
detailedCapabilities.supportedSparqlFeatures.forEach { (feature, supported) ->
    println("  $feature: $supported")
}

// Custom extension functions
println("Custom Functions: ${detailedCapabilities.customExtensionFunctions.size}")
detailedCapabilities.customExtensionFunctions.forEach { func ->
    println("  ${func.name}: ${func.description}")
}
```

### Feature Mapping

```kotlin
val detailedCapabilities = provider.getDetailedCapabilities()

// Map capabilities to feature names
val featureMap = mapOf(
    "supportsRdfStar" to detailedCapabilities.basic.supportsRdfStar,
    "supportsPropertyPaths" to detailedCapabilities.basic.supportsPropertyPaths,
    "supportsAggregation" to detailedCapabilities.basic.supportsAggregation,
    "supportsSubSelect" to detailedCapabilities.basic.supportsSubSelect,
    "supportsFederation" to detailedCapabilities.basic.supportsFederation,
    "supportsVersionDeclaration" to detailedCapabilities.basic.supportsVersionDeclaration,
    "supportsServiceDescription" to detailedCapabilities.basic.supportsServiceDescription,
    "supportsInference" to detailedCapabilities.basic.supportsInference,
    "supportsTransactions" to detailedCapabilities.basic.supportsTransactions,
    "supportsNamedGraphs" to detailedCapabilities.basic.supportsNamedGraphs,
    "supportsUpdates" to detailedCapabilities.basic.supportsUpdates
)

featureMap.forEach { (feature, supported) ->
    println("$feature: $supported")
}
```

## 🎯 Best Practices

### 1. Capability Checking

```kotlin
// Always check capabilities before using features
val provider = RdfApiRegistry.getProvider("memory")
val capabilities = provider.getCapabilities()

if (capabilities.supportsRdfStar) {
    // Use RDF-star features
    val query = """
        SELECT ?subject ?predicate ?object WHERE {
            << ?subject ?predicate ?object >> :certainty ?certainty .
            FILTER(?certainty > 0.8)
        }
    """
} else {
    // Fallback to regular SPARQL
    val query = """
        SELECT ?subject ?predicate ?object WHERE {
            ?subject ?predicate ?object .
        }
    """
}
```

### 2. Provider Selection

```kotlin
// Select provider based on required capabilities
fun selectProvider(requiresRdfStar: Boolean, requiresFederation: Boolean): RdfApiProvider? {
    val allProviders = RdfApiRegistry.getAllProviders()
    
    return allProviders.values.find { provider ->
        val capabilities = provider.getCapabilities()
        val hasRdfStar = !requiresRdfStar || capabilities.supportsRdfStar
        val hasFederation = !requiresFederation || capabilities.supportsFederation
        
        hasRdfStar && hasFederation
    }
}
```

### 3. Graceful Degradation

```kotlin
// Provide fallbacks for unsupported features
fun executeQuery(query: String, provider: RdfApiProvider): QueryResult {
    val capabilities = provider.getCapabilities()
    
    return try {
        provider.createRepository(RdfConfig()).query(query)
    } catch (e: UnsupportedOperationException) {
        if (!capabilities.supportsRdfStar && query.contains("<<")) {
            // Fallback to regular SPARQL
            val fallbackQuery = query.replace("<<", "").replace(">>", "")
            provider.createRepository(RdfConfig()).query(fallbackQuery)
        } else {
            throw e
        }
    }
}
```

## 📖 Complete Example

```kotlin
fun providerCapabilitiesExample() {
    // Get all providers
    val allProviders = RdfApiRegistry.getAllProviders()
    println("Available providers: ${allProviders.keys}")
    
    // Check each provider's capabilities
    allProviders.forEach { (type, provider) ->
        println("\n=== $type Provider ===")
        println("Name: ${provider.name}")
        println("Version: ${provider.version}")
        println("Category: ${provider.getProviderCategory()}")
        
        // Basic capabilities
        val capabilities = provider.getCapabilities()
        println("SPARQL Version: ${capabilities.sparqlVersion}")
        println("RDF-star: ${capabilities.supportsRdfStar}")
        println("Property Paths: ${capabilities.supportsPropertyPaths}")
        println("Aggregation: ${capabilities.supportsAggregation}")
        println("Federation: ${capabilities.supportsFederation}")
        println("Inference: ${capabilities.supportsInference}")
        println("Transactions: ${capabilities.supportsTransactions}")
        println("Named Graphs: ${capabilities.supportsNamedGraphs}")
        println("Updates: ${capabilities.supportsUpdates}")
        
        // Service description capabilities
        println("Supported Languages: ${capabilities.supportedLanguages}")
        println("Result Formats: ${capabilities.supportedResultFormats.size}")
        println("Extension Functions: ${capabilities.extensionFunctions.size}")
        println("Entailment Regimes: ${capabilities.entailmentRegimes}")
        
        // Detailed capabilities
        val detailedCapabilities = provider.getDetailedCapabilities()
        println("SPARQL Features: ${detailedCapabilities.supportedSparqlFeatures.size}")
        println("Custom Functions: ${detailedCapabilities.customExtensionFunctions.size}")
        
        // Generate service description
        val serviceUri = "http://example.org/$type"
        val description = provider.generateServiceDescription(serviceUri)
        if (description != null) {
            println("Service Description: ${description.getTriples().size} triples")
        } else {
            println("Service Description: Not supported")
        }
    }
    
    // Registry-level capability discovery
    println("\n=== Registry Capabilities ===")
    
    val allCapabilities = RdfApiRegistry.discoverAllCapabilities()
    println("Providers with detailed capabilities: ${allCapabilities.size}")
    
    val supportedFeatures = RdfApiRegistry.getSupportedFeatures()
    println("Supported features by provider:")
    supportedFeatures.forEach { (provider, features) ->
        println("  $provider: $features")
    }
    
    val statistics = RdfApiRegistry.getProviderStatistics()
    println("Provider statistics:")
    statistics.forEach { (category, count) ->
        println("  $category: $count")
    }
    
    // Feature availability
    val hasRdfStarSupport = RdfApiRegistry.hasProviderWithFeature("supportsRdfStar")
    val hasFederationSupport = RdfApiRegistry.hasProviderWithFeature("supportsFederation")
    val hasInferenceSupport = RdfApiRegistry.hasProviderWithFeature("supportsInference")
    
    println("Feature availability:")
    println("  RDF-star: $hasRdfStarSupport")
    println("  Federation: $hasFederationSupport")
    println("  Inference: $hasInferenceSupport")
    
    // Provider selection based on capabilities
    println("\n=== Provider Selection ===")
    
    val rdfStarProvider = allProviders.values.find { 
        it.getCapabilities().supportsRdfStar 
    }
    println("RDF-star provider: ${rdfStarProvider?.getType()}")
    
    val federationProvider = allProviders.values.find { 
        it.getCapabilities().supportsFederation 
    }
    println("Federation provider: ${federationProvider?.getType()}")
    
    val inferenceProvider = allProviders.values.find { 
        it.getCapabilities().supportsInference 
    }
    println("Inference provider: ${inferenceProvider?.getType()}")
}
```

## 🔗 Related Documentation

- [Enhanced Providers](enhanced-providers.md)
- [Service Description](service-description.md)
- [SPARQL 1.2 Support](sparql-1.2.md)
- [Extension Functions](extension-functions.md)
- [Registry Management](registry-management.md)

## 📞 Support

For questions about provider capabilities in Kastor:

- **Email**: stephanef@geoknoesis.com
- **Issues**: GitHub Issues
- **Documentation**: [Kastor Docs](https://docs.kastor.org)

---

*Kastor provider capability system is developed by [GeoKnoesis LLC](https://geoknoesis.com) and maintained by Stephane Fellah.*
