# Android & Kotlin Multiplatform (KMP) Support

Kastor is designed to work on Android and Kotlin Multiplatform (KMP) targets, but there are important considerations regarding provider discovery.

## âš ï¸ ServiceLoader Limitations

Java's `ServiceLoader` mechanism, which is used by default for automatic provider discovery, has limitations on Android and some KMP targets:

- **Android**: ServiceLoader requires reflection configuration and may not work reliably without ProGuard/R8 rules
- **KMP Native**: ServiceLoader is not available on native targets (iOS, macOS, Linux, Windows)
- **KMP JS**: ServiceLoader behavior may vary across JavaScript runtimes

## âœ… Solution: Explicit Provider Registration

For Android and KMP projects, **explicitly register providers** instead of relying on ServiceLoader auto-discovery.

### Basic Setup

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.*  // or rdf-rdf4j, rdf-sparql, etc.

// Initialize providers explicitly (do this early in your app lifecycle)
fun initializeKastor() {
    // Register Jena provider
    RdfProviderRegistry.register(JenaProvider())
    
    // Or register multiple providers at once
    RdfProviderRegistry.registerAll(
        JenaProvider(),
        // Rdf4jProvider(),
        // SparqlEndpointProvider()
    )
}
```

### Android Application Example

```kotlin
import android.app.Application
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.*

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Kastor providers
        initializeKastor()
    }
    
    private fun initializeKastor() {
        // Register providers explicitly for Android
        RdfProviderRegistry.register(JenaProvider())
        
        // Optionally check if registration succeeded
        if (RdfProviderRegistry.supports("jena")) {
            println("Jena provider registered successfully")
        }
    }
}
```

### KMP Shared Code Example

```kotlin
// commonMain/kotlin/AppInitializer.kt
import com.geoknoesis.kastor.rdf.*

expect fun initializeKastorProviders()

// androidMain/kotlin/AppInitializer.android.kt
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.*

actual fun initializeKastorProviders() {
    // Android: Use JVM providers
    RdfProviderRegistry.register(JenaProvider())
}

// iosMain/kotlin/AppInitializer.ios.kt
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryRepositoryProvider

actual fun initializeKastorProviders() {
    // iOS: Use memory provider (native providers may be limited)
    RdfProviderRegistry.register(MemoryRepositoryProvider())
}
```

### Custom Registry (Advanced)

For complete control, create a custom registry with auto-discovery disabled:

```kotlin
import com.geoknoesis.kastor.rdf.*

// Create registry without ServiceLoader discovery
val customRegistry = DefaultProviderRegistry(
    autoDiscover = false,  // Disable ServiceLoader
    registerDefaultMemoryProvider = true  // Still register memory provider
)

// Register providers explicitly
customRegistry.register(JenaProvider())
customRegistry.register(MyCustomProvider())

// Use the custom registry
val repo = Rdf.repository(customRegistry) {
    providerId = "jena"
    variantId = "memory"
}
```

## ðŸ“± Android-Specific Considerations

### ProGuard/R8 Rules

If you use ProGuard or R8, you may need to add rules to keep provider classes:

```proguard
# Keep RDF provider classes
-keep class com.geoknoesis.kastor.rdf.** { *; }
-keep class com.geoknoesis.kastor.rdf.jena.** { *; }
-keep class com.geoknoesis.kastor.rdf.rdf4j.** { *; }

# If using ServiceLoader (not recommended for Android)
-keep class * implements com.geoknoesis.kastor.rdf.RdfProvider
-keep class * implements com.geoknoesis.kastor.rdf.reasoning.RdfReasonerProvider
-keep class * implements com.geoknoesis.kastor.rdf.shacl.ShaclValidatorProvider
```

**Note**: Explicit registration avoids the need for most of these rules.

### Checking Provider Availability

```kotlin
// Check if a provider is available
if (RdfProviderRegistry.supports("jena")) {
    val repo = Rdf.repository {
        providerId = "jena"
        variantId = "memory"
    }
} else {
    // Fallback to memory provider
    val repo = Rdf.memory()
}
```

## ðŸŽ¯ KMP Native Targets

For KMP native targets (iOS, macOS, etc.), provider options are more limited:

```kotlin
// commonMain/kotlin/RepositoryFactory.kt
expect fun createRepository(): RdfRepository

// nativeMain/kotlin/RepositoryFactory.native.kt
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryRepositoryProvider

actual fun createRepository(): RdfRepository {
    // Register memory provider (most compatible for native)
    RdfProviderRegistry.register(MemoryRepositoryProvider())
    
    return Rdf.memory()
}
```

## ðŸ” Other Registries

Kastor also uses ServiceLoader for other registries. Register them explicitly if needed:

### Reasoner Registry

```kotlin
import com.geoknoesis.kastor.rdf.reasoning.*

// Register reasoner providers explicitly
ReasonerRegistry.register(MyReasonerProvider())
```

### SHACL Validator Registry

```kotlin
import com.geoknoesis.kastor.rdf.shacl.*

// Register validator providers explicitly
ValidatorRegistry.register(MyValidatorProvider())
```

## ðŸ“ Best Practices

1. **Always register providers explicitly** on Android and KMP native targets
2. **Initialize early** in your application lifecycle (e.g., `Application.onCreate()`)
3. **Check provider availability** before using provider-specific features
4. **Use memory provider as fallback** for maximum compatibility
5. **Test on target platforms** to ensure providers work correctly

## ðŸ› Troubleshooting

### Providers Not Found

If you get "No provider found" errors:

1. **Check registration**: Ensure providers are registered before use
2. **Check dependencies**: Ensure provider modules are included in your build
3. **Check initialization order**: Register providers before creating repositories

```kotlin
// âŒ Wrong: Using provider before registration
val repo = Rdf.repository { providerId = "jena" }  // May fail

// âœ… Correct: Register first
RdfProviderRegistry.register(JenaProvider())
val repo = Rdf.repository { providerId = "jena" }  // Works
```

### ServiceLoader Errors on Android

If you see ServiceLoader-related errors:

1. **Disable auto-discovery**: Use `DefaultProviderRegistry(autoDiscover = false)`
2. **Register explicitly**: Use `RdfProviderRegistry.register()` for all providers
3. **Add ProGuard rules**: If you must use ServiceLoader, add the rules above

## ðŸ“š Related Documentation

- [Extending Kastor](./extending.md) - How to create custom providers
- [Factory DSL](../reference/factory.md) - Repository creation patterns
- [Provider Architecture](../features/enhanced-providers.md) - Provider capabilities


