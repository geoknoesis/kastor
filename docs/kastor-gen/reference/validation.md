# Validation API Reference

Complete reference for Kastor Gen validation interfaces and adapters.

## Core Interfaces

### ValidationContext

Interface for SHACL validation implementations.

```kotlin
interface ValidationContext {
    fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult
}
```

**Methods:**
- `validate(data: RdfGraph, focus: RdfTerm): ValidationResult` - Validate RDF data against SHACL shapes

**Parameters:**
- `data: RdfGraph` - The RDF graph containing the data
- `focus: RdfTerm` - The focus node to validate

**Throws:**
- `ValidationException` - When you call `ValidationResult.orThrow()`

**Usage:**
```kotlin
class CustomValidation : ValidationContext {
    override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
        // Custom validation logic
        if (!isValid(data, focus)) {
            return ValidationResult.Violations(listOf(ShaclViolation(null, "Validation failed")))
        }
        return ValidationResult.Ok
    }
}
```

### Validation Usage

Validation is explicit and optional. You provide a `ValidationContext` when you want SHACL checks enforced.

```kotlin
val validation = CustomValidation()
val person: Person = OntoMapper.materializeValidated(ref, Person::class.java, validation)
```

## Validation Adapters

### JenaValidation

Jena-based SHACL validation adapter.

```kotlin
class JenaValidation : ValidationContext {
    override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult
}
```

**Features:**
- Explicit validation context
- Jena SHACL engine integration
- Support for complex SHACL shapes
- Detailed validation reporting

**Dependencies:**
- `com.geoknoesis.kastor:rdf-jena`
- `org.apache.jena:jena-shacl`

**Usage:**
```kotlin
val validation = JenaValidation()
val person: Person = OntoMapper.materializeValidated(ref, Person::class.java, validation)
```

### Rdf4jValidation

RDF4J-based SHACL validation adapter.

```kotlin
class Rdf4jValidation : ValidationContext {
    override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult
}
```

**Features:**
- Explicit validation context
- RDF4J SHACL engine integration
- Support for complex SHACL shapes
- Detailed validation reporting

**Dependencies:**
- `com.geoknoesis.kastor:rdf-rdf4j`
- `org.eclipse.rdf4j:rdf4j-shacl`

**Usage:**
```kotlin
val validation = Rdf4jValidation()
val person: Person = OntoMapper.materializeValidated(ref, Person::class.java, validation)
```

## Exception Classes

### ValidationException

Exception thrown when SHACL validation fails.

```kotlin
class ValidationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

**Constructor Parameters:**
- `message: String` - Error message
- `cause: Throwable? = null` - Optional cause

**Usage:**
```kotlin
try {
    rdfHandle.validateOrThrow()
} catch (e: ValidationException) {
    println("Validation failed: ${e.message}")
}
```

## Validation Patterns

### Basic Validation

```kotlin
class BasicValidation : ValidationContext {
    override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
        val triples = data.getTriples()
        val focusTriples = triples.filter { it.subject == focus }
        
        // Check required properties
        if (!focusTriples.any { it.predicate == FOAF.name }) {
            throw ValidationException("Person must have a name")
        }
    }
}
```

### SHACL Shape Validation

```kotlin
class ShaclShapeValidation : ValidationContext {
    private val shapesGraph: RdfGraph by lazy {
        loadShapesFromResource("shapes.ttl")
    }
    
    override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
        val validator = createValidationContext(shapesGraph)
        val report = validator.validate(data, focus)
        
        if (!report.isValid) {
            val violations = report.violations.map { violation ->
                ShaclViolation(null, violation.message)
            }
            return ValidationResult.Violations(violations)
        }
        return ValidationResult.Ok
    }
    
    private fun loadShapesFromResource(resourceName: String): RdfGraph {
        val repo = Rdf.memory()
        val inputStream = javaClass.getResourceAsStream(resourceName)
        repo.load(inputStream, RdfFormat.TURTLE)
        return repo.defaultGraph
    }
}
```

### Composite Validation

```kotlin
class CompositeValidation : ValidationContext {
    private val validators = listOf(
        BasicValidation(),
        ShaclShapeValidation(),
        BusinessRuleValidation()
    )
    
