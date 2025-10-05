# Best Practices

This guide provides best practices for using OntoMapper effectively in your projects.

## Table of Contents

- [Domain Interface Design](#domain-interface-design)
- [Ontology Management](#ontology-management)
- [Performance Optimization](#performance-optimization)
- [Error Handling](#error-handling)
- [Testing Strategies](#testing-strategies)
- [Code Organization](#code-organization)
- [Security Considerations](#security-considerations)
- [Deployment Guidelines](#deployment-guidelines)

## Domain Interface Design

### ✅ **Keep Interfaces Pure**

Design domain interfaces that focus on business logic without RDF dependencies:

```kotlin
// ✅ Good: Pure domain interface
@RdfClass(iri = "http://www.w3.org/ns/dcat#Catalog")
interface Catalog {
    @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
    val title: String
    
    @get:RdfProperty(iri = "http://www.w3.org/ns/dcat#dataset")
    val dataset: List<Dataset>
}

// ❌ Avoid: RDF types in domain interface
interface Catalog {
    val title: String
    val dataset: List<Dataset>
    val rdfNode: RdfTerm // Don't expose RDF types
}
```

### ✅ **Use Descriptive Property Names**

Choose property names that reflect business concepts:

```kotlin
// ✅ Good: Business-focused names
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val fullName: String
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/knows")
    val acquaintances: List<Person>
}

// ❌ Avoid: Technical RDF names
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val foafName: String
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/knows")
    val foafKnows: List<Person>
}
```

### ✅ **Handle Cardinality Appropriately**

Design properties based on expected cardinality:

```kotlin
// ✅ Good: Appropriate cardinality
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: String // Single value expected
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/knows")
    val knows: List<Person> // Multiple values expected
}

// ❌ Avoid: Inappropriate cardinality
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: List<String> // Overkill for single name
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/knows")
    val knows: Person // Should be List<Person>
}
```

## Ontology Management

### ✅ **Use SHACL for Validation**

Define SHACL shapes for your ontologies:

```turtle
@prefix dcat: <http://www.w3.org/ns/dcat#> .
@prefix dcterms: <http://purl.org/dc/terms/> .
@prefix sh: <http://www.w3.org/ns/shacl#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

<http://example.org/shapes/Catalog>
    a sh:NodeShape ;
    sh:targetClass dcat:Catalog ;
    sh:property [
        sh:path dcterms:title ;
        sh:name "title" ;
        sh:description "A name given to the catalog." ;
        sh:datatype xsd:string ;
        sh:minCount 1 ;
        sh:maxCount 1 ;
    ] .
```

### ✅ **Generate Code from Ontologies**

Use ontology-driven code generation for consistency:

```kotlin
@GenerateFromOntology(
    shaclPath = "ontologies/dcat.shacl.ttl",
    contextPath = "ontologies/dcat.context.jsonld",
    packageName = "com.example.generated"
)
class OntologyGenerator
```

### ✅ **Version Your Ontologies**

Track ontology changes in version control:

```
src/main/resources/ontologies/
├── dcat-v1.0.shacl.ttl
├── dcat-v1.0.context.jsonld
├── dcat-v2.0.shacl.ttl
└── dcat-v2.0.context.jsonld
```

## Performance Optimization

### ✅ **Use Lazy Evaluation**

Properties are evaluated lazily by default:

```kotlin
// ✅ Good: Lazy evaluation (automatic)
val catalog: Catalog = catalogRef.asType()
val title = catalog.title // Only evaluated when accessed

// ❌ Avoid: Eager evaluation
val catalog: Catalog = catalogRef.asType()
val allProperties = listOf(
    catalog.title,
    catalog.description,
    catalog.dataset
) // All evaluated immediately
```

### ✅ **Minimize RDF Graph Traversal**

Access related objects efficiently:

```kotlin
// ✅ Good: Efficient access
val catalog: Catalog = catalogRef.asType()
val firstDataset = catalog.dataset.firstOrNull()
val firstDistribution = firstDataset?.distribution?.firstOrNull()

// ❌ Avoid: Multiple graph traversals
val catalog: Catalog = catalogRef.asType()
val datasets = catalog.dataset
val distributions = datasets.flatMap { it.distribution }
val titles = distributions.map { it.title }
```

### ✅ **Use Side-Channel for Bulk Operations**

Access RDF directly for bulk operations:

```kotlin
// ✅ Good: Bulk access via side-channel
val rdfHandle = catalog.asRdf()
val extras = rdfHandle.extras
val allPredicates = extras.predicates()
val allValues = allPredicates.flatMap { extras.values(it) }

// ❌ Avoid: Individual property access
val catalog: Catalog = catalogRef.asType()
val title = catalog.title
val description = catalog.description
val dataset = catalog.dataset
```

## Error Handling

### ✅ **Handle Missing Properties Gracefully**

Design interfaces to handle optional properties:

```kotlin
// ✅ Good: Optional properties
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: String // Required
    
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/age")
    val age: Int? // Optional
}

// Usage
val person: Person = personRef.asType()
val ageText = person.age?.toString() ?: "Unknown"
```

### ✅ **Validate Data When Needed**

Use validation for critical operations:

```kotlin
// ✅ Good: Validation for critical operations
val catalog: Catalog = catalogRef.asType(validate = true)

// Or validate explicitly
val rdfHandle = catalog.asRdf()
try {
    rdfHandle.validateOrThrow()
    println("Validation passed")
} catch (e: ValidationException) {
    println("Validation failed: ${e.message}")
}
```

### ✅ **Handle Type Conversion Errors**

Be prepared for type conversion failures:

```kotlin
// ✅ Good: Safe type conversion
val wrapper = object : RdfBacked {
    override val rdf = handle
    override val age: Int by lazy {
        try {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age)
                .map { it.lexical.toInt() }.firstOrNull() ?: 0
        } catch (e: NumberFormatException) {
            0 // Default value
        }
    }
}
```

## Testing Strategies

### ✅ **Test Domain Interfaces Independently**

Test business logic without RDF dependencies:

```kotlin
// ✅ Good: Test domain logic
class CatalogBusinessLogicTest {
    @Test
    fun `catalog should have valid title`() {
        val catalog = mockk<Catalog>()
        every { catalog.title } returns "Test Catalog"
        
        assertThat(catalog.title).isEqualTo("Test Catalog")
    }
}
```

### ✅ **Test RDF Integration Separately**

Test RDF materialization and side-channel access:

```kotlin
// ✅ Good: Test RDF integration
class CatalogRdfIntegrationTest {
    @Test
    fun `catalog materialization works`() {
        val repo = Rdf.memory()
        repo.add {
            val catalog = iri("http://example.org/catalog")
            catalog - RDF.type - DCAT.Catalog
            catalog - DCTERMS.title - "Test Catalog"
        }
        
        val catalogRef = RdfRef(iri("http://example.org/catalog"), repo.defaultGraph)
        val catalog: Catalog = catalogRef.asType()
        
        assertThat(catalog.title).isEqualTo("Test Catalog")
    }
}
```

### ✅ **Test Generated Code**

Verify generated interfaces and wrappers:

```kotlin
// ✅ Good: Test generated code
class GeneratedCatalogTest {
    @Test
    fun `generated catalog interface has expected properties`() {
        val catalogClass = Catalog::class.java
        val methods = catalogClass.declaredMethods
        
        assertThat(methods).anyMatch { it.name == "getTitle" }
        assertThat(methods).anyMatch { it.name == "getDataset" }
    }
}
```

## Code Organization

### ✅ **Separate Generated Code**

Keep generated code separate from hand-written code:

```
src/main/kotlin/
├── com/example/
│   ├── domain/           # Hand-written domain interfaces
│   ├── generated/        # Generated interfaces and wrappers
│   ├── business/         # Business logic
│   └── integration/      # RDF integration
```

### ✅ **Use Package Structure**

Organize code by domain and functionality:

```kotlin
// ✅ Good: Organized packages
package com.example.dcatus.domain
interface Catalog { ... }

package com.example.dcatus.generated
class CatalogWrapper { ... }

package com.example.dcatus.business
class CatalogService { ... }

package com.example.dcatus.integration
class CatalogRepository { ... }
```

### ✅ **Configuration Management**

Centralize configuration for different environments:

```kotlin
// ✅ Good: Configuration management
object OntoMapperConfig {
    val validationEnabled: Boolean = System.getProperty("validation.enabled", "false").toBoolean()
    val shaclShapesPath: String = System.getProperty("shacl.shapes.path", "ontologies/shapes.ttl")
    
    fun initialize() {
        if (validationEnabled) {
            ValidationRegistry.register(JenaValidation())
        }
    }
}
```

## Security Considerations

### ✅ **Validate Input Data**

Always validate RDF data before processing:

```kotlin
// ✅ Good: Input validation
fun loadCatalog(iri: String, graph: RdfGraph): Catalog {
    require(iri.isNotBlank()) { "IRI cannot be blank" }
    require(graph.getTriples().isNotEmpty()) { "Graph cannot be empty" }
    
    val catalogRef = RdfRef(Iri(iri), graph)
    return catalogRef.asType(validate = true)
}
```

### ✅ **Handle Sensitive Data**

Be careful with sensitive information in RDF:

```kotlin
// ✅ Good: Sensitive data handling
interface Person {
    @get:RdfProperty(iri = "http://xmlns.com/foaf/0.1/name")
    val name: String
    
    // Don't expose sensitive data through domain interface
    // Handle separately with proper security controls
}
```

### ✅ **Use Secure RDF Sources**

Validate RDF sources and use secure connections:

```kotlin
// ✅ Good: Secure RDF source
fun loadSecureCatalog(url: String): Catalog {
    require(url.startsWith("https://")) { "Only HTTPS URLs allowed" }
    
    val repo = Rdf.sparql(url, credentials = secureCredentials)
    val graph = repo.defaultGraph
    return loadCatalog(url, graph)
}
```

## Deployment Guidelines

### ✅ **Build Configuration**

Configure build for different environments:

```kotlin
// build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":ontomapper:runtime"))
    ksp(project(":ontomapper:processor"))
}

ksp {
    arg("validation.enabled", "true")
    arg("shacl.shapes.path", "ontologies/production.shacl.ttl")
}
```

### ✅ **Runtime Configuration**

Configure runtime behavior:

```kotlin
// ✅ Good: Runtime configuration
fun initializeOntoMapper() {
    // Register validation port
    ValidationRegistry.register(JenaValidation())
    
    // Initialize generated wrappers
    OntoMapper.initialize()
    
    // Configure logging
    LoggerFactory.getLogger(OntoMapper::class.java).info("OntoMapper initialized")
}
```

### ✅ **Monitoring and Logging**

Add monitoring for production deployments:

```kotlin
// ✅ Good: Monitoring
class OntoMapperMetrics {
    private val materializationCounter = Counter.build()
        .name("ontomapper_materializations_total")
        .help("Total number of materializations")
        .register()
    
    fun <T> materializeWithMetrics(ref: RdfRef, type: Class<T>): T {
        materializationCounter.inc()
        return OntoMapper.materialize(ref, type)
    }
}
```

## Common Pitfalls

### ❌ **Avoid Exposing RDF Types**

Don't expose RDF types in domain interfaces:

```kotlin
// ❌ Bad: RDF types in domain interface
interface Catalog {
    val title: String
    val rdfNode: RdfTerm // Don't do this
    val graph: RdfGraph // Don't do this
}
```

### ❌ **Avoid Eager Evaluation**

Don't force eager evaluation of properties:

```kotlin
// ❌ Bad: Eager evaluation
val catalog: Catalog = catalogRef.asType()
val allData = listOf(
    catalog.title,
    catalog.description,
    catalog.dataset
) // All properties evaluated immediately
```

### ❌ **Avoid Complex Inheritance**

Keep inheritance simple and focused:

```kotlin
// ❌ Bad: Complex inheritance
interface Resource {
    val iri: String
}

interface Catalog : Resource {
    val title: String
}

interface Dataset : Resource {
    val title: String
}

interface CatalogDataset : Catalog, Dataset {
    // Complex inheritance can cause issues
}
```

### ❌ **Avoid Mutable State**

Keep domain interfaces immutable:

```kotlin
// ❌ Bad: Mutable state
interface Catalog {
    var title: String // Don't use var
    val dataset: MutableList<Dataset> // Don't use mutable collections
}
```

## Conclusion

Following these best practices will help you build robust, maintainable applications with OntoMapper. Remember to:

1. **Keep domain interfaces pure** - Focus on business logic
2. **Use ontology-driven generation** - Maintain consistency
3. **Optimize for performance** - Use lazy evaluation and bulk operations
4. **Handle errors gracefully** - Validate data and handle edge cases
5. **Test thoroughly** - Test both domain logic and RDF integration
6. **Organize code well** - Separate concerns and use clear package structure
7. **Consider security** - Validate input and handle sensitive data
8. **Configure for deployment** - Set up proper build and runtime configuration

For more specific guidance, see the [FAQ](faq.md) and [API Reference](reference/).