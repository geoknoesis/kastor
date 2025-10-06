# Kastor Documentation Summary

This document provides a comprehensive overview of the Kastor documentation structure and key topics.

## 📚 Documentation Structure

```
docs/
├── README.md                    # Main documentation hub
├── SUMMARY.md                   # This file
├── kastor/                      # Kastor RDF Framework documentation
│   ├── README.md               # Kastor overview
│   ├── getting-started.md      # Quick start guide
│   ├── quick-start.md          # Copy-paste examples
│   ├── reasoning.md            # RDFS/OWL reasoning
│   ├── shacl-validation.md     # SHACL validation
│   ├── api/                    # Core API documentation
│   │   ├── core-api.md         # Complete API reference
│   │   ├── compact-dsl-guide.md # Domain-specific language
│   │   ├── repository-manager.md # Repository management
│   │   └── transactions.md     # Transaction support
│   ├── concepts/               # Core concepts
│   │   ├── rdf-fundamentals.md # RDF basics
│   │   ├── sparql-fundamentals.md # SPARQL tutorial
│   │   └── vocabularies.md     # RDF vocabularies
│   ├── providers/              # Backend implementations
│   │   ├── README.md           # Provider overview
│   │   ├── memory.md           # In-memory storage
│   │   ├── jena.md             # Apache Jena integration
│   │   ├── rdf4j.md            # Eclipse RDF4J
│   │   └── sparql.md           # Remote SPARQL endpoints
│   ├── advanced/               # Advanced topics
│   │   ├── performance.md      # Optimization strategies
│   │   └── formats.md          # Serialization formats
│   ├── examples/               # Working examples
│   │   └── README.md           # Example catalog
│   ├── tutorials/              # Step-by-step tutorials
│   │   ├── hello-world.md      # First program
│   │   ├── load-and-query.md   # Data operations
│   │   ├── remote-endpoint.md  # SPARQL endpoints
│   │   └── jena-bridge.md      # Jena integration
│   ├── reference/              # Detailed references
│   │   ├── dsl.md              # DSL reference
│   │   ├── factory.md          # Factory patterns
│   │   ├── repository.md       # Repository API
│   │   └── types.md            # Type system
│   ├── best-practices.md       # Usage guidelines
│   ├── faq.md                  # Frequently asked questions
│   └── troubleshooting.md      # Common issues
└── ontomapper/                 # OntoMapper documentation
    ├── README.md               # OntoMapper overview
    ├── best-practices.md       # Usage guidelines
    ├── faq.md                  # Frequently asked questions
    ├── reference/              # API reference
    │   ├── annotations.md      # Available annotations
    │   ├── runtime.md          # Runtime functionality
    │   └── validation.md       # Validation features
    ├── tutorials/              # Step-by-step tutorials
    │   ├── getting-started.md  # Quick start
    │   ├── core-concepts.md    # Architecture overview
    │   ├── domain-modeling.md  # Interface design
    │   ├── rdf-integration.md  # RDF side-channels
    │   ├── validation.md       # Data validation
    │   ├── ontology-generation.md # Code generation
    │   ├── advanced-usage.md   # Advanced patterns
    │   ├── gradle-configuration.md # Build setup
    │   └── prefix-mappings.md  # Namespace handling
    └── examples/               # Working examples
        └── README.md           # Example catalog
```

## 🎯 Key Topics

### Kastor RDF Framework

#### **Getting Started**
- [Getting Started Guide](kastor/getting-started.md) - Setup and introduction
- [Quick Start Examples](kastor/quick-start.md) - Copy-paste examples
- [Hello World Tutorial](kastor/tutorials/hello-world.md) - Your first RDF program

#### **Core Concepts**
- [RDF Fundamentals](kastor/concepts/rdf-fundamentals.md) - Understanding RDF basics
- [SPARQL Fundamentals](kastor/concepts/sparql-fundamentals.md) - Query language basics
- [Vocabularies](kastor/concepts/vocabularies.md) - RDF vocabularies and namespaces

