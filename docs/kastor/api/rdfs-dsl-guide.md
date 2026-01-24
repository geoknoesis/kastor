# RDFS DSL Guide

{% include version-banner.md %}

The RDFS DSL provides a type-safe, natural language syntax for creating RDF Schema ontologies in Kotlin. Instead of manually creating RDF triples, you can use an intuitive DSL that makes RDFS class and property definitions readable and maintainable.

## Overview

The RDFS DSL allows you to define RDF Schema vocabularies using Kotlin code that reads like natural language. It supports **all RDFS 1.1 constructs**, including classes, properties, datatypes, and their relationships. The DSL generates standard RDF that works with any RDF-compliant system.

### Benefits

- **Type-safe**: Compile-time validation of RDFS constructs
- **Readable**: Natural language syntax that's easy to understand
- **Maintainable**: Less boilerplate than manual RDF triple creation
- **Complete**: Supports all RDFS 1.1 vocabulary constructs
- **Standard**: Generates standard RDF/Turtle that works with any RDF system

## Quick Start

### Basic Example

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.dsl.rdfs
import com.geoknoesis.kastor.rdf.vocab.RDFS

val ontology = rdfs {
    `class`("http://example.org/Person") {
        label("Person", "en")
        comment("A human being", "en")
        subClassOf(RDFS.Resource)
    }
    
    property("http://example.org/name") {
        label("Name", "en")
        domain("http://example.org/Person")
        range(RDFS.Literal)
    }
}
```

## Classes

### Basic Class Definition

```kotlin
val ontology = rdfs {
    `class`("http://example.org/Person") {
        label("Person", "en")
        comment("A human being", "en")
    }
}
```

### Class Hierarchies

Define class hierarchies using `subClassOf`:

```kotlin
val ontology = rdfs {
    `class`("http://example.org/Animal") {
        subClassOf(RDFS.Resource)
    }
    
    `class`("http://example.org/Mammal") {
        subClassOf("http://example.org/Animal")
    }
    
    `class`("http://example.org/Dog") {
        subClassOf("http://example.org/Mammal")
    }
}
```

You can use either IRI strings or IRI objects:

```kotlin
`class`("http://example.org/Person") {
    subClassOf(RDFS.Resource)  // Using IRI constant
    subClassOf("http://example.org/Animal")  // Using IRI string
    subClassOf(Iri("http://example.org/Mammal"))  // Using IRI object
}
```

### Labels and Comments

Add human-readable labels and comments with optional language tags:

```kotlin
`class`("http://example.org/Person") {
    // Single label without language tag
    label("Person")
    
    // Multiple labels with language tags
    label("Person", "en")
    label("Personne", "fr")
    label("Persona", "es")
    
    // Comments
    comment("A human being", "en")
    comment("Un être humain", "fr")
}
```

### Documentation Links

Add `seeAlso` and `isDefinedBy` links:

```kotlin
`class`("http://example.org/Person") {
    seeAlso("http://example.org/docs/Person")
    isDefinedBy("http://example.org/ontology")
}
```

## Properties

### Basic Property Definition

```kotlin
val ontology = rdfs {
    property("http://example.org/name") {
        label("Name", "en")
        comment("The name of a person", "en")
    }
}
```

### Domain and Range

Specify the domain (subject type) and range (object type) of properties:

```kotlin
property("http://example.org/name") {
    domain("http://example.org/Person")
    range(RDFS.Literal)
}
```

You can specify multiple domains or ranges (RDFS allows this):

```kotlin
property("http://example.org/name") {
    // Multiple domains
    domains("http://example.org/Person", "http://example.org/Organization")
    
    // Multiple ranges
    ranges(RDFS.Literal, XSD.string)
}
```

### Property Hierarchies

Define property hierarchies using `subPropertyOf`:

```kotlin
val ontology = rdfs {
    property("http://example.org/name") {
        label("Name")
    }
    
    property("http://example.org/firstName") {
        subPropertyOf("http://example.org/name")
    }
    
    property("http://example.org/lastName") {
        subPropertyOf("http://example.org/name")
    }
}
```

### Complete Property Example

```kotlin
property("http://example.org/name") {
    label("Name", "en")
    comment("The name of a person", "en")
    domain("http://example.org/Person")
    range(RDFS.Literal)
    subPropertyOf(RDF.value)
    seeAlso("http://example.org/docs/name")
    isDefinedBy("http://example.org/ontology")
}
```

## Datatypes

Define custom RDFS datatypes:

```kotlin
val ontology = rdfs {
    datatype("http://example.org/EmailAddress") {
        label("Email Address", "en")
        comment("A valid email address", "en")
        seeAlso("http://example.org/docs/EmailAddress")
        isDefinedBy("http://example.org/ontology")
    }
}
```

## Prefixes and QNames

### Single Prefix

```kotlin
val ontology = rdfs {
    prefix("ex", "http://example.org/")
    
    `class`("ex:Person") {
        label("Person")
    }
    
    property("ex:name") {
        domain("ex:Person")
    }
}
```

### Multiple Prefixes

```kotlin
val ontology = rdfs {
    prefixes {
        put("ex", "http://example.org/")
        put("foaf", "http://xmlns.com/foaf/0.1/")
        put("rdfs", "http://www.w3.org/2000/01/rdf-schema#")
    }
    
    `class`("ex:Person") {
        subClassOf("foaf:Person")
    }
}
```

## Complete Example

Here's a complete RDFS ontology example:

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.dsl.rdfs
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.XSD

val ontology = rdfs {
    prefix("ex", "http://example.org/")
    
    // Define classes
    `class`("ex:Person") {
        label("Person", "en")
        comment("A human being", "en")
        subClassOf(RDFS.Resource)
        seeAlso("http://example.org/docs/Person")
    }
    
    `class`("ex:Student") {
        label("Student", "en")
        subClassOf("ex:Person")
    }
    
    // Define properties
    property("ex:name") {
        label("Name", "en")
        comment("The name of a person", "en")
        domain("ex:Person")
        range(RDFS.Literal)
    }
    
    property("ex:age") {
        label("Age", "en")
        domain("ex:Person")
        range(XSD.integer)
    }
    
    property("ex:email") {
        label("Email", "en")
        domain("ex:Person")
        range(XSD.string)
    }
    
    property("ex:firstName") {
        label("First Name", "en")
        subPropertyOf("ex:name")
        domain("ex:Person")
        range(RDFS.Literal)
    }
    
    property("ex:lastName") {
        label("Last Name", "en")
        subPropertyOf("ex:name")
        domain("ex:Person")
        range(RDFS.Literal)
    }
    
    // Define a datatype
    datatype("ex:EmailAddress") {
        label("Email Address", "en")
        comment("A valid email address format", "en")
    }
}
```

