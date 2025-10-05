# Validation with OntoMapper

This tutorial covers SHACL validation integration with OntoMapper, including validation adapters, custom validation patterns, and best practices.

## Overview

OntoMapper provides built-in support for SHACL (Shapes Constraint Language) validation through a pluggable validation system. This allows you to validate RDF data against SHACL shapes while maintaining clean domain interfaces.

## Validation Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Domain Layer  │    │   Validation     │    │   SHACL Shapes  │
│                 │    │                  │    │                 │
│  Pure Interfaces│◄──►│  ValidationPort  │◄──►│  Jena/RDF4J     │
│  RdfBacked      │    │  ValidationRegistry│   │  SHACL Engine   │
└─────────────────┘    └──────────────────┘    └─────────────────┘
```

## Validation Adapters

OntoMapper provides validation adapters for different RDF backends:

### Jena Validation Adapter

```kotlin
// Add dependency
runtimeOnly(project(":ontomapper:validation-jena"))

// Automatic registration
class JenaValidation : ValidationPort {
    override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
        // SHACL validation using Jena
    }
}
```

### RDF4J Validation Adapter

```kotlin
// Add dependency
runtimeOnly(project(":rdf:rdf4j"))
runtimeOnly(project(":ontomapper:validation-rdf4j"))

// Automatic registration
class Rdf4jValidation : ValidationPort {
    override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
        // SHACL validation using RDF4J
    }
}
```

## Basic Validation Usage

### Materialization with Validation

```kotlin
val person: Person = rdfRef.asType(validate = true)
// Automatically validates against SHACL shapes
```

### Manual Validation

```kotlin
val person: Person = rdfRef.asType()
val rdfHandle = person.asRdf()

try {
    rdfHandle.validateOrThrow()
    println("Validation passed")
} catch (e: ValidationException) {
    println("Validation failed: ${e.message}")
}
```

### Validation Registry

```kotlin
// Check if validation is available
val validation = ValidationRegistry.current()
println("Validation available: ${validation != null}")

// Use validation
validation.validateOrThrow(graph, focusNode)
```

## SHACL Shapes

### Creating SHACL Shapes

Create SHACL shapes to define validation rules:

```turtle
# person-shape.ttl
@prefix sh: <http://www.w3.org/ns/shacl#> .
@prefix foaf: <http://xmlns.com/foaf/0.1/> .
@prefix dcterms: <http://purl.org/dc/terms/> .

<http://example.org/PersonShape>
    a sh:NodeShape ;
    sh:targetClass foaf:Person ;
    sh:property [
        sh:path foaf:name ;
        sh:minCount 1 ;
        sh:maxCount 1 ;
        sh:datatype xsd:string ;
    ] ;
    sh:property [
        sh:path foaf:age ;
        sh:minCount 0 ;
        sh:maxCount 1 ;
        sh:datatype xsd:integer ;
        sh:minInclusive 0 ;
        sh:maxInclusive 150 ;
    ] ;
    sh:property [
        sh:path foaf:mbox ;
        sh:minCount 0 ;
        sh:maxCount 1 ;
        sh:datatype xsd:string ;
        sh:pattern "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$" ;
    ] .
```

### Loading SHACL Shapes

```kotlin
class PersonValidation : ValidationPort {
    private val shapesGraph: RdfGraph by lazy {
        loadShapesFromResource("person-shape.ttl")
    }
    
    override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
        // Load and apply SHACL shapes
        val validator = createShaclValidator(shapesGraph)
        val report = validator.validate(data, focus)
        
        if (!report.isValid) {
            val errors = report.violations.joinToString("\n") { violation ->
                "Validation error: ${violation.message}"
            }
            throw ValidationException(errors)
        }
    }
    
    private fun loadShapesFromResource(resourceName: String): RdfGraph {
        val repo = Rdf.memory()
        val inputStream = javaClass.getResourceAsStream(resourceName)
        repo.load(inputStream, RdfFormat.TURTLE)
        return repo.defaultGraph
    }
}
```

## Custom Validation Patterns

### Domain-Specific Validation

```kotlin
class PersonDomainValidation : ValidationPort {
    override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
        val triples = data.getTriples()
        val focusTriples = triples.filter { it.subject == focus }
        
