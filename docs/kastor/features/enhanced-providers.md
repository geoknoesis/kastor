# Enhanced Provider Architecture

Kastor features a comprehensive provider architecture that supports specialized providers for different use cases, including SPARQL endpoints, reasoning engines, and SHACL validators. This enhanced architecture provides unified access to diverse RDF capabilities while maintaining type safety and extensibility.

## 🎯 Overview

The enhanced provider architecture includes:

- **Unified Interface**: Single `RdfApiProvider` interface for all providers
- **Specialized Providers**: Dedicated providers for specific use cases
- **Category-Based Organization**: Providers organized by functionality
- **Service Description Support**: Automatic capability discovery and description
- **Extension Function Registry**: Comprehensive function management
- **Dynamic Registration**: Automatic provider discovery and registration

## 🚀 Provider Categories

### 1. RDF Store Providers

Basic RDF storage and query providers:

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

### 2. Specialized Provider Types

#### SPARQL Endpoint Provider

For remote SPARQL endpoints with full SPARQL 1.2 support:

```kotlin
val sparqlProvider = SparqlEndpointProvider()

// Capabilities include federation and service description
val capabilities = sparqlProvider.getCapabilities()
println("Type: ${sparqlProvider.getType()}") // "sparql-endpoint"
println("Category: ${sparqlProvider.getProviderCategory()}") // SPARQL_ENDPOINT
println("Federation: ${capabilities.supportsFederation}") // true
println("SPARQL Version: ${capabilities.sparqlVersion}") // "1.2"
```

#### Reasoner Provider

For inference engines with reasoning capabilities:

```kotlin
val reasonerProvider = ReasonerProvider()

// Capabilities include inference and entailment regimes
val capabilities = reasonerProvider.getCapabilities()
println("Type: ${reasonerProvider.getType()}") // "reasoner"
println("Category: ${reasonerProvider.getProviderCategory()}") // REASONER
println("Inference: ${capabilities.supportsInference}") // true
println("Entailment Regimes: ${capabilities.entailmentRegimes}")
// [RDFS, OWL-RL, OWL-EL, OWL-Q]
```

#### SHACL Validator Provider

For SHACL validation services:

```kotlin
val shaclProvider = ShaclValidatorProvider()

// Capabilities include validation features
val capabilities = shaclProvider.getCapabilities()
println("Type: ${shaclProvider.getType()}") // "shacl-validator"
println("Category: ${shaclProvider.getProviderCategory()}") // SHACL_VALIDATOR
println("Validation: ${capabilities.supportsValidation}") // true
```

## 🔧 Unified Provider Interface

### Enhanced RdfApiProvider

All providers implement the unified `RdfApiProvider` interface:

```kotlin
interface RdfApiProvider {
    // Basic provider information
    fun getType(): String
    val name: String
    val version: String
    
    // Repository creation
    fun createRepository(config: RdfConfig): RdfRepository
    
    // Basic capabilities
    fun getCapabilities(): ProviderCapabilities
    fun getSupportedTypes(): List<String>
    fun isSupported(type: String): Boolean
    
    // Enhanced capabilities (optional)
    fun getProviderCategory(): ProviderCategory = ProviderCategory.RDF_STORE
    fun generateServiceDescription(serviceUri: String): RdfGraph? = null
    fun getDetailedCapabilities(): DetailedProviderCapabilities
}
```

### Backward Compatibility

The interface maintains backward compatibility through default implementations:

```kotlin
// Basic providers only need to implement core methods
class BasicProvider : RdfApiProvider {
    override fun getType(): String = "basic"
    override val name: String = "Basic Provider"
    override val version: String = "1.0.0"
    
    override fun createRepository(config: RdfConfig): RdfRepository {
        // Implementation
    }
    
    override fun getCapabilities(): ProviderCapabilities {
        // Implementation
    }
    
    override fun getSupportedTypes(): List<String> {
        // Implementation
    }
    
    override fun isSupported(type: String): Boolean {
        // Implementation
    }
    
    // Enhanced methods use default implementations
}
```

## 📊 Enhanced Capabilities

### ProviderCapabilities

Enhanced capabilities include SPARQL 1.2 and service description features:

```kotlin
data class ProviderCapabilities(
    // Existing capabilities
    val supportsInference: Boolean = false,
    val supportsTransactions: Boolean = false,
    val supportsNamedGraphs: Boolean = false,
    val supportsUpdates: Boolean = false,
    val supportsRdfStar: Boolean = false,
    val maxMemoryUsage: Long = Long.MAX_VALUE,
    
    // SPARQL 1.2 specific capabilities
    val sparqlVersion: String = "1.2",
    val supportsPropertyPaths: Boolean = true,
    val supportsAggregation: Boolean = true,
    val supportsSubSelect: Boolean = true,
    val supportsFederation: Boolean = false,
    val supportsVersionDeclaration: Boolean = true,
    val supportsServiceDescription: Boolean = true,
    
    // Service description capabilities
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

More granular capability information:

```kotlin
data class DetailedProviderCapabilities(
    val basic: ProviderCapabilities,
    val providerCategory: ProviderCategory,
    val supportedSparqlFeatures: Map<String, Boolean>,
    val customExtensionFunctions: List<SparqlExtensionFunction>
)
```

## 🎨 Registry Integration

### Unified Registry

The `RdfApiRegistry` provides unified access to all providers:

```kotlin
// Get all providers
val allProviders = RdfApiRegistry.getAllProviders()

