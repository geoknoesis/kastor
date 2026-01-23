# Instance DSL Generation Design for kastor-gen

## Overview

This document describes the design for generating domain-specific DSL builders from ontology and SHACL graphs in kastor-gen. The generated DSL allows creating instances of classes defined in the ontology, with type-safe property access that respects SHACL constraints.

## Purpose

Generate Kotlin DSL builders that enable creating instances of ontology classes with:
- **Type-safe property access**: Properties are strongly typed based on SHACL constraints
- **Constraint enforcement**: Required properties, cardinality, and value constraints are enforced
- **IntelliSense support**: Full IDE autocomplete for available properties
- **Validation**: Built-in validation against SHACL shapes

## Use Case Example: SKOS

Given a SKOS ontology and SHACL shapes, generate a DSL like:

```kotlin
// Generated DSL usage
val conceptScheme = skos {
    conceptScheme("http://example.org/scheme1") {
        prefLabel("Geographic Concepts", "en")
        definition("A scheme for geographic concepts", "en")
    }
}

val concept = skos {
    concept("http://example.org/concept1") {
        prefLabel("Country", "en")           // Required by SHACL minCount=1
        altLabel("Nation", "en")              // Optional
        definition("A nation or sovereign state", "en")
        inScheme("http://example.org/scheme1")
        broader("http://example.org/geography") // Optional
    }
}
```

The generated DSL ensures:
- Required properties (from `sh:minCount >= 1`) must be set
- Property types match SHACL constraints (`sh:datatype`, `sh:class`)
- Cardinality constraints are enforced (`sh:maxCount`)
- Value constraints are validated (`sh:pattern`, `sh:minLength`, etc.)

## Architecture

### Components

```
┌─────────────────┐
│  Ontology Graph │
│  (OWL/RDFS)     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  SHACL Shapes   │
│  Graph          │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Parser         │
│  (Existing)     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  DSL Generator  │ ◄─── NEW
│  - ClassBuilder │
│  - PropertyGen  │
│  - Validator    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Generated DSL  │
│  Builder Code   │
└─────────────────┘
```

### Generator Flow

1. **Parse Ontology**: Extract classes and properties from OWL/RDFS ontology
2. **Parse SHACL**: Extract shapes and constraints for each class
3. **Generate Builder DSL**: Create type-safe builder for each class
4. **Generate Validator**: Create validation logic based on SHACL constraints

## Design Principles

### 1. Type Safety
- Properties are strongly typed based on SHACL `sh:datatype` or `sh:class`
- Required vs optional properties based on `sh:minCount`
- List vs single value based on `sh:maxCount`

### 2. Constraint Enforcement
- Required properties must be set (compile-time or runtime check)
- Value constraints (pattern, minLength, etc.) are validated
- Cardinality constraints are enforced

### 3. Readability
- Natural language property names (from `sh:name` or property IRI)
- Fluent builder API
- Clear error messages for constraint violations

### 4. Completeness
- Support all SHACL constraint types
- Handle complex paths (sequence, alternative, etc.)
- Support nested objects (qualified value shapes)

## Data Model

### Input Models

Uses existing models:
- `ShaclShape`: Node shapes with target classes
- `ShaclProperty`: Property constraints with types and cardinality
- `JsonLdContext`: Prefix mappings and property metadata

### Output Model

```kotlin
data class GeneratedDslCode(
    val packageName: String,
    val dslName: String,              // e.g., "skos", "dcat"
    val builders: List<ClassBuilder>,
    val mainFunction: String,          // Top-level DSL function
    val imports: List<String>
)

data class ClassBuilder(
    val className: String,            // e.g., "Concept", "ConceptScheme"
    val classIri: String,             // Full IRI
    val builderName: String,           // e.g., "concept", "conceptScheme"
    val properties: List<PropertyBuilder>,
    val validator: ValidatorCode
)

data class PropertyBuilder(
    val propertyName: String,         // Kotlin property name
    val propertyIri: String,          // Full IRI
    val kotlinType: String,           // e.g., "String", "List<String>", "Concept?"
    val isRequired: Boolean,           // From sh:minCount >= 1
    val isList: Boolean,               // From sh:maxCount > 1 or null
    val constraints: PropertyConstraints
)

data class PropertyConstraints(
    val minLength: Int?,
    val maxLength: Int?,
    val pattern: String?,
    val minInclusive: Double?,
    val maxInclusive: Double?,
    val inValues: List<String>?,
    // ... other SHACL constraints
)
```

