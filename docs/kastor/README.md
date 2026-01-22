# Kastor RDF Framework

Kastor is a modern Kotlin RDF framework that makes semantic web development accessible, powerful, and enjoyable. Built with Kotlin-first design principles, it provides a natural language DSL, type-safe operations, and pluggable backends.

## Why Kastor (in one sentence)
Use Kastor when you want **domain-first RDF** in Kotlin: pure domain interfaces with a side-channel back to RDF, plus a vocabulary-agnostic DSL that keeps RDF explicit without forcing RDF types into your business code.

## Philosophy & Tradeoffs
**Philosophy**
- **Domain-first, RDF second**: work with pure Kotlin domain interfaces; access RDF through a side-channel when needed.
- **Vocabulary-agnostic core**: no hardcoded vocab assumptions; your vocabularies drive the model.
- **Explicit semantics over magic**: the DSL avoids hidden inference; validation and reasoning are explicit.
- **Provider-agnostic**: swap Jena, RDF4J, Memory, or SPARQL without changing your app model.

**Anti-goals**
- Not a triple store or RDF database.
- Not a SPARQL engine (it integrates with providers that are).
- Not an ontology reasoner beyond provider capabilities.

**Comparison (neutral)**
| Concern | Kastor | Jena / RDF4J / rdflib |
|---|---|---|
| Domain objects without RDF dependencies | ✅ | ❌ |
| Side-channel RDF access | ✅ | ❌ (typically direct RDF types) |
| Vocabulary-agnostic DSL | ✅ | ⚠️ (varies) |
| Provider-agnostic core API | ✅ | ❌ (engine-specific APIs) |

For a deeper explanation, see the [Design Philosophy](philosophy.md) page.

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
- **Kastor Gen Integration**: Code generation from ontologies

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
    val results = repo.select(SparqlSelectQuery("""
        SELECT ?name ?age WHERE {
            ?person foaf:name ?name .
            ?person ex:age ?age .
        }
    """)))
    
    results.forEach { binding ->
        println("${binding.getString("name")} is ${binding.getInt("age")} years old")
    }
}
```

## 📚 Documentation Structure

### **Getting Started**
- **[Getting Started Guide](getting-started/README.md)** - Complete setup and introduction
- **[Quick Start Examples](getting-started/quick-start.md)** - Copy-paste examples for immediate success
- **[Hello World Tutorial](tutorials/hello-world.md)** - Step-by-step first program
- **[How-To Guides](guides/README.md)** - Task-oriented workflows

### **Core Features**
- **[DSL Guide](api/compact-dsl-guide.md)** - Natural language syntax
- **[Reasoning](features/reasoning.md)** - RDFS, OWL inference capabilities
- **[SHACL Validation](features/shacl-validation.md)** - Data validation and constraints
- **[RDF-star Support](api/compact-dsl-guide.md#rdf-star-support)** - Metadata about statements

### **Guides**
- **[Provider Overview](providers/README.md)** - Available backends comparison
- **[Performance Guide](advanced/performance.md)** - Optimization strategies
- **[Serialization Formats](advanced/formats.md)** - RDF format support
- **[Best Practices](guides/best-practices.md)** - Usage guidelines and patterns
- **[Extending Kastor](guides/extending.md)** - Custom providers and plugins

### **Resources**
- **[Vocabularies](concepts/vocabularies.md)** - Working with RDF vocabularies
- **[RDF Fundamentals](concepts/rdf-fundamentals.md)** - Understanding RDF basics
- **[SPARQL Fundamentals](concepts/sparql-fundamentals.md)** - Query language introduction
- **[Troubleshooting](guides/troubleshooting.md)** - Common issues and solutions
- **[FAQ](guides/faq.md)** - Frequently asked questions

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
val validation = JenaValidation()
val result = validation.validate(dataGraph, focusNode)

if (result is ValidationResult.Violations) {
    result.items.forEach { violation ->
        println("Validation error: ${violation.message}")
    }
}
```

### **Web Applications**
Integrate with SPARQL endpoints and build web services.

```kotlin
val repo = Rdf.repository {
    providerId = "sparql"
    variantId = "endpoint"
}

val results = repo.select(SparqlSelectQuery("""
    SELECT ?name WHERE {
        ?person rdfs:label ?name .
        ?person dbo:birthPlace <http://dbpedia.org/resource/Paris> .
    } LIMIT 10
""")))
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
1. [RDF Fundamentals](concepts/rdf-fundamentals.md) - Learn RDF basics
2. [Getting Started Guide](getting-started/README.md) - Setup and first steps
3. [Hello World Tutorial](tutorials/hello-world.md) - Your first program
4. [DSL Guide](api/compact-dsl-guide.md) - Natural language syntax

### **RDF Expert?**
1. [Provider Overview](providers/README.md) - Choose your backend
2. [Performance Guide](advanced/performance.md) - Optimization strategies
3. [Reasoning](features/reasoning.md) - RDFS, OWL inference capabilities
4. [SHACL Validation](features/shacl-validation.md) - Data validation

### **Application Developer?**
1. [Getting Started Guide](getting-started/README.md) - Quick setup
2. [DSL Guide](api/compact-dsl-guide.md) - Natural language syntax
3. [Provider Overview](providers/README.md) - Backend comparison
4. [Best Practices](guides/best-practices.md) - Production guidelines

## 🌐 Community & Support

### **About Kastor**
**Kastor** is developed by **[GeoKnoesis LLC](https://geoknoesis.com/)**, a company specializing in semantic web technologies and knowledge engineering.

**Main Developer**: **Stephane Fellah** - [info@geoknoesis.com](mailto:info@geoknoesis.com)

### **Getting Help**
- **[GitHub Repository](https://github.com/geoknoesis/kastor)** - Source code and issues
- **[GitHub Discussions](https://github.com/geoknoesis/kastor/discussions)** - Community support
- **[Stack Overflow](https://stackoverflow.com/questions/tagged/kastor)** - Technical questions
- **[FAQ](guides/faq.md)** - Frequently asked questions
- **Direct Contact**: [stephanef@geoknoesis.com](mailto:stephanef@geoknoesis.com)

### **Support This Project**
Kastor is open-source infrastructure for RDF in Kotlin. Keeping it correct, secure, and well-maintained takes ongoing work.

If Kastor helps you or your organization, consider supporting its long-term sustainability through sponsorship, contributions, or advocacy. Your support funds maintenance, security, performance improvements, and documentation.

You can help by:

- 🌟 **Star the repository** on [GitHub](https://github.com/geoknoesis/kastor)
- 💰 **Sponsor the project** on [GitHub Sponsors](https://github.com/sponsors/geoknoesis)
- ☕ **Buy us a coffee** via [Ko-fi](https://ko-fi.com/geoknoesis)
- 🏢 **Enterprise support** - Contact us for commercial licensing and support options

## 📄 License

Kastor is open source and available under the [Apache License 2.0](../../LICENSE).

---

**Ready to get started?** Check out the [Getting Started Guide](getting-started/README.md) or jump right into [Hello World](tutorials/hello-world.md)!