#### **API Documentation**
- [Core API Reference](kastor/api/core-api.md) - Complete API documentation
- [DSL Guide](kastor/api/compact-dsl-guide.md) - Domain-specific language
- [Repository Manager](kastor/api/repository-manager.md) - Multi-repository setup
- [Transactions](kastor/api/transactions.md) - ACID transaction support

#### **Backend Providers**
- [Provider Overview](kastor/providers/README.md) - Available backends
- [Memory Provider](kastor/providers/memory.md) - In-memory storage
- [Jena Provider](kastor/providers/jena.md) - Apache Jena integration
- [RDF4J Provider](kastor/providers/rdf4j.md) - Eclipse RDF4J
- [SPARQL Provider](kastor/providers/sparql.md) - Remote endpoints

#### **Advanced Features**
- [Reasoning](kastor/reasoning.md) - RDFS/OWL inference
- [SHACL Validation](kastor/shacl-validation.md) - Data validation
- [Performance](kastor/advanced/performance.md) - Optimization strategies
- [Formats](kastor/advanced/formats.md) - Serialization formats

#### **Tutorials**
- [Hello World](kastor/tutorials/hello-world.md) - First program
- [Load and Query](kastor/tutorials/load-and-query.md) - Data operations
- [Remote Endpoint](kastor/tutorials/remote-endpoint.md) - SPARQL endpoints
- [Jena Bridge](kastor/tutorials/jena-bridge.md) - Jena integration

### OntoMapper

#### **Getting Started**
- [Getting Started](ontomapper/tutorials/getting-started.md) - Quick start guide
- [Core Concepts](ontomapper/tutorials/core-concepts.md) - Understanding the architecture
- [Domain Modeling](ontomapper/tutorials/domain-modeling.md) - Creating domain interfaces

#### **RDF Integration**
- [RDF Integration](ontomapper/tutorials/rdf-integration.md) - Working with RDF side-channels
- [Validation](ontomapper/tutorials/validation.md) - SHACL validation
- [Advanced Usage](ontomapper/tutorials/advanced-usage.md) - Advanced patterns

#### **Code Generation**
- [Ontology Generation](ontomapper/tutorials/ontology-generation.md) - SHACL/JSON-LD generation
- [Annotations](ontomapper/reference/annotations.md) - Available annotations
- [Runtime API](ontomapper/reference/runtime.md) - Runtime functionality

