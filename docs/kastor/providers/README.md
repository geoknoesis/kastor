# Backend Providers

Kastor uses a pluggable provider architecture that allows you to choose the best backend for your specific use case. Each provider offers different trade-offs in terms of performance, persistence, and features.

## 🏗️ Provider Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Kastor API Layer                         │
├─────────────────────────────────────────────────────────────┤
│              Provider Interface (RdfProvider)              │
├─────────────────────────────────────────────────────────────┤
│  Memory  │  Jena  │  RDF4J  │  SPARQL  │  Custom Providers │
└─────────────────────────────────────────────────────────────┘
```

## 📊 Provider Comparison

| Provider | Persistence | Performance | Memory Usage | Features | Best For |
|----------|-------------|-------------|--------------|----------|----------|
| **[Memory](memory.md)** | ❌ | ⭐⭐⭐⭐⭐ | High | Basic operations, RDF-star | Development, Testing |
| **[Jena](jena.md)** | ✅ | ⭐⭐⭐⭐ | Medium | Full RDF support, RDF-star | Production, TDB2 |
| **[RDF4J](rdf4j.md)** | ✅ | ⭐⭐⭐⭐ | Medium | Enterprise features, RDF-star | Production, Native |
| **[SPARQL](sparql.md)** | ✅ | ⭐⭐⭐ | Low | Federation | Remote data, Web |

## 🚀 Quick Provider Selection

How a provider is chosen:

- `Rdf.memory()` returns Jena's in-memory store when `rdf-jena` is on the classpath, otherwise RDF4J's, and throws `RdfProviderException` if neither is present. It never falls back to the graph-only `memory` provider.
- `Rdf.repository { providerId = ...; variantId = ... }` uses exactly that provider and variant. An unknown provider, an unsupported variant or unmet `requirements` throw `IllegalArgumentException` instead of silently selecting another provider.
- A configuration with `requirements` but no `providerId` picks the matching provider with the highest `RdfProvider.priority` (Jena 50, RDF4J 40, memory −100).
- A configuration with neither uses the default provider (`memory` unless changed with `Rdf.setDefaultProvider`).

### **Development & Testing**
```kotlin
val repo = Rdf.memory() // In-memory Jena (or RDF4J) store
```

### **Production with Persistence**
```kotlin
val repo = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2"
    location = "./data/storage"
}
```

### **Enterprise Features**
```kotlin
val repo = Rdf.repository {
    providerId = "rdf4j"
    variantId = "native"
    location = "./data/store"
}
```

### **Remote Data Access**
```kotlin
val repo = RdfProviderRegistry.create(
    RdfConfig(
        providerId = "sparql",
        variantId = "sparql",
        options = mapOf("location" to "https://dbpedia.org/sparql")
    )
)
```

## 📋 Provider Features

### **Core Features**

| Feature | Memory | Jena | RDF4J | SPARQL |
|---------|--------|------|-------|--------|
| **Triple Storage** | ✅ | ✅ | ✅ | ✅ |
| **SPARQL Queries** | ✅ | ✅ | ✅ | ✅ |
| **Transactions** | ✅ | ✅ | ✅ | ❌ |
| **Inference** | ✅ | ✅ | ✅ | ✅ |
| **SHACL Validation** | ✅ | ✅ | ✅ | ❌ |
| **RDF 1.2 Triple Terms** | ✅ | ✅ | ✅ | ❌ |
| **Federation** | ❌ | ❌ | ❌ | ✅ |
| **Persistence** | ❌ | ✅ | ✅ | ✅ |

### **Advanced Features**

| Feature | Memory | Jena | RDF4J | SPARQL |
|---------|--------|------|-------|--------|
| **Indexing** | Basic | Advanced | Advanced | Server-dependent |
| **Compression** | ❌ | ✅ | ✅ | Server-dependent |
| **Backup/Restore** | ❌ | ✅ | ✅ | Server-dependent |
| **Clustering** | ❌ | ✅ | ✅ | Server-dependent |
| **Security** | ❌ | ✅ | ✅ | Server-dependent |

## 🔧 Configuration Examples

### **Memory Provider**
```kotlin
val repo = Rdf.memory()
```

### **Jena Provider**
```kotlin
val repo = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2-inference"
    location = "./data/storage"
}
```

### **RDF4J Provider**
```kotlin
val repo = Rdf.repository {
    providerId = "rdf4j"
    variantId = "native"
    location = "./data/store"
}
```

### **SPARQL Provider**
```kotlin
val repo = RdfProviderRegistry.create(
    RdfConfig(
        providerId = "sparql",
        variantId = "sparql",
        options = mapOf("location" to "https://dbpedia.org/sparql")
    )
)
```

## 🎯 Use Case Recommendations

### **Development & Testing**
- **Memory Provider**: Fast, no setup, perfect for unit tests
- **Use when**: Building and testing applications, prototyping

### **Small to Medium Applications**
- **Jena Provider**: Good balance of features and performance
- **Use when**: Need persistence, moderate data size (< 10GB)

### **Enterprise Applications**
- **RDF4J Provider**: Advanced features, clustering support
- **Use when**: Need enterprise features, large datasets, high availability

### **Data Integration**
- **SPARQL Provider**: Access remote data, federation
- **Use when**: Querying external datasets, building data catalogs

### **Hybrid Approaches**
```kotlin
val repositories = mapOf(
    "dev" to Rdf.memory(),
    "prod" to Rdf.repository {
        providerId = "jena"
        variantId = "tdb2"
        location = "./data/prod"
    },
    "external" to RdfProviderRegistry.create(
        RdfConfig(
            providerId = "sparql",
            variantId = "sparql",
            options = mapOf("location" to "https://api.example.com/sparql")
        )
    )
)
```

## 🌟 RDF-star Support

Kastor follows the RDF 1.2 model: triple terms (`<<( s p o )>>`) appear only in object position, and statement metadata is attached to a reifier (`_:r rdf:reifies <<( s p o )>>`). See [RDF-star and triple terms](../features/rdf-star.md).

### **Supported Providers**
- **Memory Provider**: ✅ RDF 1.2 triple terms (graph-only: no parsing, serialization or SPARQL)
- **Jena Provider**: ✅ RDF 1.2 triple terms
- **RDF4J Provider**: ✅ RDF 1.2 triple terms
- **SPARQL Provider**: ❌ The HTTP adapter does not decode triple terms from results

### **RDF 1.2 Reifier Example**
```kotlin
val repo = Rdf.repository {
    providerId = "jena"
    variantId = "memory"
}

