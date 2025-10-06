# Backend Providers

Kastor uses a pluggable provider architecture that allows you to choose the best backend for your specific use case. Each provider offers different trade-offs in terms of performance, persistence, and features.

## 🏗️ Provider Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Kastor API Layer                         │
├─────────────────────────────────────────────────────────────┤
│              Provider Interface (RdfApiProvider)            │
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

### **Development & Testing**
```kotlin
val repo = Rdf.memory() // Fast, no setup required
```

### **Production with Persistence**
```kotlin
val repo = Rdf.factory {
    jenaTdb2("./data/storage") // Apache Jena with TDB2
}
```

### **Enterprise Features**
```kotlin
val repo = Rdf.factory {
    rdf4jNative("./data/store") // Eclipse RDF4J Native Store
}
```

### **Remote Data Access**
```kotlin
val repo = Rdf.factory {
    sparql("https://dbpedia.org/sparql") // Remote SPARQL endpoint
}
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
| **RDF-star Support** | ✅ | ✅ | ✅ | ❌ |
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
val repo = Rdf.factory {
    memory {
        transactions = true
        initialCapacity = 10000
        indexing = true
    }
}
```

### **Jena Provider**
```kotlin
val repo = Rdf.factory {
    jenaTdb2 {
        location = "./data/storage"
        enableInference = true
        enableValidation = true
        compression = true
    }
}
```

### **RDF4J Provider**
```kotlin
val repo = Rdf.factory {
    rdf4jNative {
        location = "./data/store"
        enableInference = true
        enableValidation = true
        indexing = true
    }
}
```

### **SPARQL Provider**
```kotlin
val repo = Rdf.factory {
    sparql {
        endpoint = "https://dbpedia.org/sparql"
        timeout = Duration.ofMinutes(5)
        authentication = BasicAuth("user", "pass")
        headers = mapOf("X-API-Key" to "key")
    }
}
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
val manager = Rdf.manager {
    // Local development data
    repository("dev") { memory() }
    
    // Production data
    repository("prod") { jenaTdb2("./data/prod") }
    
    // External data
    repository("external") { sparql("https://api.example.com/sparql") }
}
```

## 🌟 RDF-star Support

RDF-star enables representing metadata about statements by allowing triples to be quoted and used as subjects or objects in other triples.

### **Supported Providers**
- **Memory Provider**: ✅ Full RDF-star support
- **Jena Provider**: ✅ Full RDF-star support  
- **RDF4J Provider**: ✅ Full RDF-star support
- **SPARQL Provider**: ❌ Depends on endpoint support

### **RDF-star Usage Example**
```kotlin
val repo = Rdf.memory() // Memory provider supports RDF-star

repo.add {
    val alice = iri("http://example.org/alice")
    val bob = iri("http://example.org/bob")
    
    // Basic fact
    alice - FOAF.knows - bob
    
    // Metadata about the statement using RDF-star
    val statement = embedded(alice, FOAF.knows, bob)
    statement - DCTERMS.source - "LinkedIn"
    statement - iri("http://example.org/confidence") - 0.95
}
```

### **Checking RDF-star Support**
```kotlin
val repo = Rdf.memory()
val capabilities = repo.getCapabilities()

if (capabilities.supportsRdfStar) {
    // Use RDF-star features
    println("RDF-star is supported!")
} else {
    println("RDF-star is not supported by this provider")
}
```

## 🔄 Migration Between Providers

### **Memory to Jena**
```kotlin
// Start with memory
val memoryRepo = Rdf.memory()
// ... populate with data ...

// Migrate to Jena
val jenaRepo = Rdf.factory { jenaTdb2("./data/storage") }

// Copy all data
memoryRepo.query("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }").use { results ->
    jenaRepo.add(results)
}
```

### **Jena to RDF4J**
```kotlin
// Export from Jena
val jenaRepo = Rdf.factory { jenaTdb2("./data/jena") }
val data = jenaRepo.export(RdfFormat.TURTLE)

// Import to RDF4J
val rdf4jRepo = Rdf.factory { rdf4jNative("./data/rdf4j") }
rdf4jRepo.load(data, RdfFormat.TURTLE)
```

## 🛠️ Custom Providers

Create your own provider by implementing `RdfApiProvider`:

```kotlin
class CustomProvider : RdfApiProvider {
    override fun createRepository(config: RepositoryConfig): RdfRepository {
        // Your custom implementation
        return CustomRepository(config)
    }
    
    override fun getType(): String = "CUSTOM"
    override val name: String = "Custom Provider"
    override val version: String = "1.0.0"
}

// Register and use
Rdf.registerProvider(CustomProvider())
val repo = Rdf.factory { custom() }
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

**Need help choosing a provider?** Check the [FAQ](../faq.md) or [ask the community](https://github.com/geoknoesis/kastor/discussions)!