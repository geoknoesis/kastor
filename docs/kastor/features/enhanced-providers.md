# Enhanced Provider Architecture

Kastor features a comprehensive provider architecture that supports specialized providers for different use cases, including SPARQL endpoints, reasoning engines, and SHACL validators. This enhanced architecture provides unified access to diverse RDF capabilities while maintaining type safety and extensibility.

## 🎯 Overview

The enhanced provider architecture includes:

- **Unified Interface**: Single `RdfProvider` interface for all RDF store and endpoint providers
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

For remote SPARQL 1.1 Protocol endpoints (artifact `rdf-sparql`; the adapter reports only what it supports itself):

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.sparql.SparqlEndpointConfig
import com.geoknoesis.kastor.rdf.sparql.SparqlProvider
import com.geoknoesis.kastor.rdf.sparql.SparqlRepository

val sparqlProvider = RdfProviderRegistry.getProvider("sparql") ?: SparqlProvider()

// Capabilities describe the HTTP adapter, not the remote server
val capabilities = sparqlProvider.getCapabilities()
println("Type: ${sparqlProvider.id}") // "sparql"
println("Category: ${sparqlProvider.getProviderCategory()}") // SPARQL_ENDPOINT
println("Federation: ${capabilities.supportsFederation}") // false
println("SPARQL Version: ${capabilities.sparqlVersion}") // "1.1"

// Create a repository through the registry (options map onto SparqlEndpointConfig)
val remote = RdfProviderRegistry.create(
    RdfConfig(
        providerId = "sparql",
        variantId = "sparql",
        options = mapOf("location" to "https://example.org/sparql", "header.X-API-Key" to "…")
    )
)

// Or construct it directly with a typed configuration
val direct = SparqlRepository(
    SparqlEndpointConfig(
        endpoint = "https://example.org/sparql",
        headers = mapOf("X-API-Key" to "…")
    )
)
```

See [SPARQL Provider](../providers/sparql.md) for all options.

#### Reasoners and SHACL Validators

Reasoners and SHACL validators are **not** `RdfProvider`s and are not returned by `RdfProviderRegistry`; no bundled provider reports the `REASONER` or `SHACL_VALIDATOR` categories. Each has its own entry point:

```kotlin
import com.geoknoesis.kastor.rdf.reasoning.*
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile

// Reasoning (rdf-reasoning plus optional rdf-jena-reasoning / rdf-rdf4j-reasoning / rdf-reasoning-hermit)
RdfReasoning.reasonerProviders().forEach { provider ->
    println("${provider.name}: ${provider.getSupportedTypes()} (priority ${provider.priority()})")
}
val reasoner = RdfReasoning.reasoner(ReasonerType.RDFS)

// SHACL validation (rdf-shacl-validation)
val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
val report = validator.validate(dataGraph, shapesGraph)
```

See [Reasoning](reasoning.md) and [SHACL Validation](shacl-validation.md).

## 🔧 Unified Provider Interface

### RdfProvider

All RDF store and endpoint providers implement the `RdfProvider` interface:

```kotlin
interface RdfProvider {
    // Basic provider information
    val id: String
    val name: String get() = id
    val version: String get() = "unspecified"
    
    // Repository creation
    fun createRepository(variantId: String, config: RdfConfig): RdfRepository
    
    // Basic capabilities
    fun getCapabilities(variantId: String? = null): ProviderCapabilities = ProviderCapabilities()
    fun variants(): List<RdfVariant> = listOf(RdfVariant("default"))
    fun defaultVariantId(): String = variants().firstOrNull()?.id ?: "default"
    
    // Enhanced capabilities (optional)
    fun getProviderCategory(): ProviderCategory = ProviderCategory.RDF_STORE
    fun generateServiceDescription(serviceUri: String, variantId: String? = null): RdfGraph? = null
    fun getDetailedCapabilities(variantId: String? = null): DetailedProviderCapabilities
}
```

### Default Implementations

The interface provides sensible defaults for optional methods:

```kotlin
// Basic providers only need to implement core methods
class BasicProvider : RdfProvider {
    override val id: String = "basic"
    
    override fun createRepository(variantId: String, config: RdfConfig): RdfRepository {
        // Implementation
    }
    