## Implementation Details

### 1. DSL Structure

For each class in the ontology with a SHACL shape, generate:

```kotlin
// Top-level DSL function
fun skos(configure: SkosDsl.() -> Unit): SkosDsl {
    return SkosDsl().apply(configure)
}

// Main DSL class
class SkosDsl {
    private val graph = MemoryGraph()
    private val instances = mutableListOf<RdfResource>()
    
    // Builder for each class
    fun concept(iri: String, configure: ConceptBuilder.() -> Unit): RdfResource {
        val resource = Iri(iri)
        graph.addTriple(resource, RDF.type, SKOS.Concept)
        val builder = ConceptBuilder(resource, graph)
        builder.configure()
        builder.validate() // Validate against SHACL
        instances.add(resource)
        return resource
    }
    
    fun conceptScheme(iri: String, configure: ConceptSchemeBuilder.() -> Unit): RdfResource {
        // Similar structure
    }
    
    fun build(): MutableRdfGraph = graph
}

// Builder for Concept class
class ConceptBuilder(
    private val resource: RdfResource,
    private val graph: MutableRdfGraph
) {
    // Required property (minCount >= 1)
    fun prefLabel(value: String, lang: String? = null) {
        val literal = if (lang != null) LangString(value, lang) else Literal(value, XSD.string)
        graph.addTriple(resource, SKOS.prefLabel, literal)
    }
    
    // Optional property (minCount = 0 or null)
    fun altLabel(value: String, lang: String? = null) {
        val literal = if (lang != null) LangString(value, lang) else Literal(value, XSD.string)
        graph.addTriple(resource, SKOS.altLabel, literal)
    }
    
    // List property (maxCount > 1 or null)
    fun altLabel(vararg values: String) {
        values.forEach { value ->
            graph.addTriple(resource, SKOS.altLabel, Literal(value, XSD.string))
        }
    }
    
    // Object property (sh:class)
    fun broader(conceptIri: String) {
        graph.addTriple(resource, SKOS.broader, Iri(conceptIri))
    }
    
    // List of objects
    fun broader(vararg conceptIris: String) {
        conceptIris.forEach { iri ->
            graph.addTriple(resource, SKOS.broader, Iri(iri))
        }
    }
    
    // Validation
    fun validate() {
        val violations = mutableListOf<String>()
        
        // Check required properties
        val prefLabelCount = graph.getTriples(resource, SKOS.prefLabel, null).size
        if (prefLabelCount < 1) {
            violations.add("prefLabel is required (minCount=1)")
        }
        
        // Check value constraints
        graph.getTriples(resource, SKOS.prefLabel, null).forEach { triple ->
            val literal = triple.obj as? Literal
            if (literal != null) {
                val value = literal.lexical
                if (value.length < 1) {
                    violations.add("prefLabel must have minLength >= 1")
                }
            }
        }
        
        if (violations.isNotEmpty()) {
            throw ValidationException("Concept validation failed: ${violations.joinToString(", ")}")
        }
    }
}
```

### 2. Property Type Mapping

Map SHACL constraints to Kotlin types:

| SHACL Constraint | Kotlin Type | Example |
|-----------------|-------------|---------|
| `sh:datatype = xsd:string` + `sh:maxCount = 1` | `String` | `prefLabel(value: String)` |
| `sh:datatype = xsd:string` + `sh:maxCount > 1` | `List<String>` | `altLabel(vararg values: String)` |
| `sh:datatype = xsd:integer` + `sh:maxCount = 1` | `Int` | `age(value: Int)` |
| `sh:class = Concept` + `sh:maxCount = 1` | `String` (IRI) | `broader(conceptIri: String)` |
| `sh:class = Concept` + `sh:maxCount > 1` | `List<String>` | `broader(vararg iris: String)` |
| `sh:minCount = 0` or null | Optional parameter | `altLabel(value: String)` |
| `sh:minCount >= 1` | Required (validation) | `prefLabel(value: String)` - must be called |

### 3. Constraint Validation

Generate validation code for each constraint:

```kotlin
// Pattern constraint
if (property.constraints.pattern != null) {
    val regex = Regex(property.constraints.pattern)
    if (!regex.matches(value)) {
        violations.add("${propertyName} must match pattern: ${property.constraints.pattern}")
    }
}

// MinLength constraint
if (property.constraints.minLength != null) {
    if (value.length < property.constraints.minLength) {
        violations.add("${propertyName} must have minLength >= ${property.constraints.minLength}")
    }
}

// In constraint
if (property.constraints.inValues != null) {
    if (value !in property.constraints.inValues) {
        violations.add("${propertyName} must be one of: ${property.constraints.inValues.joinToString()}")
    }
}
```

