# Registry Management

Kastor features a unified registry system that provides comprehensive provider management, capability discovery, and service description generation. The `RdfApiRegistry` serves as the central hub for all provider operations, combining basic and enhanced functionality in a single, easy-to-use interface.

## 🎯 Overview

The unified registry system provides:

- **Provider Management**: Registration, discovery, and management of all providers
- **Capability Discovery**: Automatic detection and advertisement of provider capabilities
- **Service Description Generation**: Automatic service description creation for all providers
- **Feature Support Checking**: Runtime validation of provider feature support
- **Provider Statistics**: Comprehensive statistics and analytics
- **Category-Based Organization**: Providers organized by functionality and use case

## 🚀 Core Registry Operations

### Basic Provider Operations

```kotlin
// Get all registered providers
val allProviders = RdfApiRegistry.getAllProviders()
println("Available providers: ${allProviders.keys}")

// Get specific provider
val memoryProvider = RdfApiRegistry.getProvider("memory")
val jenaProvider = RdfApiRegistry.getProvider("jena")

// Check provider availability
if (memoryProvider != null) {
    println("Memory provider available: ${memoryProvider.name}")
} else {
    println("Memory provider not available")
}
```

### Enhanced Provider Operations

```kotlin
// Get providers by category
val sparqlProviders = RdfApiRegistry.getProvidersByCategory(ProviderCategory.SPARQL_ENDPOINT)
val reasonerProviders = RdfApiRegistry.getProvidersByCategory(ProviderCategory.REASONER)
val shaclProviders = RdfApiRegistry.getProvidersByCategory(ProviderCategory.SHACL_VALIDATOR)

println("SPARQL providers: ${sparqlProviders.size}")
println("Reasoner providers: ${reasonerProviders.size}")
println("SHACL providers: ${shaclProviders.size}")
```

### Provider Discovery

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

## 📊 Service Description Generation

### Individual Provider Service Descriptions

```kotlin
// Generate service description for specific provider
val memoryDescription = RdfApiRegistry.generateServiceDescription(
    providerType = "memory",
    serviceUri = "http://example.org/memory"
)

val jenaDescription = RdfApiRegistry.generateServiceDescription(
    providerType = "jena",
    serviceUri = "http://example.org/jena"
)

if (memoryDescription != null) {
    println("Memory service description: ${memoryDescription.getTriples().size} triples")
}
```

### Bulk Service Description Generation

```kotlin
// Generate service descriptions for all providers
val baseUri = "http://example.org"
val allDescriptions = RdfApiRegistry.getAllServiceDescriptions(baseUri)

allDescriptions.forEach { (providerType, description) ->
    println("Provider: $providerType")
    println("Service description: ${description.getTriples().size} triples")
    
    // Print as Turtle
    val generator = SparqlServiceDescriptionGenerator(
        "$baseUri/$providerType", 
        RdfApiRegistry.getProvider(providerType)!!.getCapabilities()
    )
    val turtle = generator.generateAsTurtle()
    println("Turtle:\n$turtle")
}
```

## 🔍 Feature Support Discovery

### Feature Availability Checking

```kotlin
// Check if any provider supports specific features
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
val memorySupportsRdfStar = RdfApiRegistry.supportsFeature("memory", "supportsRdfStar")
val jenaSupportsRdfStar = RdfApiRegistry.supportsFeature("jena", "supportsRdfStar")

println("Memory supports RDF-star: $memorySupportsRdfStar")
println("Jena supports RDF-star: $jenaSupportsRdfStar")
```

### Supported Features by Provider

```kotlin
// Get all supported features organized by provider
val supportedFeatures = RdfApiRegistry.getSupportedFeatures()

supportedFeatures.forEach { (providerType, features) ->
    println("$providerType supports:")
    features.forEach { feature ->
        println("  - $feature")
    }
    println()
}
```

## 📋 Provider Statistics

### Category Statistics

```kotlin
// Get provider statistics by category
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
// Analyze capability distribution
val allCapabilities = RdfApiRegistry.discoverAllCapabilities()

// Count providers by capability
val rdfStarProviders = allCapabilities.values.count { it.basic.supportsRdfStar }
val federationProviders = allCapabilities.values.count { it.basic.supportsFederation }
val inferenceProviders = allCapabilities.values.count { it.basic.supportsInference }

println("RDF-star providers: $rdfStarProviders")
println("Federation providers: $federationProviders")
println("Inference providers: $inferenceProviders")

// Count by SPARQL version
val sparql12Providers = allCapabilities.values.count { it.basic.sparqlVersion == "1.2" }
println("SPARQL 1.2 providers: $sparql12Providers")
```