    // Enhanced methods use default implementations
}
```

## 📊 Enhanced Capabilities

### ProviderCapabilities

Capabilities include RDF 1.2, SPARQL 1.2 and service description features:

```kotlin
data class ProviderCapabilities(
    val rdfVersion: String = "1.1",
    val supportsTripleTerms: Boolean = false,

    // Existing capabilities
    val supportsInference: Boolean = false,
    val supportsTransactions: Boolean = false,
    val supportsNamedGraphs: Boolean = false,
    val supportsUpdates: Boolean = false,
    val supportsRdfStar: Boolean = false,
    val supportsShacl: Boolean = false,
    val maxMemoryUsage: Long = Long.MAX_VALUE,

    // SPARQL 1.2 specific capabilities
    val sparqlVersion: String = "1.1",
    val supportsPropertyPaths: Boolean = false,
    val supportsAggregation: Boolean = false,
    val supportsSubSelect: Boolean = false,
    val supportsFederation: Boolean = false,
    val supportsVersionDeclaration: Boolean = false,
    val supportsServiceDescription: Boolean = false,

    // Service description capabilities
    val supportedLanguages: List<String> = emptyList(),
    val supportedResultFormats: List<String> = emptyList(),
    val supportedInputFormats: List<String> = emptyList(),
    val supportedOutputFormats: List<String> = emptyList(),
    val extensionFunctions: List<SparqlExtensionFunction> = emptyList(),
    val entailmentRegimes: List<String> = emptyList(),
    val namedGraphs: List<String> = emptyList(),
    val defaultGraphs: List<String> = emptyList(),
    val sparqlFeatures: Set<SparqlFeature> = emptySet()
)
```

### DetailedProviderCapabilities

More granular capability information:

```kotlin
data class DetailedProviderCapabilities(
    val basic: ProviderCapabilities,
    val providerCategory: ProviderCategory,
    val supportedSparqlFeatures: Map<String, Boolean>,
    val sparqlFeatures: Set<SparqlFeature> = emptySet(),
    val customExtensionFunctions: List<SparqlExtensionFunction>,
    val performanceMetrics: PerformanceMetrics? = null,
    val limitations: List<String> = emptyList()
)
```

The default `RdfProvider.getDetailedCapabilities` reports an empty `supportedSparqlFeatures` map. Providers that override it choose their own feature names; the SPARQL provider, for example, reports `RDF-star`, `Federation`, `Property Paths`, `Aggregation`, `Subqueries`, `Named Graphs`, `Updates` and `Transactions`. `hasProviderWithFeature`, `supportsFeature` and `getSupportedFeatures` use these names.

## 🎨 Registry Integration

### Unified Registry

The `RdfProviderRegistry` provides unified access to all providers:

```kotlin
// Get all providers
val allProviders = RdfProviderRegistry.getAllProviders()

// Get providers by category
val sparqlProviders = RdfProviderRegistry.getProvidersByCategory(ProviderCategory.SPARQL_ENDPOINT)
val reasonerProviders = RdfProviderRegistry.getProvidersByCategory(ProviderCategory.REASONER)

// Get specific provider
val memoryProvider = RdfProviderRegistry.getProvider("memory")
val sparqlProvider = RdfProviderRegistry.getProvider("sparql")
```

### Dynamic Registration

Providers are automatically registered when available:

```kotlin
// Specialized providers are registered automatically
val sparqlProvider = RdfProviderRegistry.getProvider("sparql")
if (sparqlProvider != null) {
    println("SPARQL Endpoint Provider available")
} else {
    println("SPARQL Endpoint Provider not available")
}
```

### Provider Discovery

```kotlin
// Discover all capabilities
val allCapabilities = RdfProviderRegistry.discoverAllCapabilities()

// Check feature support across providers
val hasRdfStarSupport = RdfProviderRegistry.hasProviderWithFeature("RDF-star")
val hasFederationSupport = RdfProviderRegistry.hasProviderWithFeature("Federation")

// Get provider statistics
val statistics = RdfProviderRegistry.getProviderStatistics()
println("Provider Statistics: $statistics")
```

## 🔍 Service Description Generation

### Generating a Description

`RdfProvider.generateServiceDescription` defaults to `null`, and the bundled providers (`memory`, `jena`, `rdf4j`, `sparql`) do not override it. Build a description from any provider's capabilities with `SparqlServiceDescriptionGenerator` (module `rdf-sparql-lang`):

```kotlin
import com.geoknoesis.kastor.rdf.sparql.SparqlServiceDescriptionGenerator

val provider = RdfProviderRegistry.getProvider("jena") ?: error("rdf-jena is not on the classpath")
val serviceUri = "http://example.org/sparql"

val description = SparqlServiceDescriptionGenerator(serviceUri, provider.getCapabilities())
    .generateServiceDescription()
println("Service description generated with ${description.getTriples().size} triples")
```

### Provider-Specific Descriptions

Custom providers can return a description from `generateServiceDescription` (see [Creating Custom Providers](#creating-custom-providers)); `RdfProviderRegistry.generateServiceDescription(providerId, serviceUri, variantId)` then delegates to them.

## 🎯 Extension Function Registry

### Built-in Functions

The SPARQL 1.2 built-in function descriptions ([Extension Functions](extension-functions.md)) are registered automatically:

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

## 📋 Provider Implementation

### Creating Custom Providers

```kotlin
class CustomProvider : RdfProvider {
    override val id: String = "custom"
    override val name: String = "Custom Provider"
    override val version: String = "1.0.0"
    
