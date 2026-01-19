# Kastor Gen Examples

Practical examples and use cases demonstrating Kastor Gen capabilities.

## Table of Contents

- [Basic Examples](basic.md) - Simple usage patterns
- [Domain Modeling](domain-modeling.md) - Complex domain models
- [RDF Integration](rdf-integration.md) - Advanced RDF operations
- [Validation Examples](validation.md) - SHACL validation patterns
- [Performance Examples](performance.md) - Optimization techniques
- [Integration Patterns](integration.md) - Real-world integration scenarios

## Quick Start Examples

### Simple Person Model

```kotlin
@RdfClass(iri = "http://xmlns.com/foaf/0.1/Person")
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/age")
    val age: List<Int>
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/mbox")
    val email: List<String>
}

fun main() {
    val repo = Rdf.memory()
    repo.add {
        val person = iri("http://example.org/person")
        person - FOAF.name - "Alice Johnson"
        person - FOAF.age - 30
        person - FOAF.mbox - "alice@example.com"
    }
    
    val personRef = RdfRef(iri("http://example.org/person"), repo.defaultGraph)
    val person: Person = personRef.asType()
    
    println("Name: ${person.name.firstOrNull()}")
    println("Age: ${person.age.firstOrNull()}")
    println("Email: ${person.email.firstOrNull()}")
}
```

### Organization with Employees

```kotlin
@RdfClass(iri = "http://example.org/Organization")
interface Organization {
    @get:RdfProperty(iri = "http://example.org/name")
    val name: List<String>
    
    @get:RdfProperty(iri = "http://example.org/employee")
    val employees: List<Person>
}

fun main() {
    val repo = Rdf.memory()
    repo.add {
        val org = iri("http://example.org/org")
        val alice = iri("http://example.org/alice")
        val bob = iri("http://example.org/bob")
        
        // Organization
        org - RDF.type - Organization
        org - iri("http://example.org/name") - "Acme Corp"
        org - iri("http://example.org/employee") - alice
        org - iri("http://example.org/employee") - bob
        
        // Alice
        alice - RDF.type - FOAF.Person
        alice - FOAF.name - "Alice Johnson"
        alice - FOAF.age - 30
        
        // Bob
        bob - RDF.type - FOAF.Person
        bob - FOAF.name - "Bob Smith"
        bob - FOAF.age - 25
    }
    
    val orgRef = RdfRef(iri("http://example.org/org"), repo.defaultGraph)
    val org: Organization = orgRef.asType()
    
    println("Organization: ${org.name.firstOrNull()}")
    println("Employees:")
    org.employees.forEach { employee ->
        println("  - ${employee.name.firstOrNull()} (age ${employee.age.firstOrNull()})")
    }
}
```

## Example Categories

### 1. Basic Examples
- Simple domain models
- Property mapping
- Basic materialization
- Side-channel access

### 2. Domain Modeling
- Complex hierarchies
- Polymorphic collections
- Temporal data
- Business rules

### 3. RDF Integration
- Graph traversal
- Custom queries
- RDF manipulation
- Inference

### 4. Validation
- SHACL shapes
- Custom validation
- Error handling
- Performance optimization

### 5. Performance
- Caching strategies
- Batch operations
- Async processing
- Memory optimization

### 6. Integration Patterns
- Repository pattern
- Service layer
- Web APIs
- Database integration

## Getting Started

1. **Choose an example** that matches your use case
2. **Copy the code** and adapt it to your needs
3. **Add dependencies** to your project
4. **Run the example** to see it in action
5. **Modify and extend** as needed

## Contributing Examples

We welcome contributions of new examples! Please:

1. Follow the existing structure
2. Include clear comments
3. Add appropriate error handling
4. Test your examples
5. Update this README

## Need Help?

- Check the [Tutorials](../tutorials/README.md) for step-by-step guides
- Review the [API Reference](../reference/README.md) for detailed documentation
- Look at the [FAQ](../faq.md) for common questions
- See [Best Practices](../best-practices.md) for guidelines



