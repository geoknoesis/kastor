# 📚 Kastor Documentation Hub

Welcome to the comprehensive documentation for Kastor - a modern Kotlin RDF framework that makes semantic web development accessible and powerful.

## 🚀 Quick Start

### New to Kastor?
- **[Getting Started Guide](kastor/getting-started.md)** - 5-minute setup and first steps
- **[Hello World Tutorial](kastor/tutorials/hello-world.md)** - Your first RDF program
- **[Quick Start Examples](kastor/quick-start.md)** - Copy-paste examples

### New to RDF?
- **[RDF Fundamentals](kastor/concepts/rdf-fundamentals.md)** - Understanding RDF basics
- **[SPARQL Fundamentals](kastor/concepts/sparql-fundamentals.md)** - Query language introduction
- **[Vocabularies Guide](kastor/concepts/vocabularies.md)** - Working with RDF vocabularies

## 🏗️ Architecture Overview

```
Kastor Framework
├── 📦 RDF Core          - Core RDF operations and DSL
├── 🧠 Reasoning         - RDFS, OWL reasoning capabilities  
├── ✅ SHACL Validation  - Data validation and constraints
├── 🏷️ OntoMapper       - Code generation from ontologies
└── 🔌 Providers         - Jena, RDF4J, SPARQL backends
```

## 📖 Documentation Structure

### **Core Modules**

#### **RDF Core** (`rdf/core`)
- **[API Reference](kastor/api/core-api.md)** - Complete API documentation
- **[DSL Guide](kastor/api/compact-dsl-guide.md)** - Domain-specific language
- **[Repository Management](kastor/api/repository-manager.md)** - Working with repositories
- **[Transactions](kastor/api/transactions.md)** - ACID transaction support

