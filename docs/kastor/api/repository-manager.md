# Managing Multiple Repositories

Kastor does not provide a built‑in repository manager. Instead, you compose multiple
repositories explicitly in your application and manage their lifecycle directly.

## Overview

Recommended pattern:
- Create repositories with `Rdf.repository { ... }` or `RdfProviderRegistry.create(...)`
- Store them in a map keyed by purpose (e.g., `"users"`, `"analytics"`)
- Close them at shutdown

## Basic Usage

```kotlin
import com.geoknoesis.kastor.rdf.*

val repositories = mapOf(
    "users" to Rdf.repository {
        providerId = "jena"
        variantId = "memory"
    },
    "products" to Rdf.repository {
        providerId = "rdf4j"
        variantId = "native"
        location = "./data/products"
    },
    "external" to RdfProviderRegistry.create(
        RdfConfig(
            providerId = "sparql",
            variantId = "sparql",
            options = mapOf("location" to "https://dbpedia.org/sparql")
        )
    )
)
```

## Lifecycle Management

```kotlin
repositories.values.forEach { repo ->
    repo.close()
}
```

## Provider Selection by Requirements

Use `ProviderRequirements` with `RdfProviderRegistry.selectProvider` if you want
automatic selection based on capabilities.

```kotlin
val selection = RdfProviderRegistry.selectProvider(
    ProviderRequirements(supportsTransactions = true, supportsNamedGraphs = true)
)
val repo = selection?.let {
    RdfProviderRegistry.create(RdfConfig(providerId = it.provider.id, variantId = it.variantId))
}
```

## Federated Queries

Federated querying is provider‑dependent. If you need federation, use a SPARQL
provider that supports it and issue queries against that endpoint. Otherwise, compose
results in your application.

## Notes

If you need centralized management or federation, build it explicitly in your
application or via a SPARQL provider that supports federation.



