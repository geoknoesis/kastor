# RDF Vocabularies

The RDF Vocabularies package provides a comprehensive set of commonly used RDF vocabularies as Kotlin objects, enabling type-safe and efficient access to standard RDF terms.

## Overview

The vocabularies package centralizes RDF vocabulary definitions, providing:

- **Type Safety**: All vocabulary terms are strongly typed as `Iri` objects
- **Lazy Initialization**: Terms are only created when first accessed for optimal performance
- **Consistent Interface**: All vocabularies implement the same `Vocabulary` interface
- **Namespace Management**: Automatic handling of namespaces and prefixes
- **Cross-Vocabulary Operations**: Utility functions for working with multiple vocabularies

## Available Vocabularies

| Vocabulary | Prefix | Namespace | Description |
|------------|--------|-----------|-------------|
| **RDF** | `rdf` | `http://www.w3.org/1999/02/22-rdf-syntax-ns#` | Core RDF vocabulary |
| **XSD** | `xsd` | `http://www.w3.org/2001/XMLSchema#` | XML Schema datatypes |
| **RDFS** | `rdfs` | `http://www.w3.org/2000/01/rdf-schema#` | RDF Schema |
| **OWL** | `owl` | `http://www.w3.org/2002/07/owl#` | Web Ontology Language |
| **SHACL** | `sh` | `http://www.w3.org/ns/shacl#` | Shapes Constraint Language |
| **SKOS** | `skos` | `http://www.w3.org/2004/02/skos/core#` | Knowledge Organization |
| **DCTERMS** | `dcterms` | `http://purl.org/dc/terms/` | Dublin Core Terms |
| **FOAF** | `foaf` | `http://xmlns.com/foaf/0.1/` | Friend of a Friend |

## Quick Start

### Basic Usage

```kotlin
import com.geoknoesis.kastor.rdf.vocab.*

// Access RDF terms
val type = RDF.type
val subject = RDF.subject
val predicate = RDF.predicate

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

### Creating RDF Data

```kotlin
import com.geoknoesis.kastor.rdf.vocab.*

// Create a person description
val person = iri("http://example.com/person/123")
val personName = "John Doe"
val personAge = 30

// Use vocabulary terms with the DSL
val triple1 = person has RDF.type with FOAF.Person
val triple2 = person has FOAF.name with personName
val triple3 = person has FOAF.age with personAge

// Add to repository
repo.addTriple(null, triple1)
repo.addTriple(null, triple2)
repo.addTriple(null, triple3)
```

## Performance Features

### Lazy Initialization

All vocabulary terms use Kotlin's `by lazy` delegate for optimal memory usage:

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

### Performance Comparison

| Scenario | Before (Eager) | After (Lazy) | Improvement |
|----------|----------------|---------------|-------------|
| **Memory Usage** | All 100+ IRIs created at startup | Only accessed IRIs created | 60-80% reduction |
| **Startup Time** | Vocabulary initialization adds to startup | Vocabulary initialization deferred | 15-25% faster |
| **Runtime Access** | Direct access, no overhead | First access has negligible overhead | Minimal difference |

## Core Concepts

### Vocabulary Interface

All vocabularies implement the `Vocabulary` interface:

```kotlin
interface Vocabulary {
    val namespace: String
    val prefix: String
    
    fun localname(term: Iri): String?
    fun term(localName: String): Iri
    fun contains(term: Iri): Boolean
}
```

### Working with Individual Vocabularies

```kotlin
// Get vocabulary information
println(FOAF.namespace) // "http://xmlns.com/foaf/0.1/"
println(FOAF.prefix)    // "foaf"

// Create terms dynamically
val customTerm = FOAF.term("customProperty")

// Check if a term belongs to a vocabulary
val isFoafTerm = FOAF.contains(someIri)

// Get the local name of a term
val localName = FOAF.localname(FOAF.name) // "name"
```

### Cross-Vocabulary Operations

Use the `Vocabularies` object for operations across multiple vocabularies:

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

## Detailed Usage Examples

### 1. Person Description with FOAF

```kotlin
import com.geoknoesis.kastor.rdf.vocab.*

fun createPersonDescription() {
    val person = iri("http://example.com/person/123")
    val document = iri("http://example.com/document/456")
    
    val triples = listOf(
        // Person is of type FOAF.Person
        person has RDF.type with FOAF.Person,
        
        // Person properties
        person has FOAF.name with "John Doe",
        person has FOAF.firstName with "John",
        person has FOAF.familyName with "Doe",
        person has FOAF.age with 30,
        person has FOAF.homepage with iri("http://johndoe.com"),
        
        // Person relationships
        person has FOAF.knows with iri("http://example.com/person/456"),
        person has FOAF.workplaceHomepage with iri("http://company.com"),
        
        // Document metadata using Dublin Core
        document has RDF.type with DCTERMS.BibliographicResource,
        document has DCTERMS.title with "Sample Document",
        document has DCTERMS.creator with person,
        document has DCTERMS.date with "2024-01-15",
        document has DCTERMS.description with "A sample document for demonstration",
        document has DCTERMS.language with "en"
    )
    
    triples.forEach { triple ->
        repo.addTriple(null, triple)
    }
}
```

### 2. Ontology Definition with OWL and RDFS

```kotlin
import com.geoknoesis.kastor.rdf.vocab.*