        // Check if it's a Person
        val isPerson = focusTriples.any { 
            it.predicate == RDF.type && it.obj == FOAF.Person 
        }
        
        if (!isPerson) {
            return // Not a person, skip validation
        }
        
        // Validate Person-specific rules
        validatePersonRules(focusTriples, focus)
    }
    
    private fun validatePersonRules(triples: List<RdfTriple>, focus: RdfTerm) {
        // Check required name property
        val hasName = triples.any { it.predicate == FOAF.name }
        if (!hasName) {
            throw ValidationException("Person must have a name property")
        }
        
        // Check age constraints
        val ageTriples = triples.filter { it.predicate == FOAF.age }
        ageTriples.forEach { triple ->
            val age = (triple.obj as? Literal)?.lexical?.toIntOrNull()
            if (age != null && (age < 0 || age > 150)) {
                throw ValidationException("Person age must be between 0 and 150")
            }
        }
        
        // Check email format
        val emailTriples = triples.filter { it.predicate == FOAF.mbox }
        emailTriples.forEach { triple ->
            val email = (triple.obj as? Literal)?.lexical
            if (email != null && !isValidEmail(email)) {
                throw ValidationException("Invalid email format: $email")
            }
        }
    }
    
    private fun isValidEmail(email: String): Boolean {
        return email.matches(Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"))
    }
}
```

### Business Rule Validation

```kotlin
class BusinessRuleValidation : ValidationPort {
    override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
        val triples = data.getTriples()
        val focusTriples = triples.filter { it.subject == focus }
        
        // Business rule: Employees must have an employer
        val isEmployee = focusTriples.any { 
            it.predicate == RDF.type && it.obj == Employee
        }
        
        if (isEmployee) {
            val hasEmployer = focusTriples.any { it.predicate == EMPLOYER }
            if (!hasEmployer) {
                throw ValidationException("Employee must have an employer")
            }
        }
        
        // Business rule: Products must have a price
        val isProduct = focusTriples.any { 
            it.predicate == RDF.type && it.obj == Product
        }
        
        if (isProduct) {
            val hasPrice = focusTriples.any { it.predicate == PRICE }
            if (!hasPrice) {
                throw ValidationException("Product must have a price")
            }
        }
    }
}
```

### Cross-Entity Validation

```kotlin
class CrossEntityValidation : ValidationPort {
    override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
        val triples = data.getTriples()
        
        // Validate relationships
        validateRelationships(triples, focus)
        
        // Validate consistency
        validateConsistency(triples, focus)
    }
    
    private fun validateRelationships(triples: List<RdfTriple>, focus: RdfTerm) {
        // Check that all referenced entities exist
        val referencedEntities = triples
            .filter { it.subject == focus }
            .mapNotNull { triple ->
                when (triple.obj) {
                    is Iri -> triple.obj
                    else -> null
                }
            }
        
        referencedEntities.forEach { entityIri ->
            val entityExists = triples.any { it.subject == entityIri }
            if (!entityExists) {
                throw ValidationException("Referenced entity does not exist: $entityIri")
            }
        }
    }
    
    private fun validateConsistency(triples: List<RdfTriple>, focus: RdfTerm) {
        // Check for circular references
        val visited = mutableSetOf<RdfTerm>()
        val visiting = mutableSetOf<RdfTerm>()
        
        fun hasCycle(node: RdfTerm): Boolean {
            if (visiting.contains(node)) return true
            if (visited.contains(node)) return false
            
            visiting.add(node)
            
            val outgoing = triples
                .filter { it.subject == node }
                .mapNotNull { it.obj as? RdfTerm }
            
            for (neighbor in outgoing) {
                if (hasCycle(neighbor)) return true
            }
            
            visiting.remove(node)
            visited.add(node)
            return false
        }
        
        if (hasCycle(focus)) {
            throw ValidationException("Circular reference detected")
        }
    }
}
```

## Validation Configuration

### Multiple Validation Ports

```kotlin
class CompositeValidation : ValidationPort {
    private val validators = listOf(
        PersonDomainValidation(),
        BusinessRuleValidation(),
        CrossEntityValidation()
    )
    
    override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
        val errors = mutableListOf<String>()
        