// Get providers by category
val sparqlProviders = RdfApiRegistry.getProvidersByCategory(ProviderCategory.SPARQL_ENDPOINT)
val reasonerProviders = RdfApiRegistry.getProvidersByCategory(ProviderCategory.REASONER)

// Get specific provider
val memoryProvider = RdfApiRegistry.getProvider("memory")
val sparqlProvider = RdfApiRegistry.getProvider("sparql-endpoint")
```

### Dynamic Registration

Providers are automatically registered when available:

```kotlin
// Specialized providers are registered automatically
val sparqlProvider = RdfApiRegistry.getProvider("sparql-endpoint")
if (sparqlProvider != null) {
    println("SPARQL Endpoint Provider available")
} else {
    println("SPARQL Endpoint Provider not available")
}
```

### Provider Discovery

```kotlin
// Discover all capabilities
val allCapabilities = RdfApiRegistry.discoverAllCapabilities()

// Check feature support across providers
val hasRdfStarSupport = RdfApiRegistry.hasProviderWithFeature("supportsRdfStar")
val hasFederationSupport = RdfApiRegistry.hasProviderWithFeature("supportsFederation")

// Get provider statistics
val statistics = RdfApiRegistry.getProviderStatistics()
println("Provider Statistics: $statistics")
```

## 🔍 Service Description Generation

### Automatic Generation

All providers can generate service descriptions:

```kotlin
val provider = RdfApiRegistry.getProvider("memory")
val serviceUri = "http://example.org/sparql"
val description = provider.generateServiceDescription(serviceUri)

if (description != null) {
    println("Service description generated with ${description.getTriples().size} triples")
}
```

### Provider-Specific Descriptions

```kotlin
// SPARQL Endpoint Provider
val sparqlProvider = SparqlEndpointProvider()
val sparqlDescription = sparqlProvider.generateServiceDescription(
    "http://example.org/sparql"
)

// Reasoner Provider
val reasonerProvider = ReasonerProvider()
val reasonerDescription = reasonerProvider.generateServiceDescription(
    "http://example.org/reasoner"
)

// SHACL Validator Provider
val shaclProvider = ShaclValidatorProvider()
val shaclDescription = shaclProvider.generateServiceDescription(
    "http://example.org/shacl"
)
```

## 🎯 Extension Function Registry

### Built-in Functions

All SPARQL 1.2 built-in functions are automatically registered:

```kotlin
val builtInFunctions = SparqlExtensionFunctionRegistry.getBuiltInFunctions()

builtInFunctions.forEach { func ->
    println("Function: ${func.name}")
    println("Description: ${func.description}")
    println("Return Type: ${func.returnType}")
    println("Is Aggregate: ${func.isAggregate}")
}
```

### Custom Functions

```kotlin
// Register custom function
val customFunction = SparqlExtensionFunction(
    iri = "http://example.org/functions#customFunction",
    name = "customFunction",
    description = "A custom SPARQL function",
    argumentTypes = listOf("http://www.w3.org/2001/XMLSchema#string"),
    returnType = "http://www.w3.org/2001/XMLSchema#string",
    isAggregate = false,
    isBuiltIn = false
)

SparqlExtensionFunctionRegistry.register(customFunction)
```

## 📋 Provider Implementation

### Creating Custom Providers

```kotlin
class CustomProvider : RdfApiProvider {
    override fun getType(): String = "custom"
    override val name: String = "Custom Provider"
    override val version: String = "1.0.0"
    
    override fun getProviderCategory(): ProviderCategory = ProviderCategory.RDF_STORE
    
    override fun createRepository(config: RdfConfig): RdfRepository {
        // Custom repository implementation
        return CustomRepository(config)
    }
    
    override fun getCapabilities(): ProviderCapabilities {
        return ProviderCapabilities(
            sparqlVersion = "1.2",
            supportsRdfStar = true,
            supportsPropertyPaths = true,
            supportsAggregation = true,
            supportsServiceDescription = true,
            extensionFunctions = SparqlExtensionFunctionRegistry.getBuiltInFunctions()
        )
    }
    
    override fun getSupportedTypes(): List<String> {
        return listOf("custom")
    }
    
    override fun isSupported(type: String): Boolean {
        return type == "custom"
    }
    
    override fun generateServiceDescription(serviceUri: String): RdfGraph? {
        val capabilities = getCapabilities()
        return SparqlServiceDescriptionGenerator(serviceUri, capabilities).generateServiceDescription()
    }
    
