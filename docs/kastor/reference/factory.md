## Factory DSL

```kotlin
// Provider registry (explicit configuration)
val repo = RdfProviderRegistry.create(
  RdfConfig(providerId = "jena", variantId = "memory")
)

val persistentRepo = RdfProviderRegistry.create(
  RdfConfig(providerId = "jena", variantId = "tdb2", options = mapOf("location" to "/data/tdb2"))
)

// Repository builder DSL (convenience)
val repo2 = Rdf.repository {
  providerId = "jena"
  variantId = "memory"
}
```

### Configuration Model

```kotlin
// Repository configuration
val config = RdfConfig(
    providerId = "jena",
    variantId = "tdb2",
    options = mapOf("location" to "/data/tdb2")
)
```

### Variant Discovery

```kotlin
val providers = RdfProviderRegistry.discoverProviders()
providers.forEach { provider ->
    provider.variants().forEach { variant ->
        println("${provider.id}:${variant.id} — ${variant.description}")
        if (variant.defaultOptions.isNotEmpty()) {
            println("  defaults: ${variant.defaultOptions}")
        }
    }
}
```

### Discovery
`RdfProviderRegistry` finds providers via Java `ServiceLoader`. You can also register a provider programmatically.




