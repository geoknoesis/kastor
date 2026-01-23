## Extending (SPI)

You can add new providers by implementing the `RdfApiProvider` SPI.

### 1) Implement the provider
```kotlin
class MyProvider : RdfApiProvider {
  override val id: String = "my"

  override fun variants(): List<RdfVariant> = listOf(RdfVariant("memory"))

  override fun createRepository(variantId: String, config: RdfConfig): RdfRepository = when (variantId) {
    "memory" -> MyRepository()
    else -> error("Unsupported variant: ${'$'}variantId")
  }
}
```

### 2) Register via ServiceLoader
Create a file at `META-INF/services/com.geoknoesis.kastor.rdf.RdfApiProvider` with the single line:
```
com.example.MyProvider
```

Ensure the resource is packaged with your provider module.

### 3) Or register programmatically
```kotlin
RdfProviderRegistry.register(MyProvider())
```

### 4) Use it
```kotlin
val api = Rdf.repository {
  providerId = "my"
  variantId = "memory"
}
```




