# SHACL Validation

Kastor provides a comprehensive SHACL (Shapes Constraint Language) validation framework with a pluggable provider architecture. This allows you to validate RDF data against SHACL shapes using different validation engines.

## Table of Contents
- [Introduction](#introduction)
- [Quick Start](#quick-start)
- [Validation Providers](#validation-providers)
- [Configuration](#configuration)
- [Validation Results](#validation-results)
- [Usage Examples](#usage-examples)
- [Advanced Features](#advanced-features)
- [Best Practices](#best-practices)

## Introduction

SHACL (Shapes Constraint Language) is a W3C standard for validating RDF data against a set of constraints expressed as shapes. Kastor's SHACL validation framework provides:

- **Provider Architecture**: Pluggable validation engines (Memory, Jena, RDF4J)
- **Rich Results**: Detailed validation reports with explanations and suggestions
- **Flexible Configuration**: Multiple validation profiles and options
- **Performance Options**: Streaming and parallel validation for large graphs
- **Easy Integration**: Simple API that fits with existing Kastor patterns

## Quick Start

This section provides a comprehensive introduction to SHACL validation with Kastor. We'll cover everything from basic validation to advanced scenarios.

### Prerequisites

First, add the SHACL validation dependency to your project:

```kotlin
dependencies {
    implementation("com.geoknoesis.kastor:rdf-shacl-validation:0.1.0")
}
```

### Basic Validation Example

Let's start with a simple example that validates person data against SHACL shapes:

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.shacl.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

// Step 1: Create your data graph
val dataGraph = Rdf.graph {
    val person = iri("http://example.org/person1")
    person - RDF.type - "http://example.org/Person"
    person - "http://example.org/name" - "Alice Johnson"
    person - "http://example.org/email" - "alice@example.org"
    person - "http://example.org/age" - 30
    person - "http://example.org/phone" - "+1-555-123-4567"
}

// Step 2: Create your shapes graph with validation rules
val shapesGraph = Rdf.graph {
    // Define the Person shape
    val personShape = iri("http://example.org/PersonShape")
    personShape - RDF.type - "http://www.w3.org/ns/shacl#NodeShape"
    personShape - "http://www.w3.org/ns/shacl#targetClass" - "http://example.org/Person"
    
    // Name property (required, single value)
    val nameProperty = iri("http://example.org/nameProperty")
    personShape - "http://www.w3.org/ns/shacl#property" - nameProperty
    nameProperty - RDF.type - "http://www.w3.org/ns/shacl#PropertyShape"
    nameProperty - "http://www.w3.org/ns/shacl#path" - "http://example.org/name"
    nameProperty - "http://www.w3.org/ns/shacl#minCount" - 1
    nameProperty - "http://www.w3.org/ns/shacl#maxCount" - 1
    nameProperty - "http://www.w3.org/ns/shacl#datatype" - XSD.string
    
    // Email property (required, single value, string)
    val emailProperty = iri("http://example.org/emailProperty")
    personShape - "http://www.w3.org/ns/shacl#property" - emailProperty
    emailProperty - RDF.type - "http://www.w3.org/ns/shacl#PropertyShape"
    emailProperty - "http://www.w3.org/ns/shacl#path" - "http://example.org/email"
    emailProperty - "http://www.w3.org/ns/shacl#minCount" - 1
    emailProperty - "http://www.w3.org/ns/shacl#maxCount" - 1
    emailProperty - "http://www.w3.org/ns/shacl#datatype" - XSD.string
    
    // Age property (required, single value, integer)
    val ageProperty = iri("http://example.org/ageProperty")
    personShape - "http://www.w3.org/ns/shacl#property" - ageProperty
    ageProperty - RDF.type - "http://www.w3.org/ns/shacl#PropertyShape"
    ageProperty - "http://www.w3.org/ns/shacl#path" - "http://example.org/age"
    ageProperty - "http://www.w3.org/ns/shacl#minCount" - 1
    ageProperty - "http://www.w3.org/ns/shacl#maxCount" - 1
    ageProperty - "http://www.w3.org/ns/shacl#datatype" - XSD.integer
    
    // Phone property (optional, single value, string)
    val phoneProperty = iri("http://example.org/phoneProperty")
    personShape - "http://www.w3.org/ns/shacl#property" - phoneProperty
    phoneProperty - RDF.type - "http://www.w3.org/ns/shacl#PropertyShape"
    phoneProperty - "http://www.w3.org/ns/shacl#path" - "http://example.org/phone"
    phoneProperty - "http://www.w3.org/ns/shacl#minCount" - 0
    phoneProperty - "http://www.w3.org/ns/shacl#maxCount" - 1
    phoneProperty - "http://www.w3.org/ns/shacl#datatype" - XSD.string
}

// Step 3: Validate the data against the shapes
val validator = ShaclValidation.validator()
val report = validator.validate(dataGraph, shapesGraph)

// Step 4: Check the validation results
if (report.isValid) {
    println("✅ Validation passed!")
    println("Validated ${report.validatedResources} resources")
    println("Validated ${report.validatedConstraints} constraints")
    println("Validation time: ${report.validationTime.toMillis()}ms")
} else {
    println("❌ Validation failed with ${report.violations.size} violations")
    report.violations.forEach { violation ->
        println("  - ${violation.severity}: ${violation.message}")
        println("    Resource: ${violation.resource}")
        println("    Constraint: ${violation.constraint.constraintType}")
    }
}
```

### Understanding the Output

When validation fails, you'll get detailed information about what went wrong:

```
❌ Validation failed with 3 violations
  - VIOLATION: Property 'http://example.org/name' has 0 values, but minimum is 1
    Resource: <http://example.org/person2>
    Constraint: MIN_COUNT
  - VIOLATION: Property 'http://example.org/email' has datatype 'http://www.w3.org/2001/XMLSchema#integer', but expected 'http://www.w3.org/2001/XMLSchema#string'
    Resource: <http://example.org/person3>
    Constraint: DATATYPE
  - VIOLATION: Property 'http://example.org/age' has 2 values, but maximum is 1
    Resource: <http://example.org/person4>
    Constraint: MAX_COUNT
```

### Quick Conformance Check

For simple boolean validation without detailed results:

```kotlin
val validator = ShaclValidation.validator()
val conforms = validator.conforms(dataGraph, shapesGraph)
println("Data conforms: $conforms") // true or false
```

### Common Validation Scenarios

#### 1. Validating Multiple Resources

```kotlin
val dataGraph = Rdf.graph {
    // Person 1 - Valid
    val person1 = iri("http://example.org/person1")
    person1 - RDF.type - "http://example.org/Person"
    person1 - "http://example.org/name" - "Alice Johnson"
    person1 - "http://example.org/email" - "alice@example.org"
    person1 - "http://example.org/age" - 30
    
    // Person 2 - Missing required name
    val person2 = iri("http://example.org/person2")
    person2 - RDF.type - "http://example.org/Person"
    person2 - "http://example.org/email" - "bob@example.org"
    person2 - "http://example.org/age" - 25
    
    // Person 3 - Invalid age type
    val person3 = iri("http://example.org/person3")
    person3 - RDF.type - "http://example.org/Person"
    person3 - "http://example.org/name" - "Charlie Brown"
    person3 - "http://example.org/email" - "charlie@example.org"
    person3 - "http://example.org/age" - "thirty" // Should be integer
}

val report = validator.validate(dataGraph, shapesGraph)

// Get violations by resource
report.violations.groupBy { it.resource }.forEach { (resource, violations) ->
    println("Resource $resource has ${violations.size} violations:")
    violations.forEach { println("  - ${it.message}") }
}
```

#### 2. Resource-Specific Validation

Validate only a specific resource instead of the entire graph:

```kotlin
val person1 = iri("http://example.org/person1")
val person1Report = validator.validateResource(dataGraph, shapesGraph, person1)

if (person1Report.isValid) {
    println("✅ Person1 is valid")
} else {
    println("❌ Person1 has ${person1Report.violations.size} violations")
}
```

#### 3. Constraint-Specific Validation

Validate against specific constraints only:

```kotlin
val constraints = listOf(
    ShaclConstraint(
        constraintType = ConstraintType.MIN_COUNT,
        path = "http://example.org/name",
        parameters = mapOf("value" to 1)
    ),
    ShaclConstraint(
        constraintType = ConstraintType.DATATYPE,
        path = "http://example.org/age",
        parameters = mapOf("value" to iri("http://www.w3.org/2001/XMLSchema#integer"))
    )
)

val report = validator.validateConstraints(dataGraph, constraints)
```

### Using Different Validator Configurations

#### 1. Strict Validation

```kotlin
val strictValidator = ShaclValidation.strictValidator()
val report = strictValidator.validate(dataGraph, shapesGraph)
// Strict mode includes warnings and validates inactive shapes
```

#### 2. Fast Validation

```kotlin
val fastValidator = ShaclValidation.fastValidator()
val report = fastValidator.validate(dataGraph, shapesGraph)
// Fast mode skips explanations and suggestions for better performance
```

#### 3. Large Graph Validation

```kotlin
val largeGraphValidator = ShaclValidation.largeGraphValidator()
val report = largeGraphValidator.validate(largeDataGraph, shapesGraph)
// Optimized for large datasets with streaming and parallel processing
```

#### 4. Custom Configuration

```kotlin
val config = ValidationConfig(
    profile = ValidationProfile.SHACL_CORE,
    strictMode = true,
    includeWarnings = true,
    maxViolations = 100,
    timeout = Duration.ofMinutes(5),
    enableExplanations = true,
    enableSuggestions = true
)

val validator = ShaclValidation.validator(config)
val report = validator.validate(dataGraph, shapesGraph)
```

### Working with Validation Results

#### Detailed Violation Analysis

```kotlin
val report = validator.validate(dataGraph, shapesGraph)

// Get violations by severity
val errors = report.getViolationsBySeverity(ViolationSeverity.VIOLATION)
val warnings = report.getViolationsBySeverity(ViolationSeverity.WARNING)

// Get violations by constraint type
val minCountViolations = report.getViolationsForConstraint(ConstraintType.MIN_COUNT)
val datatypeViolations = report.getViolationsForConstraint(ConstraintType.DATATYPE)

// Get violations for a specific shape
val personViolations = report.getViolationsForShape("http://example.org/PersonShape")

// Get violations for a specific resource
val resourceViolations = report.getViolationsForResource(someResource)
```

#### Validation Statistics

```kotlin
val stats = report.statistics
println("Total resources: ${stats.totalResources}")
println("Validated resources: ${stats.validatedResources}")
println("Total constraints: ${stats.totalConstraints}")
println("Validated constraints: ${stats.validatedConstraints}")
println("Shapes processed: ${stats.shapesProcessed}")
println("Constraints by type: ${stats.constraintsByType}")
println("Violations by type: ${stats.violationsByType}")
println("Average validation time per resource: ${stats.averageValidationTimePerResource.toMillis()}ms")
```

#### Summary Information

```kotlin
val summary = report.getSummary()
println(summary.getDescription())

// Or get specific summary data
println("Valid: ${summary.isValid}")
println("Total violations: ${summary.totalViolations}")
println("Violations by severity: ${summary.violationsBySeverity}")
println("Violations by shape: ${summary.violationsByShape}")
println("Validation time: ${summary.validationTime}")
```

### Error Handling

```kotlin
try {
    val report = validator.validate(dataGraph, shapesGraph)
    // Process results
} catch (e: ValidationException) {
    println("Validation failed with error: ${e.message}")
    e.cause?.let { println("Cause: ${it.message}") }
} catch (e: IllegalArgumentException) {
    println("Invalid configuration: ${e.message}")
}
```

### Best Practices for Quick Start

1. **Start Simple**: Begin with basic constraints like `minCount`, `maxCount`, and `datatype`
2. **Use Meaningful Names**: Use descriptive IRIs for your shapes and properties
3. **Test Incrementally**: Validate small datasets first, then scale up
4. **Handle Results Properly**: Always check `report.isValid` before processing violations
5. **Use Appropriate Validators**: Choose the right validator configuration for your use case
6. **Monitor Performance**: Check validation times and adjust configuration if needed

### Next Steps

Once you're comfortable with basic validation:

1. **Explore Advanced Constraints**: Learn about `pattern`, `class`, `in`, `hasValue`, etc.
2. **Use Complex Shapes**: Combine multiple constraints and nested shapes
3. **Optimize Performance**: Use streaming and parallel validation for large datasets
4. **Integrate with Backends**: Use Jena or RDF4J validators for full SHACL support
5. **Build Validation Pipelines**: Create automated validation workflows

## Validation Providers

Kastor's SHACL validation uses a provider architecture similar to the reasoning module.

### Available Providers

1. **Memory Validator** (`MemoryShaclValidator`)
   - Built-in, no additional dependencies
   - Supports SHACL Core constraints
   - Fast performance for small to medium graphs
   - Good for development and testing

2. **Jena Validator** (coming soon)
   - Uses Apache Jena's SHACL implementation
   - Full SHACL support including SPARQL constraints
   - Excellent performance and validation coverage

3. **RDF4J Validator** (coming soon)
   - Uses Eclipse RDF4J's SHACL implementation
   - Comprehensive SHACL support
   - Good integration with RDF4J backends

### Provider Discovery

```kotlin
// Get all available providers
val providers = ShaclValidation.validatorProviders()
providers.forEach { provider ->
    println("${provider.name} - ${provider.version}")
    println("  Type: ${provider.getType()}")
    println("  Capabilities: ${provider.getCapabilities()}")
}

// Check supported profiles
val supportedProfiles = ShaclValidation.supportedProfiles()
println("Supported profiles: ${supportedProfiles.joinToString(", ")}")

// Check if a specific profile is supported
if (ShaclValidation.isSupported(ValidationProfile.SHACL_CORE)) {
    println("SHACL Core validation is available")
}
```

### Provider Capabilities

```kotlin
val provider = ShaclValidation.validatorProviders().first()
val capabilities = provider.getCapabilities()

println("SHACL Core: ${capabilities.supportsShaclCore}")
println("SHACL SPARQL: ${capabilities.supportsShaclSparql}")
println("SHACL JavaScript: ${capabilities.supportsShaclJs}")
println("Parallel validation: ${capabilities.supportsParallelValidation}")
println("Streaming validation: ${capabilities.supportsStreamingValidation}")
println("Max graph size: ${capabilities.maxGraphSize}")
println("Performance profile: ${capabilities.performanceProfile}")
```

## Configuration

### Validation Profiles

```kotlin
// SHACL Core validation
val coreValidator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)

// SHACL SPARQL validation
val sparqlValidator = ShaclValidation.validator(ValidationProfile.SHACL_SPARQL)

// Strict validation
val strictValidator = ShaclValidation.strictValidator()

// Fast validation
val fastValidator = ShaclValidation.fastValidator()

// Large graph validation
val largeGraphValidator = ShaclValidation.largeGraphValidator()

// Memory-constrained validation
val memoryValidator = ShaclValidation.memoryConstrainedValidator()
```

### Custom Configuration

```kotlin
val config = ValidationConfig(
    profile = ValidationProfile.SHACL_CORE,
    strictMode = true,
    includeWarnings = true,
    maxViolations = 100,
    timeout = Duration.ofMinutes(5),
    parallelValidation = true,
    streamingMode = false,
    batchSize = 1000,
    enableExplanations = true,
    enableSuggestions = true,
    validateClosedShapes = true,
    validateInactiveShapes = false,
    customParameters = mapOf(
        "debug" to true,
        "verbose" to false
    )
)

val validator = ShaclValidation.validator(config)
```

### Configuration Factory Methods

```kotlin
// Default configuration
val defaultConfig = ValidationConfig.default()

// SHACL Core configuration
val coreConfig = ValidationConfig.shaclCore()

// SHACL SPARQL configuration
val sparqlConfig = ValidationConfig.shaclSparql()

// Strict configuration
val strictConfig = ValidationConfig.strict()

// For large graphs
val largeGraphConfig = ValidationConfig.forLargeGraphs()

// For fast validation
val fastConfig = ValidationConfig.forFastValidation()

// For memory-constrained environments
val memoryConfig = ValidationConfig.forMemoryConstrained()
```

## Validation Results

### Validation Report

```kotlin
val report = validator.validate(dataGraph, shapesGraph)

// Basic information
println("Valid: ${report.isValid}")
println("Violations: ${report.violations.size}")
println("Warnings: ${report.warnings.size}")
println("Validation time: ${report.validationTime.toMillis()}ms")
println("Validated resources: ${report.validatedResources}")
println("Validated constraints: ${report.validatedConstraints}")
```

### Violations

```kotlin
// Iterate through violations
report.violations.forEach { violation ->
    println("${violation.severity}: ${violation.message}")
    println("  Resource: ${violation.resource}")
    println("  Constraint: ${violation.constraint.constraintType}")
    violation.explanation?.let { println("  Explanation: $it") }
    violation.suggestedFix?.let { println("  Suggested fix: $it") }
}

// Get violations by severity
val errors = report.getViolationsBySeverity(ViolationSeverity.VIOLATION)
val warnings = report.getViolationsBySeverity(ViolationSeverity.WARNING)

// Get violations for a specific resource
val resourceViolations = report.getViolationsForResource(someResource)

// Get violations for a specific shape
val shapeViolations = report.getViolationsForShape("http://example.org/PersonShape")

// Get violations for a specific constraint type
val minCountViolations = report.getViolationsForConstraint(ConstraintType.MIN_COUNT)
```

### Validation Statistics

```kotlin
val stats = report.statistics
println("Total resources: ${stats.totalResources}")
println("Validated resources: ${stats.validatedResources}")
println("Total constraints: ${stats.totalConstraints}")
println("Validated constraints: ${stats.validatedConstraints}")
println("Shapes processed: ${stats.shapesProcessed}")
println("Constraints by type: ${stats.constraintsByType}")
println("Violations by type: ${stats.violationsByType}")
println("Average validation time per resource: ${stats.averageValidationTimePerResource.toMillis()}ms")
```

### Validation Summary

```kotlin
val summary = report.getSummary()
println(summary.getDescription())

// Detailed summary
println("Valid: ${summary.isValid}")
println("Total violations: ${summary.totalViolations}")
println("Violations by severity: ${summary.violationsBySeverity}")
println("Violations by shape: ${summary.violationsByShape}")
println("Violations by constraint: ${summary.violationsByConstraint}")
```

## Usage Examples

### Resource-Specific Validation

```kotlin
val dataGraph = Rdf.graph {
    val person1 = iri("http://example.org/person1")
    val person2 = iri("http://example.org/person2")
    
    person1 - RDF.type - "http://example.org/Person"
    person1 - "http://example.org/name" - "Alice"
    
    person2 - RDF.type - "http://example.org/Person"
    // person2 missing required name
}

val validator = ShaclValidation.validator()

// Validate specific resource
val person1Report = validator.validateResource(
    dataGraph, 
    shapesGraph, 
    iri("http://example.org/person1")
)
println("Person1 valid: ${person1Report.isValid}")

val person2Report = validator.validateResource(
    dataGraph, 
    shapesGraph, 
    iri("http://example.org/person2")
)
println("Person2 valid: ${person2Report.isValid}")
```

### Validating Specific Constraints

```kotlin
val constraints = listOf(
    ShaclConstraint(
        constraintType = ConstraintType.MIN_COUNT,
        path = "http://example.org/name",
        parameters = mapOf("value" to 1)
    ),
    ShaclConstraint(
        constraintType = ConstraintType.DATATYPE,
        path = "http://example.org/age",
        parameters = mapOf("value" to iri("http://www.w3.org/2001/XMLSchema#integer"))
    )
)

val report = validator.validateConstraints(dataGraph, constraints)
```

### Working with Shapes

```kotlin
val shapes = listOf(
    ShaclShape(
        shapeUri = "http://example.org/PersonShape",
        targetClass = "http://example.org/Person",
        constraints = listOf(
            ShaclConstraint(
                constraintType = ConstraintType.MIN_COUNT,
                path = "http://example.org/name",
                parameters = mapOf("value" to 1)
            )
        )
    )
)

val report = validator.validate(dataGraph, shapes)
```

## Advanced Features

### Streaming Validation

For large graphs, use streaming mode to avoid loading all data into memory:

```kotlin
val config = ValidationConfig.forLargeGraphs()
val validator = ShaclValidation.validator(config)
val report = validator.validate(largeDataGraph, shapesGraph)
```

### Parallel Validation

Enable parallel validation for faster processing:

```kotlin
val config = ValidationConfig(
    profile = ValidationProfile.SHACL_CORE,
    parallelValidation = true,
    streamingMode = true,
    batchSize = 5000
)

val validator = ShaclValidation.validator(config)
val report = validator.validate(dataGraph, shapesGraph)
```

### Custom Validation Parameters

```kotlin
val config = ValidationConfig(
    profile = ValidationProfile.SHACL_CORE,
    customParameters = mapOf(
        "debug" to true,
        "verbose" to true,
        "optimize" to false,
        "maxDepth" to 10
    )
)

val validator = ShaclValidation.validator(config)
```

## Best Practices

### 1. Choose the Right Provider

- Use **Memory Validator** for development and small graphs
- Use **Jena Validator** for comprehensive SHACL support
- Use **RDF4J Validator** if you're already using RDF4J

### 2. Configure Appropriately

- Use `strictMode` for critical validations
- Enable `includeWarnings` for comprehensive feedback
- Set appropriate `maxViolations` to limit result size
- Use `streamingMode` for large graphs

### 3. Handle Violations

```kotlin
val report = validator.validate(dataGraph, shapesGraph)

if (!report.isValid) {
    report.violations.forEach { violation ->
        when (violation.severity) {
            ViolationSeverity.ERROR -> logger.error(violation.message)
            ViolationSeverity.VIOLATION -> logger.warn(violation.message)
            ViolationSeverity.WARNING -> logger.info(violation.message)
            ViolationSeverity.INFO -> logger.debug(violation.message)
        }
    }
}
```

### 4. Optimize Performance

```kotlin
// For repeated validations, reuse validators
val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)

// For large datasets, use streaming and batching
val config = ValidationConfig.forLargeGraphs()
val largeValidator = ShaclValidation.validator(config)

// For fast feedback, disable explanations and suggestions
val fastConfig = ValidationConfig.forFastValidation()
val fastValidator = ShaclValidation.validator(fastConfig)
```

### 5. Test Validation Logic

```kotlin
@Test
fun `should validate person with required properties`() {
    val dataGraph = createValidPersonGraph()
    val shapesGraph = createPersonShapesGraph()
    
    val validator = ShaclValidation.validator()
    val report = validator.validate(dataGraph, shapesGraph)
    
    assertTrue(report.isValid)
    assertEquals(0, report.violations.size)
}

@Test
fun `should detect missing required property`() {
    val dataGraph = createInvalidPersonGraph()
    val shapesGraph = createPersonShapesGraph()
    
    val validator = ShaclValidation.validator()
    val report = validator.validate(dataGraph, shapesGraph)
    
    assertFalse(report.isValid)
    assertTrue(report.violations.isNotEmpty())
    
    val minCountViolations = report.getViolationsForConstraint(ConstraintType.MIN_COUNT)
    assertTrue(minCountViolations.isNotEmpty())
}
```

## SHACL Constraint Types

Kastor supports a wide range of SHACL constraint types:

### Property Constraints
- `PROPERTY_SHAPE` - Property shape constraints
- `MIN_COUNT` - Minimum cardinality
- `MAX_COUNT` - Maximum cardinality
- `UNIQUE_LANG` - Unique language tags
- `LANGUAGE_IN` - Language restrictions
- `EQUALS` - Equality constraints
- `DISJOINT` - Disjointness constraints
- `LESS_THAN` - Ordering constraints
- `LESS_THAN_OR_EQUALS` - Ordering constraints

### Value Constraints
- `DATATYPE` - Datatype restrictions
- `CLASS` - Class restrictions
- `NODE_KIND` - Node kind restrictions
- `MIN_LENGTH` - Minimum string length
- `MAX_LENGTH` - Maximum string length
- `PATTERN` - Regular expression patterns
- `MIN_INCLUSIVE` - Minimum value (inclusive)
- `MAX_INCLUSIVE` - Maximum value (inclusive)
- `MIN_EXCLUSIVE` - Minimum value (exclusive)
- `MAX_EXCLUSIVE` - Maximum value (exclusive)
- `IN` - Enumeration constraints
- `HAS_VALUE` - Fixed value constraints

### Logical Constraints
- `NOT` - Negation
- `AND` - Conjunction
- `OR` - Disjunction
- `XONE` - Exclusive disjunction
- `NODE` - Node shape reference

### Advanced Constraints
- `SPARQL_CONSTRAINT` - SPARQL-based constraints
- `JS_CONSTRAINT` - JavaScript-based constraints
- `PY_CONSTRAINT` - Python-based constraints
- `CUSTOM_CONSTRAINT` - Custom constraint types

## See Also

- [SHACL Specification](https://www.w3.org/TR/shacl/)
- [Kastor RDF Core](./core-api.md)
- [Kastor Reasoning](./reasoning.md)
- [Examples](../examples/BasicShaclValidationExample.kt)
