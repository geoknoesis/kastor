# Frequently Asked Questions (FAQ)

This FAQ addresses common questions about Kastor Gen.

## Table of Contents

- [General Questions](#general-questions)
- [Domain Interface Design](#domain-interface-design)
- [RDF Integration](#rdf-integration)
- [Ontology Generation](#ontology-generation)
- [Performance](#performance)
- [Troubleshooting](#troubleshooting)
- [Migration](#migration)

## General Questions

### What is Kastor Gen?

Kastor Gen is a Kotlin library that bridges the gap between RDF ontologies and domain objects. It provides:

- **Pure domain interfaces** with no RDF dependencies
- **Automatic materialization** from RDF to domain objects
- **RDF side-channel access** for advanced use cases
- **Ontology-driven code generation** from SHACL and JSON-LD

### How is Kastor Gen different from other RDF libraries?

Unlike traditional RDF libraries that expose RDF types directly, Kastor Gen provides:

- **Pure domain objects** - Your business code doesn't know about RDF
- **Type safety** - Compile-time validation of property types
- **Lazy evaluation** - Properties are loaded only when accessed
- **Side-channel access** - RDF power when you need it

### What RDF backends does Kastor Gen support?

Kastor Gen supports multiple RDF backends through Kastor:

- **Apache Jena** - Full-featured RDF framework
- **RDF4J** - Eclipse RDF4J framework
- **SPARQL** - Remote SPARQL endpoints
- **Memory** - In-memory RDF storage

## Domain Interface Design

### How do I design domain interfaces?

Follow these principles:

1. **Keep interfaces pure** - No RDF types in domain interfaces
2. **Use business-focused names** - Choose names that reflect business concepts
3. **Handle cardinality appropriately** - Use `List<T>` for multiple values
4. **Make properties immutable** - Use `val` instead of `var`

```kotlin
@RdfClass(iri = "http://www.w3.org/ns/dcat#Catalog")
interface Catalog {
    @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
    val title: String
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#dataset")
    val dataset: List<Dataset>
}
```

### Can I use inheritance in domain interfaces?

Yes, but keep it simple:

```kotlin
// ✅ Good: Simple inheritance
interface Resource {
    val iri: String
}

interface Catalog : Resource {
    val title: String
}

// ❌ Avoid: Complex inheritance
interface ComplexCatalog : Resource, Temporal, Spatial {
    // Can cause issues with property resolution
}
```

### How do I handle optional properties?

Use nullable types for optional properties:

```kotlin
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: String // Required
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/age")
    val age: Int? // Optional
}
```

### Can I use custom types in domain interfaces?

Yes, but you need to register them:

```kotlin
// Define custom type
data class CustomDate(val year: Int, val month: Int, val day: Int)

// Register converter
kastor.gen.registry[CustomDate::class.java] = { handle ->
    // Custom materialization logic
    CustomDate(2024, 1, 1)
}
```

## RDF Integration

### How do I access RDF side-channel?

Use the `asRdf()` extension function:

```kotlin
val catalog: Catalog = catalogRef.asType()
val rdfHandle = catalog.asRdf()

// Access unmapped properties
val extras = rdfHandle.extras
val altLabels = extras.strings(SKOS.altLabel)

// Validate against SHACL
rdfHandle.validateOrThrow()
```

### How do I handle validation?

OntoMapper supports SHACL validation:

```kotlin
// Validate during materialization
val catalog: Catalog = catalogRef.asType(validate = true)

// Or validate explicitly
val rdfHandle = catalog.asRdf()
try {
    rdfHandle.validateOrThrow()
} catch (e: ValidationException) {
    // Handle validation error
}
```

### How do I access unmapped properties?

Use the `extras` property bag:

```kotlin
val rdfHandle = catalog.asRdf()
val extras = rdfHandle.extras

// Get all predicates
val predicates = extras.predicates()

// Get values for specific predicate
val values = extras.values(SKOS.altLabel)
val strings = extras.strings(SKOS.altLabel)
val iris = extras.iris(SKOS.altLabel)

// Materialize objects
val objects = extras.objects(SKOS.altLabel, SomeType::class.java)
```

### How do I handle different RDF backends?

Choose the appropriate backend for your needs:

```kotlin
// Memory backend (development)
val repo = Rdf.memory()

// Jena backend (production)
val repo = Rdf.jena()

// RDF4J backend (production)
val repo = Rdf.rdf4j()

// SPARQL backend (remote)
val repo = Rdf.sparql("http://example.org/sparql")
```

## Ontology Generation

### How do I generate code from SHACL and JSON-LD?

Use the `@GenerateFromOntology` annotation:

```kotlin
@GenerateFromOntology(
    shaclPath = "ontologies/dcat.shacl.ttl",
    contextPath = "ontologies/dcat.context.jsonld",
    packageName = "com.example.generated"
)
class OntologyGenerator
```

### What SHACL features are supported?

Currently supported:
- NodeShapes with `sh:targetClass`
- Property constraints with `sh:path`
- Datatype constraints with `sh:datatype`
- Cardinality constraints with `sh:minCount`/`sh:maxCount`
- Class constraints with `sh:class`

Not yet supported:
- Complex constraint combinations (`sh:or`, `sh:and`)
- Custom validation rules
- Shape inheritance

### How do I handle custom datatypes?

Map custom datatypes to Kotlin types:

```kotlin
// In SHACL
sh:property [
    sh:path custom:customDate ;
    sh:datatype custom:CustomDate ;
] .

// In JSON-LD context
"customDate": {
  "@id": "http://example.org/customDate",
  "@type": "custom:CustomDate"
}
```

### Can I generate code from multiple ontologies?

Yes, use multiple generator classes:

```kotlin
@GenerateFromOntology(
    shaclPath = "ontologies/dcat.shacl.ttl",
    contextPath = "ontologies/dcat.context.jsonld",
    packageName = "com.example.dcatus.generated"
)
class DcatGenerator

@GenerateFromOntology(
    shaclPath = "ontologies/foaf.shacl.ttl",
    contextPath = "ontologies/foaf.context.jsonld",
    packageName = "com.example.foaf.generated"
)
class FoafGenerator
```

## Performance

### How does OntoMapper handle performance?

OntoMapper is optimized for performance:

- **Lazy evaluation** - Properties are loaded only when accessed
- **Efficient graph traversal** - Uses optimized RDF graph operations
- **Minimal memory footprint** - Only loads what you need
- **Caching** - Results are cached for repeated access

### How do I optimize for large datasets?

For large datasets:

1. **Use side-channel access** for bulk operations
2. **Minimize property access** - Only access what you need
3. **Use appropriate backends** - Choose backend based on data size
4. **Implement pagination** - For large result sets

```kotlin
// ✅ Good: Bulk access
val rdfHandle = catalog.asRdf()
val extras = rdfHandle.extras
val allPredicates = extras.predicates()

// ❌ Avoid: Individual property access
val catalog: Catalog = catalogRef.asType()
val title = catalog.title
val description = catalog.description
```

### How do I handle memory usage?

To minimize memory usage:

1. **Use lazy evaluation** - Properties are loaded on demand
2. **Close RDF repositories** - When done with them
3. **Use appropriate backends** - Memory backend for small datasets
4. **Implement streaming** - For very large datasets

```kotlin
// ✅ Good: Proper resource management
val repo = Rdf.memory()
try {
    val catalog: Catalog = catalogRef.asType()
    // Use catalog
} finally {
    repo.close()
}
```

## Troubleshooting

### Why is my generated code not compiling?

Common issues:

1. **Missing dependencies** - Ensure KSP processor is configured
2. **Annotation processing** - Check KSP configuration
3. **Package conflicts** - Ensure unique package names
4. **Type mismatches** - Check SHACL datatype mappings

### Why are my properties not loading?

Check these:

1. **Property annotations** - Ensure `@get:RdfProperty` is correct
2. **IRI matching** - Verify IRI matches RDF data
3. **Type mapping** - Check datatype conversion
4. **Graph content** - Ensure RDF data exists

### How do I debug materialization issues?

Use debugging techniques:

```kotlin
// Enable debug logging
val repo = Rdf.memory()
repo.add { /* your RDF data */ }

// Check graph content
val triples = repo.defaultGraph.getTriples()
println("Graph has ${triples.size} triples")

// Check specific node
val node = iri("http://example.org/catalog")
val nodeTriples = triples.filter { it.subject == node }
println("Node has ${nodeTriples.size} triples")

// Try materialization
val catalog: Catalog = catalogRef.asType()
```

### Why is validation failing?

Common validation issues:

1. **Missing required properties** - Check SHACL constraints
2. **Wrong datatypes** - Verify property types
3. **Cardinality violations** - Check min/max counts
4. **Shape mismatches** - Ensure correct target classes

### How do I handle circular references?

Circular references are handled automatically:

```kotlin
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/knows")
    val knows: List<Person> // Circular reference
}

// OntoMapper handles this automatically
val person: Person = personRef.asType()
val friends = person.knows
val friendsOfFriends = friends.flatMap { it.knows }
```

## Migration

### How do I migrate from other RDF libraries?

Migration steps:

1. **Define domain interfaces** - Create pure Kotlin interfaces
2. **Map RDF properties** - Add `@RdfProperty` annotations
3. **Replace RDF access** - Use OntoMapper materialization
4. **Update business logic** - Work with domain objects
5. **Add side-channel access** - For advanced RDF operations

### How do I migrate from manual wrappers?

If you have manual wrapper classes:

1. **Extract interfaces** - Create domain interfaces
2. **Replace manual code** - Use generated wrappers
3. **Update registrations** - Use OntoMapper registry
4. **Test thoroughly** - Ensure behavior is preserved

### How do I upgrade OntoMapper versions?

Upgrade process:

1. **Check changelog** - Review breaking changes
2. **Update dependencies** - Use latest versions
3. **Run tests** - Ensure compatibility
4. **Update code** - Apply any required changes
5. **Validate** - Test with your data

## Getting Help

### Where can I get help?

- **Documentation** - Check the [documentation](README.md)
- **Examples** - See [sample applications](examples/)
- **GitHub Issues** - Report bugs and request features
- **GitHub Discussions** - Ask questions and share ideas
- **Stack Overflow** - Use the `kastor-gen` tag

### How do I report bugs?

When reporting bugs, include:

1. **OntoMapper version** - Version you're using
2. **Kotlin version** - Kotlin version
3. **RDF backend** - Which backend you're using
4. **Reproduction steps** - How to reproduce the issue
5. **Expected behavior** - What you expected to happen
6. **Actual behavior** - What actually happened
7. **Sample code** - Minimal code to reproduce
8. **Sample data** - RDF data that causes the issue

### How do I request features?

Feature requests should include:

1. **Use case** - Why you need this feature
2. **Proposed solution** - How it should work
3. **Alternatives** - Other ways to achieve the goal
4. **Impact** - Who would benefit from this feature
5. **Implementation ideas** - How it might be implemented

---

**Still have questions?** Check out our [documentation](README.md) or [ask for help](https://github.com/geoknoesis/kastor/discussions)!


