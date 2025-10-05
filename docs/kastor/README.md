# 🚀 Kastor RDF - The Most Elegant RDF API for Kotlin

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg)]()

> **The most elegant and modern RDF API for Kotlin** - designed with developer productivity and code elegance as top priorities.

## 📖 Table of Contents

- [Why Kastor RDF?](#-why-kastor-rdf)
- [Quick Start](#-quick-start)
- [Key Features](#-key-features)
- [Documentation](#-documentation)
- [Examples](#-examples)
- [Architecture](#-architecture)
- [Performance](#-performance)
- [Contributing](#-contributing)

## ✨ Why Kastor RDF?

Kastor RDF is the **most elegant RDF API for Kotlin**, designed to make RDF development enjoyable and productive. With its fluent interfaces, performance monitoring, batch operations, and comprehensive feature set, it's the best choice for modern RDF applications.

### 🎯 What Makes It Special?

- **🏭 Elegant Factory Methods** - Create repositories with minimal code
- **🔧 Enhanced Configuration System** - Rich parameter metadata with validation
- **🔄 Fluent Interface Operations** - Chain operations for readable code
- **⚡ Performance Monitoring** - Built-in timing and statistics
- **📦 Batch Operations** - Efficient bulk data processing
- **🔍 Advanced Query Features** - Type-safe result processing
- **💼 Transaction Operations** - Atomic operations with rollback
- **🎯 Operator Overloads** - Natural triple creation syntax
- **🛠️ Convenience Functions** - Minimal boilerplate code
- **📊 Graph Operations** - Named graph management
- **📈 Comprehensive Statistics** - Detailed repository insights
- **🎨 Multiple DSL Syntax Styles** - Choose what feels natural to you
- **🔧 Advanced Configuration** - Fine-tune repository behavior

## 🚀 Quick Start

### Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.geoknoesis:kastor-rdf-core:0.1.0")
    implementation("com.geoknoesis:kastor-rdf-jena:0.1.0")  // Jena backend
    implementation("com.geoknoesis:kastor-rdf-rdf4j:0.1.0")  // RDF4J backend
}
```

### Basic Usage

```kotlin
import com.geoknoesis.kastor.rdf.*

fun main() {
    // Create a repository
    val repo = Rdf.memory()
    
    // Define vocabulary objects for clean organization
    object PersonVocab {
        val name = "http://example.org/person/name".toIri()
        val age = "http://example.org/person/age".toIri()
        val worksFor = "http://example.org/person/worksFor".toIri()
    }
    
    // Add data using fluent interface
    repo.fluent()
        .add {
            val alice = "http://example.org/person/alice".toResource()
            val company = "http://example.org/company/tech".toResource()
            
            // Ultra-compact syntax
            alice[PersonVocab.name] = "Alice Johnson"
            alice[PersonVocab.age] = 30
            alice[PersonVocab.worksFor] = company
            
            // Natural language syntax
            alice has PersonVocab.name with "Alice"
            alice has PersonVocab.age with 30
        }
        .query("SELECT ?name ?age WHERE { ?person <http://example.org/person/name> ?name ; <http://example.org/person/age> ?age }")
        .forEach { binding ->
            println("${binding.getString("name")} is ${binding.getInt("age")} years old")
        }
    
    repo.close()
}
```

## 🎯 Key Features

### 🏭 Elegant Factory Methods

Create repositories with minimal code and maximum clarity:

```kotlin
val repo = Rdf.memory()                    // Simple in-memory repository
val persistentRepo = Rdf.persistent("data") // Persistent storage
val inferenceRepo = Rdf.memoryWithInference() // With RDFS inference
val customRepo = Rdf.factory {              // Custom configuration
    type = "tdb2"
    location = "custom-data"
    inference = true
    optimization = true
}
```

### 🔧 Enhanced Configuration System

Discover and validate configuration parameters with rich metadata:

```kotlin
// Get detailed parameter information
val variant = RdfApiRegistry.getConfigVariant("jena:tdb2")
variant?.parameters?.forEach { param ->
    println("${param.name} (${param.type}): ${param.description}")
    println("Examples: ${param.examples.joinToString(", ")}")
}

// Validate configuration before creating repository
val requiredParams = RdfApiRegistry.getRequiredParameters("sparql")
val missingParams = requiredParams.filter { param -> 
    !config.params.containsKey(param.name) 
}

// Generate usage examples automatically
val examples = requiredParams.map { param ->
    param.examples.firstOrNull() ?: "\"value\""
}
```

### 🔄 Fluent Interface Operations

Chain operations together for elegant, readable code:

```kotlin
repo.fluent()
    .add { /* add data */ }
    .query("SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }")
    .forEach { /* process results */ }
    .transaction { /* atomic operations */ }
    .clear()
    .statistics()
```

### 🎨 Multiple DSL Syntax Styles

Choose the syntax that feels most natural to you:

```kotlin
val person = "http://example.org/person/alice".toResource()
val name = "http://example.org/person/name".toIri()

// 1. Ultra-compact syntax (most concise)
person[name] = "Alice"
person["http://example.org/person/age"] = 30

// 2. Natural language syntax (most explicit)
person has name with "Alice"
person has "http://example.org/person/age" with 30

// 3. Generic infix operator (natural flow)
person has name with "Alice"
person has "http://example.org/person/age" with 30

// 4. Operator overloads (minimal syntax)
person -> (name to "Alice")
person -> ("http://example.org/person/age" to 30)

// 5. Convenience functions (explicit)
triple(person, name, "Alice")
triple(person, "http://example.org/person/age", 30)
```

### ⚡ Performance Monitoring

Monitor query and operation performance with built-in timing:

```kotlin
// Time query execution
val (results, queryDuration) = repo.queryTimed("""
    SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }
""")
println("Query executed in: $queryDuration")

// Get comprehensive statistics
println(repo.statisticsFormatted())
```

### 📦 Batch Operations

Process large datasets efficiently:

```kotlin
// Add in batches for better performance
repo.addBatch(batchSize = 1000) {
    people.forEachIndexed { index, person ->
        person[PersonVocab.name] = "Person ${index + 1}"
        person[PersonVocab.age] = 20 + (index % 50)
    }
}
```

### 🔍 Advanced Query Features

Type-safe query results with convenient access patterns:

```kotlin
// Get first result directly
val firstPerson = repo.queryFirst("""
    SELECT ?name WHERE { ?person <http://example.org/person/name> ?name } LIMIT 1
""")

// Get results as a map
val nameAgeMap = repo.queryMap(
    sparql = "SELECT ?name ?age WHERE { ?person <http://example.org/person/name> ?name ; <http://example.org/person/age> ?age }",
    keySelector = { it.getString("name") ?: "Unknown" },
    valueSelector = { it.getInt("age").toString() }
)

// Get results as specific types
val names: List<String> = repo.query("""
    SELECT ?name WHERE { ?person <http://example.org/person/name> ?name }
""").mapAs()
```

### 💼 Transaction Operations

Atomic operations with automatic rollback:

```kotlin
repo.transaction {
    // All operations in this block are atomic
    add {
        val person = "http://example.org/person/atomic".toResource()
        person[PersonVocab.name] = "Atomic Person"
        person[PersonVocab.age] = 35
    }
    
    // If any operation fails, all changes are rolled back
}
```

## 📚 Documentation

### 🚀 Getting Started
- **[Quick Start Guide](quick-start.md)** - Get up and running in minutes
- **[Getting Started](getting-started.md)** - Step-by-step introduction
- **[Installation Guide](installation.md)** - Detailed setup instructions

### 🔗 Interoperability
- **[Jena Bridge](tutorials/jena-bridge.md)** - Seamless integration with Apache Jena

### 🎯 Core Guides
- **[Super Sleek API Guide](super-sleek-api-guide.md)** - Comprehensive feature showcase
- **[Compact DSL Guide](compact-dsl-guide.md)** - Multiple syntax styles and DSL options
- **[API Design Principles](api-design-principles.md)** - Design philosophy and principles

### 📖 Fundamentals
- **[RDF Fundamentals](rdf-fundamentals.md)** - Understanding RDF basics
- **[SPARQL Fundamentals](sparql-fundamentals.md)** - Query language introduction
- **[RDF Terms](rdfterms.md)** - Core RDF concepts and terminology

### 🏗️ Architecture & Reference
- **[Core API Reference](core-api.md)** - Core interfaces and types
- **[Repository Management](repository-manager.md)** - Multi-repository operations
- **[Provider Comparison](provider-comparison.md)** - Backend comparison
- **[Providers](providers.md)** - Available RDF backends

### 🎨 Advanced Topics
- **[Vocabularies](vocabularies.md)** - Working with RDF vocabularies
- **[Vocabularies Quick Reference](vocabularies-quickref.md)** - Common vocabularies
- **[Vocabularies Index](vocabularies-index.md)** - Complete vocabulary reference
- **[Transactions](transactions.md)** - Transaction management
- **[Performance](performance.md)** - Optimization and performance tuning

### 🛠️ Tutorials
- **[Hello World Tutorial](tutorials/hello-world.md)** - Your first RDF application
- **[Load and Query Tutorial](tutorials/load-and-query.md)** - Working with data
- **[Remote Endpoint Tutorial](tutorials/remote-endpoint.md)** - Connecting to remote repositories

### 📋 Reference
- **[Factory Reference](reference/factory.md)** - Repository creation options
- **[Repository Reference](reference/repository.md)** - Repository operations
- **[Types Reference](reference/types.md)** - Core data types
- **[Formats](formats.md)** - Supported RDF formats

### 🔧 Development
- **[Cookbook](cookbook.md)** - Common patterns and solutions
- **[Examples](examples.md)** - Code examples and use cases
- **[Extending](extending.md)** - Creating custom extensions
- **[Troubleshooting](troubleshooting.md)** - Common issues and solutions
- **[FAQ](faq.md)** - Frequently asked questions

### 📚 Additional Resources
- **[Glossary](glossary.md)** - Key terms and definitions
- **[Guides](guides.md)** - Additional learning resources

## 🎯 Examples

### 🚀 Comprehensive Examples
- **[Super Sleek Example](examples/src/main/kotlin/com/geoknoesis/kastor/rdf/examples/SuperSleekExample.kt)** - Complete feature showcase
- **[Ultra-Compact with Variables](examples/src/main/kotlin/com/geoknoesis/kastor/rdf/examples/UltraCompactWithVariablesExample.kt)** - Variable usage patterns
- **[Vocabulary Agnostic](examples/src/main/kotlin/com/geoknoesis/kastor/rdf/examples/VocabularyAgnosticExample.kt)** - Core API without vocabulary assumptions

### 📖 Tutorial Examples
- **Hello World** - Basic repository creation and data addition
- **Load and Query** - Working with existing RDF data
- **Remote Endpoint** - Connecting to SPARQL endpoints

## 🏗️ Architecture

Kastor RDF is built with a modular architecture:

```
kastor-rdf/
├── core/           # Core API and interfaces
├── jena/           # Apache Jena backend
├── rdf4j/          # Eclipse RDF4J backend
├── examples/       # Example applications
└── docs/           # Documentation
```

### 🔧 Core Components

- **RdfRepository** - Main interface for RDF operations
- **RdfGraph** - Named graph management
- **TripleDsl** - Elegant triple creation DSL
- **QueryResult** - Type-safe query results
- **RepositoryManager** - Multi-repository operations

### 🚀 Backend Providers

- **Apache Jena** - In-memory and TDB2 repositories
- **Eclipse RDF4J** - In-memory and native repositories
- **SPARQL** - Remote endpoint support

## 🚀 Performance

- **Optimized for both small and large datasets**
- **Batch operations for efficient bulk processing**
- **Built-in performance monitoring**
- **Lazy evaluation for large result sets**
- **Memory-efficient operations**

### 📊 Performance Features

- **Query timing** - Measure query execution time
- **Operation timing** - Monitor operation performance
- **Statistics** - Comprehensive repository metrics
- **Batch processing** - Efficient bulk operations
- **Memory optimization** - Lazy evaluation and streaming

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

### 🎯 How to Contribute

1. **Fork the repository**
2. **Create a feature branch**
3. **Make your changes**
4. **Add tests**
5. **Submit a pull request**

### 📋 Development Setup

```bash
git clone https://github.com/geoknoesis/kastor-rdf.git
cd kastor-rdf
./gradlew build
```

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- **Apache Jena team** for the excellent RDF framework
- **Eclipse RDF4J team** for the powerful RDF toolkit
- **Kotlin team** for the amazing language features

## 📞 Support

- **Documentation**: [docs/](docs/)
- **Examples**: [examples/](examples/)
- **Issues**: [GitHub Issues](https://github.com/geoknoesis/kastor-rdf/issues)
- **Discussions**: [GitHub Discussions](https://github.com/geoknoesis/kastor-rdf/discussions)

---

**🚀 This is the most elegant RDF API for Kotlin - designed for developers who value clean, readable, and maintainable code.**