        validators.forEach { validator ->
            try {
                validator.validateOrThrow(data, focus)
            } catch (e: ValidationException) {
                errors.add(e.message ?: "Validation failed")
            }
        }
        
        if (errors.isNotEmpty()) {
            throw ValidationException("Multiple validation errors:\n${errors.joinToString("\n")}")
        }
    }
}

// Register composite validation
ValidationRegistry.register(CompositeValidation())
```

### Conditional Validation

```kotlin
class ConditionalValidation : ValidationPort {
    override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
        val triples = data.getTriples()
        val focusTriples = triples.filter { it.subject == focus }
        
        // Only validate if entity is marked for validation
        val shouldValidate = focusTriples.any { 
            it.predicate == VALIDATION_FLAG && it.obj == Literal("true")
        }
        
        if (!shouldValidate) {
            return // Skip validation
        }
        
        // Perform validation
        validateEntity(focusTriples, focus)
    }
    
    private fun validateEntity(triples: List<RdfTriple>, focus: RdfTerm) {
        // Validation logic here
    }
}
```

## Error Handling and Reporting

### Detailed Error Reporting

```kotlin
class DetailedValidation : ValidationPort {
    override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
        val errors = mutableListOf<ValidationError>()
        
        // Collect validation errors
        collectErrors(data, focus, errors)
        
        if (errors.isNotEmpty()) {
            val errorReport = createErrorReport(errors)
            throw ValidationException(errorReport)
        }
    }
    
    private fun collectErrors(
        data: RdfGraph, 
        focus: RdfTerm, 
        errors: MutableList<ValidationError>
    ) {
        val triples = data.getTriples()
        val focusTriples = triples.filter { it.subject == focus }
        
        // Check required properties
        if (!focusTriples.any { it.predicate == FOAF.name }) {
            errors.add(ValidationError(
                severity = ValidationSeverity.ERROR,
                message = "Required property 'name' is missing",
                property = FOAF.name,
                focus = focus
            ))
        }
        
        // Check property constraints
        focusTriples
            .filter { it.predicate == FOAF.age }
            .forEach { triple ->
                val age = (triple.obj as? Literal)?.lexical?.toIntOrNull()
                if (age != null && age < 0) {
                    errors.add(ValidationError(
                        severity = ValidationSeverity.ERROR,
                        message = "Age cannot be negative",
                        property = FOAF.age,
                        focus = focus,
                        value = triple.obj
                    ))
                }
            }
    }
    
    private fun createErrorReport(errors: List<ValidationError>): String {
        val errorCount = errors.count { it.severity == ValidationSeverity.ERROR }
        val warningCount = errors.count { it.severity == ValidationSeverity.WARNING }
        
        return buildString {
            appendLine("Validation Report")
            appendLine("================")
            appendLine("Errors: $errorCount, Warnings: $warningCount")
            appendLine()
            
            errors.forEach { error ->
                appendLine("${error.severity}: ${error.message}")
                appendLine("  Property: ${error.property.value}")
                appendLine("  Focus: ${error.focus}")
                if (error.value != null) {
                    appendLine("  Value: ${error.value}")
                }
                appendLine()
            }
        }
    }
}

data class ValidationError(
    val severity: ValidationSeverity,
    val message: String,
    val property: Iri,
    val focus: RdfTerm,
    val value: RdfTerm? = null
)

enum class ValidationSeverity {
    ERROR, WARNING, INFO
}
```

### Graceful Error Handling

```kotlin
class ValidationService {
    fun validatePerson(person: Person): ValidationResult {
        return try {
            person.asRdf().validateOrThrow()
            ValidationResult.Success()
        } catch (e: ValidationException) {
            ValidationResult.Error(e.message ?: "Validation failed")
        } catch (e: Exception) {
            ValidationResult.Error("Unexpected validation error: ${e.message}")
        }
    }
    