### 4. Language-Tagged Strings

For properties that commonly use language tags (like `skos:prefLabel`), generate overloaded methods:

```kotlin
// With language tag
fun prefLabel(value: String, lang: String) {
    graph.addTriple(resource, SKOS.prefLabel, LangString(value, lang))
}

// Without language tag (defaults to no language)
fun prefLabel(value: String) {
    graph.addTriple(resource, SKOS.prefLabel, Literal(value, XSD.string))
}
```

## Integration with kastor-gen

### Annotation-Based Generation

Add a new annotation for instance DSL generation:

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateInstanceDsl(
    val ontologyPath: String,              // OWL/RDFS ontology file
    val shaclPath: String,                 // SHACL shapes file
    val contextPath: String? = null,       // Optional JSON-LD context
    val dslName: String,                   // Name of the DSL (e.g., "skos", "dcat")
    val packageName: String = "",          // Target package
    val options: DslGenerationOptions = DslGenerationOptions()
)
```

### Usage Example

```kotlin
@GenerateFromOntology(
    shaclPath = "ontologies/skos.shacl.ttl",
    contextPath = "ontologies/skos.context.jsonld",
    packageName = "com.example.generated"
)
@GenerateInstanceDsl(
    ontologyPath = "ontologies/skos.ttl",
    shaclPath = "ontologies/skos.shacl.ttl",
    contextPath = "ontologies/skos.context.jsonld",
    dslName = "skos",
    packageName = "com.example.generated.dsl"
)
class SkosOntologyGenerator
```

### KSP Processor Integration

Extend `OntologyProcessor` to handle `@GenerateInstanceDsl` annotations:

```kotlin
class OntologyProcessor {
    private val instanceDslGenerator = InstanceDslGenerator(logger)
    
    override fun process(resolver: Resolver): List<KSAnnotated> {
        // Existing ontology processing...
        
        // Process instance DSL generation annotations
        val dslAnnotations = resolver.getSymbolsWithAnnotation(
            "com.geoknoesis.kastor.gen.annotations.GenerateInstanceDsl"
        )
        
        dslAnnotations.forEach { symbol ->
            if (symbol is KSClassDeclaration) {
                processInstanceDslAnnotation(symbol)
            }
        }
        
        return processedAnnotations
    }
    
    private fun processInstanceDslAnnotation(symbol: KSClassDeclaration) {
        val annotation = symbol.annotations.find { 
            it.shortName.asString() == "GenerateInstanceDsl" 
        } ?: return
        
        val ontologyPath = getAnnotationValue(annotation, "ontologyPath") as? String
        val shaclPath = getAnnotationValue(annotation, "shaclPath") as? String
        val dslName = getAnnotationValue(annotation, "dslName") as? String
        val packageName = getAnnotationValue(annotation, "packageName") as? String
            ?: symbol.packageName.asString()
        
        if (ontologyPath == null || shaclPath == null || dslName == null) return
        
        // Load and parse ontology
        val ontologyGraph = loadGraph(ontologyPath)
        val shaclGraph = loadGraph(shaclPath)
        
        // Parse SHACL shapes
        val shapes = shaclParser.parseShacl(shaclGraph)
        
        // Extract classes from ontology
        val classes = extractClasses(ontologyGraph)
        
        // Generate DSL
        val generatedCode = instanceDslGenerator.generate(
            dslName = dslName,
            classes = classes,
            shapes = shapes,
            packageName = packageName,
            options = parseOptions(annotation)
        )
        
        // Write generated file
        generateFile("${dslName.capitalize()}Dsl.kt", packageName, generatedCode)
    }
}
```

## Options and Configuration

### DslGenerationOptions

```kotlin
data class DslGenerationOptions(
    // Validation
    val validateOnBuild: Boolean = true,      // Validate when builder completes
    val strictMode: Boolean = false,            // Throw on validation errors vs collect
    
    // Property naming
    val usePropertyNames: Boolean = true,      // Use sh:name or extract from IRI
    val camelCaseProperties: Boolean = true,    // Convert to camelCase
    
    // Language tags
    val supportLanguageTags: Boolean = true,   // Generate lang tag overloads
    val defaultLanguage: String? = null,       // Default language if not specified
    
    // Code generation
    val includeComments: Boolean = true,        // Include Javadoc comments
    val includeValidation: Boolean = true,     // Include validation code
    
    // Output
    val returnType: ReturnType = ReturnType.GRAPH  // What the DSL returns
)

