# Kastor

A comprehensive Kotlin library for working with RDF (Resource Description Framework) data, providing both low-level RDF operations and high-level domain object mapping.

## Why "Kastor"?

The name **Kastor** is a tribute to [Castor](http://www.castor.org/), the pioneering Java XML data binding framework developed by Exolab. Castor was one of the first frameworks to bridge the gap between object-oriented programming and structured data formats, making it easier for developers to work with XML data in Java applications.

Just as Castor revolutionized XML data binding in Java, Kastor aims to bring the same level of developer experience to RDF and semantic web technologies in Kotlin. The "K" in Kastor represents:

- **Kotlin** - Our chosen language for modern, expressive, and type-safe development
- **Knowledge** - The semantic web's focus on knowledge representation and reasoning

Kastor honors Castor's legacy while embracing the future of semantic technologies and the Kotlin ecosystem.

## Features

### Kastor RDF (Core)
- **Repository Management** - Multiple RDF backends (Memory, Jena, RDF4J, SPARQL)
- **Graph Operations** - Triple storage, retrieval, and manipulation
- **SPARQL Support** - Query language for RDF data
- **Serialization** - RDF/XML, Turtle, JSON-LD, and other formats
- **Transactions** - ACID transactions for data consistency
- **Graph Isomorphism** - Advanced blank node comparison algorithms

### OntoMapper (High-Level)
- **Domain Interfaces** - Pure Kotlin interfaces with no RDF dependencies
- **Automatic Materialization** - Convert RDF nodes to domain objects
- **Type Safety** - Compile-time validation of property types
- **RDF Side-Channel** - Access RDF power when needed
- **Ontology Generation** - Generate code from SHACL and JSON-LD
- **Gradle Plugin** - Seamless integration with build systems

## Quick Start

### Basic RDF Operations

```kotlin
// Create a repository
val repo = Rdf.memory()

// Add RDF data using QNames
repo.add {
    prefixes {
        "foaf" to "http://xmlns.com/foaf/0.1/"
        "rdf" to "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    }
    
    val person = iri("http://example.org/person")
    person - "rdf:type" - "foaf:Person"
    person - "foaf:name" - "John Doe"
    person - "foaf:age" - 30
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

### Domain Object Mapping

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

## Documentation

- **[Kastor RDF Documentation](docs/kastor/README.md)** - Core RDF functionality
- **[OntoMapper Documentation](docs/ontomapper/README.md)** - Domain object mapping
- **[Getting Started Guide](docs/kastor/getting-started.md)** - Quick start tutorial
- **[API Reference](docs/kastor/reference/)** - Detailed API documentation

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

## Development

### Building from Source

```bash
git clone https://github.com/geoknoesis/kastor.git
cd kastor
./gradlew build
```

### Running Tests

```bash
./gradlew test
```

## License

Kastor is licensed under the [Apache License 2.0](LICENSE).

## Acknowledgments

- Built with [Kotlin](https://kotlinlang.org/)
- Compatible with [Apache Jena](https://jena.apache.org/)
- Compatible with [RDF4J](https://rdf4j.org/)
- Supports [SHACL](https://www.w3.org/TR/shacl/) validation
- Follows [RDF 1.1](https://www.w3.org/TR/rdf11-concepts/) specification
- Inspired by [Castor](http://www.castor.org/) XML data binding framework

---

**Ready to get started?** Check out our [Getting Started Guide](docs/kastor/getting-started.md) or explore the [examples](samples/)!
