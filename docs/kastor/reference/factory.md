## Factory DSL

```kotlin
// Modern approach using RdfApiRegistry
val repo = RdfApiRegistry.create(RdfConfig(providerId = "jena", variantId = "memory"))
val persistentRepo = RdfApiRegistry.create(
  RdfConfig(providerId = "jena", variantId = "tdb2", options = mapOf("location" to "/data/tdb2"))
)

// Legacy factory approach (still supported)
val api = Rdf.factory {
  providerId = "jena"
  variantId = "memory"
  // param("name", "value") // provider-specific
  // defaultGraph(iri("urn:graph"))
  // strict(true)
}
val repo = api.repository
```

### Enhanced Configuration Model

```kotlin
// Configuration with rich parameter metadata
data class ConfigParameter(
    val name: String,           // Parameter name (e.g., "location")
    val description: String,     // Human-readable description
    val type: String = "String", // Data type (default: "String")
    val optional: Boolean = false, // Whether parameter is optional
    val defaultValue: String? = null, // Default value if optional
    val examples: List<String> = emptyList() // Example values
)

data class ConfigVariant(
    val type: String,
    val description: String,
    val parameters: List<ConfigParameter> = emptyList()
)

// Configuration creation
val config = RdfConfig(providerId: String, variantId: String, options: Map<String,String>)
```

### Parameter Discovery

```kotlin
// Get all available variants with parameter details
val variants = RdfApiRegistry.getAllConfigVariants()
variants.forEach { variant ->
    println("${variant.type}: ${variant.description}")
    variant.parameters.forEach { param ->
        println("  ${param.name} (${param.type}): ${param.description}")
        if (param.examples.isNotEmpty()) {
            println("    Examples: ${param.examples.joinToString(", ")}")
        }
    }
}

// Get parameter information for specific variant
val variant = RdfApiRegistry.getConfigVariant("jena:tdb2")
val locationParam = RdfApiRegistry.getParameterInfo("jena:tdb2", "location")

// Validate configuration before creating repository
val requiredParams = RdfApiRegistry.getRequiredParameters("sparql")
val missingParams = requiredParams.filter { param -> 
    !config.params.containsKey(param.name) 
}
```

### Discovery
`RdfApiRegistry` finds providers via Java `ServiceLoader`. You can also register a provider programmatically.