    override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
        val errors = mutableListOf<String>()
        
        validators.forEach { validator ->
            try {
                validator.validate(data, focus).orThrow()
            } catch (e: ValidationException) {
                errors.add(e.message ?: "Validation failed")
            }
        }
        
        if (errors.isNotEmpty()) {
            throw ValidationException("Multiple validation errors:\n${errors.joinToString("\n")}")
        }
    }
}
```

## Integration with Materialization

### Materialization with Validation

```kotlin
// Validation during materialization
val validation = JenaValidation()
val person: Person = rdfRef.asValidatedType(validation)

// Manual validation after materialization (same context)
val person: Person = rdfRef.asType(validation)
person.asRdf().validateOrThrow()
```

### Validation in RdfHandle

```kotlin
interface RdfHandle {
    fun validateOrThrow()  // Uses the validation context passed at materialization time
}

// Usage
val rdfHandle = person.asRdf()
rdfHandle.validateOrThrow()
```

## Configuration

### Dependency Configuration

**For Jena validation:**
```kotlin
dependencies {
    runtimeOnly(project(":rdf:jena"))
    runtimeOnly(project(":kastor-gen:validation-jena"))
}
```

**For RDF4J validation:**
```kotlin
dependencies {
    runtimeOnly(project(":rdf:rdf4j"))
    runtimeOnly(project(":kastor-gen:validation-rdf4j"))
}
```

### Custom Validation Configuration

```kotlin
class CustomValidationConfig {
    fun configureValidation() {
        val validation = CompositeValidation()
        val person: Person = rdfRef.asValidatedType(validation)
    }
}
```

## Error Handling

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
}

sealed class ValidationResult {
    object Success : ValidationResult()
    data class Error(val message: String) : ValidationResult()
}
```

### Detailed Error Reporting

```kotlin
class DetailedValidation : ValidationContext {
    override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        
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
        // Collect validation errors
    }
    
    private fun createErrorReport(errors: List<ValidationError>): String {
        return buildString {
            appendLine("Validation Report")
            appendLine("================")
            errors.forEach { error ->
                appendLine("${error.severity}: ${error.message}")
                appendLine("  Property: ${error.property.value}")
                appendLine("  Focus: ${error.focus}")
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

## Performance Considerations

### Lazy Validation

```kotlin
class LazyValidation : ValidationContext {
    private val validationCache = mutableMapOf<RdfTerm, Boolean>()
    
    override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
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
}
```

### Batch Validation

```kotlin
class BatchValidation : ValidationContext {
    override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
        val entitiesToValidate = collectEntities(data, focus)
        
        entitiesToValidate.chunked(100).forEach { batch ->
            validateBatch(data, batch)
        }
    }
}
```

## Testing

### Unit Testing

```kotlin
class ValidationTest {
    @Test
    fun `validation passes for valid data`() {
        val validation = BasicValidation()
        val repo = Rdf.memory()
        val person = iri("http://example.org/person")
        
        repo.add {
            person - RDF.type - FOAF.Person
            person - FOAF.name - "John Doe"
        }
        
        assertDoesNotThrow {
            validation.validate(repo.defaultGraph, person).orThrow()
        }
    }
    
    @Test
    fun `validation fails for invalid data`() {
        val validation = BasicValidation()
        val repo = Rdf.memory()
        val person = iri("http://example.org/person")
        
        repo.add {
            person - RDF.type - FOAF.Person
            // Missing required name property
        }
        
        assertThrows(ValidationException::class.java) {
            validation.validate(repo.defaultGraph, person).orThrow()
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
        }
        
        val personRef = RdfRef(person, repo.defaultGraph)
        
        val validation = JenaValidation()
        assertDoesNotThrow {
            val personObj: Person = personRef.asValidatedType(validation)
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

## Troubleshooting

### Common Issues

1. **Validation not working:**
   - Check that ValidationContext is registered
   - Verify validation dependencies are included
   - Ensure validation is called correctly

2. **Performance issues:**
   - Use lazy validation
   - Implement caching
   - Validate in batches

3. **Error handling:**
   - Catch ValidationException specifically
   - Provide meaningful error messages
   - Handle validation failures gracefully



