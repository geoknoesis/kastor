## Extending (SPI)

You can add new providers by implementing the `RdfProvider` SPI.

### 1) Implement the provider
```kotlin
class MyProvider : RdfProvider {
  override val id: String = "my"
  override val name: String = "My Custom Provider"
  override val version: String = "1.0.0"

  override fun variants(): List<RdfVariant> = listOf(RdfVariant("memory"))

  override fun createRepository(variantId: String, config: RdfConfig): RdfRepository = when (variantId) {
    "memory" -> MyRepository()
    else -> error("Unsupported variant: ${'$'}variantId")
  }
  
  override fun getCapabilities(variantId: String?): ProviderCapabilities {
    return ProviderCapabilities(
      sparqlVersion = "1.1",
      supportsRdfStar = false,
      supportsPropertyPaths = true,
      supportsAggregation = true,
      supportsServiceDescription = false
    )
  }
  
  override fun getProviderCategory(): ProviderCategory = ProviderCategory.RDF_STORE
}
```

### 2) Register the provider

#### Option A: ServiceLoader (JVM only)
Create a file at `META-INF/services/com.geoknoesis.kastor.rdf.RdfProvider` with the single line:
```
com.example.MyProvider
```

Ensure the resource is packaged with your provider module.

**⚠️ Note**: ServiceLoader may not work on Android or KMP native targets. See [Android/KMP Guide](./android-kmp.md) for alternatives.

#### Option B: Explicit Registration (Recommended for Android/KMP)
```kotlin
// Register programmatically (works everywhere)
RdfProviderRegistry.register(MyProvider())
```

**✅ Recommended**: Explicit registration works on all platforms including Android and KMP native.

### 3) Use it
```kotlin
val repo = Rdf.repository {
  providerId = "my"
  variantId = "memory"
}
```

### Platform-Specific Notes

- **JVM**: Both ServiceLoader and explicit registration work
- **Android**: Use explicit registration (see [Android/KMP Guide](./android-kmp.md))
- **KMP Native**: Use explicit registration only
- **KMP JS**: Use explicit registration for best compatibility




