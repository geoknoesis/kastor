# RDF Vocabularies Package

This package provides commonly used RDF vocabularies as Kotlin objects, making it easy to work with standard RDF terms in a type-safe manner.

## Available Vocabularies

- **RDF** - Core RDF vocabulary (`rdf:`)
- **XSD** - XML Schema Definition datatypes (`xsd:`)
- **RDFS** - RDF Schema (`rdfs:`)
- **OWL** - Web Ontology Language (`owl:`)
- **SHACL** - Shapes Constraint Language (`sh:`)
- **SKOS** - Simple Knowledge Organization System (`skos:`)
- **DCTERMS** - Dublin Core Terms (`dcterms:`)
- **FOAF** - Friend of a Friend (`foaf:`)

## Performance Features

### Lazy Initialization
All vocabulary terms use Kotlin's `by lazy` delegate for efficient memory usage:

```kotlin
// Terms are only created when first accessed
val Person = FOAF.Person  // IRI created here
val name = FOAF.name      // IRI created here

// If you never use FOAF.age, that IRI is never created
// This is especially beneficial for large vocabularies like OWL
```

**Benefits:**
- **Memory Efficiency**: Only creates IRIs for terms you actually use
- **Startup Performance**: Faster application startup, no unnecessary object creation
- **Scalability**: Large vocabularies don't impact memory until needed
- **Idiomatic Kotlin**: Uses standard Kotlin lazy initialization patterns

## Basic Usage

### Accessing Vocabulary Terms

```kotlin
import com.geoknoesis.kastor.rdf.vocab.*

// Access RDF terms
val type = RDF.type
val subject = RDF.subject
val predicate = RDF.predicate
val `object` = RDF.`object`

// Access XSD datatypes
val stringType = XSD.string
val integerType = XSD.integer
val booleanType = XSD.boolean

// Access FOAF terms
val Person = FOAF.Person
val name = FOAF.name
val knows = FOAF.knows

// Access Dublin Core terms
val title = DCTERMS.title
val creator = DCTERMS.creator
val date = DCTERMS.date
```

### Using the Vocabulary Interface

Each vocabulary implements the `Vocabulary` interface, providing common functionality:

```kotlin
// Get the namespace and prefix
println(FOAF.namespace) // "http://xmlns.com/foaf/0.1/"
println(FOAF.prefix)    // "foaf"

// Create a term dynamically
val customTerm = FOAF.term("customProperty")

// Check if a term belongs to a vocabulary
val isFoafTerm = FOAF.contains(someIri)

// Get the local name of a term
val localName = FOAF.localname(FOAF.name) // "name"
```

### Working with Multiple Vocabularies

Use the `Vocabularies` object for cross-vocabulary operations:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.Vocabularies.*

// Find which vocabulary a term belongs to
val vocab = findVocabularyForTerm(someIri)
println(vocab?.prefix) // e.g., "foaf"

// Get the local name from any vocabulary
val localName = getLocalName(someIri)

// Check if a term is from a known vocabulary
val isKnown = isKnownTerm(someIri)

// Find vocabulary by prefix
val foafVocab = findByPrefix("foaf")

// Get all terms from a vocabulary
val foafTerms = getTermsByPrefix("foaf")
```

## Creating Custom Vocabularies

You can create custom vocabularies by implementing the `Vocabulary` interface:

```kotlin
object MyCustomVocab : Vocabulary {
    override val namespace: String = "http://example.com/vocab#"
    override val prefix: String = "ex"
    
    // Define your terms with lazy initialization
    val MyClass: Iri by lazy { term("MyClass") }
    val myProperty: Iri by lazy { term("myProperty") }
    val anotherProperty: Iri by lazy { term("anotherProperty") }
}
```

## Benefits

1. **Type Safety**: All vocabulary terms are strongly typed as `Iri` objects
2. **IntelliSense Support**: Full IDE autocomplete for vocabulary terms
3. **Namespace Management**: Automatic handling of namespaces and prefixes
4. **Consistency**: Uniform interface across all vocabularies
5. **Maintainability**: Centralized vocabulary definitions
6. **Validation**: Easy to check if terms belong to specific vocabularies
7. **Performance**: Lazy initialization ensures only used terms consume memory
8. **Scalability**: Large vocabularies don't impact startup performance

## Example: Creating RDF Data

```kotlin
import com.geoknoesis.kastor.rdf.vocab.*

// Create a person description
val person = iri("http://example.com/person/123")
val personName = "John Doe"
val personAge = 30

// Use vocabulary terms
val triple1 = person has RDF.type with FOAF.Person
val triple2 = person has FOAF.name with personName
val triple3 = person has FOAF.age with personAge

// Add to repository
repo.addTriple(null, triple1)
repo.addTriple(null, triple2)
repo.addTriple(null, triple3)
```

## Example: Working with Dublin Core Metadata

```kotlin
import com.geoknoesis.kastor.rdf.vocab.*

val document = iri("http://example.com/document/456")
val documentTitle = "Sample Document"
val documentCreator = "Jane Smith"
val documentDate = "2024-01-15"

val metadata = listOf(
    document has DCTERMS.title with documentTitle,
    document has DCTERMS.creator with documentCreator,
    document has DCTERMS.date with documentDate,
    document has DCTERMS.type with "Document"
)

metadata.forEach { triple ->
    repo.addTriple(null, triple)
}
```

## Performance Considerations

### Memory Usage
- **Before**: All IRIs created at startup, even unused ones
- **After**: IRIs created only when first accessed
- **Example**: If you only use 5 out of 50 FOAF terms, only 5 IRIs are created

### Startup Time
- **Before**: Vocabulary initialization adds to startup time
- **After**: Vocabulary initialization is deferred until needed
- **Benefit**: Faster application startup, especially with large vocabularies

### Runtime Performance
- **First Access**: Slight overhead for lazy initialization (negligible)
- **Subsequent Access**: No performance difference, same as direct access
- **Overall**: Better performance characteristics for most use cases

## Vocabulary Specifications

- **RDF**: [RDF 1.1 Concepts](https://www.w3.org/TR/rdf11-concepts/)
- **XSD**: [XML Schema Definition](https://www.w3.org/TR/xmlschema11-2/)
- **RDFS**: [RDF Schema 1.1](https://www.w3.org/TR/rdf-schema/)
- **OWL**: [OWL 2 Web Ontology Language](https://www.w3.org/TR/owl2-overview/)
- **SHACL**: [SHACL Specification](https://www.w3.org/TR/shacl/)
- **SKOS**: [SKOS Reference](https://www.w3.org/TR/skos-reference/)
- **DCTERMS**: [Dublin Core Terms](https://www.dublincore.org/specifications/dublin-core/dcmi-terms/)
- **FOAF**: [FOAF Specification](http://xmlns.com/foaf/spec/)
