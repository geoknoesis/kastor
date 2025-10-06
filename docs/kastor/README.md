# Kastor RDF Framework

Kastor is a modern Kotlin RDF framework that makes semantic web development accessible, powerful, and enjoyable. Built with Kotlin-first design principles, it provides a natural language DSL, type-safe operations, and pluggable backends.

## 🌟 Features

### **Core Capabilities**
- **Natural Language DSL**: `person has name with "Alice"` instead of verbose RDF APIs
- **Type Safety**: Compile-time validation and type-safe query results
- **Multiple Backends**: Jena, RDF4J, Memory, and SPARQL providers
- **Modern Kotlin**: Leverages Kotlin's strengths with extension functions and sealed classes
- **Performance**: Optimized for speed with streaming and parallel processing

### **Advanced Features**
- **Reasoning**: RDFS, OWL-EL, OWL-RL, and OWL-DL inference
- **SHACL Validation**: Data validation and constraint checking
- **Transactions**: ACID compliance for data integrity
- **Repository Management**: Multi-repository setup and federation
- **OntoMapper Integration**: Code generation from ontologies

## 🚀 Quick Start

### 5-Minute Setup

```kotlin
// Add to your build.gradle.kts
dependencies {
    implementation("com.geoknoesis.kastor:rdf-core:0.1.0")
    implementation("com.geoknoesis.kastor:rdf-jena:0.1.0") // or rdf-rdf4j
}
```

### Your First RDF Program

```kotlin
import com.geoknoesis.kastor.rdf.*

fun main() {
    // Create a repository
    val repo = Rdf.memory()
    
    // Add some data using natural language DSL
    repo.add {
        val alice = iri("http://example.org/alice")
        alice has name with "Alice Johnson"
        alice has age with 30
        alice has email with "alice@example.org"
        alice is "http://xmlns.com/foaf/0.1/Person"
    }
    
    // Query the data
    val results = repo.query("""
        SELECT ?name ?age WHERE {
            ?person foaf:name ?name .
            ?person ex:age ?age .
        }
    """)
    
    results.forEach { binding ->
        println("${binding.getString("name")} is ${binding.getInt("age")} years old")
    }
}
```

## 📚 Documentation Structure

### **Getting Started**
- **[Getting Started Guide](getting-started/README.md)** - Complete setup and introduction
- **[Quick Start Examples](getting-started/quick-start.md)** - Copy-paste examples for immediate success
- **[Hello World Tutorial](getting-started/hello-world.md)** - Step-by-step first program

### **Core Features**
- **[DSL Guide](features/compact-dsl-guide.md)** - Natural language syntax
- **[Reasoning](features/reasoning.md)** - RDFS, OWL inference capabilities
- **[SHACL Validation](features/shacl-validation.md)** - Data validation and constraints
- **[RDF-star Support](features/rdf-star.md)** - Metadata about statements

### **Guides**
- **[Provider Overview](guides/providers-overview.md)** - Available backends comparison
- **[Performance Guide](guides/performance.md)** - Optimization strategies
- **[Serialization Formats](guides/formats.md)** - RDF format support
- **[Best Practices](guides/best-practices.md)** - Usage guidelines and patterns
- **[Extending Kastor](guides/extending.md)** - Custom providers and plugins

### **Resources**
- **[Vocabularies](resources/vocabularies.md)** - Working with RDF vocabularies
- **[RDF Fundamentals](resources/rdf-fundamentals.md)** - Understanding RDF basics
- **[SPARQL Fundamentals](resources/sparql-fundamentals.md)** - Query language introduction
- **[Troubleshooting](resources/troubleshooting.md)** - Common issues and solutions
- **[FAQ](resources/faq.md)** - Frequently asked questions

## 🎯 Use Cases

### **Data Catalogs**
Build comprehensive data catalogs with DCAT vocabulary support and validation.

```kotlin
val catalog = Rdf.graph {
    val dataset = iri("http://example.org/dataset1")
    dataset is "http://www.w3.org/ns/dcat#Dataset"
    dataset has title with "My Dataset"
    dataset has description with "A sample dataset"
    dataset has contactPoint with "admin@example.org"
}
```

### **Knowledge Graphs**
Create and query knowledge graphs with reasoning capabilities.

```kotlin
val graph = Rdf.graph {
    val person = iri("http://example.org/alice")
    val employee = iri("http://example.org/Employee")
    
    person is employee
    employee subclassOf "http://xmlns.com/foaf/0.1/Person"
}

// Reasoning will infer that Alice is a Person
val reasoner = RdfReasoning.reasoner(ReasonerType.RDFS)
val result = reasoner.reason(graph)
```