### Extension Function Statistics

```kotlin
// Analyze extension function support
val allCapabilities = RdfApiRegistry.discoverAllCapabilities()

val totalFunctions = allCapabilities.values.sumOf { it.basic.extensionFunctions.size }
val providersWithFunctions = allCapabilities.values.count { it.basic.extensionFunctions.isNotEmpty() }

println("Total extension functions: $totalFunctions")
println("Providers with extension functions: $providersWithFunctions")

// Most common functions
val functionCounts = mutableMapOf<String, Int>()
allCapabilities.values.forEach { capabilities ->
    capabilities.basic.extensionFunctions.forEach { func ->
        functionCounts[func.name] = functionCounts.getOrDefault(func.name, 0) + 1
    }
}

val mostCommonFunctions = functionCounts.toList()
    .sortedByDescending { it.second }
    .take(10)

println("Most common functions:")
mostCommonFunctions.forEach { (name, count) ->
    println("  $name: $count providers")
}
```

## 🔧 Dynamic Provider Registration

### Automatic Registration

Specialized providers are automatically registered when available:

```kotlin
// Check for specialized providers
val sparqlProvider = RdfApiRegistry.getProvider("sparql-endpoint")
val reasonerProvider = RdfApiRegistry.getProvider("reasoner")
val shaclProvider = RdfApiRegistry.getProvider("shacl-validator")

println("SPARQL Endpoint Provider: ${sparqlProvider?.name ?: "Not available"}")
println("Reasoner Provider: ${reasonerProvider?.name ?: "Not available"}")
println("SHACL Validator Provider: ${shaclProvider?.name ?: "Not available"}")
```

### Manual Registration

```kotlin
// Register custom provider
class CustomProvider : RdfApiProvider {
    override fun getType(): String = "custom"
    override val name: String = "Custom Provider"
    override val version: String = "1.0.0"
    
    override fun createRepository(config: RdfConfig): RdfRepository {
        return CustomRepository(config)
    }
    
    override fun getCapabilities(): ProviderCapabilities {
        return ProviderCapabilities(
            sparqlVersion = "1.2",
            supportsRdfStar = true,
            supportsPropertyPaths = true,
            supportsAggregation = true
        )
    }
    
    override fun getSupportedTypes(): List<String> = listOf("custom")
    override fun isSupported(type: String): Boolean = type == "custom"
}

// Register the custom provider
RdfApiRegistry.register("custom", CustomProvider())

// Verify registration
val customProvider = RdfApiRegistry.getProvider("custom")
println("Custom provider registered: ${customProvider?.name}")
```

## 🎨 Provider Selection Strategies

### Capability-Based Selection

```kotlin
// Select provider based on required capabilities
fun selectProvider(
    requiresRdfStar: Boolean = false,
    requiresFederation: Boolean = false,
    requiresInference: Boolean = false
): RdfApiProvider? {
    val allProviders = RdfApiRegistry.getAllProviders()
    
    return allProviders.values.find { provider ->
        val capabilities = provider.getCapabilities()
        
        val hasRdfStar = !requiresRdfStar || capabilities.supportsRdfStar
        val hasFederation = !requiresFederation || capabilities.supportsFederation
        val hasInference = !requiresInference || capabilities.supportsInference
        
        hasRdfStar && hasFederation && hasInference
    }
}

// Use capability-based selection
val rdfStarProvider = selectProvider(requiresRdfStar = true)
val federationProvider = selectProvider(requiresFederation = true)
val inferenceProvider = selectProvider(requiresInference = true)
```

### Category-Based Selection

```kotlin
// Select provider by category
fun selectProviderByCategory(category: ProviderCategory): RdfApiProvider? {
    val providers = RdfApiRegistry.getProvidersByCategory(category)
    return providers.values.firstOrNull()
}

// Use category-based selection
val sparqlProvider = selectProviderByCategory(ProviderCategory.SPARQL_ENDPOINT)
val reasonerProvider = selectProviderByCategory(ProviderCategory.REASONER)
val shaclProvider = selectProviderByCategory(ProviderCategory.SHACL_VALIDATOR)
```

### Performance-Based Selection