    override fun getProviderCategory(): ProviderCategory = ProviderCategory.RDF_STORE
    
    override fun variants(): List<RdfVariant> = listOf(RdfVariant("default"))
    
    override fun createRepository(variantId: String, config: RdfConfig): RdfRepository {
        // Custom repository implementation
        return CustomRepository(config)
    }
    
    override fun getCapabilities(variantId: String?): ProviderCapabilities {
        return ProviderCapabilities(
            sparqlVersion = "1.2",
            supportsRdfStar = true,
            supportsPropertyPaths = true,
            supportsAggregation = true,
            supportsServiceDescription = true,
            extensionFunctions = SparqlExtensionFunctionRegistry.getBuiltInFunctions()
        )
    }
    
    override fun generateServiceDescription(serviceUri: String, variantId: String?): RdfGraph? {
        val capabilities = getCapabilities(variantId)
        return SparqlServiceDescriptionGenerator(serviceUri, capabilities).generateServiceDescription()
    }
    
    override fun getDetailedCapabilities(variantId: String?): DetailedProviderCapabilities {
        return DetailedProviderCapabilities(
            basic = getCapabilities(variantId),
            providerCategory = getProviderCategory(),
            supportedSparqlFeatures = mapOf(
                "RDF-star" to true,
                "Property Paths" to true,
                "Aggregation" to true
            ),
            customExtensionFunctions = emptyList()
        )
    }
}
```

### Registering Custom Providers

```kotlin
// Register custom provider
RdfProviderRegistry.register(CustomProvider())

// Use custom provider
val customProvider = RdfProviderRegistry.getProvider("custom")
val repo = customProvider.createRepository(
    customProvider.defaultVariantId(),
    RdfConfig(providerId = "custom")
)
```

## 🎨 Best Practices

### 1. Provider Design

```kotlin
// Implement only necessary methods for basic providers
class BasicProvider : RdfProvider {
    // Implement core methods only
    // Enhanced methods use default implementations
}

// Override enhanced methods for specialized providers
class SpecializedProvider : RdfProvider {
    // Implement core methods
    override fun getProviderCategory(): ProviderCategory = ProviderCategory.SPARQL_ENDPOINT
    override fun generateServiceDescription(serviceUri: String, variantId: String?): RdfGraph? {
        // Custom service description
    }
    override fun getDetailedCapabilities(variantId: String?): DetailedProviderCapabilities {
        // Custom detailed capabilities
    }
}
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

### 3. Service Description

```kotlin
// Always provide meaningful service descriptions
val serviceUri = "https://api.example.org/sparql/v1"
val description = provider.generateServiceDescription(serviceUri, provider.defaultVariantId())

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
    val allProviders = RdfProviderRegistry.getAllProviders()
    println("Available providers: ${allProviders.map { it.id }}")
    
    // Get providers by category
    val sparqlProviders = RdfProviderRegistry.getProvidersByCategory(ProviderCategory.SPARQL_ENDPOINT)
    println("SPARQL Endpoint providers: ${sparqlProviders.size}")
    
    // Get specific provider
    val memoryProvider = RdfProviderRegistry.getProvider("memory")
    if (memoryProvider != null) {
        println("Memory provider: ${memoryProvider.name} v${memoryProvider.version}")
        println("Category: ${memoryProvider.getProviderCategory()}")
        
        // Get capabilities
        val capabilities = memoryProvider.getCapabilities(memoryProvider.defaultVariantId())
        println("SPARQL Version: ${capabilities.sparqlVersion}")
        println("RDF-star Support: ${capabilities.supportsRdfStar}")
        println("Extension Functions: ${capabilities.extensionFunctions.size}")
        
        // Generate service description
        val serviceUri = "http://example.org/sparql"
        val description = memoryProvider.generateServiceDescription(
            serviceUri,
            memoryProvider.defaultVariantId()
        )
        if (description != null) {
            println("Service description generated with ${description.getTriples().size} triples")
        }
        
        // Get detailed capabilities
        val detailedCapabilities = memoryProvider.getDetailedCapabilities()
        println("Detailed capabilities available: ${detailedCapabilities.supportedSparqlFeatures.size} features")
    }
    
    // Discover all capabilities
    val allCapabilities = RdfProviderRegistry.discoverAllCapabilities()
    println("Total providers with detailed capabilities: ${allCapabilities.size}")
    
    // Check feature support
    val hasRdfStarSupport = RdfProviderRegistry.hasProviderWithFeature("RDF-star")
    val hasFederationSupport = RdfProviderRegistry.hasProviderWithFeature("Federation")
    println("RDF-star support available: $hasRdfStarSupport")
    println("Federation support available: $hasFederationSupport")
    
    // Get provider statistics
    val statistics = RdfProviderRegistry.getProviderStatistics()
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