repo.add {
    val alice = iri("http://example.org/alice")
    val bob = iri("http://example.org/bob")

    // Basic fact
    alice - FOAF.knows - bob

    // Metadata about the statement: _:r rdf:reifies <<( alice foaf:knows bob )>>
    reifies(alice, FOAF.knows, bob) { r ->
        r - DCTERMS.source - "LinkedIn"
        r - iri("http://example.org/confidence") - 0.95
    }
}
```

### **Checking Triple-Term Support**
```kotlin
val capabilities = repo.getCapabilities()

if (capabilities.supportsTripleTerms) {
    println("RDF 1.2 triple terms are supported")
} else {
    println("Triple terms are not supported by this provider")
}
```

## 🔄 Migration Between Providers

### **Memory to Jena**
```kotlin
// Start with memory
val memoryRepo = Rdf.memory()
// ... populate with data ...

// Migrate to Jena
val jenaRepo = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2"
    location = "./data/storage"
}

// Copy all data
val data = memoryRepo.defaultGraph.serialize(RdfFormat.TURTLE)
val graph = Rdf.parse(data, RdfFormat.TURTLE)
jenaRepo.addTriples(graph.getTriples())
```

### **Jena to RDF4J**
```kotlin
// Export from Jena
val jenaRepo = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2"
    location = "./data/jena"
}
val data = jenaRepo.defaultGraph.serialize(RdfFormat.TURTLE)

// Import to RDF4J
val rdf4jRepo = Rdf.repository {
    providerId = "rdf4j"
    variantId = "native"
    location = "./data/rdf4j"
}
val graph = Rdf.parse(data, RdfFormat.TURTLE)
rdf4jRepo.addTriples(graph.getTriples())
```

## 🛠️ Custom Providers

Create your own provider by implementing `RdfProvider`:

```kotlin
class CustomProvider : RdfProvider {
    override val id: String = "custom"
    override val name: String = "Custom Provider"
    override val version: String = "1.0.0"
    
    override fun variants(): List<RdfVariant> = listOf(RdfVariant("default"))
    
    override fun createRepository(variantId: String, config: RdfConfig): RdfRepository {
        // Your custom implementation
        return CustomRepository(config)
    }
}

// Register and use
RdfProviderRegistry.register(CustomProvider())
val repo = RdfProviderRegistry.create(
    RdfConfig(providerId = "custom", variantId = "default")
)
```

## 📈 Performance Guidelines

### **Memory Provider**
- **Pros**: Extremely fast, simple
- **Cons**: Limited by RAM, no persistence
- **Best for**: < 1GB data, development

### **Jena Provider**
- **Pros**: Good performance, full features
- **Cons**: Higher memory usage
- **Best for**: 1GB - 100GB data, production

### **RDF4J Provider**
- **Pros**: Enterprise features, clustering
- **Cons**: Setup complexity
- **Best for**: > 100GB data, enterprise

### **SPARQL Provider**
- **Pros**: No local storage, federation
- **Cons**: Network latency, server dependency
- **Best for**: Remote data access, data integration

## 🔍 Troubleshooting

### **Memory Issues**
- Use memory provider only for small datasets
- Consider streaming for large operations
- Monitor GC pressure in production

### **Performance Issues**
- Enable indexing for complex queries
- Use appropriate transaction boundaries
- Consider caching for frequently accessed data

### **Persistence Issues**
- Ensure proper backup procedures
- Monitor disk space and I/O
- Use appropriate file system settings

## 📚 Additional Resources

- **[Memory Provider](memory.md)** - Detailed memory provider documentation
- **[Jena Provider](jena.md)** - Apache Jena integration guide
- **[RDF4J Provider](rdf4j.md)** - Eclipse RDF4J backend documentation
- **[SPARQL Provider](sparql.md)** - Remote SPARQL endpoint guide
- **[Performance Guide](../advanced/performance.md)** - Optimization strategies

---

**Need help choosing a provider?** Check the [FAQ](../guides/faq.md) or [ask the community](https://github.com/geoknoesis/kastor/discussions)!