## Advanced Usage

### Direct Triple Addition

For advanced use cases, you can add triples directly:

```kotlin
val ontology = rdfs {
    val person = Iri("http://example.org/Person")
    triple(person, RDF.type, RDFS.Class)
    triple(person, RDFS.label, string("Person"))
}
```

### Building the Graph

The `rdfs` function returns a `MutableRdfGraph` that you can use with any RDF system:

```kotlin
val ontology = rdfs {
    `class`("http://example.org/Person") {
        label("Person")
    }
}

// Use the graph
val triples = ontology.getTriples()
val repo = Rdf.memory()
repo.defaultGraph.addAll(ontology.getTriples())
```

## Best Practices

### 1. Use Prefixes

Always use prefixes to make your code more readable:

```kotlin
// Good
rdfs {
    prefix("ex", "http://example.org/")
    `class`("ex:Person") { ... }
}

// Less readable
rdfs {
    `class`("http://example.org/Person") { ... }
}
```

### 2. Provide Labels and Comments

Always provide labels and comments for better documentation:

```kotlin
`class`("ex:Person") {
    label("Person", "en")
    comment("A human being in our domain model", "en")
}
```

### 3. Organize by Type

Group related definitions together:

```kotlin
rdfs {
    // All classes first
    `class`("ex:Person") { ... }
    `class`("ex:Organization") { ... }
    
    // Then properties
    property("ex:name") { ... }
    property("ex:email") { ... }
    
    // Then datatypes
    datatype("ex:EmailAddress") { ... }
}
```