enum class ReturnType {
    GRAPH,          // Returns MutableRdfGraph
    RESOURCE,       // Returns RdfResource (last created)
    LIST,           // Returns List<RdfResource> (all created)
    DSL             // Returns DSL instance for chaining
}
```

## Complete Example: SKOS DSL

### Input: skos.shacl.ttl
```turtle
@prefix sh: <http://www.w3.org/ns/shacl#> .
@prefix skos: <http://www.w3.org/2004/02/skos/core#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

<http://example.org/ConceptShape>
    a sh:NodeShape ;
    sh:targetClass skos:Concept ;
    sh:property [
        sh:path skos:prefLabel ;
        sh:name "preferred label" ;
        sh:minCount 1 ;
        sh:maxCount 1 ;
        sh:datatype xsd:string ;
        sh:minLength 1
    ] ;
    sh:property [
        sh:path skos:altLabel ;
        sh:name "alternative label" ;
        sh:minCount 0 ;
        sh:maxCount 1 ;
        sh:datatype xsd:string
    ] ;
    sh:property [
        sh:path skos:broader ;
        sh:name "broader concept" ;
        sh:minCount 0 ;
        sh:maxCount 1 ;
        sh:class skos:Concept
    ] .
```

### Generated Output: SkosDsl.kt
```kotlin
package com.example.generated.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SKOS
import com.geoknoesis.kastor.rdf.vocab.XSD

/**
 * DSL for creating SKOS instances.
 * Generated from SKOS ontology and SHACL shapes.
 */
fun skos(configure: SkosDsl.() -> Unit): SkosDsl {
    return SkosDsl().apply(configure)
}

class SkosDsl {
    private val graph = MemoryGraph()
    private val instances = mutableListOf<RdfResource>()
    
    /**
     * Create a SKOS Concept instance.
     * 
     * @param iri The IRI of the concept
     * @param configure Builder configuration
     * @return The created concept resource
     */
    fun concept(iri: String, configure: ConceptBuilder.() -> Unit): RdfResource {
        val resource = Iri(iri)
        graph.addTriple(resource, RDF.type, SKOS.Concept)
        val builder = ConceptBuilder(resource, graph)
        builder.configure()
        builder.validate()
        instances.add(resource)
        return resource
    }
    
    /**
     * Create a SKOS ConceptScheme instance.
     */
    fun conceptScheme(iri: String, configure: ConceptSchemeBuilder.() -> Unit): RdfResource {
        val resource = Iri(iri)
        graph.addTriple(resource, RDF.type, SKOS.ConceptScheme)
        val builder = ConceptSchemeBuilder(resource, graph)
        builder.configure()
        builder.validate()
        instances.add(resource)
        return resource
    }
    
    /**
     * Get the generated graph.
     */
    fun build(): MutableRdfGraph = graph
    
    /**
     * Get all created instances.
     */
    fun instances(): List<RdfResource> = instances.toList()
}

/**
 * Builder for SKOS Concept instances.
 */
class ConceptBuilder(
    private val resource: RdfResource,
    private val graph: MutableRdfGraph
) {
    /**
     * Set the preferred label (required).
     * 
     * @param value The label text
     * @param lang Optional language tag
     */
    fun prefLabel(value: String, lang: String? = null) {
        require(value.length >= 1) { "prefLabel must have minLength >= 1" }
        val literal = if (lang != null) {
            LangString(value, lang)
        } else {
            Literal(value, XSD.string)
        }
        graph.addTriple(resource, SKOS.prefLabel, literal)
    }
    
    /**
     * Set an alternative label (optional).
     */
    fun altLabel(value: String, lang: String? = null) {
        val literal = if (lang != null) {
            LangString(value, lang)
        } else {
            Literal(value, XSD.string)
        }
        graph.addTriple(resource, SKOS.altLabel, literal)
    }
    
    /**
     * Set a broader concept (optional).
     */
    fun broader(conceptIri: String) {
        graph.addTriple(resource, SKOS.broader, Iri(conceptIri))
    }
    
    /**
     * Validate the concept against SHACL constraints.
     */
    fun validate() {
        val violations = mutableListOf<String>()
        
        // Check required prefLabel
        val prefLabelCount = graph.getTriples(resource, SKOS.prefLabel, null).size
        if (prefLabelCount < 1) {
            violations.add("prefLabel is required (minCount=1)")
        }
        
        // Check minLength constraint
        graph.getTriples(resource, SKOS.prefLabel, null).forEach { triple ->
            val literal = triple.obj as? Literal
            if (literal != null && literal.lexical.length < 1) {
                violations.add("prefLabel must have minLength >= 1")
            }
        }
        
        if (violations.isNotEmpty()) {
            throw ValidationException(
                "Concept ${resource} validation failed: ${violations.joinToString(", ")}"
            )
        }
    }
}

