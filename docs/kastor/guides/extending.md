## Extending (SPI)

You can add new providers by implementing the `RdfApiProvider` SPI.

### 1) Implement the provider
```kotlin
class MyProvider : RdfApiProvider {
  override val id: String = "my"
  override fun variants(): List<RdfConfigVariant> = listOf(
    RdfConfigVariant(
      type = "my:memory",
      description = "In-memory store",
    ),
  )
  override fun create(config: RdfConfig): RdfApi = when (config.type) {
    "my:memory" -> SimpleRdfApi(MyRepository())
    else -> error("Unsupported variant: ${'$'}{config.type}")
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
RdfApiRegistry.register(MyProvider())
```

### 4) Use it
```kotlin
val api = Rdf.factory { type("my:memory") }
```