```kotlin
// Select provider based on performance characteristics
fun selectBestProvider(useCase: String): RdfApiProvider? {
    val allProviders = RdfApiRegistry.getAllProviders()
    
    return when (useCase) {
        "memory-intensive" -> allProviders.values.minByOrNull { 
            it.getCapabilities().maxMemoryUsage 
        }
        "sparql-advanced" -> allProviders.values.find { 
            it.getCapabilities().supportsFederation && 
            it.getCapabilities().supportsPropertyPaths 
        }
        "reasoning" -> allProviders.values.find { 
            it.getCapabilities().supportsInference 
        }
        else -> allProviders.values.firstOrNull()
    }
}
```

## 🔍 Registry Health Monitoring

### Provider Health Checks

```kotlin
// Check provider health and availability
fun checkProviderHealth(): Map<String, Boolean> {
    val allProviders = RdfApiRegistry.getAllProviders()
    val healthStatus = mutableMapOf<String, Boolean>()
    
    allProviders.forEach { (type, provider) ->
        try {
            // Try to create a repository to test provider health
            val repo = provider.createRepository(RdfConfig())
            healthStatus[type] = true
        } catch (e: Exception) {
            println("Provider $type is unhealthy: ${e.message}")
            healthStatus[type] = false
        }
    }
    
    return healthStatus
}

val healthStatus = checkProviderHealth()
healthStatus.forEach { (provider, healthy) ->
    println("$provider: ${if (healthy) "Healthy" else "Unhealthy"}")
}
```

### Capability Consistency Checks

```kotlin
// Verify capability consistency across providers
fun checkCapabilityConsistency(): Map<String, List<String>> {
    val allCapabilities = RdfApiRegistry.discoverAllCapabilities()
    val inconsistencies = mutableMapOf<String, MutableList<String>>()
    
    // Check for inconsistent SPARQL versions
    val sparqlVersions = allCapabilities.values.map { it.basic.sparqlVersion }.distinct()
    if (sparqlVersions.size > 1) {
        inconsistencies["SPARQL Version"] = sparqlVersions
    }
    
    // Check for inconsistent RDF-star support
    val rdfStarSupport = allCapabilities.values.map { it.basic.supportsRdfStar }.distinct()
    if (rdfStarSupport.size > 1) {
        inconsistencies["RDF-star Support"] = rdfStarSupport.map { it.toString() }
    }
    
    return inconsistencies
}

val inconsistencies = checkCapabilityConsistency()
if (inconsistencies.isNotEmpty()) {
    println("Capability inconsistencies found:")
    inconsistencies.forEach { (capability, values) ->
        println("  $capability: $values")
    }
} else {
    println("No capability inconsistencies found")
}
```

## 🎯 Best Practices

### 1. Provider Discovery

```kotlin
// Always check provider availability before use
fun getProviderSafely(type: String): RdfApiProvider? {
    return RdfApiRegistry.getProvider(type)?.also { provider ->
        println("Using provider: ${provider.name} v${provider.version}")
    }
}

val memoryProvider = getProviderSafely("memory")
if (memoryProvider != null) {
    // Use provider
}
```

### 2. Capability Checking

```kotlin
// Check capabilities before using advanced features
fun useAdvancedFeatures(provider: RdfApiProvider) {
    val capabilities = provider.getCapabilities()
    
    if (capabilities.supportsRdfStar) {
        // Use RDF-star features
    }
    
    if (capabilities.supportsFederation) {
        // Use federation features
    }
    
    if (capabilities.supportsInference) {
        // Use inference features
    }
}
```

### 3. Service Description Management

```kotlin
// Generate and validate service descriptions
fun generateServiceDescriptions(baseUri: String) {
    val allDescriptions = RdfApiRegistry.getAllServiceDescriptions(baseUri)
    
    allDescriptions.forEach { (providerType, description) ->
        val triples = description.getTriples()
        
        // Validate service description
        val hasServiceType = triples.any { 
            it.predicate == SPARQL_SD.Service 
        }
        val hasEndpoint = triples.any { 
            it.predicate == SPARQL_SD.endpointProp 
        }
        
        if (hasServiceType && hasEndpoint) {
            println("$providerType: Valid service description")
        } else {
            println("$providerType: Invalid service description")
        }
    }
}
```

## 📖 Complete Example