class ConceptSchemeBuilder(
    private val resource: RdfResource,
    private val graph: MutableRdfGraph
) {
    // Similar structure for ConceptScheme
    fun prefLabel(value: String, lang: String? = null) {
        val literal = if (lang != null) {
            LangString(value, lang)
        } else {
            Literal(value, XSD.string)
        }
        graph.addTriple(resource, SKOS.prefLabel, literal)
    }
    
    fun validate() {
        // Validation logic
    }
}
```

### Usage
```kotlin
import com.example.generated.dsl.skos

// Create a concept scheme
val scheme = skos {
    conceptScheme("http://example.org/scheme1") {
        prefLabel("Geographic Concepts", "en")
    }
}

// Create concepts
val concepts = skos {
    val country = concept("http://example.org/concept/country") {
        prefLabel("Country", "en")
        altLabel("Nation", "en")
        definition("A nation or sovereign state", "en")
    }
    
    val city = concept("http://example.org/concept/city") {
        prefLabel("City", "en")
        broader("http://example.org/concept/country")
    }
}

// Get the graph
val graph = skos {
    concept("http://example.org/concept1") {
        prefLabel("Example", "en")
    }
}.build()
```

## Implementation Plan

### Phase 1: Core Infrastructure
1. Create `InstanceDslGenerator` class
2. Implement class extraction from ontology
3. Implement property mapping from SHACL
4. Basic code generation for simple properties

### Phase 2: Type System
1. Map SHACL datatypes to Kotlin types
2. Handle object properties (sh:class)
3. Handle list vs single value (sh:maxCount)
4. Handle required vs optional (sh:minCount)

### Phase 3: Validation
1. Generate validation code for constraints
2. Handle pattern, minLength, maxLength
3. Handle numeric constraints
4. Handle value set constraints (sh:in)

### Phase 4: Advanced Features
1. Language-tagged string support
2. Nested builders (qualified value shapes)
3. Complex paths
4. Error messages

### Phase 5: Integration
1. Add `@GenerateInstanceDsl` annotation
2. Extend `OntologyProcessor`
3. Add configuration options
4. Documentation

## Testing Strategy

### Unit Tests
- Test property type mapping
- Test constraint validation generation
- Test code generation for various SHACL constraints

### Integration Tests
- Test with real ontologies (SKOS, DCAT, FOAF)
- Test with real SHACL shapes
- Verify generated code compiles
- Verify generated DSL creates valid instances

### Validation Tests
- Test that required properties are enforced
- Test that value constraints are validated
- Test that cardinality constraints are enforced

## Open Questions

1. **Validation Timing**: When should validation occur?
   - During builder configuration (immediate)?
   - When `build()` is called (deferred)?
   - Both (with option to disable)?

2. **Error Handling**: How should validation errors be handled?
   - Throw exceptions (strict)?
   - Collect and return (lenient)?
   - Both (configurable)?

3. **Nested Objects**: How to handle qualified value shapes?
   - Nested builder DSL?
   - Separate builder functions?
   - Inline configuration?

4. **Complex Paths**: How to handle sequence/alternative paths?
   - Generate helper methods?
   - Use path evaluation at runtime?
   - Limit to simple IRI paths?

5. **Multiple Shapes**: What if a class has multiple SHACL shapes?
   - Merge constraints?
   - Generate separate builders?
   - Use shape selection?

## Related Work

- **Kotlin Type-Safe Builders**: Similar pattern to HTML builders
- **GraphQL Code Generation**: Similar approach for generating type-safe APIs
- **Protocol Buffers**: Similar code generation from schemas

## References

- [Kastor SHACL DSL Documentation](../kastor/api/shacl-dsl-guide.md)
- [SHACL Specification](https://www.w3.org/TR/shacl/)
- [SKOS Reference](https://www.w3.org/TR/skos-reference/)
