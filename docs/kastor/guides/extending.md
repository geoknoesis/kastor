# How to Extend Kastor (SPI)

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** provider model → [Provider architecture](../features/enhanced-providers.md), [Repository architecture](../concepts/architecture.md). **Reference:** [`RdfProvider`](../api/core-api.md), [Android & KMP](android-kmp.md) when **`ServiceLoader`** is unavailable.

## Problem

- Plug in a custom **`RdfRepository`** implementation behind a stable **`providerId`** / **`variantId`** so **`Rdf.repository { }`** and tooling can select it like built-in Jena or RDF4J.

## Prerequisites

- **`rdf-core`** and familiarity with **`RdfRepository`**, **`RdfConfig`**, and **`ProviderCapabilities`**.

## Steps

### Step 1: Implement `RdfProvider`

`MyRepository` in the snippet below is **your** `RdfRepository` type (graph edits, SPARQL, lifecycle).

```kotlin
import com.geoknoesis.kastor.rdf.ProviderCapabilities
import com.geoknoesis.kastor.rdf.ProviderCategory
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfProvider
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfVariant

class MyProvider : RdfProvider {
    override val id: String = "my"
    override val name: String = "My Custom Provider"
    override val version: String = "1.0.0"

    override fun variants(): List<RdfVariant> = listOf(RdfVariant("memory", "In-memory backend"))

    override fun createRepository(variantId: String, config: RdfConfig): RdfRepository = when (variantId) {
        "memory" -> MyRepository()
        else -> error("Unsupported variant: $variantId")
    }

    override fun getCapabilities(variantId: String?): ProviderCapabilities {
        return ProviderCapabilities(
            sparqlVersion = "1.1",
            supportsRdfStar = false,
            supportsPropertyPaths = true,
            supportsAggregation = true,
            supportsServiceDescription = false,
        )
    }

    override fun getProviderCategory(): ProviderCategory = ProviderCategory.RDF_STORE
}
```

`MyRepository` must satisfy **`RdfRepository`** (dataset, SPARQL, graph lifecycle, …).

### Step 2: Register the provider

#### Option A: `ServiceLoader` (JVM)

Create **`META-INF/services/com.geoknoesis.kastor.rdf.RdfProvider`** containing one line:

```text
com.example.MyProvider
```

Package that resource with your module. Discovery runs when **`DefaultProviderRegistry`** starts with **`autoDiscover = true`** (default on JVM).

This path is often **unsuitable for Android and KMP native**—see Option B and [Android & KMP](android-kmp.md).

#### Option B: Explicit registration (recommended on Android / KMP)

```kotlin
import com.geoknoesis.kastor.rdf.RdfProviderRegistry

RdfProviderRegistry.register(MyProvider())
```

### Step 3: Select it from application code

```kotlin
import com.geoknoesis.kastor.rdf.Rdf

val repo = Rdf.repository {
    providerId = "my"
    variantId = "memory"
}
```

## Validation

- **`RdfProviderRegistry.supports("my")`** (or your id) returns **`true`** after registration.
- **`Rdf.repository { providerId = "my"; variantId = "memory" }`** returns a working **`RdfRepository`**.

## Troubleshooting

- **`No provider found`:** Register before first use; check **`providerId`** / **`variantId`** strings match **`override val id`** and **`variants()`**.
- **Android / iOS builds:** Prefer Option B; disable **`autoDiscover`** in tests if **`ServiceLoader`** noise obscures failures.

## Related

- [Android & KMP](android-kmp.md)
- [Providers overview](../providers/README.md)
- [Factory DSL](../reference/factory.md)