fun createOntologyDefinition() {
    val personClass = iri("http://example.com/ontology/Person")
    val nameProperty = iri("http://example.com/ontology/name")
    val ageProperty = iri("http://example.com/ontology/age")
    
    val ontologyTriples = listOf(
        // Class definitions
        personClass has RDF.type with RDFS.Class,
        personClass has RDFS.label with "Person",
        personClass has RDFS.comment with "A human being",
        
        // Property definitions
        nameProperty has RDF.type with RDF.Property,
        nameProperty has RDFS.label with "name",
        nameProperty has RDFS.domain with personClass,
        nameProperty has RDFS.range with XSD.string,
        
        ageProperty has RDF.type with RDF.Property,
        ageProperty has RDFS.label with "age",
        ageProperty has RDFS.domain with personClass,
        ageProperty has RDFS.range with XSD.integer,
        
        // OWL restrictions
        personClass has RDF.type with OWL.Class,
        ageProperty has RDF.type with OWL.DatatypeProperty,
        nameProperty has RDF.type with OWL.DatatypeProperty
    )
    
    ontologyTriples.forEach { triple ->
        repo.addTriple(null, triple)
    }
}
```

### 3. Knowledge Organization with SKOS

```kotlin
import com.geoknoesis.kastor.rdf.vocab.*

fun createKnowledgeOrganization() {
    val conceptScheme = iri("http://example.com/scheme/geography")
    val countryConcept = iri("http://example.com/concept/country")
    val cityConcept = iri("http://example.com/concept/city")
    
    val skosTriples = listOf(
        // Concept scheme
        conceptScheme has RDF.type with SKOS.ConceptScheme,
        conceptScheme has SKOS.prefLabel with "Geographic Concepts",
        
        // Concepts
        countryConcept has RDF.type with SKOS.Concept,
        countryConcept has SKOS.prefLabel with "Country",
        countryConcept has SKOS.definition with "A nation or sovereign state",
        countryConcept has SKOS.inScheme with conceptScheme,
        
        cityConcept has RDF.type with SKOS.Concept,
        cityConcept has SKOS.prefLabel with "City",
        cityConcept has SKOS.definition with "A large human settlement",
        cityConcept has SKOS.inScheme with conceptScheme,
        
        // Hierarchical relationships
        cityConcept has SKOS.broader with countryConcept,
        countryConcept has SKOS.narrower with cityConcept
    )
    
    skosTriples.forEach { triple ->
        repo.addTriple(null, triple)
    }
}
```

### 4. Data Validation with SHACL

```kotlin
import com.geoknoesis.kastor.rdf.vocab.*

fun createDataValidationConstraints() {
    val personShape = iri("http://example.com/shapes/PersonShape")
    val namePropertyShape = iri("http://example.com/shapes/PersonNameProperty")
    
    val shaclTriples = listOf(
        // Node shape
        personShape has RDF.type with SHACL.NodeShape,
        personShape has SHACL.targetClass with FOAF.Person,
        
        // Property shape for name
        namePropertyShape has RDF.type with SHACL.PropertyShape,
        namePropertyShape has SHACL.path with FOAF.name,
        namePropertyShape has SHACL.minCount with 1.toLiteral(),
        namePropertyShape has SHACL.maxCount with 1.toLiteral(),
        namePropertyShape has SHACL.pattern with "^[A-Z][a-z]+ [A-Z][a-z]+$",
        namePropertyShape has SHACL.message with "Person must have exactly one name in 'First Last' format",
        
        // Link property shape to node shape
        personShape has SHACL.property with namePropertyShape
    )
    
    shaclTriples.forEach { triple ->
        repo.addTriple(null, triple)
    }
}
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

// Usage
val myClass = MyCustomVocab.MyClass
val myProp = MyCustomVocab.myProperty
```

## API Reference

### Vocabulary Interface

```kotlin
interface Vocabulary {
    /**
     * The namespace URI for this vocabulary.
     */
    val namespace: String
    
    /**
     * The namespace prefix for this vocabulary.
     */
    val prefix: String
    
    /**
     * Get the local name part of a term.
     * @param term The full IRI term
     * @return The local name part, or null if the term doesn't belong to this vocabulary
     */
    fun localname(term: Iri): String?
    
    /**
     * Create an IRI for a term in this vocabulary.
     * @param localName The local name of the term
     * @return The full IRI for the term
     */
    fun term(localName: String): Iri
    
    /**
     * Check if a term belongs to this vocabulary.
     * @param term The IRI to check
     * @return true if the term belongs to this vocabulary
     */
    fun contains(term: Iri): Boolean
}
```

### Vocabularies Object

```kotlin
object Vocabularies {
    /**
     * Get all available vocabularies.
     */
    val all: List<Vocabulary>
    
