# How to Create SHACL Shapes

{% include version-banner.md %}

This guide shows you how to create SHACL shapes graphs using Kastor's SHACL DSL. The DSL provides a type-safe, readable way to define validation constraints.

## What you'll learn
- Create SHACL shapes using the DSL
- Define property constraints
- Use logical constraints
- Integrate shapes with validation

## Prerequisites

No additional dependencies required - the SHACL DSL is part of `rdf-core`.

## Using the SHACL DSL

The SHACL DSL is the recommended way to create shapes graphs. It's more readable and type-safe than manually creating RDF triples.

### Basic Example

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.XSD

val shapesGraph = shacl {
    nodeShape("http://example.org/PersonShape") {
        targetClass(FOAF.Person)
        
        property(FOAF.name) {
            minCount = 1
            datatype = XSD.string
        }
    }
}
```

### Complete Example

```kotlin
val personShapes = shacl {
    nodeShape("http://example.org/PersonShape") {
        targetClass(FOAF.Person)
        
        // Required name
        property(FOAF.name) {
            minCount = 1
            maxCount = 1
            datatype = XSD.string
            minLength = 1
            maxLength = 100
        }
        
        // Optional email with validation
        property(FOAF.email) {
            minCount = 0
            maxCount = 1
            pattern = "^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$"
        }
        
        // Age with range
        property(FOAF.age) {
            minCount = 0
            maxCount = 1
            datatype = XSD.integer
            minInclusive = 0.0
            maxInclusive = 150.0
        }
    }
}
```

## Common Patterns

### Required vs Optional Properties

```kotlin
shacl {
    nodeShape("http://example.org/Shape") {
        // Required property
        property("http://example.org/required") {
            minCount = 1
        }
        
        // Optional property
        property("http://example.org/optional") {
            minCount = 0
        }
        
        // Multiple values allowed
        property("http://example.org/multiple") {
            minCount = 0
            // maxCount = null means unlimited
        }
    }
}
```

### String Validation

```kotlin
property("http://example.org/email") {
    minLength = 5
    maxLength = 100
    pattern = "^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$"
    flags = "i"  // Case-insensitive
}
```

### Numeric Ranges

```kotlin
property("http://example.org/price") {
    datatype = XSD.decimal
    minInclusive = 0.0
    maxInclusive = 1000.0
}

property("http://example.org/score") {
    datatype = XSD.double
    minExclusive = 0.0  // Must be > 0
    maxExclusive = 100.0  // Must be < 100
}
```

### Value Sets

```kotlin
property("http://example.org/status") {
    `in`("active", "inactive", "pending")
}

property("http://example.org/priority") {
    `in`(1, 2, 3, 4, 5)
}
```

## Using with Validation

Once you've created your shapes graph, use it with Kastor's SHACL validator:

```kotlin
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation

// Create shapes
val shapesGraph = shacl {
    nodeShape("http://example.org/PersonShape") {
        targetClass(FOAF.Person)
        property(FOAF.name) {
            minCount = 1
        }
    }
}

// Create data
val dataGraph = Rdf.graph {
    val person = iri("http://example.org/alice")
    person `is` FOAF.Person
    // Missing name - will fail validation
}

// Validate
val validator = ShaclValidation.validator()
val report = validator.validate(dataGraph, shapesGraph)

if (!report.isValid) {
    report.violations.forEach { violation ->
        println("Violation: ${violation.message}")
    }
}
```

## Next Steps

- [SHACL DSL Guide](../api/shacl-dsl-guide.md) - Complete DSL reference
- [How to Validate with SHACL](how-to-validate-shacl.md) - Validation workflow
- [SHACL Validation Features](../features/shacl-validation.md) - Validation capabilities


