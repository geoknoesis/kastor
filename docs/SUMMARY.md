# Kastor Documentation Summary

This document provides a comprehensive overview of the Kastor documentation structure and key topics.

## 📚 Documentation Structure

```
docs/
├── README.md                    # Main documentation hub
├── SUMMARY.md                   # This file
├── kastor/                      # Kastor RDF Core documentation
│   ├── README.md               # Kastor RDF overview
│   ├── getting-started.md      # Quick start guide
│   ├── core-api.md             # Core API documentation
│   ├── sparql-fundamentals.md  # SPARQL tutorial
│   ├── providers.md            # Backend implementations
│   ├── best-practices.md       # Usage guidelines
│   ├── faq.md                  # Frequently asked questions
│   ├── reference/              # API reference
│   └── tutorials/              # Step-by-step tutorials
└── ontomapper/                 # OntoMapper documentation
    ├── README.md               # OntoMapper overview
    ├── best-practices.md       # Usage guidelines
    ├── faq.md                  # Frequently asked questions
    ├── reference/              # API reference
    └── tutorials/              # Step-by-step tutorials
```

## 🎯 Key Topics

### Kastor RDF Core

#### **Getting Started**
- [Quick Start Guide](kastor/quick-start.md) - Get up and running in minutes
- [Installation](kastor/installation.md) - Setup instructions
- [Hello World](kastor/tutorials/hello-world.md) - Your first RDF program

#### **Core Concepts**
- [RDF Fundamentals](kastor/rdf-fundamentals.md) - Understanding RDF basics
- [Repository Management](kastor/repository-manager.md) - Working with repositories
- [Graph Operations](kastor/core-api.md) - Triple storage and retrieval

#### **Querying**
- [SPARQL Fundamentals](kastor/sparql-fundamentals.md) - Query language basics
- [Kastor Query DSL](kastor/kastor-query-dsl-tutorial.md) - Type-safe queries
- [Load and Query](kastor/tutorials/load-and-query.md) - Practical examples

#### **Backends**
- [Providers Overview](kastor/providers.md) - Available backends
- [Memory Provider](kastor/providers.md#memory) - In-memory storage
- [Jena Provider](kastor/enhanced-jena.md) - Apache Jena integration
- [RDF4J Provider](kastor/rdf4j-repository-management.md) - Eclipse RDF4J
- [SPARQL Provider](kastor/tutorials/remote-endpoint.md) - Remote endpoints

#### **Advanced Topics**
- [Transactions](kastor/transactions.md) - ACID transactions
- [Performance](kastor/performance.md) - Optimization strategies
- [Formats](kastor/formats.md) - Serialization formats
- [Vocabularies](kastor/vocabularies.md) - RDF vocabularies

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