### 4. Use IRI Constants When Available

Prefer using vocabulary constants when available:

```kotlin
property("ex:name") {
    range(RDFS.Literal)  // Better than string("http://www.w3.org/2000/01/rdf-schema#Literal")
}
```

## API Reference

### Top-Level Function

- **`rdfs(block: RdfsDsl.() -> Unit): MutableRdfGraph`** - Creates an RDFS ontology graph

### RdfsDsl Methods

- **`prefix(name: String, namespace: String)`** - Add a single prefix mapping
- **`prefixes(configure: MutableMap<String, String>.() -> Unit)`** - Configure multiple prefixes
- **`class(classIri: String, configure: RdfsClassDsl.() -> Unit)`** - Define an RDFS class
- **`property(propertyIri: String, configure: RdfsPropertyDsl.() -> Unit)`** - Define an RDFS property
- **`datatype(datatypeIri: String, configure: RdfsDatatypeDsl.() -> Unit)`** - Define an RDFS datatype
- **`triple(subject: RdfResource, predicate: Iri, obj: RdfTerm)`** - Add a direct triple

### RdfsClassDsl Methods

- **`label(value: String, language: String? = null)`** - Add an rdfs:label
- **`comment(value: String, language: String? = null)`** - Add an rdfs:comment
- **`subClassOf(superClass: RdfResource)`** - Declare subclass relationship
- **`subClassOf(superClassIri: String)`** - Declare subclass relationship (by IRI string)
- **`seeAlso(iri: Iri)`** / **`seeAlso(iriString: String)`** - Add rdfs:seeAlso link
- **`isDefinedBy(iri: Iri)`** / **`isDefinedBy(iriString: String)`** - Add rdfs:isDefinedBy link

### RdfsPropertyDsl Methods

- **`label(value: String, language: String? = null)`** - Add an rdfs:label
- **`comment(value: String, language: String? = null)`** - Add an rdfs:comment
- **`domain(domainClass: RdfResource)`** / **`domain(domainClassIri: String)`** - Declare domain
- **`domains(vararg domainClasses: RdfResource)`** / **`domains(vararg domainClassIris: String)`** - Declare multiple domains
- **`range(rangeClass: RdfResource)`** / **`range(rangeClassIri: String)`** - Declare range
- **`ranges(vararg rangeClasses: RdfResource)`** / **`ranges(vararg rangeClassIris: String)`** - Declare multiple ranges
- **`subPropertyOf(superProperty: RdfResource)`** / **`subPropertyOf(superPropertyIri: String)`** - Declare subproperty relationship
- **`seeAlso(iri: Iri)`** / **`seeAlso(iriString: String)`** - Add rdfs:seeAlso link
- **`isDefinedBy(iri: Iri)`** / **`isDefinedBy(iriString: String)`** - Add rdfs:isDefinedBy link

### RdfsDatatypeDsl Methods

- **`label(value: String, language: String? = null)`** - Add an rdfs:label
- **`comment(value: String, language: String? = null)`** - Add an rdfs:comment
- **`seeAlso(iri: Iri)`** / **`seeAlso(iriString: String)`** - Add rdfs:seeAlso link
- **`isDefinedBy(iri: Iri)`** / **`isDefinedBy(iriString: String)`** - Add rdfs:isDefinedBy link

## See Also

- [OWL DSL Guide](owl-dsl-guide.md) - For more expressive ontologies
- [SHACL DSL Guide](shacl-dsl-guide.md) - For data validation
- [RDF Fundamentals](../concepts/rdf-fundamentals.md) - Understanding RDF basics
- [Vocabularies Guide](../concepts/vocabularies.md) - Working with RDF vocabularies