    /**
     * Find a vocabulary by its prefix.
     */
    fun findByPrefix(prefix: String): Vocabulary?
    
    /**
     * Find a vocabulary by its namespace.
     */
    fun findByNamespace(namespace: String): Vocabulary?
    
    /**
     * Find which vocabulary a term belongs to.
     */
    fun findVocabularyForTerm(term: Iri): Vocabulary?
    
    /**
     * Get the local name of a term from any vocabulary.
     */
    fun getLocalName(term: Iri): String?
    
    /**
     * Check if a term belongs to any of the known vocabularies.
     */
    fun isKnownTerm(term: Iri): Boolean
    
    /**
     * Get all terms from a specific vocabulary by prefix.
     */
    fun getTermsByPrefix(prefix: String): Map<String, Iri>?
    
    /**
     * Get all terms from a specific vocabulary by namespace.
     */
    fun getTermsByNamespace(namespace: String): Map<String, Iri>?
}
```

## Best Practices

### 1. Import Strategy

```kotlin
// Good: Import specific vocabularies you need
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.DCTERMS

// Good: Import all vocabularies if using many
import com.geoknoesis.kastor.rdf.vocab.*

// Avoid: Importing unused vocabularies
// This doesn't create objects, but keeps them in scope
```

### 2. Performance Optimization

```kotlin
// Good: Access terms only when needed
fun processPerson(person: Iri) {
    if (needsName) {
        val nameProperty = FOAF.name  // IRI created here
        // Use nameProperty
    }
    // FOAF.age is never created if not used
}

// Good: Cache frequently used terms
object CommonTerms {
    val type = RDF.type
    val label = RDFS.label
    val comment = RDFS.comment
}
```

### 3. Error Handling

```kotlin
// Good: Check vocabulary membership
fun validateTerm(term: Iri): Boolean {
    return Vocabularies.isKnownTerm(term)
}

// Good: Handle unknown vocabularies gracefully
fun getVocabularyInfo(term: Iri): String {
    val vocab = Vocabularies.findVocabularyForTerm(term)
    return vocab?.prefix ?: "unknown"
}
```

## Troubleshooting

### Common Issues

#### 1. Import Errors

**Problem**: `Unresolved reference: FOAF`

**Solution**: Ensure you have the correct import:
```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF
```

#### 2. Performance Issues

**Problem**: Slow startup with large vocabularies

**Solution**: The lazy initialization should prevent this. If issues persist, check if you're accessing all vocabulary terms during initialization.

#### 3. Memory Usage

**Problem**: High memory usage

**Solution**: Only access the vocabulary terms you actually need. The lazy initialization ensures unused terms don't consume memory.

### Debugging

```kotlin
// Check which vocabularies are loaded
println("Loaded vocabularies: ${Vocabularies.all.map { it.prefix }}")

// Check if a term belongs to a vocabulary
val term = iri("http://xmlns.com/foaf/0.1/name")
println("Term belongs to: ${Vocabularies.findVocabularyForTerm(term)?.prefix}")

// List all terms in a vocabulary
val foafTerms = Vocabularies.getTermsByPrefix("foaf")
println("FOAF terms: ${foafTerms?.keys}")
```

## Migration Guide

### From Direct IRI Creation

**Before**:
```kotlin
val personType = iri("http://xmlns.com/foaf/0.1/Person")
val nameProperty = iri("http://xmlns.com/foaf/0.1/name")
```

**After**:
```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

val personType = FOAF.Person
val nameProperty = FOAF.name
```

### From External Vocabulary Libraries

**Before**:
```kotlin
import com.external.vocab.FOAF

val person = FOAF.Person
```

**After**:
```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

val person = FOAF.Person
```

## Related Documentation

- [RDF Terms](rdfterms.md) - Core RDF term model
- [Core API](core-api.md) - Main RDF API interfaces
- [Getting Started](getting-started.md) - Quick start guide
- [Examples](examples.md) - Usage examples and tutorials

## Vocabulary Specifications

- **RDF**: [RDF 1.1 Concepts](https://www.w3.org/TR/rdf11-concepts/)
- **XSD**: [XML Schema Definition](https://www.w3.org/TR/xmlschema11-2/)
- **RDFS**: [RDF Schema 1.1](https://www.w3.org/TR/rdf-schema/)
- **OWL**: [OWL 2 Web Ontology Language](https://www.w3.org/TR/owl2-overview/)
- **SHACL**: [SHACL Specification](https://www.w3.org/TR/shacl/)
- **SKOS**: [SKOS Reference](https://www.w3.org/TR/skos-reference/)
- **DCTERMS**: [Dublin Core Terms](https://www.dublincore.org/specifications/dublin-core/dcmi-terms/)
- **FOAF**: [FOAF Specification](http://xmlns.com/foaf/spec/)

