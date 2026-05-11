# 📚 Kastor Documentation Hub

**Published site:** after you enable [GitHub Pages](https://docs.github.com/en/pages) with the **GitHub Actions** source, the site is available at `https://<owner>.github.io/<repo>/` (for example `https://geoknoesis.github.io/kastor/`). The build workflow lives at [`.github/workflows/pages.yml`](../.github/workflows/pages.yml).

**Local preview:** from this `docs/` folder, run `bundle install` and `bundle exec jekyll serve --livereload` (requires [Ruby](https://www.ruby-lang.org/) + [Bundler](https://bundler.io/)).

Welcome to the comprehensive documentation for Kastor - a modern Kotlin RDF framework that makes semantic web development accessible and powerful.

## 🚀 Quick Start

### New here?
- **[Project Landing Page](landing.md)** - Overview, value, and links

### New to Kastor?
- **[Getting Started Guide](kastor/getting-started/README.md)** - 5-minute setup and first steps
- **[Hello World Tutorial](kastor/tutorials/hello-world.md)** - Your first RDF program
- **[Quick Start Examples](kastor/getting-started/quick-start.md)** - Copy-paste examples
- **[How-To Guides](kastor/guides/README.md)** - Task-oriented workflows

### New to RDF?
- **[RDF Fundamentals](kastor/concepts/rdf-fundamentals.md)** - Understanding RDF basics
- **[SPARQL Fundamentals](kastor/concepts/sparql-fundamentals.md)** - Query language introduction
- **[Vocabularies Guide](kastor/concepts/vocabularies.md)** - Working with RDF vocabularies
- **[Design Philosophy](kastor/philosophy.md)** - Core principles

## 🧭 Documentation Pillars

The documentation is structured into four pillars, each with a distinct intent:

- **Getting Started** — install, create a graph, run a first query: [Overview](kastor/getting-started/README.md)
- **Concepts** — RDF mental model, terminology, and standards context: [Concepts](kastor/concepts/README.md)
- **How‑To Guides** — task‑oriented workflows with reproducible steps: [Guides](kastor/guides/README.md)
- **Reference** — definitive API and DSL behavior: [Reference](kastor/reference/README.md)

## 🏗️ Architecture Overview

```
Kastor Framework
├── 📦 RDF Core          - Core RDF operations and DSL
├── 🧠 Reasoning         - RDFS, OWL reasoning capabilities  
├── ✅ SHACL Validation  - Data validation and constraints
├── 🏷️ Kastor Gen       - Code generation from ontologies
└── 🔌 Providers         - Jena, RDF4J, SPARQL backends
```

## 📖 Documentation Structure

### **Core Modules**

#### **RDF Core** (`rdf/core`)
- **[API Reference](kastor/api/core-api.md)** - Complete API documentation
- **[DSL Guide](kastor/api/compact-dsl-guide.md)** - Domain-specific language
- **[Repository Management](kastor/api/repository-manager.md)** - Working with repositories
- **[Transactions](kastor/api/transactions.md)** - ACID transaction support
- **[How-To Guides](kastor/guides/README.md)** - Task-oriented workflows

#### **Reasoning** (`rdf/reasoning`)
- **[Reasoning Guide](kastor/features/reasoning.md)** - RDFS, OWL reasoning
- **[Provider Architecture](kastor/features/reasoning.md#providers)** - Pluggable reasoners
- **[Performance](kastor/features/reasoning.md#performance)** - Optimization strategies

#### **SHACL Validation** (`rdf/shacl-validation`)
- **[Validation Guide](kastor/features/shacl-validation.md)** - Data validation
- **[Quick Start](kastor/features/shacl-validation.md#quick-start)** - Get started in minutes
- **[Constraint Types](kastor/features/shacl-validation.md#constraint-types)** - Available constraints

#### **Kastor Gen** (`kastor-gen`)
- **[Getting Started](kastor-gen/tutorials/getting-started.md)** - Code generation basics
- **[Core Concepts](kastor-gen/tutorials/core-concepts.md)** - Architecture overview
- **[Domain Modeling](kastor-gen/tutorials/domain-modeling.md)** - Creating domain interfaces
- **[Best Practices](kastor-gen/best-practices.md)** - Usage guidelines

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
1. [Getting Started](kastor/getting-started/README.md) - Setup and basics
2. [RDF Fundamentals](kastor/concepts/rdf-fundamentals.md) - Core concepts
3. [Hello World](kastor/tutorials/hello-world.md) - First program
4. [DSL Guide](kastor/api/compact-dsl-guide.md) - Natural language syntax

### **RDF Expert Path**
1. [Core API](kastor/api/core-api.md) - Complete API reference
2. [Provider Comparison](kastor/providers/README.md) - Backend selection
3. [Performance Guide](kastor/advanced/performance.md) - Optimization
4. [Advanced Patterns](kastor/advanced/performance.md) - Enterprise usage

### **Domain Modeling Path**
1. [Kastor Gen Getting Started](kastor-gen/tutorials/getting-started.md) - Code generation
2. [Domain Modeling](kastor-gen/tutorials/domain-modeling.md) - Interface design
3. [RDF Integration](kastor-gen/tutorials/rdf-integration.md) - Side-channel access
4. [Validation](kastor-gen/tutorials/validation.md) - Data quality

### **Enterprise Path**
1. [Repository Management](kastor/api/repository-manager.md) - Multi-repository setup
2. [Transactions](kastor/api/transactions.md) - ACID compliance
3. [Reasoning](kastor/features/reasoning.md) - Inference capabilities
4. [SHACL Validation](kastor/features/shacl-validation.md) - Data validation
5. [Best Practices](kastor/guides/best-practices.md) - Production guidelines

## 🔧 Use Case Guides

### **Data Catalogs**
- [DCAT Examples](kastor/examples/README.md) - Data catalog modeling
- [Ontology Generation](kastor-gen/tutorials/ontology-generation.md) - Schema generation
- [Validation](kastor/features/shacl-validation.md) - Data quality checks

### **Knowledge Graphs**
- [Graph Operations](kastor/api/core-api.md) - Triple management
- [Reasoning](kastor/features/reasoning.md) - Inference and classification
- [SPARQL Queries](kastor/concepts/sparql-fundamentals.md) - Complex queries

### **Web Applications**
- [SPARQL Endpoints](kastor/providers/sparql.md) - Web service integration
- [RDF Integration](kastor-gen/tutorials/rdf-integration.md) - Side-channel access
- [Performance](kastor/advanced/performance.md) - Optimization strategies

### **Enterprise Integration**
- [Repository Manager](kastor/api/repository-manager.md) - Multi-backend setup
- [Transactions](kastor/api/transactions.md) - ACID transactions
- [Validation](kastor/features/shacl-validation.md) - Data governance

## 📚 Reference Materials

### **API References**
- [Core API](kastor/api/core-api.md) - Complete RDF operations
- [Repository API](kastor/api/repository-manager.md) - Repository management
- [Reasoning API](kastor/features/reasoning.md#api-reference) - Inference operations
- [Validation API](kastor/features/shacl-validation.md#api-reference) - SHACL validation
- [Kastor Gen API](kastor-gen/reference/README.md) - Code generation

### **Configuration References**
- [Provider Configuration](kastor/providers/README.md) - Backend setup
- [Validation Configuration](kastor/features/shacl-validation.md#configuration) - SHACL options
- [Reasoning Configuration](kastor/features/reasoning.md#configuration) - Inference options
- [Gradle Configuration](kastor-gen/tutorials/gradle-configuration.md) - Build setup

### **Format References**
- [Serialization Formats](kastor/advanced/formats.md) - RDF serialization
- [Vocabulary References](kastor/concepts/vocabularies.md) - Common vocabularies
- [SPARQL Reference](kastor/concepts/sparql-fundamentals.md) - Query language

## 🛠️ Development Resources

### **Examples and Samples**
- [Kastor Examples](kastor/examples/README.md) - Complete working examples
- [Kastor Gen Examples](kastor-gen/examples/README.md) - Code generation samples
- [Sample Applications](../samples/) - Full application examples

### **Troubleshooting**
- [FAQ](kastor/guides/faq.md) - Frequently asked questions
- [Troubleshooting Guide](kastor/guides/troubleshooting.md) - Common issues
- [Performance Issues](kastor/advanced/performance.md#troubleshooting) - Optimization problems

### **Best Practices**
- [Kastor Best Practices](kastor/guides/best-practices.md) - Usage guidelines
- [Kastor Gen Best Practices](kastor-gen/best-practices.md) - Code generation
- [Performance Best Practices](kastor/advanced/performance.md) - Optimization strategies

## 🌐 Community and Support

### **About Kastor**
**Kastor** is developed by **[GeoKnoesis LLC](https://geoknoesis.com/)**, a company specializing in semantic web technologies and knowledge engineering.

**Main Developer**: **Stephane Fellah** - [stephanef@geoknoesis.com](mailto:stephanef@geoknoesis.com)

### **Getting Help**
- [GitHub Issues](https://github.com/geoknoesis/kastor/issues) - Bug reports and feature requests
- [GitHub Discussions](https://github.com/geoknoesis/kastor/discussions) - Questions and community
- [Stack Overflow](https://stackoverflow.com/questions/tagged/kastor) - Technical questions
- **Direct Contact**: [stephanef@geoknoesis.com](mailto:stephanef@geoknoesis.com)

### **Support & Sponsorship**

**Kastor is open-source and free to use, but maintaining and evolving it requires ongoing effort.**

If Kastor is valuable to you or your organization, your financial support helps ensure:
- ✅ **Continued maintenance** - Bug fixes, security updates, and compatibility with new Kotlin/Jena/RDF4J versions
- ✅ **Feature development** - New capabilities and improvements based on community needs
- ✅ **Custom adaptations** - Priority consideration for features that align with your specific requirements
- ✅ **Long-term sustainability** - Keeping the project active and well-maintained for the community

**Ways to support:**
- 💰 **[GitHub Sponsors](https://github.com/sponsors/geoknoesis)** - Monthly or one-time sponsorship
- ☕ **[Ko-fi](https://ko-fi.com/geoknoesis)** - One-time donations
- 🏢 **Enterprise Support** - For organizations needing priority support, custom features, or commercial licensing: [stephanef@geoknoesis.com](mailto:stephanef@geoknoesis.com)
- 🌟 **Star the repository** - Help others discover Kastor on [GitHub](https://github.com/geoknoesis/kastor)

**For organizations:** If you're using Kastor in production or need specific features, consider enterprise sponsorship. This helps prioritize your needs and ensures the project continues to evolve in ways that benefit your use case.

### **Contributing**
- [Contributing guide](../CONTRIBUTING.md) — build, test, pull requests
- [Support](../SUPPORT.md) — questions, bugs, security routing
- [Code of Conduct](../CODE_OF_CONDUCT.md)
- [Security policy](../SECURITY.md)
- [GitHub Repository](https://github.com/geoknoesis/kastor) — source code and issues
- [Community Discussions](https://github.com/geoknoesis/kastor/discussions) — ask questions and share ideas

### **External Resources**
- [RDF 1.2 Concepts](https://www.w3.org/TR/rdf12-concepts/) — W3C RDF data model (Kastor targets RDF 1.2; see repo [CHANGELOG](../CHANGELOG.md))
- [RDF 1.1 Specification](https://www.w3.org/TR/rdf11-concepts/) — earlier RDF concepts (legacy interop)
- [SPARQL 1.1 Specification](https://www.w3.org/TR/sparql11-query/) - Query language
- [SHACL Specification](https://www.w3.org/TR/shacl/) - Validation language
- [JSON-LD Specification](https://www.w3.org/TR/json-ld11/) - JSON serialization

---

## 🎯 Navigation Tips

- **🔍 Search**: Use Ctrl+F to search within documentation
- **📱 Mobile**: Documentation is responsive for mobile reading
- **🔗 Links**: All internal links use relative paths for offline reading
- **📖 Print**: Use browser print function for PDF generation

**Need help finding something?** Check the [troubleshooting guide](kastor/guides/troubleshooting.md) or [ask the community](https://github.com/geoknoesis/kastor/discussions)!


