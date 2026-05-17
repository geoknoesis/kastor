# Android and Kotlin Multiplatform (KMP)

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** providers and `ServiceLoader` → [Provider architecture](../features/enhanced-providers.md), [**Glossary**](../concepts/glossary.md). **Reference:** [Extending Kastor](extending.md), [Factory DSL](../reference/factory.md).

## Problem

- Run Kastor on **Android** or **KMP** targets where **`ServiceLoader`** discovery is missing, flaky (R8/ProGuard), or undesirable.
- Register **`RdfProvider`** (and optionally **reasoner** / **SHACL** providers) **explicitly** before creating repositories.

## Prerequisites

- Dependencies on **`rdf-core`** plus the provider artifacts you actually use (for example **`rdf-jena`**, **`rdf-rdf4j`**, **`rdf-sparql`**), aligned at **`0.2.0`** or via the BOM ([Installation](../getting-started/installation.md)).
- If you use the Kotlin **`select { }`** SPARQL DSL or anything under **`com.geoknoesis.kastor.rdf.sparql`**, add **`sparql-lang`** (`com.geoknoesis.kastor:sparql-lang`). For **`shacl {}`** / **`Rdf.shacl`**, add **`rdf-shacl-dsl`** — see [Repository architecture — Dependency profiles](../concepts/architecture.md#dependency-profiles-gradle).

## Steps

### Step 1: Register RDF providers early

On JVM desktop, `META-INF/services` discovery often works. On **Android** and many **KMP** targets, call **`RdfProviderRegistry.register(...)`** during startup **before** any **`Rdf.repository { }`** / **`Rdf.memory()`** that relies on those providers.

```kotlin
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.RdfProviderRegistry

fun initializeKastor() {
    RdfProviderRegistry.register(JenaProvider())
    // RdfProviderRegistry.register(Rdf4jProvider())
}
```

Several providers:

```kotlin
RdfProviderRegistry.registerAll(
    JenaProvider(),
    // Rdf4jProvider(),
)
```
### Step 2: Android `Application` initialization

```kotlin
import android.app.Application
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.RdfProviderRegistry

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RdfProviderRegistry.register(JenaProvider())
        check(RdfProviderRegistry.supports("jena")) { "Jena provider failed to register" }
    }
}
```

Register the **`Application`** class in **`AndroidManifest.xml`** (`android:name`).

### Step 3: KMP `expect` / `actual` wiring

```kotlin
// commonMain — declare platform entrypoint
expect fun initializeKastorProviders()

// androidMain
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.RdfProviderRegistry

actual fun initializeKastorProviders() {
    RdfProviderRegistry.register(JenaProvider())
}

// iosMain (example: memory-only until a native store exists)
import com.geoknoesis.kastor.rdf.RdfProviderRegistry
import com.geoknoesis.kastor.rdf.provider.MemoryRepositoryProvider

actual fun initializeKastorProviders() {
    RdfProviderRegistry.register(MemoryRepositoryProvider())
}
```

Invoke **`initializeKastorProviders()`** from your composable / activity / entry controller **before** RDF calls.

### Step 4 (optional): Custom registry without ServiceLoader

For tests or fully controlled deployments, build a **`DefaultProviderRegistry`** with **`autoDiscover = false`** and pass it into **`Rdf.repository(registry) { … }`**.

```kotlin
import com.geoknoesis.kastor.rdf.DefaultProviderRegistry
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.Rdf

val customRegistry = DefaultProviderRegistry(
    autoDiscover = false,
    registerDefaultMemoryProvider = true,
).also { it.register(JenaProvider()) }

val repo = Rdf.repository(customRegistry) {
    providerId = "jena"
    variantId = "memory"
}
```

### Step 5 (optional): Reasoner and SHACL validator registries

They also discover via **`ServiceLoader`** on JVM. On constrained runtimes, register packaged providers explicitly:

```kotlin
import com.geoknoesis.kastor.rdf.jena.reasoning.JenaReasonerProvider
import com.geoknoesis.kastor.rdf.reasoning.ReasonerRegistry
import com.geoknoesis.kastor.rdf.shacl.ValidatorRegistry
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider

ReasonerRegistry.register(JenaReasonerProvider())
ValidatorRegistry.register(NativeShaclValidatorProvider())
```

Only depend on **`reasoning`**, **`shacl-validation`**, **`jena-reasoning`** (for `JenaReasonerProvider`), and bridge modules that ship these classes when you need them.

### Step 6: ProGuard / R8 (Android)

Explicit registration avoids most reflection rules. If you still rely on **`ServiceLoader`**, keep provider classes:

```proguard
-keep class com.geoknoesis.kastor.rdf.** { *; }
-keep class com.geoknoesis.kastor.rdf.jena.** { *; }
-keep class com.geoknoesis.kastor.rdf.rdf4j.** { *; }
-keep class * implements com.geoknoesis.kastor.rdf.RdfProvider
```

### Step 7: Fallback when a provider is missing

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfProviderRegistry

val repo = if (RdfProviderRegistry.supports("jena")) {
    Rdf.repository {
        providerId = "jena"
        variantId = "memory"
    }
} else {
    Rdf.memory()
}
```

## Validation

- After startup, **`RdfProviderRegistry.supports("jena")`** (or your provider id) is **`true`**.
- Creating **`Rdf.repository { providerId = "…" }`** succeeds without **`No provider found`**.

## Troubleshooting

- **`IllegalArgumentException: No provider found`:** Register that **`RdfProvider`** before first use; confirm the artifact is on the classpath for the target source set.
- **ServiceLoader class-not-found / R8:** Prefer **`autoDiscover = false`** plus explicit **`register`**, or widen keep rules.
- **Native targets:** Expect **memory** or explicitly linked providers only—full Jena/RDF4J may be unavailable.

## Related

- [Extending Kastor](extending.md)
- [Factory DSL](../reference/factory.md)
- [Provider architecture](../features/enhanced-providers.md)