#### **Best Practices**
- [Best Practices](ontomapper/best-practices.md) - Guidelines for effective usage
- [FAQ](ontomapper/faq.md) - Frequently asked questions
- [Troubleshooting](ontomapper/faq.md#troubleshooting) - Common issues and solutions

## 🚀 Quick Navigation

### For Beginners
1. Start with [Kastor RDF Getting Started](kastor/getting-started.md)
2. Learn [RDF Fundamentals](kastor/rdf-fundamentals.md)
3. Try [Hello World Tutorial](kastor/tutorials/hello-world.md)
4. Explore [OntoMapper Getting Started](ontomapper/tutorials/getting-started.md)

### For RDF Experts
1. Review [Core API](kastor/core-api.md)
2. Check [Provider Comparison](kastor/provider-comparison.md)
3. Learn [OntoMapper Architecture](ontomapper/tutorials/core-concepts.md)
4. Explore [Ontology Generation](ontomapper/tutorials/ontology-generation.md)

### For Application Developers
1. Start with [OntoMapper Getting Started](ontomapper/tutorials/getting-started.md)
2. Learn [Domain Modeling](ontomapper/tutorials/domain-modeling.md)
3. Understand [RDF Integration](ontomapper/tutorials/rdf-integration.md)
4. Follow [Best Practices](ontomapper/best-practices.md)

## 📖 Documentation Types

### **Tutorials**
Step-by-step guides with examples:
- [Hello World](kastor/tutorials/hello-world.md)
- [Load and Query](kastor/tutorials/load-and-query.md)
- [Remote Endpoint](kastor/tutorials/remote-endpoint.md)
- [Getting Started](ontomapper/tutorials/getting-started.md)
- [Domain Modeling](ontomapper/tutorials/domain-modeling.md)

### **Guides**
Comprehensive topic coverage:
- [SPARQL Fundamentals](kastor/sparql-fundamentals.md)
- [Kastor Query DSL](kastor/kastor-query-dsl-tutorial.md)
- [RDF Integration](ontomapper/tutorials/rdf-integration.md)
- [Ontology Generation](ontomapper/tutorials/ontology-generation.md)

### **Reference**
API documentation and specifications:
- [Core API](kastor/core-api.md)
- [Repository Reference](kastor/reference/repository.md)
- [Types Reference](kastor/reference/types.md)
- [Runtime API](ontomapper/reference/runtime.md)
- [Annotations Reference](ontomapper/reference/annotations.md)

### **Best Practices**
Guidelines and recommendations:
- [Kastor Best Practices](kastor/best-practices.md)
- [OntoMapper Best Practices](ontomapper/best-practices.md)
- [Performance](kastor/performance.md)
- [Troubleshooting](kastor/troubleshooting.md)

## 🔍 Search and Navigation

### **By Topic**
- **RDF Basics**: [RDF Fundamentals](kastor/rdf-fundamentals.md), [Vocabularies](kastor/vocabularies.md)
- **Querying**: [SPARQL](kastor/sparql-fundamentals.md), [Query DSL](kastor/kastor-query-dsl-tutorial.md)
- **Backends**: [Providers](kastor/providers.md), [Jena](kastor/enhanced-jena.md), [RDF4J](kastor/rdf4j-repository-management.md)
- **Domain Objects**: [Domain Modeling](ontomapper/tutorials/domain-modeling.md), [Core Concepts](ontomapper/tutorials/core-concepts.md)
- **Code Generation**: [Ontology Generation](ontomapper/tutorials/ontology-generation.md), [Annotations](ontomapper/reference/annotations.md)

### **By Use Case**
- **Data Catalogs**: [DCAT Examples](kastor/examples.md), [Ontology Generation](ontomapper/tutorials/ontology-generation.md)
- **Knowledge Graphs**: [Graph Operations](kastor/core-api.md), [Domain Modeling](ontomapper/tutorials/domain-modeling.md)
- **Web Applications**: [SPARQL Endpoints](kastor/tutorials/remote-endpoint.md), [RDF Integration](ontomapper/tutorials/rdf-integration.md)
- **Enterprise Integration**: [Transactions](kastor/transactions.md), [Best Practices](ontomapper/best-practices.md)

### **By Skill Level**
- **Beginner**: [Getting Started](kastor/getting-started.md), [Hello World](kastor/tutorials/hello-world.md), [Core Concepts](ontomapper/tutorials/core-concepts.md)
- **Intermediate**: [SPARQL](kastor/sparql-fundamentals.md), [Domain Modeling](ontomapper/tutorials/domain-modeling.md), [Validation](ontomapper/tutorials/validation.md)
- **Advanced**: [Performance](kastor/performance.md), [Ontology Generation](ontomapper/tutorials/ontology-generation.md), [Advanced Usage](ontomapper/tutorials/advanced-usage.md)

## 📚 Additional Resources

### **External Links**
- [RDF 1.1 Specification](https://www.w3.org/TR/rdf11-concepts/)
- [SPARQL 1.1 Specification](https://www.w3.org/TR/sparql11-query/)
- [SHACL Specification](https://www.w3.org/TR/shacl/)
- [JSON-LD Specification](https://www.w3.org/TR/json-ld11/)

### **Community**
- [GitHub Repository](https://github.com/geoknoesis/kastor)
- [Issue Tracker](https://github.com/geoknoesis/kastor/issues)
- [Discussions](https://github.com/geoknoesis/kastor/discussions)
- [Stack Overflow](https://stackoverflow.com/questions/tagged/kastor)

### **Examples and Samples**
- [Kastor Examples](kastor/examples.md)
- [OntoMapper Examples](ontomapper/examples/README.md)
- [Sample Applications](../samples/) - Complete working examples

---

**Need help finding something?** Use the search functionality or browse by topic using the navigation above!