```kotlin
fun registryManagementExample() {
    println("=== Registry Management Example ===")
    
    // 1. Basic provider operations
    println("\n1. Basic Provider Operations")
    val allProviders = RdfApiRegistry.getAllProviders()
    println("Available providers: ${allProviders.keys}")
    
    allProviders.forEach { (type, provider) ->
        println("  $type: ${provider.name} v${provider.version}")
    }
    
    // 2. Category-based operations
    println("\n2. Category-based Operations")
    val categories = listOf(
        ProviderCategory.RDF_STORE,
        ProviderCategory.SPARQL_ENDPOINT,
        ProviderCategory.REASONER,
        ProviderCategory.SHACL_VALIDATOR
    )
    
    categories.forEach { category ->
        val providers = RdfApiRegistry.getProvidersByCategory(category)
        println("  $category: ${providers.size} providers")
    }
    
    // 3. Capability discovery
    println("\n3. Capability Discovery")
    val allCapabilities = RdfApiRegistry.discoverAllCapabilities()
    println("Providers with detailed capabilities: ${allCapabilities.size}")
    
    allCapabilities.forEach { (type, capabilities) ->
        println("  $type:")
        println("    Category: ${capabilities.providerCategory}")
        println("    SPARQL Version: ${capabilities.basic.sparqlVersion}")
        println("    RDF-star: ${capabilities.basic.supportsRdfStar}")
        println("    Federation: ${capabilities.basic.supportsFederation}")
        println("    Extension Functions: ${capabilities.basic.extensionFunctions.size}")
    }
    
    // 4. Feature support checking
    println("\n4. Feature Support Checking")
    val features = listOf("supportsRdfStar", "supportsFederation", "supportsInference")
    
    features.forEach { feature ->
        val hasSupport = RdfApiRegistry.hasProviderWithFeature(feature)
        println("  $feature: $hasSupport")
    }
    
    // 5. Provider statistics
    println("\n5. Provider Statistics")
    val statistics = RdfApiRegistry.getProviderStatistics()
    statistics.forEach { (category, count) ->
        println("  $category: $count providers")
    }
    
    // 6. Service description generation
    println("\n6. Service Description Generation")
    val baseUri = "http://example.org"
    val allDescriptions = RdfApiRegistry.getAllServiceDescriptions(baseUri)
    
    allDescriptions.forEach { (type, description) ->
        println("  $type: ${description.getTriples().size} triples")
    }
    
    // 7. Provider selection
    println("\n7. Provider Selection")
    val rdfStarProvider = allProviders.values.find { 
        it.getCapabilities().supportsRdfStar 
    }
    println("  RDF-star provider: ${rdfStarProvider?.getType()}")
    
    val federationProvider = allProviders.values.find { 
        it.getCapabilities().supportsFederation 
    }
    println("  Federation provider: ${federationProvider?.getType()}")
    
    // 8. Health checking
    println("\n8. Provider Health Check")
    val healthStatus = checkProviderHealth()
    healthStatus.forEach { (provider, healthy) ->
        println("  $provider: ${if (healthy) "Healthy" else "Unhealthy"}")
    }
    
    // 9. Capability consistency
    println("\n9. Capability Consistency Check")
    val inconsistencies = checkCapabilityConsistency()
    if (inconsistencies.isNotEmpty()) {
        println("  Inconsistencies found:")
        inconsistencies.forEach { (capability, values) ->
            println("    $capability: $values")
        }
    } else {
        println("  No inconsistencies found")
    }
}

// Helper functions
fun checkProviderHealth(): Map<String, Boolean> {
    val allProviders = RdfApiRegistry.getAllProviders()
    val healthStatus = mutableMapOf<String, Boolean>()
    
    allProviders.forEach { (type, provider) ->
        try {
            provider.createRepository(RdfConfig())
            healthStatus[type] = true
        } catch (e: Exception) {
            healthStatus[type] = false
        }
    }
    
    return healthStatus
}

fun checkCapabilityConsistency(): Map<String, List<String>> {
    val allCapabilities = RdfApiRegistry.discoverAllCapabilities()
    val inconsistencies = mutableMapOf<String, MutableList<String>>()
    
    val sparqlVersions = allCapabilities.values.map { it.basic.sparqlVersion }.distinct()
    if (sparqlVersions.size > 1) {
        inconsistencies["SPARQL Version"] = sparqlVersions.toMutableList()
    }
    
    return inconsistencies
}
```

## 🔗 Related Documentation

- [Enhanced Providers](enhanced-providers.md)
- [Provider Capabilities](provider-capabilities.md)
- [Service Description](service-description.md)
- [SPARQL 1.2 Support](sparql-1.2.md)
- [Extension Functions](extension-functions.md)

## 📞 Support

For questions about registry management in Kastor:

- **Email**: stephanef@geoknoesis.com
- **Issues**: GitHub Issues
- **Documentation**: [Kastor Docs](https://docs.kastor.org)

---

*Kastor registry management system is developed by [GeoKnoesis LLC](https://geoknoesis.com) and maintained by Stephane Fellah.*
