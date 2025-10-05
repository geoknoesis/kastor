# Kastor Documentation

Welcome to the Kastor documentation! Kastor is a comprehensive Kotlin library for working with RDF (Resource Description Framework) data, providing both low-level RDF operations and high-level domain object mapping.

## Table of Contents

- [Kastor RDF](kastor/README.md) - Core RDF functionality
- [OntoMapper](ontomapper/README.md) - Domain object mapping
- [Getting Started](#getting-started)
- [Architecture Overview](#architecture-overview)
- [Use Cases](#use-cases)
- [Community](#community)

## Getting Started

### Quick Start with Kastor RDF

```kotlin
// Create a repository
val repo = Rdf.memory()

// Add RDF data
repo.add {
    val person = iri("http://example.org/person")
    person - RDF.type - FOAF.Person
    person - FOAF.name - "John Doe"
    person - FOAF.age - 30
}

// Query the data
val results = repo.query("""
    SELECT ?name ?age WHERE {
        ?person a <http://xmlns.com/foaf/0.1/Person> ;
                <http://xmlns.com/foaf/0.1/name> ?name ;
                <http://xmlns.com/foaf/0.1/age> ?age .
    }
""")
```

### Quick Start with OntoMapper

```kotlin
// Define domain interface
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: String
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/age")
    val age: Int
}

// Materialize from RDF
val personRef = RdfRef(iri("http://example.org/person"), repo.defaultGraph)
val person: Person = personRef.asType()

// Use domain object
println("Name: ${person.name}, Age: ${person.age}")
```

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Kastor Ecosystem                        │
├─────────────────────────────────────────────────────────────────┤
│  OntoMapper (High-Level)                                       │
│  ┌─────────────────┐    ┌──────────────────┐    ┌─────────────┐│
│  │   Domain        │    │   OntoMapper     │    │   RDF       ││
│  │   Interfaces    │◄──►│   Runtime        │◄──►│   Side-     ││
│  │                 │    │                  │    │   Channel   ││
│  │ • Pure Kotlin   │    │ • Materialization│    │             ││
│  │ • No RDF deps   │    │ • Type mapping   │    │ • Extras    ││
│  │ • Business logic│    │ • Validation     │    │ • Validation││
│  └─────────────────┘    └──────────────────┘    └─────────────┘│
├─────────────────────────────────────────────────────────────────┤
│  Kastor RDF (Core)                                             │
│  ┌─────────────────┐    ┌──────────────────┐    ┌─────────────┐│
│  │   RDF Graph     │    │   Repository     │    │   Providers ││
│  │                 │    │                  │    │             ││
│  │ • Triples       │    │ • Graph mgmt     │    │ • Memory    ││
│  │ • SPARQL        │    │ • Transactions   │    │ • Jena      ││
│  │ • Serialization │    │ • Named graphs   │    │ • RDF4J     ││
│  └─────────────────┘    └──────────────────┘    └─────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

## Components

### Kastor RDF (Core)
- **Repository Management** - Multiple RDF backends (Memory, Jena, RDF4J, SPARQL)
- **Graph Operations** - Triple storage, retrieval, and manipulation
- **SPARQL Support** - Query language for RDF data
- **Serialization** - RDF/XML, Turtle, JSON-LD, and other formats
- **Transactions** - ACID transactions for data consistency

### OntoMapper (High-Level)
- **Domain Interfaces** - Pure Kotlin interfaces with no RDF dependencies
- **Automatic Materialization** - Convert RDF nodes to domain objects
- **Type Safety** - Compile-time validation of property types
- **RDF Side-Channel** - Access RDF power when needed
- **Ontology Generation** - Generate code from SHACL and JSON-LD

## Use Cases

### 📊 **Data Catalogs**
- DCAT (Data Catalog Vocabulary) compliance
- Government open data portals
- Enterprise data governance

### 🏢 **Enterprise Integration**
- RDF-based data lakes
- Semantic web applications
- Knowledge graphs

### 🔬 **Research & Academia**
- Scientific data management
- Research data repositories
- Ontology-driven applications

### 🌐 **Web Applications**
- Linked data publishing
- Semantic search
- Content management systems

## Documentation Structure

### Kastor RDF Documentation
- [Getting Started](kastor/getting-started.md) - Quick start guide
- [Core API](kastor/core-api.md) - Repository and graph operations
- [SPARQL Guide](kastor/sparql-fundamentals.md) - Query language tutorial
- [Providers](kastor/providers.md) - Backend implementations
- [Best Practices](kastor/best-practices.md) - Guidelines for effective usage
- [API Reference](kastor/reference/) - Detailed API documentation

### OntoMapper Documentation
- [Getting Started](ontomapper/tutorials/getting-started.md) - Quick start guide
- [Core Concepts](ontomapper/tutorials/core-concepts.md) - Understanding the architecture
- [Domain Modeling](ontomapper/tutorials/domain-modeling.md) - Creating domain interfaces
- [RDF Integration](ontomapper/tutorials/rdf-integration.md) - Working with RDF side-channels
- [Ontology Generation](ontomapper/tutorials/ontology-generation.md) - Generating code from SHACL/JSON-LD
- [Best Practices](ontomapper/best-practices.md) - Guidelines for effective usage
- [API Reference](ontomapper/reference/) - Detailed API documentation

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    // Kastor RDF Core
    implementation("com.geoknoesis.kastor:rdf-core:0.1.0")
    
    // OntoMapper
    implementation("com.geoknoesis.kastor:ontomapper-runtime:0.1.0")
    ksp("com.geoknoesis.kastor:ontomapper-processor:0.1.0")
    
    // Optional: Specific backends
    implementation("com.geoknoesis.kastor:rdf-jena:0.1.0")
    implementation("com.geoknoesis.kastor:rdf-rdf4j:0.1.0")
}
```

### Maven

```xml
<dependencies>
    <!-- Kastor RDF Core -->
    <dependency>
        <groupId>com.geoknoesis.kastor</groupId>
        <artifactId>rdf-core</artifactId>
        <version>0.1.0</version>
    </dependency>
    
    <!-- OntoMapper -->
    <dependency>
        <groupId>com.geoknoesis.kastor</groupId>
        <artifactId>ontomapper-runtime</artifactId>
        <version>0.1.0</version>
    </dependency>
</dependencies>
```

## Examples

### Basic RDF Operations

```kotlin
// Create repository
val repo = Rdf.memory()

// Add data
repo.add {
    val person = iri("http://example.org/person")
    person - RDF.type - FOAF.Person
    person - FOAF.name - "John Doe"
    person - FOAF.knows - iri("http://example.org/friend")
}

// Query data
val results = repo.query("""
    SELECT ?name WHERE {
        ?person <http://xmlns.com/foaf/0.1/name> ?name .
    }
""")
```

### Domain Object Mapping

```kotlin
// Define domain interface with prefix mappings
@PrefixMapping(
    prefixes = [
        Prefix("foaf", "http://xmlns.com/foaf/0.1/")
    ]
)
@RdfClass(iri = "foaf:Person")
interface Person {
    @get:RdfProperty(iri = "foaf:name")
    val name: String
    
    @get:RdfProperty(iri = "foaf:knows")
    val knows: List<Person>
}

// Materialize and use
val person: Person = personRef.asType()
println("Name: ${person.name}")
println("Friends: ${person.knows.size}")
```

### Ontology-Driven Generation

```kotlin
// Generate from SHACL and JSON-LD
@GenerateFromOntology(
    shaclPath = "ontologies/foaf.shacl.ttl",
    contextPath = "ontologies/foaf.context.jsonld",
    packageName = "com.example.generated"
)
class OntologyGenerator
```

## Community

### Getting Help
- **Documentation** - Comprehensive guides and tutorials
- **GitHub Issues** - Bug reports and feature requests
- **GitHub Discussions** - Questions and community support
- **Stack Overflow** - Use `kastor` and `ontomapper` tags

### Contributing
We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

### Development Setup

```bash
git clone https://github.com/geoknoesis/kastor.git
cd kastor
./gradlew build
```

## License

Kastor is licensed under the [Apache License 2.0](LICENSE).

## Acknowledgments

- Built with [Kotlin](https://kotlinlang.org/)
- Compatible with [Apache Jena](https://jena.apache.org/)
- Compatible with [RDF4J](https://rdf4j.org/)
- Supports [SHACL](https://www.w3.org/TR/shacl/) validation
- Follows [RDF 1.1](https://www.w3.org/TR/rdf11-concepts/) specification

---

**Ready to get started?** Check out our [Kastor RDF Getting Started Guide](kastor/getting-started.md) or [OntoMapper Getting Started Guide](ontomapper/tutorials/getting-started.md)!