### **Data Validation**
Validate data quality with SHACL constraints.

```kotlin
val validator = ShaclValidation.validator()
val report = validator.validate(dataGraph, shapesGraph)

if (!report.isValid) {
    report.violations.forEach { violation ->
        println("Validation error: ${violation.message}")
    }
}
```

### **Web Applications**
Integrate with SPARQL endpoints and build web services.

```kotlin
val repo = Rdf.factory {
    sparql("https://dbpedia.org/sparql")
}

val results = repo.query("""
    SELECT ?name WHERE {
        ?person rdfs:label ?name .
        ?person dbo:birthPlace <http://dbpedia.org/resource/Paris> .
    } LIMIT 10
""")
```

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Kastor RDF Framework                     │
├─────────────────────────────────────────────────────────────┤
│  Natural Language DSL  │  Type Safety  │  Modern Kotlin    │
├─────────────────────────────────────────────────────────────┤
│              Core API Layer (rdf-core)                     │
├─────────────────────────────────────────────────────────────┤
│  Reasoning  │  SHACL Validation  │  Repository Manager     │
├─────────────────────────────────────────────────────────────┤
│              Provider Layer (pluggable)                     │
├─────────────────────────────────────────────────────────────┤
│  Memory  │  Jena  │  RDF4J  │  SPARQL  │  Custom Providers │
└─────────────────────────────────────────────────────────────┘
```

## 🔌 Backend Providers

| Provider | Use Case | Persistence | Performance | Features |
|----------|----------|-------------|-------------|----------|
| **Memory** | Development, Testing | ❌ | ⭐⭐⭐⭐⭐ | Fast, Simple |
| **Jena** | Production, TDB2 | ✅ | ⭐⭐⭐⭐ | Full RDF support |
| **RDF4J** | Production, Native | ✅ | ⭐⭐⭐⭐ | Enterprise features |
| **SPARQL** | Remote Data | ✅ | ⭐⭐⭐ | Federation support |

## 🚀 Getting Started Paths

### **New to RDF?**
1. [RDF Fundamentals](resources/rdf-fundamentals.md) - Learn RDF basics
2. [Getting Started Guide](getting-started/README.md) - Setup and first steps
3. [Hello World Tutorial](getting-started/hello-world.md) - Your first program
4. [DSL Guide](features/compact-dsl-guide.md) - Natural language syntax

### **RDF Expert?**
1. [Provider Overview](guides/providers-overview.md) - Choose your backend
2. [Performance Guide](guides/performance.md) - Optimization strategies
3. [Reasoning](features/reasoning.md) - RDFS, OWL inference capabilities
4. [SHACL Validation](features/shacl-validation.md) - Data validation

### **Application Developer?**
1. [Getting Started Guide](getting-started/README.md) - Quick setup
2. [DSL Guide](features/compact-dsl-guide.md) - Natural language syntax
3. [Provider Overview](guides/providers-overview.md) - Backend comparison
4. [Best Practices](guides/best-practices.md) - Production guidelines

## 🌐 Community & Support

### **About Kastor**
**Kastor** is developed by **[GeoKnoesis LLC](https://geoknoesis.com/)**, a company specializing in semantic web technologies and knowledge engineering.

**Main Developer**: **Stephane Fellah** - [stephanef@geoknoesis.com](mailto:stephanef@geoknoesis.com)

### **Getting Help**
- **[GitHub Repository](https://github.com/geoknoesis/kastor)** - Source code and issues
- **[GitHub Discussions](https://github.com/geoknoesis/kastor/discussions)** - Community support
- **[Stack Overflow](https://stackoverflow.com/questions/tagged/kastor)** - Technical questions
- **[FAQ](resources/faq.md)** - Frequently asked questions
- **Direct Contact**: [stephanef@geoknoesis.com](mailto:stephanef@geoknoesis.com)

### **Support the Project**
If you find Kastor useful, please consider supporting its development:

- 🌟 **Star the repository** on [GitHub](https://github.com/geoknoesis/kastor)
- 💰 **Sponsor the project** on [GitHub Sponsors](https://github.com/sponsors/geoknoesis)
- ☕ **Buy us a coffee** via [Ko-fi](https://ko-fi.com/geoknoesis)
- 🏢 **Enterprise support** - Contact us for commercial licensing and support options

## 📄 License

Kastor is open source and available under the [MIT License](../LICENSE).

---

**Ready to get started?** Check out the [Getting Started Guide](getting-started/README.md) or jump right into [Hello World](getting-started/hello-world.md)!