    fun validatePersonWithDetails(person: Person): DetailedValidationResult {
        val rdfHandle = person.asRdf()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        try {
            rdfHandle.validateOrThrow()
        } catch (e: ValidationException) {
            errors.add(e.message ?: "Validation failed")
        }
        
        // Additional domain-specific validation
        if (person.name.isEmpty()) {
            errors.add("Person name is required")
        }
        
        if (person.age.firstOrNull()?.let { it < 0 || it > 150 } == true) {
            warnings.add("Person age is outside typical range")
        }
        
        return DetailedValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
}

sealed class ValidationResult {
    object Success : ValidationResult()
    data class Error(val message: String) : ValidationResult()
}

data class DetailedValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)
```

## Performance Considerations

### Lazy Validation

```kotlin
class LazyValidation : ValidationPort {
    private val validationCache = mutableMapOf<RdfTerm, Boolean>()
    
    override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
        // Check cache first
        if (validationCache.containsKey(focus)) {
            if (!validationCache[focus]!!) {
                throw ValidationException("Cached validation failure")
            }
            return
        }
        
        // Perform validation
        val isValid = performValidation(data, focus)
        validationCache[focus] = isValid
        
        if (!isValid) {
            throw ValidationException("Validation failed")
        }
    }
    
    private fun performValidation(data: RdfGraph, focus: RdfTerm): Boolean {
        // Expensive validation logic
        return true // Simplified
    }
}
```

### Batch Validation

```kotlin
class BatchValidation : ValidationPort {
    override fun validateOrThrow(data: RdfGraph, focus: RdfTerm) {
        // Collect all entities that need validation
        val entitiesToValidate = collectEntities(data, focus)
        
        // Validate in batches
        entitiesToValidate.chunked(100).forEach { batch ->
            validateBatch(data, batch)
        }
    }
    
    private fun collectEntities(data: RdfGraph, focus: RdfTerm): List<RdfTerm> {
        val triples = data.getTriples()
        return triples
            .filter { it.predicate == RDF.type }
            .map { it.subject }
            .distinct()
    }
    
    private fun validateBatch(data: RdfGraph, batch: List<RdfTerm>) {
        // Batch validation logic
        batch.forEach { entity ->
            // Validate individual entity
        }
    }
}
```

## Testing Validation

### Unit Testing

```kotlin
class ValidationTest {
    @Test
    fun `validation passes for valid person`() {
        val validation = PersonDomainValidation()
        val repo = Rdf.memory()
        val person = iri("http://example.org/person")
        
        repo.add {
            person - RDF.type - FOAF.Person
            person - FOAF.name - "John Doe"
            person - FOAF.age - 30
        }
        
        assertDoesNotThrow {
            validation.validateOrThrow(repo.defaultGraph, person)
        }
    }
    
    @Test
    fun `validation fails for person without name`() {
        val validation = PersonDomainValidation()
        val repo = Rdf.memory()
        val person = iri("http://example.org/person")
        
        repo.add {
            person - RDF.type - FOAF.Person
            person - FOAF.age - 30
        }
        
        assertThrows(ValidationException::class.java) {
            validation.validateOrThrow(repo.defaultGraph, person)
        }
    }
}
```

### Integration Testing

```kotlin
class ValidationIntegrationTest {
    @Test
    fun `end-to-end validation works`() {
        val repo = Rdf.memory()
        val person = iri("http://example.org/person")
        
        repo.add {
            person - RDF.type - FOAF.Person
            person - FOAF.name - "John Doe"
            person - FOAF.age - 30
        }
        
        val personRef = RdfRef(person, repo.defaultGraph)
        
        // Materialize with validation
        assertDoesNotThrow {
            val personObj: Person = personRef.asType(validate = true)
            assertEquals("John Doe", personObj.name.firstOrNull())
        }
    }
}
```

## Best Practices

### ✅ Do

- Use SHACL shapes for structural validation
- Implement custom validation for business rules
- Provide detailed error messages
- Cache validation results when appropriate
- Validate in batches for performance
- Handle validation errors gracefully
- Test validation thoroughly

### ❌ Don't

- Ignore validation errors
- Perform expensive validation synchronously
- Mix validation logic with domain logic
- Assume validation always passes
- Skip validation in production
- Use validation for business logic

## Next Steps

- **Check out [Advanced Usage](advanced-usage.md)** - Complex scenarios
- **See [Practical Examples](../examples/README.md)** - Real-world use cases
- **Review [API Reference](../reference/README.md)** - Complete API documentation
- **Learn about [Best Practices](../best-practices.md)** - Guidelines for effective usage
