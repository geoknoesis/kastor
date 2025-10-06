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
- **Intuitive DSL** - Turtle-style "a" and natural language "is" aliases for rdf:type

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
    
    // Turtle-style "a" alias for rdf:type
    person["a"] = "foaf:Person"
    person - a - "foaf:Agent"      // Without quotes
    person - "a" - "foaf:Agent"    // With quotes
    
    // Natural language "is" alias for rdf:type
    person `is` "foaf:Agent"
    
    // Smart QName detection in object position
    person - "foaf:knows" - "foaf:Person"        // QName with declared prefix → IRI
    person - "foaf:name" - "Alice"               // String without colon → string literal
    person - "foaf:homepage" - "http://example.org"  // Full IRI → IRI
    
    // Traditional syntax (still works)
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

## Smart QName Detection

Kastor automatically detects and resolves QNames in object position, making the DSL more intuitive:

```kotlin
repo.add {
    // Built-in prefixes: rdf, rdfs, owl, sh, xsd (no need to declare!)
    prefixes {
        put("foaf", "http://xmlns.com/foaf/0.1/")
    }
    
    val person = iri("http://example.org/person")
    
    // ✅ Smart: QName with declared prefix → IRI
    person - "foaf:knows" - "foaf:Person"        // → <http://xmlns.com/foaf/0.1/Person>
    person - "rdfs:subClassOf" - "foaf:Agent"    // → <http://xmlns.com/foaf/0.1/Agent>
    
    // ✅ Smart: Full IRI → IRI
    person - "foaf:homepage" - "http://example.org/profile"  // → <http://example.org/profile>
    
    // ✅ Smart: String without colon → string literal
    person - "foaf:name" - "Alice"               // → "Alice"^^xsd:string
    person - "foaf:age" - 30                     // → "30"^^xsd:integer
    
    // ✅ Smart: Undeclared prefix → string literal (safe fallback)
    person - "foaf:note" - "unknown:Person"      // → "unknown:Person"^^xsd:string
    
    // ✅ Special case: rdf:type always resolves QNames
    person["a"] = "foaf:Person"                  // → <http://xmlns.com/foaf/0.1/Person>
    person `is` "foaf:Agent"                     // → <http://xmlns.com/foaf/0.1/Agent>
}
```

### Explicit Control

When you need explicit control, use the dedicated functions:

```kotlin
// Explicit QName resolution
person - "foaf:knows" - qname("foaf:Person")    // → <http://xmlns.com/foaf/0.1/Person>

// Explicit string literal
person - "foaf:name" - literal("foaf:Person")   // → "foaf:Person"^^xsd:string

// Explicit IRI
person - "foaf:homepage" - iri("http://example.org")  // → <http://example.org>

// Language-tagged literals
person - "foaf:name" - lang("Alice", "en")      // → "Alice"@en

// Typed literals
person - "foaf:age" - typed("30", XSD.integer)  // → "30"^^xsd:integer
```

## Built-in Prefixes

Kastor comes with built-in prefixes for the most common vocabularies, so you don't need to declare them:

```kotlin
repo.add {
    // ✅ Built-in prefixes available immediately:
    // rdf:   → http://www.w3.org/1999/02/22-rdf-syntax-ns#
    // rdfs:  → http://www.w3.org/2000/01/rdf-schema#
    // owl:   → http://www.w3.org/2002/07/owl#
    // sh:    → http://www.w3.org/ns/shacl#
    // xsd:   → http://www.w3.org/2001/XMLSchema#
    
    val person = iri("http://example.org/person")
    
    // Use built-in prefixes directly - no declaration needed!
    person["rdf:type"] = "rdfs:Class"              // → <http://www.w3.org/2000/01/rdf-schema#Class>
    person - "rdfs:label" - "Person Class"         // → "Person Class"^^xsd:string
    person - "owl:sameAs" - "http://example.org/person2"  // → <http://example.org/person2>
    person - "sh:targetClass" - "rdfs:Class"       // → <http://www.w3.org/2000/01/rdf-schema#Class>
    
    // Mix with custom prefixes
    prefixes {
        put("foaf", "http://xmlns.com/foaf/0.1/")
    }
    person - "foaf:name" - "Alice"                 // Custom prefix
    person - "rdfs:comment" - "A person"           // Built-in prefix
}
```

### Override Built-in Prefixes

You can override built-in prefixes if needed:

```kotlin
repo.add {
    prefixes {
        put("rdf", "http://example.org/custom-rdf#")  // Override built-in rdf prefix
        put("foaf", "http://xmlns.com/foaf/0.1/")     // Custom prefix
    }
    
    val resource = iri("http://example.org/resource")
    resource["rdf:type"] = "rdf:CustomType"  // Uses custom namespace
}
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
- **[Getting Started Guide](docs/kastor/getting-started/README.md)** - Quick start tutorial
- **[API Reference](docs/kastor/api/)** - Detailed API documentation

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

## About

**Kastor** is developed by **[GeoKnoesis LLC](https://geoknoesis.com/)**, a company specializing in semantic web technologies and knowledge engineering.

### Main Developer
- **Stephane Fellah** - Principal Developer
- **Contact**: [stephanef@geoknoesis.com](mailto:stephanef@geoknoesis.com)

### Support
We welcome community support and contributions! If you find Kastor useful, please consider:

- 🌟 **Star the repository** on [GitHub](https://github.com/geoknoesis/kastor)
- 🐛 **Report issues** or suggest improvements
- 💬 **Join discussions** in our [GitHub Discussions](https://github.com/geoknoesis/kastor/discussions)
- 📖 **Improve documentation** through pull requests

### Financial Support
If you'd like to support the development of Kastor financially, you can:

- 💰 **Sponsor the project** on [GitHub Sponsors](https://github.com/sponsors/geoknoesis)
- ☕ **Buy us a coffee** via [Ko-fi](https://ko-fi.com/geoknoesis)
- 🏢 **Enterprise support** - Contact us at [stephanef@geoknoesis.com](mailto:stephanef@geoknoesis.com) for commercial licensing and support options

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

**Ready to get started?** Check out our [Getting Started Guide](docs/kastor/getting-started/README.md) or explore the [examples](samples/)!