#### **Reasoning** (`rdf/reasoning`)
- **[Reasoning Guide](kastor/reasoning.md)** - RDFS, OWL reasoning
- **[Provider Architecture](kastor/reasoning.md#providers)** - Pluggable reasoners
- **[Performance](kastor/reasoning.md#performance)** - Optimization strategies

#### **SHACL Validation** (`rdf/shacl-validation`)
- **[Validation Guide](kastor/shacl-validation.md)** - Data validation
- **[Quick Start](kastor/shacl-validation.md#quick-start)** - Get started in minutes
- **[Constraint Types](kastor/shacl-validation.md#constraint-types)** - Available constraints

#### **OntoMapper** (`ontomapper`)
- **[Getting Started](ontomapper/tutorials/getting-started.md)** - Code generation basics
- **[Core Concepts](ontomapper/tutorials/core-concepts.md)** - Architecture overview
- **[Domain Modeling](ontomapper/tutorials/domain-modeling.md)** - Creating domain interfaces
- **[Best Practices](ontomapper/best-practices.md)** - Usage guidelines

### **Backend Providers**

#### **Memory Provider**
- **[Memory Operations](kastor/providers/memory.md)** - In-memory storage
- **[Performance Tips](kastor/providers/memory.md#performance)** - Optimization

#### **Jena Provider**
- **[Jena Integration](kastor/providers/jena.md)** - Apache Jena backend
- **[TDB2 Storage](kastor/providers/jena.md#tdb2)** - Persistent storage
- **[Fuseki Integration](kastor/providers/jena.md#fuseki)** - SPARQL endpoints

#### **RDF4J Provider**
- **[RDF4J Integration](kastor/providers/rdf4j.md)** - Eclipse RDF4J backend
- **[Native Store](kastor/providers/rdf4j.md#native-store)** - Local storage
- **[Remote Repositories](kastor/providers/rdf4j.md#remote)** - Network access

#### **SPARQL Provider**
- **[Remote Endpoints](kastor/providers/sparql.md)** - SPARQL query service
- **[Federation](kastor/providers/sparql.md#federation)** - Multi-endpoint queries

## 🎯 Learning Paths

### **Beginner Path**
1. [Getting Started](kastor/getting-started.md) - Setup and basics
2. [RDF Fundamentals](kastor/concepts/rdf-fundamentals.md) - Core concepts
3. [Hello World](kastor/tutorials/hello-world.md) - First program
4. [DSL Guide](kastor/api/compact-dsl-guide.md) - Natural language syntax

### **RDF Expert Path**
1. [Core API](kastor/api/core-api.md) - Complete API reference
2. [Provider Comparison](kastor/providers/README.md) - Backend selection
3. [Performance Guide](kastor/performance.md) - Optimization
4. [Advanced Patterns](kastor/advanced/README.md) - Enterprise usage

### **Domain Modeling Path**
1. [OntoMapper Getting Started](ontomapper/tutorials/getting-started.md) - Code generation
2. [Domain Modeling](ontomapper/tutorials/domain-modeling.md) - Interface design
3. [RDF Integration](ontomapper/tutorials/rdf-integration.md) - Side-channel access
4. [Validation](ontomapper/tutorials/validation.md) - Data quality

### **Enterprise Path**
1. [Repository Management](kastor/api/repository-manager.md) - Multi-repository setup
2. [Transactions](kastor/api/transactions.md) - ACID compliance
3. [Reasoning](kastor/reasoning.md) - Inference capabilities
4. [SHACL Validation](kastor/shacl-validation.md) - Data validation
5. [Best Practices](kastor/best-practices.md) - Production guidelines

## 🔧 Use Case Guides

### **Data Catalogs**
- [DCAT Examples](kastor/examples.md#dcat) - Data catalog modeling
- [Ontology Generation](ontomapper/tutorials/ontology-generation.md) - Schema generation
- [Validation](kastor/shacl-validation.md) - Data quality checks

### **Knowledge Graphs**
- [Graph Operations](kastor/api/core-api.md) - Triple management
- [Reasoning](kastor/reasoning.md) - Inference and classification
- [SPARQL Queries](kastor/concepts/sparql-fundamentals.md) - Complex queries

### **Web Applications**
- [SPARQL Endpoints](kastor/providers/sparql.md) - Web service integration
- [RDF Integration](ontomapper/tutorials/rdf-integration.md) - Side-channel access
- [Performance](kastor/performance.md) - Optimization strategies

### **Enterprise Integration**
- [Repository Manager](kastor/api/repository-manager.md) - Multi-backend setup
- [Transactions](kastor/api/transactions.md) - ACID transactions
- [Validation](kastor/shacl-validation.md) - Data governance

## 📚 Reference Materials

### **API References**
- [Core API](kastor/api/core-api.md) - Complete RDF operations
- [Repository API](kastor/api/repository-manager.md) - Repository management
- [Reasoning API](kastor/reasoning.md#api-reference) - Inference operations
- [Validation API](kastor/shacl-validation.md#api-reference) - SHACL validation
- [OntoMapper API](ontomapper/reference/README.md) - Code generation

### **Configuration References**
- [Provider Configuration](kastor/providers/README.md) - Backend setup
- [Validation Configuration](kastor/shacl-validation.md#configuration) - SHACL options
- [Reasoning Configuration](kastor/reasoning.md#configuration) - Inference options
- [Gradle Configuration](ontomapper/tutorials/gradle-configuration.md) - Build setup

### **Format References**
- [Serialization Formats](kastor/formats.md) - RDF serialization
- [Vocabulary References](kastor/concepts/vocabularies.md) - Common vocabularies
- [SPARQL Reference](kastor/concepts/sparql-fundamentals.md) - Query language

## 🛠️ Development Resources

### **Examples and Samples**
- [Kastor Examples](kastor/examples.md) - Complete working examples
- [OntoMapper Examples](ontomapper/examples/README.md) - Code generation samples
- [Sample Applications](../samples/) - Full application examples

### **Troubleshooting**
- [FAQ](kastor/faq.md) - Frequently asked questions
- [Troubleshooting Guide](kastor/troubleshooting.md) - Common issues
- [Performance Issues](kastor/performance.md#troubleshooting) - Optimization problems

### **Best Practices**
- [Kastor Best Practices](kastor/best-practices.md) - Usage guidelines
- [OntoMapper Best Practices](ontomapper/best-practices.md) - Code generation
- [Performance Best Practices](kastor/performance.md) - Optimization strategies

## 🌐 Community and Support

### **Getting Help**
- [GitHub Issues](https://github.com/geoknoesis/kastor/issues) - Bug reports and feature requests
- [GitHub Discussions](https://github.com/geoknoesis/kastor/discussions) - Questions and community
- [Stack Overflow](https://stackoverflow.com/questions/tagged/kastor) - Technical questions

### **Contributing**
- [Contributing Guide](../CONTRIBUTING.md) - How to contribute
- [Development Setup](../docs/development/README.md) - Local development
- [Code of Conduct](../CODE_OF_CONDUCT.md) - Community guidelines

### **External Resources**
- [RDF 1.1 Specification](https://www.w3.org/TR/rdf11-concepts/) - W3C standard
- [SPARQL 1.1 Specification](https://www.w3.org/TR/sparql11-query/) - Query language
- [SHACL Specification](https://www.w3.org/TR/shacl/) - Validation language
- [JSON-LD Specification](https://www.w3.org/TR/json-ld11/) - JSON serialization

---

## 🎯 Navigation Tips

- **🔍 Search**: Use Ctrl+F to search within documentation
- **📱 Mobile**: Documentation is responsive for mobile reading
- **🔗 Links**: All internal links use relative paths for offline reading
- **📖 Print**: Use browser print function for PDF generation

**Need help finding something?** Check the [troubleshooting guide](kastor/troubleshooting.md) or [ask the community](https://github.com/geoknoesis/kastor/discussions)!