    override fun getDetailedCapabilities(): DetailedProviderCapabilities {
        return DetailedProviderCapabilities(
            basic = getCapabilities(),
            providerCategory = getProviderCategory(),
            supportedSparqlFeatures = mapOf(
                "supportsRdfStar" to true,
                "supportsPropertyPaths" to true,
                "supportsAggregation" to true
            ),
            customExtensionFunctions = emptyList()
        )
    }
}
```

### Registering Custom Providers

```kotlin
// Register custom provider
RdfApiRegistry.register("custom", CustomProvider())

// Use custom provider
val customProvider = RdfApiRegistry.getProvider("custom")
val repo = customProvider.createRepository(RdfConfig())
```

## 🎨 Best Practices

### 1. Provider Design

```kotlin
// Implement only necessary methods for basic providers
class BasicProvider : RdfApiProvider {
    // Implement core methods only
    // Enhanced methods use default implementations
}

// Override enhanced methods for specialized providers
class SpecializedProvider : RdfApiProvider {
    // Implement core methods
    override fun getProviderCategory(): ProviderCategory = ProviderCategory.SPARQL_ENDPOINT
    override fun generateServiceDescription(serviceUri: String): RdfGraph? {
        // Custom service description
    }
    override fun getDetailedCapabilities(): DetailedProviderCapabilities {
        // Custom detailed capabilities
    }
}
```

### 2. Capability Discovery

```kotlin
// Check capabilities before using features
val provider = RdfApiRegistry.getProvider("memory")
val capabilities = provider.getDetailedCapabilities()

if (capabilities.basic.supportsRdfStar) {
    // Use RDF-star features
}

if (capabilities.basic.supportsFederation) {
    // Use federation features
}
```

### 3. Service Description

```kotlin
// Always provide meaningful service descriptions
val serviceUri = "https://api.example.org/sparql/v1"
val description = provider.generateServiceDescription(serviceUri)

if (description != null) {
    // Validate service description
    val triples = description.getTriples()
    println("Service description contains ${triples.size} triples")
}
```

## 📖 Complete Example

```kotlin
fun enhancedProviderExample() {
    // Get all providers
    val allProviders = RdfApiRegistry.getAllProviders()
    println("Available providers: ${allProviders.keys}")
    
    // Get providers by category
    val sparqlProviders = RdfApiRegistry.getProvidersByCategory(ProviderCategory.SPARQL_ENDPOINT)
    println("SPARQL Endpoint providers: ${sparqlProviders.size}")
    
    // Get specific provider
    val memoryProvider = RdfApiRegistry.getProvider("memory")
    if (memoryProvider != null) {
        println("Memory provider: ${memoryProvider.name} v${memoryProvider.version}")
        println("Category: ${memoryProvider.getProviderCategory()}")
        
        // Get capabilities
        val capabilities = memoryProvider.getCapabilities()
        println("SPARQL Version: ${capabilities.sparqlVersion}")
        println("RDF-star Support: ${capabilities.supportsRdfStar}")
        println("Extension Functions: ${capabilities.extensionFunctions.size}")
        
        // Generate service description
        val serviceUri = "http://example.org/sparql"
        val description = memoryProvider.generateServiceDescription(serviceUri)
        if (description != null) {
            println("Service description generated with ${description.getTriples().size} triples")
        }
        
        // Get detailed capabilities
        val detailedCapabilities = memoryProvider.getDetailedCapabilities()
        println("Detailed capabilities available: ${detailedCapabilities.supportedSparqlFeatures.size} features")
    }
    
    // Discover all capabilities
    val allCapabilities = RdfApiRegistry.discoverAllCapabilities()
    println("Total providers with detailed capabilities: ${allCapabilities.size}")
    
    // Check feature support
    val hasRdfStarSupport = RdfApiRegistry.hasProviderWithFeature("supportsRdfStar")
    val hasFederationSupport = RdfApiRegistry.hasProviderWithFeature("supportsFederation")
    println("RDF-star support available: $hasRdfStarSupport")
    println("Federation support available: $hasFederationSupport")
    
    // Get provider statistics
    val statistics = RdfApiRegistry.getProviderStatistics()
    println("Provider statistics: $statistics")
}
```

## 🔗 Related Documentation

- [SPARQL 1.2 Support](sparql-1.2.md)
- [Service Description](service-description.md)
- [Provider Capabilities](provider-capabilities.md)
- [Extension Functions](extension-functions.md)
- [Registry Management](registry-management.md)

## 📞 Support

For questions about the enhanced provider architecture in Kastor:

- **Email**: stephanef@geoknoesis.com
- **Issues**: GitHub Issues
- **Documentation**: [Kastor Docs](https://docs.kastor.org)

---

*Kastor enhanced provider architecture is developed by [GeoKnoesis LLC](https://geoknoesis.com) and maintained by Stephane Fellah.*
